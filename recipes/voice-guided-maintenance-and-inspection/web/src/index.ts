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
  createCard: (id: string, title: string) => void,
  setCardValue: (id: string, value: string) => void,
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
  callbacks = cb;

  for (let card in CardType) {
    callbacks.createCard(card, card);
  }
};

const start = async (): Promise<void> => {
  // TODO
};

const stop = async (): Promise<void> => {
  // TODO
};

const release = async (): Promise<void> => {
  // TODO
};

export default {
  init,
  start,
  stop,
  release,
};
