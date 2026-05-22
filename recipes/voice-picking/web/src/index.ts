import { WebVoiceProcessor } from '@picovoice/web-voice-processor';
import { PvEngine } from '@picovoice/web-voice-processor/dist/types/types';

import { PickTask, Workflow, RecipeSteps, RecipeStates, State } from './states';
import { Steps } from './steps';

export const sleep = (ms: number) => new Promise(resolve => setTimeout(resolve, ms));

export type DemoCallbacks = {
  setLoadingState: (enabled: boolean) => Promise<void>,
  onUpdateStatus: (newStatus: string) => void;
  onVolume: (volume: number) => void;
  onListening: (isListening: boolean) => void;
  onUpdateCard: (cardId: string, text: string, isFinal: boolean) => void;
  onError: (error: string) => void;
};

export let callbacks: DemoCallbacks = {
  setLoadingState: async (_) => undefined,
  onUpdateStatus: (_) => undefined,
  onVolume: (_) => undefined,
  onListening: (_) => undefined,
  onUpdateCard: (_a, _b, _c) => undefined,
  onError: (_) => undefined
};

export let isRunning = true;

let workflow: Workflow | null = null;

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

const callbackAudioEngine: PvEngine = {
  onmessage: async (event: MessageEvent) => {
    if (event.data.command !== 'process') return;
    const frame: Int16Array = event.data.inputFrame;

    callbacks.onVolume(normalizeAudio(frame));
  },
};

export const TASKS: PickTask[] = [
    {
        locationName: "bin bravo",
        checkDigit: "four two",
        itemName: "blue widgets",
        quantity: 3
    },
    {
        locationName: "bin delta",
        checkDigit: "five seven",
        itemName: "battery packs",
        quantity: 5
    },
    {
        locationName: "zone one",
        checkDigit: "one nine",
        itemName: "safety gloves",
        quantity: 1
    },
];

const init = async (accessKey: string, cb: DemoCallbacks): Promise<void> => {
  callbacks = cb;

  try {
    await callbacks.setLoadingState(true);
    callbacks.onUpdateStatus("Loading Workflow");

    workflow = new Workflow(
      accessKey,
      {
        steps: {
          [RecipeSteps.STANDBY]:     { step: Steps.PORCUPINE, keywordPath: "keywords/voice_picking_web.ppn" },
          [RecipeSteps.PROMPT_USER]: { step: Steps.ORCA },
          [RecipeSteps.RECORD_USER]: { step: Steps.RHINO, contextPath: "models/voice_picking_web.rhn" },
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
  isRunning = true;
  await workflow!.run();
};

const stop = async (): Promise<void> => {
  isRunning = false;
  workflow!.reset();
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
