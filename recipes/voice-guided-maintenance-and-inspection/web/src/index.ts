import { CheetahWorker } from '@picovoice/cheetah-web';
import { KoalaWorker } from '@picovoice/koala-web';
import { OrcaWorker } from '@picovoice/orca-web';
import { PorcupineWorker } from '@picovoice/porcupine-web';
import { RhinoWorker } from '@picovoice/rhino-web';
import { WebVoiceProcessor } from '@picovoice/web-voice-processor';
import { PvEngine } from '@picovoice/web-voice-processor/dist/types/types';

export type DemoCallbacks = {
  setStatusText: (text: string) => void,
  clearStatus: () => void,
  setErrorText: (text: string) => void,
  clearError: () => void,
  resetCards: (cards: [string, string][]) => void,
  setCardValue: (id: string, value: string) => void,
  onVolume: (volume: number) => void,
};

enum CardType {
  UNIT_ID = "UNIT ID",
  OIL_CONDITION = "OIL CONDITION",
  TIRE_CONDITION = "TIRE CONDITION",
  SERVICE_STATUS = "SERVICE STATUS",
  NOTES = "NOTES"
};

let callbacks: DemoCallbacks | null = null;

const init = async (accessKey: string, cb: DemoCallbacks): Promise<void> => {
  if (callbacks === null) {
    // TODO

    cb.clearStatus();
    callbacks = cb;
  }
};

const start = async (): Promise<void> => {
  if (callbacks !== null) {
    callbacks.resetCards(Object.entries(CardType));

    // TODO
  }
};

const stop = async (): Promise<void> => {
  if (callbacks !== null) {
    // TODO

    callbacks.setStatusText("Ready to Start");
  }
};

const release = async (): Promise<void> => {
  if (callbacks !== null) {
    // TODO
  }
};

export default {
  init,
  start,
  stop,
  release,
};
