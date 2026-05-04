import { PorcupineWorker } from '@picovoice/porcupine-web';
import {
  EagleProfilerWorker,
  EagleWorker,
  EagleProfile,
} from '@picovoice/eagle-web';
import { WebVoiceProcessor } from '@picovoice/web-voice-processor';
import { PvEngine } from '@picovoice/web-voice-processor/dist/types/types';

export type DemoCallbacks = {
  onVolume: (volume: number) => void;
  onEnrollProgress: (progress: number) => void;
  onEnrollComplete: () => void;
  onWakeWordRecognized: (isVerified: boolean, score: number) => void;
  onError: (error: string) => void;
};

const EAGLE_THRESHOLD = 0.75;

let porcupine: PorcupineWorker | null = null;
let eagleProfiler: EagleProfilerWorker | null = null;
let eagle: EagleWorker | null = null;
let speakerProfile: EagleProfile | null = null;

let currentState: 'IDLE' | 'ENROLLING' | 'TESTING' = 'IDLE';

let enrollMaxSamples = 0;
let enrollValidSamples = 0;
let enrollSlidingBuffer: Int16Array | null = null;

let testSlidingBuffer: Int16Array | null = null;
let callbacks: DemoCallbacks | null = null;

const MIN_DB = -40.0;
const MAX_DB = 0.0;

const customAudioEngine: PvEngine = {
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

    if (currentState === 'ENROLLING') {
      enrollSlidingBuffer!.copyWithin(0, frame.length);
      enrollSlidingBuffer!.set(frame, enrollMaxSamples - frame.length);
      enrollValidSamples = Math.min(
        enrollMaxSamples,
        enrollValidSamples + frame.length
      );
    } else if (currentState === 'TESTING') {
      testSlidingBuffer!.copyWithin(0, frame.length);
      testSlidingBuffer!.set(frame, testSlidingBuffer!.length - frame.length);
    }
  },
};

const porcupineKeywordCallback = async (): Promise<void> => {
  try {
    if (currentState === 'ENROLLING') {
      const eagleFrameLength = eagleProfiler!.frameLength;
      const startIndex = enrollMaxSamples - enrollValidSamples;
      const numChunks = Math.floor(enrollValidSamples / eagleFrameLength);

      let progress = 0;
      for (let i = 0; i < numChunks; i++) {
        const chunkStart = startIndex + i * eagleFrameLength;
        const chunk = enrollSlidingBuffer!.slice(
          chunkStart,
          chunkStart + eagleFrameLength
        );
        progress = await eagleProfiler!.enroll(chunk);
      }

      enrollValidSamples = 0;

      if (callbacks?.onEnrollProgress) {
        callbacks.onEnrollProgress(progress);
      }

      if (progress >= 100) {
        speakerProfile = await eagleProfiler!.export();
        await stop();
        if (callbacks?.onEnrollComplete) {
          callbacks.onEnrollComplete();
        }
      }
    } else if (currentState === 'TESTING') {
      const scores = await eagle!.process(testSlidingBuffer!, [
        speakerProfile!,
      ]);
      const score = scores && scores.length > 0 ? scores[0] : 0;
      const isVerified = score >= EAGLE_THRESHOLD;

      if (callbacks?.onWakeWordRecognized) {
        callbacks.onWakeWordRecognized(isVerified, score);
      }
    }
  } catch (e: any) {
    if (callbacks?.onError) {
      callbacks.onError(e.toString());
    }
  }
};

const init = async (accessKey: string, cb: DemoCallbacks): Promise<void> => {
  callbacks = cb;
  try {
    const porcupineModel = { publicPath: 'models/porcupine_params.pv' };
    const eagleModel = { publicPath: 'models/eagle_params.pv' };
    const keyword = {
      publicPath: 'keywords/keyword.ppn',
      label: 'wake word',
      sensitivity: 0.5,
    };

    porcupine = await PorcupineWorker.create(
      accessKey,
      [keyword],
      porcupineKeywordCallback,
      porcupineModel
    );

    eagleProfiler = await EagleProfilerWorker.create(accessKey, eagleModel, {
      minEnrollmentChunks: 4,
      voiceThreshold: 0.1,
    });
    eagle = await EagleWorker.create(accessKey, eagleModel, {
      voiceThreshold: 0.0,
    });

    enrollMaxSamples = porcupine.sampleRate * 2;
    enrollSlidingBuffer = new Int16Array(enrollMaxSamples);
    testSlidingBuffer = new Int16Array(eagle.minProcessSamples);
  } catch (e: any) {
    if (callbacks?.onError) {
      callbacks.onError(e.toString());
    }
    throw e;
  }
};

const startEnrollment = async (): Promise<void> => {
  if (WebVoiceProcessor.isRecording) {
    await stop();
  }
  currentState = 'ENROLLING';
  await eagleProfiler!.reset();
  enrollValidSamples = 0;
  enrollSlidingBuffer!.fill(0);

  await WebVoiceProcessor.subscribe(porcupine!);
  await WebVoiceProcessor.subscribe(customAudioEngine);
};

const startTesting = async (): Promise<void> => {
  if (WebVoiceProcessor.isRecording) {
    await stop();
  }
  currentState = 'TESTING';
  testSlidingBuffer!.fill(0);

  await WebVoiceProcessor.subscribe(porcupine!);
  await WebVoiceProcessor.subscribe(customAudioEngine);
};

const stop = async (): Promise<void> => {
  currentState = 'IDLE';
  await WebVoiceProcessor.unsubscribe(porcupine!);
  await WebVoiceProcessor.unsubscribe(customAudioEngine);
};

const release = async (): Promise<void> => {
  await stop();
  if (porcupine) {
    porcupine.terminate();
    porcupine = null;
  }
  if (eagleProfiler) {
    eagleProfiler.terminate();
    eagleProfiler = null;
  }
  if (eagle) {
    eagle.terminate();
    eagle = null;
  }
  speakerProfile = null;
  WebVoiceProcessor.reset();
};

export default {
  init,
  startEnrollment,
  startTesting,
  stop,
  release,
};
