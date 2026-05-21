import { WebVoiceProcessor } from '@picovoice/web-voice-processor';
import { PvEngine } from '@picovoice/web-voice-processor/dist/types/types';

import { TASKS, Workflow, RecipeSteps, RecipeStates, State } from './states';
import { Steps } from './steps';

const sleep = (ms: number) => new Promise(resolve => setTimeout(resolve, ms));

/*
// Might be needed
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
*/

export type DemoCallbacks = {
  onUpdateStatus: (newStatus: string) => void;
  setLoadingState: (enabled: boolean) => Promise<void>,
  onVolume: (volume: number) => void;
  onWordSpoken: (word: string) => void;
  onError: (error: string) => void;
};

let workflow: Workflow | null = null;

let callbacks: DemoCallbacks = {
  onUpdateStatus: (_) => undefined,
  setLoadingState: async (_) => undefined,
  onVolume: (_) => undefined,
  onWordSpoken: (_) => undefined,
  onError: (_) => undefined
};

const MIN_DB = -40.0;
const MAX_DB = 0.0;

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

// TODO: connect this to the noise suppressed recorder?
const callbackAudioEngine: PvEngine = {
  onmessage: async (event: MessageEvent) => {
    if (event.data.command !== 'process') return;
    const frame: Int16Array = event.data.inputFrame;

    callbacks.onVolume(normalizeAudio(frame));
  },
};

/*
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
*/

const init = async (accessKey: string, cb: DemoCallbacks): Promise<void> => {
  callbacks = cb;

  try {
    callbacks.setLoadingState(true);

    callbacks.onUpdateStatus("Loading Workflow");

    /*porcupine = await PorcupineWorker.create(
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
    audio = new AudioStream(orca.sampleRate);*/

    let accessKey = "TODO";
    let keywordPath = "TODO";
    let contextPath = "TODO";

    // TODO: loading should be done internally here, & I should pass in my status
    // callback so they can log it
    workflow = new Workflow(
      accessKey,
      {
        steps: {
          [RecipeSteps.STANDBY]:     { step: Steps.PORCUPINE, keywordPath },
          [RecipeSteps.PROMPT_USER]: { step: Steps.ORCA },
          [RecipeSteps.RECORD_USER]: { step: Steps.RHINO, contextPath },
        },
        all_states: [
          RecipeStates.STANDBY,
          RecipeStates.TASK_LOCATION_PROMPT,
          RecipeStates.TASK_LOCATION_REPORT,
          RecipeStates.TASK_PICK_PROMPT,
          RecipeStates.TASK_PICK_REPORT,
          RecipeStates.COMPLETE_PROMPT,
        ],
        state_creator: State.create,
        state_steps: {
          [RecipeStates.STANDBY]: RecipeSteps.STANDBY,
          [RecipeStates.TASK_LOCATION_PROMPT]: RecipeSteps.PROMPT_USER,
          [RecipeStates.TASK_LOCATION_REPORT]: RecipeSteps.RECORD_USER,
          [RecipeStates.TASK_PICK_PROMPT]: RecipeSteps.PROMPT_USER,
          [RecipeStates.TASK_PICK_REPORT]: RecipeSteps.RECORD_USER,
          [RecipeStates.COMPLETE_PROMPT]: RecipeSteps.PROMPT_USER,
        },
        start_state: { state: RecipeStates.STANDBY, tasks: TASKS },
      }
    );

    await WebVoiceProcessor.subscribe(callbackAudioEngine);

  } catch (e: any) {
    callbacks.onError(e.toString());
    throw e;
  } finally {
    callbacks.onUpdateStatus("");
    await callbacks.setLoadingState(false);
  }
};

const start = async (): Promise<void> => {
  workflow!.run();
};

const stop = async (): Promise<void> => {
  // TODO: what to do here?
};

const release = async (): Promise<void> => {
  await WebVoiceProcessor.reset();

  workflow!.release();
};

export default {
  init,
  start,
  stop,
  release,
  sleep,
};
