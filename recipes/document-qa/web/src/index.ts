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

type Message =
  "SET_STATUS"
  | "ADD_TO_BUBBLE"
  | "NEW_USER_BUBBLE"
  | "NEW_AI_BUBBLE"
  | "START_LISTENING"
  | "STOP_LISTENING"
  | "START_SPEAKING"
  | "STOP_SPEAKING"
  | "START_LLM_SPINNER"
  | "STOP_LLM_SPINNER"
  | "SET_AI_STATE"
  | "RESTART_DEMO";

type Request = "BUBBLE_CONTENTS";

type PvObject = {
  audio: AudioStream,
  cheetah: CheetahWorker,
  llmModel: PicoLLMWorker,
  llmEmbedding: PicoLLMWorker,
  orca: OrcaWorker,

  file: File,

  chunks: string[] | null,
  embeddings: number[][] | null,

  sendMessage: (message: Message, obj: any) => void,
};

let object: PvObject | null = null;

var triggerAudioCallback = (_: Int16Array) => {};

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
    object!.sendMessage("SET_STATUS", `Generating embeddings ${i + 1}/${chunks.length}`);
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

type initReturnType = {
  startFunction: (file: File) => Promise<void>;
  resetDemo: () => Promise<void>;
};
const init = async (
  accessKey: string,
  file: File,
  sendMessage: (message: Message, obj: any) => void,
  makeRequest: (message: Request) => any,
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

    sendMessage("SET_AI_STATE", "");

    await stopListenForUser();

    sendMessage("RESTART_DEMO", null);
  };

  const listenForUser = async () => {
    sendMessage("NEW_USER_BUBBLE", "[USER] ");
    sendMessage("START_LISTENING", null);
    sendMessage("SET_AI_STATE", "AI is listening to user");

    const cheetah = BufferedCheetahEngine;
    await WebVoiceProcessor.subscribe(cheetah);
  };
  const stopListenForUser = async () => {
    sendMessage("STOP_LISTENING", null);

    const cheetah = BufferedCheetahEngine;
    await WebVoiceProcessor.unsubscribe(cheetah);
  };
  const transcriptCallback = async (
    transcript: CheetahTranscript
  ): Promise<void> => {
    if (transcript.transcript.length > 0) {
      sendMessage("ADD_TO_BUBBLE", transcript.transcript);
    }

    if (transcript.isEndpoint) {
      sendMessage("ADD_TO_BUBBLE", " ");
      const cheetah = object!.cheetah;
      cheetah.flush();
    }

    let userText = makeRequest("BUBBLE_CONTENTS").replace("[USER] ", "").replace(".", "").trim();
    if (transcript.isFlushed && (userText.trim().length > 0)) {
      await stopListenForUser();
      await llmProcessCall(userText);
    }
  }

  const llmEmbeddingProcessCall = async () => {
    const cachedStr = localStorage.getItem(object!.file.name)
    if (cachedStr !== null && window.confirm("Cached embeddings found, use?")) {
      const cached = JSON.parse(cachedStr);
      object!.chunks = cached.chunks;
      object!.embeddings = cached.embeddings;
    } else {
      const text = await object!.file.text();
      const chunks = chunkDocument(text);
      const embeddings = await generateEmbeddings(chunks);

      object!.chunks = chunks;
      object!.embeddings = embeddings;

      localStorage.setItem(object!.file.name, JSON.stringify({
        chunks,
        embeddings
      }));
    }
  }

  const llmProcessCall = async (question: string) => {
    sendMessage("START_LLM_SPINNER", null);

    const retrievedChunks = await retrieveChunks(question);
    const prompt = await buildPrompt(question, retrievedChunks);

    const stream = await object!.orca.streamOpen();
    const streamMutex = new Mutex();

    sendMessage("SET_AI_STATE", "AI is processing");
    sendMessage("NEW_AI_BUBBLE", "[AI] ");

    let pcmBuffered = 0;
    const streamCallback = async (token: string) => {
      const text = token.replace(STOP_PHRASE, '');

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

      sendMessage("ADD_TO_BUBBLE", text);
    }

    const completion = await object!.llmModel.generate(
      prompt,
      {
        completionTokenLimit: COMPLETION_TOKEN_LIMIT,
        stopPhrases: [STOP_PHRASE],
        streamCallback: streamCallback,
      }
    );
    sendMessage("START_SPEAKING", null);
    const inference = completion.completion.trim().replace(STOP_PHRASE, '');

    const streamRelease = await streamMutex.acquire();
    const pcm = await stream.flush();
    if (pcm !== null) {
      object!.audio.stream(pcm);
      object!.audio.play();
    }
    streamRelease();

    await object!.audio.waitPlayback();
    await stream.close();
    sendMessage("STOP_SPEAKING", null);

    await listenForUser();
    sendMessage("STOP_LLM_SPINNER", null);
  };

  if (object == null) {
    const FORCE_WRITE = true;

    sendMessage("SET_STATUS", "Loading Cheetah");
    const cheetah = await CheetahWorker.create(
      accessKey,
      transcriptCallback,
      { publicPath: "models/cheetah_params.pv", forceWrite: FORCE_WRITE },
      {
        endpointDurationSec: 1.0,
        enableAutomaticPunctuation: true,
        enableTextNormalization: true
      }
    );

    sendMessage("SET_STATUS", "Loading PicoLLM (model)");
    const llmModel = await PicoLLMWorker.create(
      accessKey,
      { modelFile: 'models/llama-3.2-1b-instruct-385.pllm', cacheFileOverwrite: FORCE_WRITE },
      {}
    );

    sendMessage("SET_STATUS", "Loading PicoLLM (embedding)");
    const llmEmbedding = await PicoLLMWorker.create(
      accessKey,
      { modelFile: 'models/embeddinggemma-300m-375.pllm', cacheFileOverwrite: FORCE_WRITE },
      {}
    );

    sendMessage("SET_STATUS", "Loading Orca");
    const orca = await OrcaWorker.create(
      accessKey,
      { publicPath: "models/orca_params_en_female.pv", forceWrite: FORCE_WRITE },
      {}
    );

    sendMessage("SET_STATUS", "Loading Audio Stream");
    const audio = new AudioStream(orca.sampleRate);

    object = {
      audio: audio,
      cheetah: cheetah,
      llmModel: llmModel,
      llmEmbedding: llmEmbedding,
      orca: orca,

      chunks: null,
      embeddings: null,

      file: file,

      sendMessage: sendMessage,
    };
  }

  await llmEmbeddingProcessCall();
  return {
    startFunction: listenForUser,
    resetDemo
  };
};

const updateStartParameters = async (file: File): Promise<void> => {
  if (object) {
    object!.file = file;
  }
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

const skipAudio = async () => {
  if (object && object!.audio) {
    object!.audio.clear();
  }
}

export default {
  sleep,
  init,
  skipAudio,
  updateStartParameters,
  release
};
