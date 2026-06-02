import { WebVoiceProcessor } from '@picovoice/web-voice-processor';
import { PvEngine } from '@picovoice/web-voice-processor/dist/types/types';

import { Card, Workflow, RecipeSteps, RecipeStates, State } from './states';
import { Steps } from './steps';

import { sleep, DemoCallbacks, callbacks, updateCallbacks, setIsRunning } from './config';

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

const TASKS: Card[] = [
  {
    cardId: "IdentifyUnit",
    cardTitle: "Unit ID"
  },
  {
    cardId: "OilCondition",
    cardTitle: "Oil Condition"
  },
  {
    cardId: "TireCondition",
    cardTitle: "Tire Condition"
  },
  {
    cardId: "ServiceStatus",
    cardTitle: "Service Status"
  },
  {
    cardId: "FinalNote",
    cardTitle: "Final Note"
  },
];

const init = async (accessKey: string, cb: DemoCallbacks): Promise<void> => {
  updateCallbacks(cb);

  callbacks.clearStatus();

  for (const task of TASKS) {
    callbacks.createCard(task.cardId, task.cardTitle);
  }

  try {
    await callbacks.setLoadingState(true);
    callbacks.setStatusText("Loading Workflow");

    workflow = await Workflow.create(
      accessKey,
      {
        steps: {
          [RecipeSteps.STANDBY]: { 
              step: Steps.PORCUPINE,
              keywordPath: "keywords/voice_guided_maintenance_and_inspection_web.ppn",
              publicPath: "models/porcupine_params.pv",
          },
          [RecipeSteps.PROMPT_USER]: {
              step: Steps.ORCA,
              publicPath: "models/orca_params_en_female.pv",
          },
          [RecipeSteps.RECORD_USER]: {
              step: Steps.RHINO,
              publicPath: "models/rhino_params.pv",
              contextPath: "models/voice_guided_maintenance_and_inspection_web.rhn",
          },
          [RecipeSteps.TRANSCRIBE_USER]: {
              step: Steps.CHEETAH,
              publicPath: "models/cheetah_params.pv",
          },
        },
        all_states: [
          RecipeStates.STANDBY,
          RecipeStates.IDENTIFY_UNIT_PROMPT,
          RecipeStates.IDENTIFY_UNIT_REPORT,
          RecipeStates.CHECK_OIL_PROMPT,
          RecipeStates.CHECK_OIL_REPORT,
          RecipeStates.CHECK_TIRE_PROMPT,
          RecipeStates.CHECK_TIRE_REPORT,
          RecipeStates.CHECK_SERVICE_STATUS_PROMPT,
          RecipeStates.CHECK_SERVICE_STATUS_REPORT,
          RecipeStates.FINAL_NOTE_PROMPT,
          RecipeStates.FINAL_NOTE_REPORT,
          RecipeStates.REPORT_COMPILATION,
        ],
        state_creator: State.create,
        state_steps: {
          [RecipeStates.STANDBY]: RecipeSteps.STANDBY,
          [RecipeStates.IDENTIFY_UNIT_PROMPT]: RecipeSteps.PROMPT_USER,
          [RecipeStates.IDENTIFY_UNIT_REPORT]: RecipeSteps.RECORD_USER,
          [RecipeStates.CHECK_OIL_PROMPT]: RecipeSteps.PROMPT_USER,
          [RecipeStates.CHECK_OIL_REPORT]: RecipeSteps.RECORD_USER,
          [RecipeStates.CHECK_TIRE_PROMPT]: RecipeSteps.PROMPT_USER,
          [RecipeStates.CHECK_TIRE_REPORT]: RecipeSteps.RECORD_USER,
          [RecipeStates.CHECK_SERVICE_STATUS_PROMPT]: RecipeSteps.PROMPT_USER,
          [RecipeStates.CHECK_SERVICE_STATUS_REPORT]: RecipeSteps.RECORD_USER,
          [RecipeStates.FINAL_NOTE_PROMPT]: RecipeSteps.PROMPT_USER,
          [RecipeStates.FINAL_NOTE_REPORT]: RecipeSteps.TRANSCRIBE_USER,
          [RecipeStates.REPORT_COMPILATION]: RecipeSteps.PROMPT_USER,
        },
        start_state: { state: RecipeStates.STANDBY, tasks: TASKS },
      }
    );

    await WebVoiceProcessor.subscribe(callbackAudioEngine);

  } catch (e: any) {
    callbacks.setErrorText(e.toString());
    throw e;
  } finally {
    await callbacks.setLoadingState(false);
  }
};

const start = async (): Promise<void> => {
  setIsRunning(true);
  await workflow!.run();
  workflow!.reset();

  if (callbacks.currentScreen() != "init") {
    await callbacks.goToInitScreen();
  }
};

const stop = async (): Promise<void> => {
  setIsRunning(false);

  if (callbacks.currentScreen() != "init") {
    await callbacks.goToInitScreen();
  }
};

const release = async (): Promise<void> => {
  await WebVoiceProcessor.reset();

  if (workflow) {
    workflow.release();
  }
};

export default {
  init,
  start,
  stop,
  release,
  sleep,
};
