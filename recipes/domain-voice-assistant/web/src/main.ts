import { Mutex } from 'async-mutex';

import { PorcupineWorker } from '@picovoice/porcupine-web';
import { CheetahTranscript, CheetahWorker } from '@picovoice/cheetah-web';
import { CobraWorker } from '@picovoice/cobra-web';
import { OrcaWorker } from '@picovoice/orca-web';
import { PicoLLMWorker } from '@picovoice/picollm-web';
import { WebVoiceProcessor } from '@picovoice/web-voice-processor';

import { AudioStream } from './audio_stream';
import {
  Player,
  Turn,
  createTurn,
  spokenText,
  clearFeedTimeout,
  startPlayback,
  pausePlayback,
  resumePlayback,
  playPcm,
} from './playback';
import {
  LINK_WORDS,
  toWords,
  createSpeechText,
  splitSentences,
} from './speech_text';

const SYSTEM_PROMPT =
  "You are Picovoice's chatbot on picovoice.ai. You are kind, polite and diplomatic, and you reply in English only. " +
  "You help visitors understand Picovoice's on-device voice, language, and vision AI products, choose between them, " +
  'get started, and find the right contact. Answer directly and completely from Picovoice\'s documentation. ' +
  'Never state prices, plan limits, or numbers that are not documented; if something is not documented, say so and ' +
  'point to the docs, Picovoice Console, GitHub, the contact form, or hello@picovoice.ai.' +
  '\n\n' +
  'Before saying an engine has or lacks an SDK, language or platform, be sure; if you are not sure, say so and point ' +
  "to that engine's product page. Never invent product names, recipes, links, prices or features. Answer the " +
  'question that was asked, and do not add limitations nobody asked about.';

const FORCE_WRITE = true;

const MIN_DB = -40.0;
const MAX_DB = 0.0;

const WAKE_WORD_SENSITIVITY = 0.7;
const PICOLLM_DEVICE = 'cpu:6';
const ENABLE_CONTEXT_CACHING = true;
const DIALOG_HISTORY = 2;
const COMPLETION_TOKEN_LIMIT = 256;
const TEMPERATURE = 0;
const STOP_PHRASE = '<|eot_id|>';

const ENDPOINT_DURATION_SEC = 1.0;

const WAKE_WORD_LISTEN_TIMEOUT_MS = 8000;
const FOLLOW_UP_WINDOW_MS = 8000;

const VOICE_PROBABILITY_THRESHOLD = 0.7;
const VOICE_ONSET_FRAMES = 3;
const VOICE_REARM_FRAMES = 10;
const BARGE_IN_PAUSE_VOICE_SEC = 0.5;
const BARGE_IN_RESUME_SILENCE_SEC = 1.0;
const BARGE_IN_NEW_QUESTION_MIN_WORDS = 2;
const BARGE_IN_PRE_ROLL_SEC = 1.0;

type Mode = 'idle' | 'waiting' | 'listening' | 'responding';

type PvObject = {
  porcupine: PorcupineWorker;
  cheetah: CheetahWorker;
  cobra: CobraWorker;
  pllm: PicoLLMWorker;
  orca: OrcaWorker;
  player: Player;
};

type PvCallback = {
  onLoading: (engine: string) => void;
  onWaiting: () => void;
  onWakeWord: () => void;
  onListening: () => void;
  onTranscript: (transcript: string) => void;
  onQuestion: (question: string) => void;
  onAnswerStart: () => void;
  onText: (text: string) => void;
  onSpeaking: () => void;
  onBargeIn: (isActive: boolean) => void;
  onAnswerEnd: (interrupted: boolean) => void;
  onVolume: (volume: number) => void;
  onError: (error: string) => void;
};

type Exchange = {
  question: string;
  answer: string;
};

type BargeIn = {
  heard: string;
  voicedFrames: number;
};

let object: PvObject | null = null;
let callbacks: PvCallback | null = null;

let mode: Mode = 'idle';
let transcript = '';
let listenTimeout: ReturnType<typeof setTimeout> | null = null;

let turn: Turn | null = null;
let generation: Promise<void> = Promise.resolve();
let exchanges: Exchange[] = [];

let bargeIn: BargeIn | null = null;
let isBargeInArmed = true;
let voicedFrames = 0;
let unvoicedFrames = 0;

let cheetahBuffer = new Int16Array(0);
let pendingFlushes = 0;
let preRollFrames: Int16Array[] = [];
let isPorcupineActive = false;

const orcaMutex = new Mutex();

const setPorcupineActive = (active: boolean): void => {
  if (object === null || active === isPorcupineActive) {
    return;
  }
  isPorcupineActive = active;
  if (active) {
    WebVoiceProcessor.subscribe(object.porcupine);
  } else {
    WebVoiceProcessor.unsubscribe(object.porcupine);
  }
};

const toVolume = (frame: Int16Array): number => {
  let sum = 0;
  for (let i = 0; i < frame.length; i++) {
    sum += Math.pow(frame[i], 2);
  }
  const rms = sum / frame.length / Math.pow(32767, 2);
  const db = 10 * Math.log10(Math.max(rms, 1e-9));
  const normalized = (db - MIN_DB) / (MAX_DB - MIN_DB);
  return Math.max(0.0, Math.min(1.0, normalized));
};

const cheetahEngine = {
  onmessage: (event: MessageEvent): void => {
    if (event.data.command !== 'process' || object === null) {
      return;
    }

    const frame: Int16Array = event.data.inputFrame;
    callbacks!.onVolume(toVolume(frame));

    if (mode !== 'listening' && mode !== 'responding') {
      return;
    }

    if (mode === 'responding' && bargeIn === null) {
      preRollFrames.push(frame);
      const maxFrames = Math.ceil((BARGE_IN_PRE_ROLL_SEC * object.cheetah.sampleRate) / frame.length);
      if (preRollFrames.length > maxFrames) {
        preRollFrames.shift();
      }
      return;
    }

    feedCheetah(frame);
  },
};

const feedCheetah = (frame: Int16Array): void => {
  const { cheetah } = object!;
  const buffer = new Int16Array(cheetahBuffer.length + frame.length);
  buffer.set(cheetahBuffer);
  buffer.set(frame, cheetahBuffer.length);

  let start = 0;
  while (start + cheetah.frameLength <= buffer.length) {
    cheetah.process(buffer.slice(start, start + cheetah.frameLength));
    start += cheetah.frameLength;
  }
  cheetahBuffer = buffer.slice(start);
};

const resetCheetah = (): void => {
  cheetahBuffer = new Int16Array(0);
  pendingFlushes++;
  object!.cheetah.flush();
};

const clearListenTimeout = (): void => {
  if (listenTimeout !== null) {
    clearTimeout(listenTimeout);
    listenTimeout = null;
  }
};

const wait = (): void => {
  clearListenTimeout();
  mode = 'waiting';
  setPorcupineActive(true);
  callbacks!.onWaiting();
};

const listen = (afterWakeWord: boolean): void => {
  clearListenTimeout();
  mode = 'listening';
  setPorcupineActive(true);
  transcript = '';
  resetCheetah();

  if (afterWakeWord) {
    callbacks!.onWakeWord();
  } else {
    callbacks!.onListening();
  }

  listenTimeout = setTimeout(
    () => {
      if (mode === 'listening' && transcript.trim().length === 0) {
        wait();
      }
    },
    afterWakeWord ? WAKE_WORD_LISTEN_TIMEOUT_MS : FOLLOW_UP_WINDOW_MS
  );
};

const continueListening = (heard: string): void => {
  clearListenTimeout();
  mode = 'listening';
  setPorcupineActive(true);
  transcript = heard;
  callbacks!.onListening();
  callbacks!.onTranscript(heard);
};

const addExchange = (question: string, answer: string): void => {
  exchanges.push({ question, answer });
  exchanges = exchanges.slice(-DIALOG_HISTORY);
};

const clearBargeIn = (): void => {
  bargeIn = null;
  voicedFrames = 0;
  unvoicedFrames = 0;
  isBargeInArmed = true;
};

const interruptTurn = (): boolean => {
  if (turn === null || object === null) {
    return false;
  }

  const current = turn;
  turn = null;
  current.isInterrupted = true;
  clearFeedTimeout(current);

  object.pllm.interrupt();
  object.player.audioStream.clear();
  object.player.audioStream.waitPlayback(() => true);
  clearBargeIn();

  orcaMutex.runExclusive(async () => {
    if (current.stream !== null) {
      await current.stream.flush().catch(() => null);
      await current.stream.close();
      current.stream = null;
    }
  });

  const spoken = spokenText(current, object.player);
  if (spoken.length > 0) {
    addExchange(current.question, spoken);
  }

  callbacks!.onAnswerEnd(true);
  return true;
};

const speak = (current: Turn, text: string, isSentenceEnd: boolean, raw: string): Promise<void> =>
  orcaMutex.runExclusive(async () => {
    if (current.isInterrupted || object === null) {
      return;
    }

    const last = current.segments[current.segments.length - 1];
    if (last === undefined || (last.isComplete && text.trim().length > 0)) {
      current.segments.push({ text: '', synthesizedSamples: 0, isComplete: false });
    }
    const segment = current.segments[current.segments.length - 1];
    segment.text += raw;

    try {
      if (text.length > 0) {
        if (current.stream === null) {
          current.stream = await object.orca.streamOpen();
        }
        playPcm(current, segment, await current.stream.synthesize(text), object.player);
      }
      if ((isSentenceEnd || text.endsWith(LINK_WORDS)) && current.stream !== null) {
        playPcm(current, segment, await current.stream.flush(), object.player);
      }
    } catch (e: any) {
      if (current.stream !== null) {
        await current.stream.close().catch(() => null);
        current.stream = null;
      }
    }

    if (isSentenceEnd) {
      segment.isComplete = true;
    }
  });

const endSpeech = (current: Turn): Promise<void> =>
  orcaMutex.runExclusive(async () => {
    if (current.isInterrupted || current.stream === null || object === null) {
      return;
    }

    const segment = current.segments[current.segments.length - 1];
    try {
      playPcm(current, segment, await current.stream.flush(), object.player);
      await current.stream.close();
    } catch (e: any) {
      await current.stream.close().catch(() => null);
    }
    current.stream = null;
    segment.isComplete = true;
  });

const respond = async (current: Turn): Promise<void> => {
  if (turn !== current || object === null) {
    return;
  }

  const { pllm, orca, player } = object;

  const dialog = pllm.getDialog(undefined, DIALOG_HISTORY, SYSTEM_PROMPT);
  for (const { question, answer } of exchanges) {
    dialog.addHumanRequest(question);
    dialog.addLLMResponse(answer);
  }
  dialog.addHumanRequest(current.question);

  const speechText = createSpeechText(new Set(orca.validCharacters));
  let raw = '';
  let textDone: Promise<void> | null = null;
  const finishText = (): Promise<void> => {
    if (textDone === null) {
      for (const sentence of splitSentences(speechText.flush())) {
        speak(current, sentence.text, sentence.isEnd, raw);
        raw = '';
      }
      textDone = speak(current, '', true, raw).then(() => endSpeech(current));
    }
    return textDone;
  };

  const { completion } = await pllm.generate(dialog.prompt(), {
    completionTokenLimit: COMPLETION_TOKEN_LIMIT,
    temperature: TEMPERATURE,
    stopPhrases: [STOP_PHRASE],
    streamCallback: (token: string): void => {
      const text = token.replace(STOP_PHRASE, '');
      if (current.isInterrupted || textDone !== null) {
        return;
      }
      if (text.length === 0) {
        if (token.includes(STOP_PHRASE)) {
          finishText();
        }
        return;
      }

      callbacks!.onText(text);
      raw += text;
      for (const sentence of splitSentences(speechText.push(text))) {
        speak(current, sentence.text, sentence.isEnd, raw);
        raw = '';
      }
    },
  });

  await finishText();

  if (current.isInterrupted) {
    return;
  }

  current.isTextDone = true;
  startPlayback(current, player);
  do {
    await player.audioStream.waitPlayback();
  } while (!current.isInterrupted && (current.isPaused || current.handedSamples < current.bufferedSamples));

  if (current.isInterrupted) {
    return;
  }

  turn = null;
  addExchange(current.question, completion.replace(STOP_PHRASE, '').trim());
  callbacks!.onAnswerEnd(false);

  const heard = bargeIn !== null ? bargeIn.heard : '';
  clearBargeIn();
  if (toWords(heard).length > 0) {
    continueListening(heard);
  } else {
    listen(false);
  }
};

const startTurn = async (question: string): Promise<void> => {
  clearListenTimeout();
  mode = 'responding';
  setPorcupineActive(false);
  clearBargeIn();
  preRollFrames = [];

  const current = createTurn(question);
  turn = current;

  callbacks!.onQuestion(question);
  callbacks!.onAnswerStart();

  await generation;
  generation = respond(current).catch((e: any) => {
    callbacks!.onError(e.message);
    if (turn === current) {
      interruptTurn();
      listen(false);
    }
  });
};

const resumeAfterBargeIn = (): void => {
  bargeIn = null;
  isBargeInArmed = false;
  resetCheetah();
  if (turn !== null && turn.isPaused) {
    callbacks!.onBargeIn(false);
    resumePlayback(turn, object!.player);
  }
};

const pauseForBargeIn = (): void => {
  if (turn === null || turn.isPaused) {
    return;
  }
  pausePlayback(turn, object!.player);
  callbacks!.onBargeIn(true);
};

const startBargeIn = (): void => {
  resetCheetah();
  const frames = preRollFrames;
  preRollFrames = [];
  frames.forEach(feedCheetah);

  bargeIn = { heard: '', voicedFrames: 0 };
};

const onBargeInTranscript = (cheetahTranscript: CheetahTranscript): void => {
  if (bargeIn === null) {
    return;
  }

  bargeIn.heard += cheetahTranscript.transcript;
  const { heard } = bargeIn;
  const words = toWords(heard);

  if (words.length > 0) {
    pauseForBargeIn();
  }

  if (words.length >= BARGE_IN_NEW_QUESTION_MIN_WORDS) {
    interruptTurn();
    if (cheetahTranscript.isEndpoint) {
      startTurn(heard.trim());
    } else {
      continueListening(heard);
    }
    return;
  }

  if (cheetahTranscript.isEndpoint) {
    resumeAfterBargeIn();
  }
};

const onListeningTranscript = (cheetahTranscript: CheetahTranscript): void => {
  if (cheetahTranscript.transcript.length > 0) {
    clearListenTimeout();
    transcript += cheetahTranscript.transcript;
    callbacks!.onTranscript(cheetahTranscript.transcript);
  }

  if (cheetahTranscript.isEndpoint && transcript.trim().length > 0) {
    const question = transcript.trim();
    transcript = '';
    startTurn(question);
  }
};

const transcriptCallback = (cheetahTranscript: CheetahTranscript): void => {
  if (pendingFlushes > 0) {
    if (cheetahTranscript.isFlushed) {
      pendingFlushes--;
    }
    return;
  }

  if (mode === 'listening') {
    onListeningTranscript(cheetahTranscript);
  } else if (mode === 'responding') {
    onBargeInTranscript(cheetahTranscript);
  }
};

const voiceProbabilityCallback = (voiceProbability: number): void => {
  if (mode !== 'responding' || turn === null) {
    return;
  }

  if (voiceProbability >= VOICE_PROBABILITY_THRESHOLD) {
    voicedFrames++;
    unvoicedFrames = 0;
  } else {
    unvoicedFrames++;
    voicedFrames = 0;
  }

  if (!isBargeInArmed) {
    isBargeInArmed = unvoicedFrames >= VOICE_REARM_FRAMES;
    return;
  }

  if (bargeIn === null) {
    if (voicedFrames >= VOICE_ONSET_FRAMES) {
      startBargeIn();
    }
    return;
  }

  const { cobra } = object!;
  if (voiceProbability >= VOICE_PROBABILITY_THRESHOLD) {
    bargeIn.voicedFrames++;
    if (bargeIn.voicedFrames >= Math.ceil((BARGE_IN_PAUSE_VOICE_SEC * cobra.sampleRate) / cobra.frameLength)) {
      pauseForBargeIn();
    }
  } else if (
    unvoicedFrames >= Math.ceil((BARGE_IN_RESUME_SILENCE_SEC * cobra.sampleRate) / cobra.frameLength) &&
    toWords(bargeIn.heard).length === 0
  ) {
    resumeAfterBargeIn();
  }
};

const detectionCallback = (): void => {
  if (mode === 'waiting' || mode === 'listening') {
    listen(true);
  }
};

const init = async (accessKey: string, pvCallback: PvCallback): Promise<void> => {
  if (object !== null) {
    return;
  }

  callbacks = pvCallback;

  callbacks.onLoading('Porcupine');
  const porcupine = await PorcupineWorker.create(
    accessKey,
    {
      publicPath: 'keywords/domain_voice_assistant_web.ppn',
      label: 'wake word',
      sensitivity: WAKE_WORD_SENSITIVITY,
      forceWrite: FORCE_WRITE,
    },
    detectionCallback,
    { publicPath: 'models/porcupine_params.pv', forceWrite: FORCE_WRITE }
  );

  callbacks.onLoading('Cheetah');
  const cheetah = await CheetahWorker.create(
    accessKey,
    transcriptCallback,
    { publicPath: 'models/cheetah_params.pv', forceWrite: FORCE_WRITE },
    {
      endpointDurationSec: ENDPOINT_DURATION_SEC,
      enableAutomaticPunctuation: true,
      enableTextNormalization: true,
    }
  );

  callbacks.onLoading('Cobra');
  const cobra = await CobraWorker.create(accessKey, voiceProbabilityCallback);

  callbacks.onLoading('picoLLM');
  const pllm = await PicoLLMWorker.create(
    accessKey,
    { modelFile: 'models/picollm_model.pllm', cacheFileOverwrite: FORCE_WRITE },
    { device: PICOLLM_DEVICE, enableContextCaching: ENABLE_CONTEXT_CACHING }
  );

  const dialog = pllm.getDialog(undefined, DIALOG_HISTORY, SYSTEM_PROMPT);
  dialog.addHumanRequest('');
  await pllm.generate(dialog.prompt(), { completionTokenLimit: 1 });

  callbacks.onLoading('Orca');
  const orca = await OrcaWorker.create(
    accessKey,
    { publicPath: 'models/orca_params.pv', forceWrite: FORCE_WRITE }
  );

  const player: Player = {
    audioStream: new AudioStream(orca.sampleRate),
    sampleRate: orca.sampleRate,
    onSpeaking: () => callbacks!.onSpeaking(),
  };

  object = {
    porcupine,
    cheetah,
    cobra,
    pllm,
    orca,
    player,
  };
};

const start = async (): Promise<void> => {
  if (object === null) {
    return;
  }

  await WebVoiceProcessor.subscribe([object.cobra, cheetahEngine]);
  wait();
};

const ask = (question: string): void => {
  if (object === null || mode === 'idle' || question.trim().length === 0) {
    return;
  }

  interruptTurn();
  startTurn(question.trim());
};

const stop = (): void => {
  if (interruptTurn()) {
    listen(false);
  }
};

const resetDialog = (): void => {
  interruptTurn();
  exchanges = [];
  if (mode !== 'idle') {
    wait();
  }
};

const release = async (): Promise<void> => {
  if (object === null) {
    return;
  }

  interruptTurn();
  clearListenTimeout();
  await WebVoiceProcessor.reset();
  isPorcupineActive = false;

  const { porcupine, cheetah, cobra, pllm, orca, player } = object;
  player.audioStream.clear();
  porcupine.terminate();
  cheetah.terminate();
  cobra.terminate();
  await pllm.release();
  orca.terminate();

  object = null;
  mode = 'idle';
};

export {
  init,
  start,
  ask,
  stop,
  resetDialog,
  release,
};
