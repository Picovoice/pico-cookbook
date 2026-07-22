import { PorcupineWorker } from '@picovoice/porcupine-web';
import { RhinoInference, RhinoWorker } from '@picovoice/rhino-web';
import { CheetahTranscript, CheetahWorker } from '@picovoice/cheetah-web';
import { OrcaWorker } from '@picovoice/orca-web';
import { PicoLLMWorker } from '@picovoice/picollm-web';
import { WebVoiceProcessor } from '@picovoice/web-voice-processor';
import { AudioStream } from './audio_stream';

export enum UIState {
  INIT = 'INIT',
  LOADING_MODEL = 'LOADING_MODEL',
  WAKE_WORD = 'WAKE_WORD',
  VOICE_COMMAND = 'VOICE_COMMAND',
  START_RECORDING = 'START_RECORDING',
  READ_RECORDING = 'READ_RECORDING',
  SUMMARIZE_RECORDING = 'SUMMARIZE_RECORDING',
  REWRITE_RECORDING = 'REWRITE_RECORDING',
}

type PvObject = {
  porcupine: PorcupineWorker;
  rhino: RhinoWorker;
  cheetah: CheetahWorker;
  pllm: PicoLLMWorker;
  orca: OrcaWorker;
  audioStream: AudioStream;
};

type DemoCallbacks = {
  onStateChange: (state: UIState) => void;
  onOriginalText: (text: string) => void;
  onModifiedText: (text: string) => void;
  onAudioReady: (pcm: Int16Array) => void;
  onVolume: (volume: number) => void;
  onError: (error: string) => void;
};

let object: PvObject | null = null;
let currentState = UIState.INIT;
let memoText = '';
let enhancedText = '';
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

const init = async (accessKey: string, cb: DemoCallbacks): Promise<void> => {
  if (object !== null) return;

  callbacks = cb;

  callbacks.onStateChange(UIState.LOADING_MODEL);

  const keyword = {
    publicPath: 'models/porcupine_model.ppn',
    label: 'wake word',
    sensitivity: 0.5,
    forceWrite: true,
  };
  const detectionCallback = async () => {
    if (currentState === UIState.WAKE_WORD) {
      await WebVoiceProcessor.unsubscribe(porcupine);
      await WebVoiceProcessor.subscribe(rhino);
      setState(UIState.VOICE_COMMAND);
    }
  };
  const porcupine = await PorcupineWorker.create(
    accessKey,
    keyword,
    detectionCallback,
    { publicPath: 'models/porcupine_params.pv' }
  );

  const context = {
    publicPath: 'models/rhino_model.rhn',
    forceWrite: true,
  };
  const intentCallback = async (inference: RhinoInference) => {
    if (currentState === UIState.VOICE_COMMAND && inference.isFinalized) {
      if (inference.isUnderstood) {
        await WebVoiceProcessor.unsubscribe(rhino);
        handleIntent(inference.intent);
      } else {
        playAudioMessage("Sorry, I didn't understand that.");
        await WebVoiceProcessor.unsubscribe(rhino);
      }
    }
  };
  const rhino = await RhinoWorker.create(
    accessKey,
    context,
    intentCallback,
    { publicPath: 'models/rhino_params.pv' },
    { requireEndpoint: false, endpointDurationSec: 0.5 }
  );

  const transcribeCallback = async (transcript: CheetahTranscript) => {
    if (currentState === UIState.START_RECORDING) {
      memoText += transcript.transcript;

      if (transcript.isEndpoint) {
        cheetah.flush();
      }

      if (transcript.isFlushed) {
        memoText = memoText + ' ';
      }

      if (/.*(stop recording)[.\s]*$/i.test(memoText)) {
        memoText = memoText.replace(/stop recording[.\s]*$/i, '').trim();
        enhancedText = memoText;
        await WebVoiceProcessor.unsubscribe(cheetah);
        resetToWakeWord();
      }
      callbacks!.onOriginalText(memoText);
    }
  };
  const cheetah = await CheetahWorker.create(
    accessKey,
    transcribeCallback,
    { publicPath: 'models/cheetah_params.pv' },
    { enableAutomaticPunctuation: true, enableTextNormalization: true }
  );

  const pllm = await PicoLLMWorker.create(accessKey, {
    modelFile: 'models/picollm_model.pllm',
    cacheFileOverwrite: true,
  });
  const orca = await OrcaWorker.create(
    accessKey,
    { publicPath: 'models/orca_params_en_female.pv' },
    {}
  );

  const audioStream = new AudioStream(orca.sampleRate);

  object = { porcupine, rhino, cheetah, pllm, orca, audioStream };
  setState(UIState.WAKE_WORD);
};

const handleIntent = async (intent: string | undefined) => {
  if (intent === 'startMemo') {
    memoText = '';
    enhancedText = '';
    setState(UIState.START_RECORDING);
    await WebVoiceProcessor.subscribe(object!.cheetah);
  } else if (intent === 'readMemo') {
    setState(UIState.READ_RECORDING);
    const textToRead = enhancedText || NO_MEMO_ERROR_PHRASE;
    playAudioMessage(textToRead);
  } else if (intent === 'summarizeMemo' || intent === 'rewriteMemo') {
    if (!memoText) {
      playAudioMessage(NO_MEMO_ERROR_PHRASE);
      return;
    }
    setState(
      intent === 'summarizeMemo'
        ? UIState.SUMMARIZE_RECORDING
        : UIState.REWRITE_RECORDING
    );
    runLLMTask(intent);
  }
};

const runLLMTask = async (task: string) => {
  enhancedText = '';
  const dialog = object!.pllm.getDialog();
  let prompt =
    task === 'summarizeMemo'
      ? `In one brief sentence, write what needs doing from this memo, including any day or date: "${memoText}"`
      : `You are a transcription cleaner. You tidy speech into readable text without changing what was said or how much was said. Remove um, uh, you know, false starts, repeated words, and any leading Okay, So, or Well.\n\nMemo: "${memoText}"\nCleaned:`;

  dialog.addHumanRequest(prompt);

  await object!.pllm.generate(dialog.prompt(), {
    stopPhrases: ['<|im_end|>'],
    streamCallback: token => {
      enhancedText += token.replace('<|im_end|>', '');
      callbacks!.onModifiedText(enhancedText);
    },
  });
  callbacks!.onModifiedText(enhancedText);
  resetToWakeWord();
};

const playAudioMessage = async (text: string) => {
  const orcaSynthesis = await object!.orca.synthesize(text);
  if (orcaSynthesis.pcm) {
    object!.audioStream.stream(orcaSynthesis.pcm);
    object!.audioStream.play();
    await object!.audioStream.waitPlayback();
  }

  resetToWakeWord();
};

const resetToWakeWord = async () => {
  setState(UIState.WAKE_WORD);
  await WebVoiceProcessor.subscribe(object!.porcupine);
};

const setState = (state: UIState) => {
  currentState = state;
  callbacks!.onStateChange(state);
};

const start = async (): Promise<void> => {
  await WebVoiceProcessor.subscribe(object!.porcupine);
  await WebVoiceProcessor.subscribe(customAudioEngine);
};

const release = async (): Promise<void> => {
  if (object === null) {
    return;
  }

  const { porcupine, rhino, cheetah, pllm, orca } = object;
  porcupine.terminate();
  rhino.terminate();
  cheetah.terminate();
  await pllm.release();
  orca.terminate();

  object = null;
};

export { init, start, release };
