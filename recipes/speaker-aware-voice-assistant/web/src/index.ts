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

export type DemoCallbacks = {
  onUpdateStatus: (newStatus: string) => void;
  onVolume: (volume: number) => void;
  onEnrollProgress: (progress: number) => void;
  onEnrollComplete: (profile: EagleProfile) => void;
  onWakeWordRecognized: () => void;
  onWordSpoken: (word: string) => void;
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

let callbacks: DemoCallbacks = {
  onUpdateStatus: (_) => undefined,
  onVolume: (_) => undefined,
  onEnrollProgress: (_) => undefined,
  onEnrollComplete: (_) => undefined,
  onWakeWordRecognized: () => undefined,
  onWordSpoken: (_) => undefined,
  onError: (_) => undefined
};

const MIN_DB = -40.0;
const MAX_DB = 0.0;

const ENROLLMENT_SENTENCES = [
    "The quick brown fox jumps over the lazy dog.",
    "I am recording my voice for speaker enrollment.",
    "This is my normal speaking voice in a quiet room.",
    "The assistant should recognize me when I speak.",
    "Voice recognition works best with clean and natural speech.",
];

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

    var tempPcmBuffer = new Int16Array(eagleProfilerPcmBuffer.length + frame.length);
    tempPcmBuffer.set(eagleProfilerPcmBuffer);
    tempPcmBuffer.set(frame, eagleProfilerPcmBuffer.length);
    eagleProfilerPcmBuffer = tempPcmBuffer

    if (eagleProfilerPcmBuffer.length >= eagleProfiler.frameLength) {
      enrollProgress = await eagleProfiler.enroll(eagleProfilerPcmBuffer.slice(0, eagleProfiler.frameLength));
      callbacks.onEnrollProgress(enrollProgress);

      eagleProfilerPcmBuffer = eagleProfilerPcmBuffer.slice(eagleProfiler.frameLength);

      if (enrollProgress >= 100) {
        const profile = await eagleProfiler.export();
        await stopEnrollment();

        callbacks.onEnrollComplete(profile);
        eagleProfilerPcmBuffer = new Int16Array();
      }
    }
  },
};

var porcupinePcmBuffer = new Int16Array();
const bufferedPorcupineEngine: PvEngine = {
  onmessage: async (e: MessageEvent) => {
    if (e.data.command !== 'process') return;
    const frame: Int16Array = e.data.inputFrame;

    var tempPcmBuffer = new Int16Array(porcupinePcmBuffer.length + frame.length);
    tempPcmBuffer.set(porcupinePcmBuffer);
    tempPcmBuffer.set(frame, porcupinePcmBuffer.length);
    porcupinePcmBuffer = tempPcmBuffer

    if (porcupine !== null && porcupinePcmBuffer.length >= porcupine.frameLength) {
      porcupine.process(porcupinePcmBuffer.slice(0, porcupine.frameLength));
      porcupinePcmBuffer = porcupinePcmBuffer.slice(porcupine.frameLength);
    }
  }
}

var rhinoPcmBuffer = new Int16Array();
const bufferedRhinoEngine: PvEngine = {
  onmessage: async (e: MessageEvent) => {
    if (e.data.command !== 'process') return;
    const frame: Int16Array = e.data.inputFrame;

    var tempPcmBuffer = new Int16Array(rhinoPcmBuffer.length + frame.length);
    tempPcmBuffer.set(rhinoPcmBuffer);
    tempPcmBuffer.set(frame, rhinoPcmBuffer.length);
    rhinoPcmBuffer = tempPcmBuffer

    if (rhino !== null && rhinoPcmBuffer.length >= rhino.frameLength) {
      rhino.process(rhinoPcmBuffer.slice(0, rhino.frameLength));
      rhinoPcmBuffer = rhinoPcmBuffer.slice(rhino.frameLength);
    }
  }
}

// -------------------------------------------------------------------------- //

const synthesizeAndPlayback = async (
  orca: OrcaWorker,
  audio: AudioStream,
  text: string,
  addWord: (word: string) => void
) => {
  const synthesis = await orca.synthesize(text, {});
  const alignments = synthesis.alignments;

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

    setTimeout(() => {
      addWord(word);
    }, alignment.startSec * 1000);
  }

  await audio.waitPlayback();
};

// -------------------------------------------------------------------------- //

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
  if (!inference.isFinalized) {
    return;
  } else if (!inference.isUnderstood) {
    rhinoPcmBuffer = new Int16Array();
    console.log("unknown option selected");
    return;
  }

  if (rhinoPcmBuffer.length < eagle.minProcessSamples()) {
    rhinoPcmBuffer.fill(0, rhinoPcmBuffer.length, eagle.minProcessSamples());
  }

  await WebVoiceProcessor.unsubscribe(bufferedRhinoEngine);

  let similarities = await eagle.process(rhinoPcmBuffer, enrolledProfiles.map(x => x.profile));
  if (similarities == null) {
    await synthesizeAndPlayback(orca!, audio!, "Sorry, I could not identify who is speaking.", callbacks.onWordSpoken);
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
          await synthesizeAndPlayback(orca!, audio!, "Admin command approved.", callbacks.onWordSpoken);
        } else {
          await synthesizeAndPlayback(orca!, audio!, "Permission denied. This command requires an admin.", callbacks.onWordSpoken);
        }
      } else {
        await synthesizeAndPlayback(orca!, audio!, "Sorry, I could not verify your voice.", callbacks.onWordSpoken);
      }
    } else if (inference.intent === "speakerPersonalized") {
      await synthesizeAndPlayback(orca!, audio!, `Hi ${user.name}. I will personalize this command for you.`, callbacks.onWordSpoken);
    } else if (inference.intent === "generic") {
      await synthesizeAndPlayback(orca!, audio!, 'Okay. This command is available to everyone.', callbacks.onWordSpoken);
    }
  }

  rhinoPcmBuffer = new Int16Array();
  await WebVoiceProcessor.subscribe(bufferedRhinoEngine);
};

const init = async (accessKey: string, cb: DemoCallbacks): Promise<void> => {
  callbacks = cb;

  try {
    const FORCE_WRITE = true;

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
      voiceThreshold: 0.0,
    });
    callbacks.onUpdateStatus("Loading Eagle");
    eagle = await EagleWorker.create(accessKey, EAGLE_MODEL, {
      voiceThreshold: 0.0
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
      { publicPath: 'models/call_assist_demo_web.rhn', forceWrite: FORCE_WRITE },
      rhinoInferenceCallback,
      { publicPath: 'models/rhino_params.pv', forceWrite: FORCE_WRITE },
    );

    callbacks.onUpdateStatus("Loading Audio Stream");
    audio = new AudioStream(orca.sampleRate);

    await WebVoiceProcessor.subscribe(callbackAudioEngine);

  } catch (e: any) {
    callbacks.onError(e.toString());
    throw e;
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
};
