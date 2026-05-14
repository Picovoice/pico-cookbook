import { CheetahTranscript, CheetahWorker } from '@picovoice/cheetah-web';
import { PicoLLMWorker } from '@picovoice/picollm-web';
import { OrcaWorker } from '@picovoice/orca-web';
import { WebVoiceProcessor } from '@picovoice/web-voice-processor';
import { AudioStream } from './audio_stream';
import { Action, promptFromAction, isTerminalFromAction, allActions } from './action';

const MIN_DB = -40.0;
const MAX_DB = 0.0;

const TOPK = 4
const COMPLETION_TOKEN_LIMIT = 256
const CHUNK_SIZE = 300
const CHUNK_OVERLAP = 100

const ASK_FOR_DETAILS_RETRY_LIMIT = 2;

const SYSTEM = "You are a document question-answering assistant. "
+ "Answer only using the provided document excerpts. "
+ "If the answer is not in the excerpts, say that you do not know from the provided document. "
+ "Do not give legal advice. "
+ "Keep the answer concise. "
+ "Do not use Markdown formatting. "
+ "Do not use bullet points. "
+ "Use plain text only.";

const sleep = (ms: number) => new Promise(resolve => setTimeout(resolve, ms));

const extractCallerAndReasonFromLLMInference = (inference: string): [string, string] => {
    const inferenceLower = inference.toLowerCase();
    const callerIndex = inferenceLower.indexOf("caller:");
    const reasonIndex = inferenceLower.indexOf("reason:");

    if (callerIndex == -1 || reasonIndex == -1) {
        return ["unknown", "unknown"];
    }

    let callerEOL = inferenceLower.slice(callerIndex).indexOf("\n");
    let reasonEOL = inferenceLower.slice(reasonIndex).indexOf("\n");
    callerEOL = (callerEOL == -1) ? inferenceLower.length : (callerIndex + callerEOL);
    reasonEOL = (reasonEOL == -1) ? inferenceLower.length : (reasonIndex + reasonEOL);
    
    const callerContents = inference.slice(callerIndex, callerEOL).split(":")[1];
    const reasonContents = inference.slice(reasonIndex, reasonEOL).split(":")[1];

    return [callerContents.trim(), reasonContents.trim()];
}

// ------------------------------------------------------------------------- //

type PvObject = {
  audio: AudioStream,
  cheetah: CheetahWorker,
  llmModel: PicoLLMWorker,
  llmEmbedding: PicoLLMWorker,
  orca: OrcaWorker,
  action: Action,

  chunks: string[] | null,
  embeddings: number[][] | null,

  askForDetailsRetryCount: number,
};

let object: PvObject | null = null;

var triggerAudioCallback = (_: Int16Array) => {};

// ------------------------------------------------------------------------- //

var cheetahPcmBuffer = new Int16Array();
const BufferedCheetahEngine = {
  onmessage: function(e: any) {
    switch (e.data.command) {
      case 'process':
        triggerAudioCallback(e.data.inputFrame);

        var tempPcmBuffer = new Int16Array(cheetahPcmBuffer.length + e.data.inputFrame.length);
        tempPcmBuffer.set(cheetahPcmBuffer);
        tempPcmBuffer.set(e.data.inputFrame, cheetahPcmBuffer.length);
        cheetahPcmBuffer = tempPcmBuffer

        if (object !== null) {
          while (cheetahPcmBuffer.length >= object!.cheetah.frameLength) {
            object!.cheetah.process(cheetahPcmBuffer.slice(0, object!.cheetah.frameLength));
            cheetahPcmBuffer = cheetahPcmBuffer.slice(object!.cheetah.frameLength)
          }
        }
        break;
    }
  }
}

// ------------------------------------------------------------------------- //

const normalizeVector = (vector: number[]): number[] => {
  const norm = Math.pow(vector.reduce((partial, x) => partial + x, 0), 0.5);
  return vector.map(x => x / norm);
};

const dotProduct = (a: number[], b: number[]): number => {
  return a.reduce((partial, x, i) => partial + (x * b[i]), 0);
}

const generateEmbeddings = async (chunks: string[]): Promise<number[][]> => {
  // status = Generating embeddgins x/x

  let embeddings = []
  for (let i = 0; i < chunks.length; i++) {
    console.log(`Generating embeddings ${i + 1}/${chunks.length}`)
    const embedding = await object!.llmEmbedding.generateEmbeddings(chunks[i]);
    embeddings.push(normalizeVector(embedding.embeddings));
  }

  return embeddings;
};

const chunkDocument = (text: string): string[] => {
  // TODO: Process line endings?

  let chunks = [];
  let start = 0;

  while (start < text.length) {
    let end = Math.min(start + CHUNK_SIZE, text.length);

    if (end < text.length) {
            // paragraph_break = text.rfind("\n\n", start, end)
            // if paragraph_break > start + int(chunk_size * 0.5):
            //     end = paragraph_break
    }

    const chunk = text.slice(start, end).trim();

    if (chunk.length > 0) {
      chunks.push(chunk);
    }

    if (end >= text.length) {
      break;
    }

    start = Math.max(0, end - CHUNK_OVERLAP);
  }

  return chunks;
};

type chunkEmbeddingType = {
  score: number;
  chunk: string;
};
const retreiveChunks = async (question: string): Promise<chunkEmbeddingType[]> => {
  const questionEmbeddingCompletion = await object!.llmEmbedding.generateEmbeddings(question);
  const questionEmbedding = normalizeVector(questionEmbeddingCompletion.embeddings);

  let scores = object!.embeddings!.map((embedding, i) => {
    return {
      score: dotProduct(embedding, questionEmbedding),
      chunk: object!.chunks![i],
    };
  });

  scores.sort((a, b) => b.score - a.score);
  return scores.slice(0, TOPK);
};

const buildPrompt = async (question: string, retreivedChunks: chunkEmbeddingType[]): Promise<string> => {
  const context = retreivedChunks.map(ce => ce.chunk).map((c, i) => `[Excerpt ${i}]\n${c}`).join("\n\n");

  const dialog = await object!.llmModel.getDialog(undefined, undefined, SYSTEM);
  dialog.addHumanRequest(`Document excerpts:\n\n${context}\n\nQuestion:\n${question}`);
  return dialog.prompt();
};

// ------------------------------------------------------------------------- //

const init = async (
  accessKey: string,
  sendMessage: (message: string, obj: any) => void,
  makeRequest: (message: string) => any,
  onVolumeCallback: (volume: number) => void,
): Promise<null | ((file: File) => Promise<void>)> => {

  triggerAudioCallback = (frame: Int16Array) => {
    let sum = 0;
    for (let i = 0; i < frame.length; i++) {
      sum += Math.pow(frame[i], 2);
    }
    const rms = sum / frame.length / Math.pow(32767, 2);
    const db = 10 * Math.log10(Math.max(rms, 1e-9));
    const normalized = (db - MIN_DB) / (MAX_DB - MIN_DB);
    const normalizedVolume = Math.max(0.0, Math.min(1.0, normalized));

    onVolumeCallback(normalizedVolume);
  }

  const resetDemo = async () => {
    object!.audio.clear();

    sendMessage("ai state", "");

    await stopListenForCaller();
    await stopGiveUserOptions();

    sendMessage("restart demo", null);
  };

  const speakToCaller = async (text: string) => {
    const orca = object!.orca;

    const synthesis = await orca.synthesize(text, {});
    const alignments = synthesis.alignments;

    object!.audio.stream(synthesis.pcm);
    object!.audio.play();

    sendMessage("ai state", "AI: Speaking to caller");
    sendMessage("new ai bubble", "[AI] ");

    for (let alignment of alignments) {
      const index = alignments.indexOf(alignment);
      let word = alignment.word;
      if (index < alignments.length - 1 &&
          !(/[.,:;!?]/.test(alignments[index + 1].word))
      ) {
        word += " ";
      }

      setTimeout(() => {
        sendMessage("add to bubble", word);
      }, alignment.startSec * 1000);
    }

    await object!.audio.waitPlayback();

    if (isTerminalFromAction(object!.action)) {
      await sleep(1700);
      resetDemo();
    } else {
      listenForCaller();
    }
  };

  const listenForCaller = async () => {
    sendMessage("new caller bubble", "[CALLER] ");
    sendMessage("start listening", null);
    sendMessage("ai state", "AI: Listening to caller");

    const cheetah = BufferedCheetahEngine;
    await WebVoiceProcessor.subscribe(cheetah);
  };
  const stopListenForCaller = async () => {
    sendMessage("stop listening", null);
    const cheetah = BufferedCheetahEngine;
    await WebVoiceProcessor.unsubscribe(cheetah);
  };
  const transcriptCallback = async (
    transcript: CheetahTranscript
  ): Promise<void> => {
    if (transcript.transcript.length > 0) {
      sendMessage("add to bubble", transcript.transcript);
    }

    if (transcript.isEndpoint) {
      sendMessage("add to bubble", " ");
      const cheetah = object!.cheetah;
      cheetah.flush();
    }

    if (transcript.isFlushed && makeRequest("bubble length") > 0) {
      await stopListenForCaller();

      let callerText = makeRequest("bubble contents");
      await llmProcessCall(callerText);
    }
  }

  const llmEmbeddingProcessCall = async (file: File) => {
    const reader = new FileReader();
    reader.readAsText(file, 'UTF-8');
    reader.onload = ({ target }) => {
      const text = target!.result as string;
      const chunks = chunkDocument(text);
      generateEmbeddings(chunks).then((embeddings) => {
        object!.chunks = chunks;
        object!.embeddings = embeddings;
        listenForCaller();
      });
    }
    reader.onerror = () => {
      // TODO
    }
  }

  const llmProcessCall = async (callerText: string) => {
    sendMessage("start llm spinner", null);

    const question = callerText.replace("[CALLER]", "").trim();
    const retreivedChunks = await retreiveChunks(question);
    const prompt = await buildPrompt(question, retreivedChunks);

    // TODO: Should stream response
    const completion = await object!.llmModel.generate(
      prompt,
      {
        completionTokenLimit: COMPLETION_TOKEN_LIMIT,
        stopPhrases: ['<|eot_id|>'],
        streamCallback: (token: string) => {},
      }
    );
    const inference = completion.completion.trim().replace('<|eot_id|>', '');

    sendMessage("stop llm spinner", null);

    object!.action = Action.ASK_FOR_DETAILS;
    speakToCaller(inference);

    // let dialog = await object!.llm.getDialog(undefined, undefined, SYSTEM);
    // dialog.addHumanRequest(`Caller said: \"${callerText.replace("[CALLER]", "").trim()}\"\n`);

    // const completion = await llm.generate(
    //     dialog.prompt(),
    //     { stopPhrases: ['<|eot_id|>'] });

    // const inference = completion.completion.trim().replace('<|eot_id|>', '');
    // console.log(`# Inference\n${inference}`);
    // const [caller, reason] = extractCallerAndReasonFromLLMInference(inference);

    // sendMessage("stop llm spinner", null);

    // if (caller != 'unknown' && reason != 'unknown') {
    //   sendMessage(
    //       "ai report",
    //       `[AI] <b style="color: var(--brand-primary);">${caller}</b> is trying to speak with you about ` +
    //       `<b style="color: var(--brand-primary);">${reason}</b>.`);
    //   setTimeout(() => { giveUserOptions(); }, 200);
    //   return;
    // } else if (object!.askForDetailsRetryCount < ASK_FOR_DETAILS_RETRY_LIMIT) {
    //   object!.action = Action.ASK_FOR_DETAILS;

    //   if (caller == 'unknown' && reason == 'unknown') {
    //     sendMessage("ai report", "[AI] Unknown caller with no specific reason. I will ask for more information.");
    //   } else if (caller == 'unknown') {
    //     sendMessage(
    //         "ai report",
    //         `[AI] Unknown caller is trying to speak with you about <b style="color: var(--brand-primary);">${reason}</b>. ` +
    //         `I will ask for their identity.`);
    //   } else {
    //     sendMessage(
    //         "ai report",
    //         `[AI] <b style="color: var(--brand-primary);">${caller}</b> is trying to speak with you. I will ask for their reason.`);
    //   }
    // } else {
    //   object!.action = Action.DECLINE_CALL;
      
    //   sendMessage(
    //       "ai report",
    //       `[AI] Couldn't understand caller's identity and agenda after <b style="color: var(--brand-primary);">${ASK_FOR_DETAILS_RETRY_LIMIT}</b> ` +
    //       "inquiries. Declining their call.");
    // }

    // object!.askForDetailsRetryCount += 1;
    // setTimeout(() => { speakToCaller(); }, 200);
  };

  const giveUserOptions = async () => {
    sendMessage("give user options", allActions());
    sendMessage("ai state", "AI: Listening for " + "TODO" + "'s command");

    // const rhino = BufferedRhinoEngine;
    // await WebVoiceProcessor.subscribe(rhino);
  };
  const stopGiveUserOptions = async () => {
  //   const rhino = BufferedRhinoEngine;
  //   await WebVoiceProcessor.unsubscribe(rhino);
  };

  sendMessage("status", "Loading Cheetah");
  const cheetah = await CheetahWorker.create(
    accessKey,
    transcriptCallback,
    { publicPath: "models/cheetah_params.pv", forceWrite: true },
    {
      endpointDurationSec: 1.0,
      enableAutomaticPunctuation: true,
      enableTextNormalization: true
    }
  );

  sendMessage("status", "Loading PicoLLM (model)");
  const llmModel = await PicoLLMWorker.create(
    accessKey,
    { modelFile: 'models/llama-3.2-1b-instruct-385.pllm', cacheFileOverwrite: true },
    {}
  );

  sendMessage("status", "Loading PicoLLM (embedding)");
  const llmEmbedding = await PicoLLMWorker.create(
    accessKey,
    { modelFile: 'models/embeddinggemma-300m-375.pllm', cacheFileOverwrite: true },
    {}
  );

  sendMessage("status", "Loading Orca");
  const orca = await OrcaWorker.create(
    accessKey,
    { publicPath: "models/orca_params_en_female.pv", forceWrite: true },
    {}
  );

  sendMessage("ai state", "AI: Connecting caller");
  sendMessage("status", "Loading Audio Stream");
  const audio = new AudioStream(orca.sampleRate);

  object = {
    audio: audio,
    cheetah: cheetah,
    llmModel: llmModel,
    llmEmbedding: llmEmbedding,
    orca: orca,
    action: Action.GREET,

    chunks: null,
    embeddings: null,

    askForDetailsRetryCount: 0,
  };

  return (documentFile: File) => llmEmbeddingProcessCall(documentFile);
};

const updateStartParameters = async (name: string): Promise<boolean> => {
  // if (object) {
  //   object!.action = Action.GREET;
  //   object!.username = name;
  //   object!.askForDetailsRetryCount = 0;
  //   return true;
  // } else {
  //   return false;
  // }
  return !!object;
};

const release = async () => {
  WebVoiceProcessor.reset();

  if (object && object!.audio) {
    object!.audio.clear();
  }

  if (object && object!.cheetah) {
    await object!.cheetah.release();
  }

  if (object && object!.orca) {
    await object!.orca.release();
  }

  object = null;

  cheetahPcmBuffer = new Int16Array();
};

export default {
  sleep,
  init,
  updateStartParameters,
  release
};
