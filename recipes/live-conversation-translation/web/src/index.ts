import { CheetahTranscript, CheetahWorker } from '@picovoice/cheetah-web';
import { WebVoiceProcessor } from '@picovoice/web-voice-processor';
import { ZebraWorker } from '@picovoice/zebra-web';
import { OrcaWorker } from '@picovoice/orca-web';
import { AudioStream } from './audio_stream';

type PvObject = {
  audio: AudioStream,
  cheetah0: CheetahWorker,
  cheetah1: CheetahWorker,
  zebra0: ZebraWorker,
  zebra1: ZebraWorker,
  orca0: OrcaWorker,
  orca1: OrcaWorker,
  transcript: string,
  translation: string,
};

let object: PvObject | null = null;

const init = async (
  accessKey: string,
  sourceLanguage: string,
  targetLanguage: string,
  sendState: (mode: string, text: string) => void,
): Promise<() => Promise<void>> => {

  let mode: boolean = false;
  const startListening = async () => {
    if (object === null) {
      return;
    }

    const language = mode ? targetLanguage : sourceLanguage;
    const side = mode ? "left" : "right";
    const cheetah = mode ? object!.cheetah1 : object!.cheetah0;

    await WebVoiceProcessor.subscribe(cheetah);
    sendState("prompt", `Listening for ${language}`);
    sendState("listen", side);
  };

  const stopListening = async () => {
    if (object === null) {
      return;
    }

    const cheetah = mode ? object!.cheetah1: object!.cheetah0;

    await WebVoiceProcessor.unsubscribe(cheetah);
  };

  const startTranslating = async () => {
    if (object === null) {
      return;
    }

    const language = mode ? sourceLanguage : targetLanguage;
    const side = mode ? "left" : "right";
    const zebra = mode ? object!.zebra1 : object!.zebra0;

    const transcript = object!.transcript;
    object!.transcript = "";

    sendState("prompt", `Translating to ${language}`);
    sendState("translate", "");
    const translation = await zebra.translate(transcript);
    object!.translation = translation;
  };

  const startSpeaking = async () => {
    if (object === null) {
      return;
    }

    const language = mode ? sourceLanguage : targetLanguage;
    const orca = mode ? object!.orca0 : object!.orca1;

    sendState("prompt", `Synthesizing ${language} speech`)
    const translation = object!.translation;
    const synthesis = await orca.synthesize(translation, {});
    const alignments = synthesis.alignments;

    sendState("prompt", `Speaking in ${language}`)
    object!.audio.stream(synthesis.pcm);
    object!.audio.play();

    for (let alignment of alignments) {
      const index = alignments.indexOf(alignment);
      let word = alignment.word;
      if (index < alignments.length - 1 &&
          !(/[.,:;!?]/.test(alignments[index + 1].word))
      ) {
        word += " ";
      }

      setTimeout(() => {
        sendState("translation", word);
      }, alignment.startSec * 1000);
    }

    await object!.audio.waitPlayback();
    sendState("translation", "");
  };

  const switchModes = async () => {
    mode = !mode;
  }

  const transcriptCallback = async (
    transcript: CheetahTranscript
  ): Promise<void> => {
    if (object === null) {
      return;
    }

    if (transcript.transcript.length > 0) {
      sendState("transcript", transcript.transcript);
      object!.transcript += transcript.transcript;
    }

    if (transcript.isEndpoint) {
      const cheetah = mode ? object!.cheetah1 : object!.cheetah0;
      cheetah.flush();
    }

    if (transcript.isFlushed && object!.transcript.length > 0) {
      await stopListening();
      await startTranslating();
      await startSpeaking();
      await switchModes();
      await startListening();
    }
  }

  sendState("status", `Loading cheetah_${sourceLanguage}`);
  const cheetah0 = await CheetahWorker.create(
    accessKey,
    transcriptCallback,
    {
      publicPath: `models/cheetah_params_${sourceLanguage}.pv`,
      customWritePath: `cheetah_${sourceLanguage}`
    },
    {
      endpointDurationSec: 1.0,
      enableAutomaticPunctuation: true,
      enableTextNormalization: true
    }
  );

  sendState("status", `Loading cheetah_${targetLanguage}`);
  const cheetah1 = await CheetahWorker.create(
    accessKey,
    transcriptCallback,
    {
      publicPath: `models/cheetah_params_${targetLanguage}.pv`,
      customWritePath: `cheetah_${targetLanguage}`
    },
    {
      endpointDurationSec: 1.0,
      enableAutomaticPunctuation: true,
      enableTextNormalization: true
    }
  );

  sendState("status", `Loading zebra_${sourceLanguage}_${targetLanguage}`);
  const zebra0 = await ZebraWorker.create(
    accessKey,
    {
      publicPath: `models/zebra_params_${sourceLanguage}_${targetLanguage}.pv`,
      customWritePath: `zebra_${sourceLanguage}_${targetLanguage}`
    }
  );

  sendState("status", `Loading zebra_${targetLanguage}_${sourceLanguage}`);
  const zebra1 = await ZebraWorker.create(
    accessKey,
    {
      publicPath: `models/zebra_params_${targetLanguage}_${sourceLanguage}.pv`,
      customWritePath: `zebra_${targetLanguage}_${sourceLanguage}`
    }
  );

  sendState("status", `Loading orca_${sourceLanguage}`);
  const orca0 = await OrcaWorker.create(
    accessKey,
    {
      publicPath: `models/orca_params_${sourceLanguage}_male.pv`,
      customWritePath: `orca_${sourceLanguage}`
    },
    {}
  )

  sendState("status", `Loading orca_${targetLanguage}`);
  const orca1 = await OrcaWorker.create(
    accessKey,
    {
      publicPath: `models/orca_params_${targetLanguage}_male.pv`,
      customWritePath: `orca_${targetLanguage}`
    },
    {}
  )
  const audio = new AudioStream(orca0.sampleRate);

  object = {
    audio: audio,
    cheetah0: cheetah0,
    cheetah1: cheetah1,
    zebra0: zebra0,
    zebra1: zebra1,
    orca0: orca0,
    orca1: orca1,
    transcript: "",
    translation: "",
  };

  return startListening;
};

const release = async () => {
  if (object !== null) {
    WebVoiceProcessor.reset();
    object!.audio.clear();

    object!.cheetah0.release();
    object!.cheetah1.release();
    object!.zebra0.release();
    object!.zebra1.release();
    object!.orca0.release();
    object!.orca1.release();

    object = null;
  }
}

export default {
  init,
  release
};
