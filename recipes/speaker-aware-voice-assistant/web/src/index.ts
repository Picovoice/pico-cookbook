import { AudioStream } from './audio_stream';
import { PorcupineWorker } from '@picovoice/porcupine-web';
import {
  EagleProfilerWorker,
  EagleWorker,
  EagleProfile,
} from '@picovoice/eagle-web';
import { OrcaWorker } from '@picovoice/orca-web';
import { RhinoInference, RhinoWorker } from '@picovoice/rhino-web';
import { WebVoiceProcessor } from '@picovoice/web-voice-processor';
import { PvEngine } from '@picovoice/web-voice-processor/dist/types/types';

const sleep = (ms: number) => new Promise(resolve => setTimeout(resolve, ms));

class Mutex {
  private locked: boolean = false;
  private queue: Array<(unlock: () => void) => void> = [];

  async lock(): Promise<() => void> {
    return new Promise(resolve => {
      const unlock = () => {
        const next = this.queue.shift();
        if (next) {
          next(unlock);
        } else {
          this.locked = false;
        }
      };

      if (this.locked) {
        this.queue.push(resolve);
      } else {
        this.locked = true;
        resolve(unlock);
      }
    });
  }
}

export type DemoCallbacks = {
  onUpdateStatus: (newStatus: string) => void;
  setLoadingState: (enabled: boolean) => Promise<void>,
  onVolume: (volume: number) => void;
  onEnrollProgress: (progress: number) => void;
  onEnrollComplete: (profile: EagleProfile) => void;
  onWakeWordRecognized: () => void;
  beforeInferenceResponse: () => Promise<void>;
  onWordSpoken: (word: string) => void;
  afterInferenceResponse: () => void;
  onError: (error: string) => void;
};

enum UserRole {
  ADMIN = 'admin',
  USER = 'user',
}

type UserProfile = {
  profile: EagleProfile;
  name: string;
  role: UserRole;
};

let audio: AudioStream | null = null;
let eagleProfiler: EagleProfilerWorker | null = null;
let eagle: EagleWorker | null = null;
let orca: OrcaWorker | null = null;
let porcupine: PorcupineWorker | null = null;
let rhino: RhinoWorker | null = null;

let enrolledProfiles: UserProfile[] = [];
let enrollProgress = 0;
let enrollLock = new Mutex();

let callbacks: DemoCallbacks = {
  onUpdateStatus: (_) => undefined,
  setLoadingState: async (_) => undefined,
  onVolume: (_) => undefined,
  onEnrollProgress: (_) => undefined,
  onEnrollComplete: (_) => undefined,
  onWakeWordRecognized: () => undefined,
  beforeInferenceResponse: async () => undefined,
  onWordSpoken: (_) => undefined,
  afterInferenceResponse: () => undefined,
  onError: (_) => undefined
};

const MIN_DB = -40.0;
const MAX_DB = 0.0;

const ADMIN_SIMILARITY_THRESHOLD = 0.9;

const normalizeAudio = (frame: Int16Array) => {
  let sum = 0;
  for (let i = 0; i < frame.length; i++) {
    sum += Math.pow(frame[i], 2);
  }
  const rms = sum / frame.length / Math.pow(32767, 2);
  const db = 10 * Math.log10(Math.max(rms, 1e-9));
  const normalized = (db - MIN_DB) / (MAX_DB - MIN_DB);
  const normalizedVolume = Math.max(0.0, Math.min(1.0, normalized));

  return normalizedVolume;
}

const concat = (frame1: Int16Array, frame2: Int16Array): Int16Array => {
  const tempPcmBuffer = new Int16Array(frame1.length + frame2.length);
  tempPcmBuffer.set(frame1);
  tempPcmBuffer.set(frame2, frame1.length);
  return tempPcmBuffer;
}

const callbackAudioEngine: PvEngine = {
  onmessage: async (event: MessageEvent) => {
    if (event.data.command !== 'process') return;
    const frame: Int16Array = event.data.inputFrame;

    callbacks.onVolume(normalizeAudio(frame));
  },
};

var eagleProfilerPcmBuffer = new Int16Array();
const eagleProfilerAudioEngine: PvEngine = {
  onmessage: async (event: MessageEvent) => {
    if (event.data.command !== 'process')
      return;
    else if (eagleProfiler == null)
      throw Error("bad state. unexpected eagleProfiler to be null");

    const frame: Int16Array = event.data.inputFrame;

    const unlock = await enrollLock.lock();

    try {
      if (enrollProgress >= 100) {
        return;
      }

      eagleProfilerPcmBuffer = concat(eagleProfilerPcmBuffer, frame);

      if (eagleProfilerPcmBuffer.length >= eagleProfiler.frameLength) {
        enrollProgress = await eagleProfiler.enroll(eagleProfilerPcmBuffer.slice(0, eagleProfiler.frameLength));
        callbacks.onEnrollProgress(enrollProgress);

        eagleProfilerPcmBuffer = eagleProfilerPcmBuffer.slice(eagleProfiler.frameLength);

        if (enrollProgress >= 100) {
          await stopEnrollment();

          const profile = await eagleProfiler.export();

          callbacks.onEnrollComplete(profile);
          eagleProfilerPcmBuffer = new Int16Array();
        }
      }
    } finally {
      unlock();
    }
  },
};

var porcupinePcmBuffer = new Int16Array();
const bufferedPorcupineEngine: PvEngine = {
  onmessage: async (e: MessageEvent) => {
    if (e.data.command !== 'process') return;
    else if (porcupine == null) return;
    const frame: Int16Array = e.data.inputFrame;

    porcupinePcmBuffer = concat(porcupinePcmBuffer, frame);

    if (porcupinePcmBuffer.length >= porcupine.frameLength) {
      porcupine.process(porcupinePcmBuffer.slice(0, porcupine.frameLength));
      porcupinePcmBuffer = porcupinePcmBuffer.slice(porcupine.frameLength);
    }
  }
}

const RUNNING_PCM_BUFFER_MAX_LENGTH_S = 15;

var rhinoPcmBuffer = new Int16Array();
var rhinoRunningPcmBuffer = new Int16Array();
const bufferedRhinoEngine: PvEngine = {
  onmessage: async (e: MessageEvent) => {
    if (e.data.command !== 'process') return;
    else if (rhino == null) return;
    const frame: Int16Array = e.data.inputFrame;

    rhinoPcmBuffer = concat(rhinoPcmBuffer, frame);
    rhinoRunningPcmBuffer = concat(rhinoRunningPcmBuffer, frame);

    if (rhinoPcmBuffer.length >= rhino.frameLength) {
      rhino.process(rhinoPcmBuffer.slice(0, rhino.frameLength));
      rhinoPcmBuffer = rhinoPcmBuffer.slice(rhino.frameLength);
    }
    
    if (rhinoRunningPcmBuffer.length >= (rhino.sampleRate * RUNNING_PCM_BUFFER_MAX_LENGTH_S)) {
      rhinoRunningPcmBuffer = rhinoRunningPcmBuffer.slice(
        rhinoRunningPcmBuffer.length - RUNNING_PCM_BUFFER_MAX_LENGTH_S
      );
    }
  }
}

const synthesizeAndPlayback = async (
  orca: OrcaWorker,
  audio: AudioStream,
  text: string,
  waitCondition: () => Promise<void>,
  addWord: (word: string) => void
) => {
  const synthesis = await orca.synthesize(text, {});
  const alignments = synthesis.alignments;

  await waitCondition();

  audio.stream(synthesis.pcm);
  audio.play();

  for (let alignment of alignments) {
    const index = alignments.indexOf(alignment);
    let word = alignment.word;
    if (index < alignments.length - 1 &&
        !(/[.,:;!?]/.test(alignments[index + 1].word))
    ) {
      word += " ";
    }

    // Display the word 200ms early because we have a 300ms css transition, and
    // the word should be mostly visible by that time.
    setTimeout(() => {
      addWord(word);
    }, (alignment.startSec * 1000) - 200);
  }

  await audio.waitPlayback();
};

const porcupineKeywordCallback = async (): Promise<void> => {
  try {
    await WebVoiceProcessor.unsubscribe(bufferedPorcupineEngine);
    porcupinePcmBuffer = new Int16Array();
    
    await WebVoiceProcessor.subscribe(bufferedRhinoEngine);
    callbacks.onWakeWordRecognized();

  } catch (e: any) {
    callbacks.onError(e.toString());
  }
};

const rhinoInferenceCallback = async (
  inference: RhinoInference
): Promise<void> => {
  if (!eagle) {
    throw Error("Eagle was not initialized")
  } else if (!inference.isFinalized) {
    return;
  } else if (!inference.isUnderstood) {
    rhinoPcmBuffer = new Int16Array();
    rhinoRunningPcmBuffer = new Int16Array();
    console.log("unknown option selected");
    return;
  }

  let paddedPcmBuffer = new Int16Array(Math.max(eagle.minProcessSamples, rhinoRunningPcmBuffer.length));
  paddedPcmBuffer.set(rhinoRunningPcmBuffer);

  await WebVoiceProcessor.unsubscribe(bufferedRhinoEngine);
  await WebVoiceProcessor.unsubscribe(callbackAudioEngine);
  try {
    callbacks.onVolume(0);

    let similarities = await eagle.process(paddedPcmBuffer, enrolledProfiles.map(x => x.profile));
    callbacks.beforeInferenceResponse();

    let spokenText;
    if (similarities == null) {
      spokenText = "Sorry, I could not identify who is speaking.";
    } else {
      let highestSimilarityUserIndex = 0;
      for (let i = 1; i < similarities.length; i++) {
        if (similarities[i] > similarities[highestSimilarityUserIndex]) {
          highestSimilarityUserIndex = i;
        }
      }

      const similarityScore = similarities[highestSimilarityUserIndex];
      const user = enrolledProfiles[highestSimilarityUserIndex];

      if (inference.intent === "adminOnly") {
        if (similarityScore >= ADMIN_SIMILARITY_THRESHOLD) {
          if (user.role === UserRole.ADMIN) {
            spokenText = "Admin command approved.";
          } else {
            spokenText = "Permission denied. This command requires an admin.";
          }
        } else {
          spokenText = "Sorry, I could not verify your voice.";
        }
      } else if (inference.intent === "speakerPersonalized") {
        spokenText = `Hi ${user.name}. I will personalize this command for you.`;
      } else if (inference.intent === "generic") {
        spokenText = "Okay. This command is available to everyone.";
      } else {
        rhinoPcmBuffer = new Int16Array();
        rhinoRunningPcmBuffer = new Int16Array();
        console.log("unknown intent. Was probably noise");
        return;
      }
    }

    const promise = callbacks.beforeInferenceResponse();
    await synthesizeAndPlayback(
        orca!,
        audio!,
        spokenText,
        async () => { await promise; },
        callbacks.onWordSpoken
    );

    await sleep(800);

  } finally {
    await WebVoiceProcessor.subscribe(callbackAudioEngine);
  }

  callbacks.afterInferenceResponse();

  rhinoPcmBuffer = new Int16Array();
  rhinoRunningPcmBuffer = new Int16Array();

  await WebVoiceProcessor.subscribe(bufferedPorcupineEngine);
};

const init = async (accessKey: string, cb: DemoCallbacks): Promise<void> => {
  callbacks = cb;

  try {
    const FORCE_WRITE = true;

    callbacks.setLoadingState(true);

    callbacks.onUpdateStatus("Loading Porcupine");
    porcupine = await PorcupineWorker.create(
      accessKey,
      [{
        publicPath: 'keywords/speaker_aware_voice_assistant_demo_web.ppn',
        label: 'wake word',
        sensitivity: 0.5,
        forceWrite: FORCE_WRITE
      }],
      porcupineKeywordCallback,
      { publicPath: 'models/porcupine_params.pv', forceWrite: FORCE_WRITE }
    );

    const EAGLE_MODEL = {
      publicPath: 'models/eagle_params.pv',
      forceWrite: FORCE_WRITE
    };

    callbacks.onUpdateStatus("Loading Eagle Profiler");
    eagleProfiler = await EagleProfilerWorker.create(accessKey, EAGLE_MODEL, {
      minEnrollmentChunks: 4,
      voiceThreshold: 0.3,
    });
    callbacks.onUpdateStatus("Loading Eagle");
    eagle = await EagleWorker.create(accessKey, EAGLE_MODEL, {
      voiceThreshold: 0.3
    });

    callbacks.onUpdateStatus("Loading Orca");
    orca = await OrcaWorker.create(
      accessKey,
      { publicPath: "models/orca_params_en_female.pv", forceWrite: FORCE_WRITE },
      {}
    );

    callbacks.onUpdateStatus("Loading Rhino");
    rhino = await RhinoWorker.create(
      accessKey,
      { publicPath: 'models/speaker_aware_voice_assistant_demo_web.rhn', forceWrite: FORCE_WRITE },
      rhinoInferenceCallback,
      { publicPath: 'models/rhino_params.pv', forceWrite: FORCE_WRITE },
    );

    callbacks.onUpdateStatus("Loading Audio Stream");
    audio = new AudioStream(orca.sampleRate);

    await WebVoiceProcessor.subscribe(callbackAudioEngine);

  } catch (e: any) {
    callbacks.onError(e.toString());
    throw e;
  } finally {
    callbacks.onUpdateStatus("");
    await callbacks.setLoadingState(false);
  }
};

const startEnrollment = async (userRole:string): Promise<void> => {
  if (userRole != UserRole.ADMIN && userRole != UserRole.USER) {
    throw new Error('Got unknown user role');
  }
  
  await eagleProfiler!.reset();
  
  enrollProgress = 0;
  await WebVoiceProcessor.subscribe(eagleProfilerAudioEngine);
};

const stopEnrollment = async (): Promise<void> => {
  await WebVoiceProcessor.unsubscribe(eagleProfilerAudioEngine);
};

const startTesting = async (profiles: UserProfile[]): Promise<void> => {
  if (profiles.length === 0) {
    throw new Error('No speaker profiles enrolled.');
  }

  enrolledProfiles = profiles;

  await WebVoiceProcessor.subscribe(bufferedPorcupineEngine);
};

const stopTesting = async (): Promise<void> => {
  await WebVoiceProcessor.unsubscribe(bufferedPorcupineEngine);
};

const release = async (): Promise<void> => {
  await WebVoiceProcessor.reset();
  enrolledProfiles = [];

  if (rhino) {
    await rhino.release();
    rhino = null;
  }

  if (porcupine) {
    await porcupine.terminate();
    porcupine = null;
  }

  if (orca) {
    await orca.release();
    orca = null;
  }

  if (eagleProfiler) {
    await eagleProfiler.terminate();
    eagleProfiler = null;
  }

  if (eagle) {
    await eagle.terminate();
    eagle = null;
  }

  if (audio) {
    audio.clear();
    audio = null;
  }

  rhinoPcmBuffer = new Int16Array();
  rhinoRunningPcmBuffer = new Int16Array();
  porcupinePcmBuffer = new Int16Array();
  eagleProfilerPcmBuffer = new Int16Array();
};

export default {
  init,
  startEnrollment,
  stopEnrollment,
  startTesting,
  stopTesting,
  release,
  sleep,
};