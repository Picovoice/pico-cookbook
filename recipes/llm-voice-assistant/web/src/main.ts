import { Mutex } from 'async-mutex';

import { BuiltInKeyword, PorcupineDetection, PorcupineWorker } from '@picovoice/porcupine-web';
import { CheetahTranscript, CheetahWorker } from '@picovoice/cheetah-web';
import { OrcaStreamWorker, OrcaWorker } from '@picovoice/orca-web';
import { Dialog, PicoLLMEndpoint, PicoLLMModel, PicoLLMWorker } from '@picovoice/picollm-web';
import { WebVoiceProcessor } from '@picovoice/web-voice-processor';

type PvObject = {
  porcupine: PorcupineWorker;
  cheetah: CheetahWorker;
  pllm: PicoLLMWorker;
  dialog: Dialog;
  orca: OrcaWorker;
  stream: OrcaStreamWorker;
};

type PvCallback = {
  onDetection: (detection: PorcupineDetection) => void;
  onTranscript: (transcript: string) => void;
  onEndpoint: () => void;
  onText: (text: string) => void;
  onStream: (pcm: Int16Array) => void;
  onComplete: (interrupted: boolean) => Promise<void>;
}

let object: PvObject | null = null;

const init = async (
  accessKey: string,
  picollmModel: PicoLLMModel,
  {
    onDetection,
    onEndpoint,
    onTranscript,
    onText,
    onStream,
    onComplete,
  }: PvCallback): Promise<void> => {
  if (object !== null) {
    return;
  }

  const detectionCallback = async (detection: PorcupineDetection): Promise<void> => {
    if (detection.index === 0) {
      pllm.interrupt();

      await WebVoiceProcessor.subscribe(cheetah);
      await WebVoiceProcessor.unsubscribe(porcupine);

      const release = await mutex.acquire();
      onDetection(detection);
      release();
    }
  };

  const porcupine = await PorcupineWorker.create(
    accessKey,
    BuiltInKeyword.Picovoice,
    detectionCallback,
    {
      publicPath: "models/porcupine_params.pv"
    });

  const transcriptCallback = async (transcript: CheetahTranscript): Promise<void> => {
    if (transcript.isEndpoint) {
      await WebVoiceProcessor.subscribe(porcupine);
      await WebVoiceProcessor.unsubscribe(cheetah);
      cheetah.flush();
    }
    if (transcript.transcript.length > 0) {
      transcripts.push(transcript.transcript);
      onTranscript(transcript.transcript);
    }
    if (transcript.isFlushed) {
      onEndpoint();
      await onCheetahFlushed();
    }
  };

  const cheetah = await CheetahWorker.create(
    accessKey,
    transcriptCallback,
    {
      publicPath: "models/cheetah_params.pv"
    });

  const pllm = await PicoLLMWorker.create(
    accessKey,
    picollmModel);

  const orca = await OrcaWorker.create(
    accessKey,
    {
      publicPath: "models/orca_params.pv"
    });

  const dialog = pllm.getDialog();
  const stream = await orca.streamOpen();

  const mutex = new Mutex();
  let transcripts: string[] = [];
  let synthesized = 0;
  let stopTokens = 0;

  const stopPhrases = [
    '</s>', // Llama-2, Mistral
    '<end_of_turn>', // Gemma
    '<|endoftext|>', // Phi-2
    '<|eot_id|>', // Llama-3
    '<|end|>', '<|user|>', '<|assistant|>'
  ];

  const onCheetahFlushed = async (): Promise<void> => {
    const prompt = transcripts.join('');
    transcripts = [];
    dialog.addHumanRequest(prompt);

    const release = await mutex.acquire();

    const { completion, completionTokens, endpoint } = await pllm.generate(dialog.prompt(), {
      completionTokenLimit: 128,
      stopPhrases: stopPhrases,
      streamCallback: async token => {
        if (!stopPhrases.includes(token)) {
          onText(token);
          const pcm = await stream.synthesize(token);
          synthesized++;
          if (pcm !== null) {
            onStream(pcm);
          }
        } else {
          stopTokens++;
        }
      }
    });

    release();

    dialog.addLLMResponse(completion);

    const waitForSynthesize = (): Promise<void> => new Promise<void>(resolve => {
      const interval = setInterval(() => {
        if (synthesized === (completionTokens.length - stopTokens)) {
          clearInterval(interval);
          resolve();
        }
      }, 100);
    });

    await waitForSynthesize();
    const pcm = await stream.flush();
    if (pcm !== null) {
      onStream(pcm);
    }
    synthesized = 0;
    stopTokens = 0;

    const interrupted = (endpoint === PicoLLMEndpoint.INTERRUPTED);
    await onComplete(interrupted);
  };

  object = {
    porcupine,
    cheetah,
    pllm,
    orca,
    dialog,
    stream,
  };
};

const start = async (): Promise<void> => {
  if (object === null) {
    return;
  }

  await WebVoiceProcessor.subscribe(object.porcupine);
};

const getStreamSampleRate = (): number => {
  if (object) {
    return object.orca.sampleRate;
  }
  return 0;
};

const release = async (): Promise<void> => {
  if (object === null) {
    return;
  }

  const { porcupine, cheetah, pllm, orca } = object;
  porcupine.terminate();
  cheetah.terminate();
  await pllm.release();
  orca.terminate();

  object = null;
};

export {
  init,
  start,
  getStreamSampleRate,
  release,
};
