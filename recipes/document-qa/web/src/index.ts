import { Mutex } from 'async-mutex';

import { CheetahTranscript, CheetahWorker } from '@picovoice/cheetah-web';
import { PicoLLMWorker } from '@picovoice/picollm-web';
import { OrcaWorker } from '@picovoice/orca-web';
import { WebVoiceProcessor } from '@picovoice/web-voice-processor';
import { AudioStream } from './audio_stream';

const MIN_DB = -40.0;
const MAX_DB = 0.0;

const TOPK = 4
const COMPLETION_TOKEN_LIMIT = 256
const CHUNK_SIZE = 300
const CHUNK_OVERLAP = 100

const ASK_FOR_DETAILS_RETRY_LIMIT = 2;

const STOP_PHRASE = "<|eot_id|>";

const SYSTEM = "You are a document question-answering assistant. "
+ "Answer only using the provided document excerpts. "
+ "If the answer is not in the excerpts, say that you do not know from the provided document. "
+ "Do not give legal advice. "
+ "Keep the answer concise. "
+ "Do not use Markdown formatting. "
+ "Do not use bullet points. "
+ "Use plain text only.";

const sleep = (ms: number) => new Promise(resolve => setTimeout(resolve, ms));

type PvObject = {
  audio: AudioStream,
  cheetah: CheetahWorker,
  llmModel: PicoLLMWorker,
  llmEmbedding: PicoLLMWorker,
  orca: OrcaWorker,

  chunks: string[] | null,
  embeddings: number[][] | null,

  sendMessage: (message: string, obj: any) => void,
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
  let embeddings = []
  for (let i = 0; i < chunks.length; i++) {
    object!.sendMessage("ai state", `AI: Generating embeddings ${i + 1}/${chunks.length}`);
    const embedding = await object!.llmEmbedding.generateEmbeddings(chunks[i]);
    embeddings.push(normalizeVector(embedding.embeddings));
  }

  return embeddings;
};

const chunkDocument = (text: string): string[] => {
  let chunks = [];
  let start = 0;

  while (start < text.length) {
    let end = Math.min(start + CHUNK_SIZE, text.length);

    if (end < text.length) {
      const paragraph_break = text.lastIndexOf("\n\n", end);
      if (paragraph_break > start + (CHUNK_SIZE * 0.5)) {
        end = paragraph_break;
      }
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
const retrieveChunks = async (question: string): Promise<chunkEmbeddingType[]> => {
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

const buildPrompt = async (question: string, retrievedChunks: chunkEmbeddingType[]): Promise<string> => {
  const context = retrievedChunks.map(ce => ce.chunk).map((c, i) => `[Excerpt ${i}]\n${c}`).join("\n\n");

  const dialog = await object!.llmModel.getDialog(undefined, undefined, SYSTEM);
  dialog.addHumanRequest(`Document excerpts:\n\n${context}\n\nQuestion:\n${question}`);
  return dialog.prompt();
};

// ------------------------------------------------------------------------- //

type initReturnType = {
  startFunction: (file: File) => Promise<void>;
  resetDemo: () => Promise<void>;
};

const init = async (
  accessKey: string,
  sendMessage: (message: string, obj: any) => void,
  makeRequest: (message: string) => any,
  onVolumeCallback: (volume: number) => void,
): Promise<null | initReturnType> => {

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

    await stopListenForUser();

    sendMessage("restart demo", null);
  };

  const listenForUser = async () => {
    sendMessage("new user bubble", "[USER] ");
    sendMessage("start listening", null);
    sendMessage("ai state", "AI: Listening");

    const cheetah = BufferedCheetahEngine;
    await WebVoiceProcessor.subscribe(cheetah);
  };
  const stopListenForUser = async () => {
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

    let userText = makeRequest("bubble contents").replace("[USER]", "").trim();
    if (transcript.isFlushed && userText.length > 0) {
      await stopListenForUser();
      await llmProcessCall(userText);
    }
  }

  const llmEmbeddingProcessCall = async (file: File) => {
    const cachedStr = localStorage.getItem(file.name)
    if (cachedStr !== null && window.confirm("Cached embeddings found, use?")) {
      const cached = JSON.parse(cachedStr);
      object!.chunks = cached.chunks;
      object!.embeddings = cached.embeddings;
      listenForUser();
    } else {
      const reader = new FileReader();
      reader.readAsText(file, 'UTF-8');
      reader.onload = ({ target }) => {
        sendMessage("start llm spinner", null);

        const text = target!.result as string;
        const chunks = chunkDocument(text);
        generateEmbeddings(chunks).then((embeddings) => {
          object!.chunks = chunks;
          object!.embeddings = embeddings;

          localStorage.setItem(file.name, JSON.stringify({
            chunks,
            embeddings
          }));

          sendMessage("stop llm spinner", null);
          listenForUser();
        });
      }
    }
  }

  const llmProcessCall = async (question: string) => {
    sendMessage("start llm spinner", null);

    const retrievedChunks = await retrieveChunks(question);
    const prompt = await buildPrompt(question, retrievedChunks);

    const stream = await object!.orca.streamOpen();
    const streamMutex = new Mutex();

    sendMessage("ai state", "AI: Speaking");
    sendMessage("new ai bubble", "[AI] ");

    let pcmBuffered = 0;
    const streamCallback = async (token: string) => {
      const text = token.replace(STOP_PHRASE, '');
      sendMessage("add to bubble", text);

      const streamRelease = await streamMutex.acquire();
      const pcm = await stream.synthesize(text);
      if (pcm !== null) {
        object!.audio.stream(pcm);

        pcmBuffered += pcm.length
        if (pcmBuffered >= 16000) {
          object!.audio.play();
        }
      }
      streamRelease();
    }

    const completion = await object!.llmModel.generate(
      prompt,
      {
        completionTokenLimit: COMPLETION_TOKEN_LIMIT,
        stopPhrases: [STOP_PHRASE],
        streamCallback: streamCallback,
      }
    );
    const inference = completion.completion.trim().replace(STOP_PHRASE, '');

    sendMessage("stop llm spinner", null);

    const streamRelease = await streamMutex.acquire();
    const pcm = await stream.flush();
    if (pcm !== null) {
      object!.audio.stream(pcm);
      object!.audio.play();
    }
    streamRelease();

    await object!.audio.waitPlayback();
    await stream.close();

    listenForUser();
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

  sendMessage("ai state", "AI: Waiting for Document");
  sendMessage("status", "Loading Audio Stream");
  const audio = new AudioStream(orca.sampleRate);

  object = {
    audio: audio,
    cheetah: cheetah,
    llmModel: llmModel,
    llmEmbedding: llmEmbedding,
    orca: orca,

    chunks: null,
    embeddings: null,

    sendMessage,
  };

  return {
    startFunction: llmEmbeddingProcessCall,
    resetDemo
  };
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
  release
};
