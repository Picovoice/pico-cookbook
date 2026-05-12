import { CheetahTranscript, CheetahWorker } from '@picovoice/cheetah-web';
import { ZebraWorker } from '@picovoice/zebra-web';
import { AudioStream } from './audio_stream';
import { Mutex } from 'async-mutex';

const textMutex = new Mutex();
const translateMutex = new Mutex();

const LANGUAGE_PAIRS: any = {
  de: [
    'de',
    'en',
    'es',
    'fr',
    'it',
  ],
  en: [
    'de',
    'en',
    'es',
    'fr',
    'it',
  ],
  es: [
    'de',
    'en',
    'es',
    'fr',
    'it',
  ],
  fr: [
    'de',
    'en',
    'es',
    'fr',
  ],
  it: [
    'de',
    'en',
    'es',
    'it',
  ]
};

export enum UIState {
  INIT = 'INIT',
  RECORDING = 'RECORDING',
  LOADING_MODEL = 'LOADING_MODEL',
  CAPTIONING = 'CAPTIONING',
}

type PvObject = {
  cheetah: CheetahWorker;
  zebra: ZebraWorker | null;
  audioStream: AudioStream;
};

type DemoCallbacks = {
  onStateChange: (state: UIState) => void;
  onOriginalText: (text: TranscriptLine[]) => void;
  onModifiedText: (text: string) => void;
  onAudioReady: (pcm: Int16Array) => void;
  onVolume: (volume: number) => void;
  onError: (error: string) => void;
};

let object: PvObject | null = null;
let currentState = UIState.INIT;

type TranscriptLine = {
  caption: string;
  translated: string;
}

let transcriptLines: TranscriptLine[] = [];
let transcriptQueue = '';

const NO_MEMO_ERROR_PHRASE = 'You need to record a memo first.';

const MIN_DB = -40.0;
const MAX_DB = 0.0;
let callbacks: DemoCallbacks | null = null;

const customAudioEngine = {
  onmessage: async (event: MessageEvent) => {
    if (event.data.command !== 'process') return;
    const frame: Int16Array = event.data.inputFrame;

    let sum = 0;
    for (let i = 0; i < frame.length; i++) {
      sum += Math.pow(frame[i], 2);
    }
    const rms = sum / frame.length / Math.pow(32767, 2);
    const db = 10 * Math.log10(Math.max(rms, 1e-9));
    const normalized = (db - MIN_DB) / (MAX_DB - MIN_DB);
    const normalizedVolume = Math.max(0.0, Math.min(1.0, normalized));

    if (callbacks?.onVolume) {
      callbacks.onVolume(normalizedVolume);
    }
  },
};

const init = async (
    accessKey: string,
    sourceLanguage: string,
    targetLanguage: string,
    cb: DemoCallbacks): Promise<void> => {
  if (object !== null) return;

  callbacks = cb;

  setState(UIState.LOADING_MODEL);

  const transcribeCallback = async (transcript: CheetahTranscript) => {
    if (currentState === UIState.CAPTIONING) {
      const textRelease = await textMutex.acquire();
      transcriptQueue += transcript.transcript;

      if (transcript.isEndpoint) {
        cheetah.flush();
      }

      callbacks!.onModifiedText(transcriptQueue);
      await translateCallback(transcript.isFlushed ?? false);
      textRelease();
    }
  };
  const cheetah = await CheetahWorker.create(
    accessKey,
    transcribeCallback,
    { publicPath: `models/cheetah_params_${sourceLanguage}.pv`, forceWrite: true },
    {
      endpointDurationSec: 1,
      enableAutomaticPunctuation: true,
      enableTextNormalization: true
    }
  );

  const translateCallback = async (isFlushed: boolean) => {
    const hasEndRe = /[.!?](?:\s|$)/;
    const sentencesRe = /^((?:.*?[.!?](?:\s+|$))+)?(.*)$/s;

    // TODO: Handle flush

    if (transcriptQueue.search(hasEndRe) === -1) {
      return;
    }

    const match = transcriptQueue.match(sentencesRe);
    if (match === null) {
      return;
    }
    const sentences = match![1]
    const tail = match![2]

    if (zebra !== null && sentences) {
      const translateRelease = await translateMutex.acquire();
      const translated = await zebra.translate(sentences);
      translateRelease();

      transcriptLines.push({ caption: sentences, translated })
      callbacks!.onOriginalText(transcriptLines);
    }

    transcriptQueue = tail;
  }
  let zebra: ZebraWorker | null = null;
  if (sourceLanguage !== targetLanguage) {
    zebra = await ZebraWorker.create(
      accessKey,
      { publicPath: `models/zebra_params_${sourceLanguage}_${targetLanguage}.pv`, forceWrite: true },
    );
  }

  const audioStream = new AudioStream(cheetah.sampleRate);

  object = { cheetah, zebra, audioStream };
};

const setState = (state: UIState) => {
  currentState = state;
  callbacks!.onStateChange(state);
};

const start = async (audioFile: File): Promise<void> => {
  setState(UIState.CAPTIONING);

  // @ts-ignore
  const audioContext = new (window.AudioContext || window.webKitAudioContext)({
    sampleRate: 16000,
  });

  function readAudioFile(selectedFile: any, callback: any) {
    let reader = new FileReader();
    reader.onload = function (ev: any) {
      let wavBytes = reader.result;
      // @ts-ignore
      audioContext.decodeAudioData(wavBytes, callback);
    };
    reader.readAsArrayBuffer(selectedFile);
  }

  readAudioFile(audioFile, async (audioBuffer: any) => {
    const f32PCM = audioBuffer.getChannelData(0);
    const i16PCM = new Int16Array(f32PCM.length);

    const INT16_MAX = 32767;
    const INT16_MIN = -32768;
    i16PCM.set(
      f32PCM.map((f: number) => {
        let i = Math.trunc(f * INT16_MAX);
        if (f > INT16_MAX) i = INT16_MAX;
        if (f < INT16_MIN) i = INT16_MIN;
        return i;
      }),
    );

    await caption(i16PCM);
  });
};

const caption = async (pcm: Int16Array) => {
  const playback_start_ms = performance.now();
  let played_samples = 0;

  const numCheetahFrames = Math.floor(pcm.length / object!.cheetah.frameLength)
  for (let i = 1; i < numCheetahFrames; i++) {
    const pcmStart = (i - 1) * object!.cheetah.frameLength;
    const pcmEnd = (i) * object!.cheetah.frameLength;
    const pcmSlice = pcm.slice(pcmStart, pcmEnd);

    object!.cheetah.process(pcmSlice);
    object!.audioStream.stream(pcmSlice);
    object!.audioStream.play();

    played_samples += pcmSlice.length;
    const playback_time_ms = (played_samples / object!.cheetah.sampleRate) * 1000;
    const delay_ms = playback_start_ms + playback_time_ms - performance.now()

    if (delay_ms > 0) {
      await new Promise(r => setTimeout(r, delay_ms));
    }
  }

  await object!.audioStream.waitPlayback();
}

const release = async (): Promise<void> => {
  if (object === null) {
    return;
  }

  const { cheetah, zebra } = object;
  cheetah.terminate();
  if (zebra !== null) {
    zebra!.terminate();
  }

  object = null;
};

export {
  LANGUAGE_PAIRS,
  init,
  start,
  release,
};