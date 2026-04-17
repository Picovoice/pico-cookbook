import {
  BatScores,
  BatLanguages,
  batLanguageToString,
  batLanguageFromString,
  BatWorker
} from '@picovoice/bat-web';
import { CheetahTranscript, CheetahWorker } from '@picovoice/cheetah-web';
import { WebVoiceProcessor } from '@picovoice/web-voice-processor';
import { ZebraWorker } from '@picovoice/zebra-web';
import { OrcaWorker } from '@picovoice/orca-web';
import { AudioStream } from './audio_stream';

type PvObject = {
  audio: AudioStream,
  cheetah: CheetahWorker,
  zebra: ZebraWorker,
  orca: OrcaWorker,
  transcript: string,
  translation: string,
};

let object: PvObject | null = null;

var cheetahPcmBuffer = new Int16Array();
const BufferedCheetahEngine = {
  onmessage: function(e: any) {
    switch (e.data.command) {
        case 'process':
            var tempPcmBuffer = new Int16Array(cheetahPcmBuffer.length + e.data.inputFrame.length);
            tempPcmBuffer.set(cheetahPcmBuffer);
            tempPcmBuffer.set(e.data.inputFrame, cheetahPcmBuffer.length);
            cheetahPcmBuffer = tempPcmBuffer

            if (object !== null) {
              while (cheetahPcmBuffer.length >= object!.cheetah.frameLength) {
                object!.cheetah.process(cheetahPcmBuffer.slice(0, object!.cheetah.frameLength));
                cheetahPcmBuffer = cheetahPcmBuffer.slice(object!.cheetah.frameLength)
              }
            }
            break;
    }
  }
}

let bat: BatWorker | null = null;

var batPcmBuffer = new Int16Array();
const BufferedBatEngine = {
  onmessage: function(e: any) {
    switch (e.data.command) {
        case 'process':
            var tempPcmBuffer = new Int16Array(batPcmBuffer.length + e.data.inputFrame.length);
            tempPcmBuffer.set(batPcmBuffer);
            tempPcmBuffer.set(e.data.inputFrame, batPcmBuffer.length);
            batPcmBuffer = tempPcmBuffer

            if (bat !== null && batPcmBuffer.length >= bat!.frameLength) {
              bat!.process(batPcmBuffer.slice(0, bat!.frameLength));
              batPcmBuffer = batPcmBuffer.slice(bat!.frameLength)
            }
            break;
    }
  }
}

const init = async (
  accessKey: string,
  sourceLanguage: string | null,
  targetLanguage: string,
  sendState: (mode: string, text: string) => void,
): Promise<() => Promise<void>> => {
  if (sourceLanguage === "automatic") {
    sourceLanguage = null;
  }

  if (sourceLanguage === null) {
    const scoresCallback = (
      scores: BatScores | null,
    ): void => {
      if (scores !== null) {
        let maxLanguage = BatLanguages.UNKNOWN;
        let maxLanguageScore = 0.0;

        for (const [lang, score] of Object.entries(scores)) {
          if (score > maxLanguageScore) {
            maxLanguage = Number(lang) as BatLanguages;
            maxLanguageScore = score;
          }
        }

        if ((maxLanguage !== BatLanguages.UNKNOWN) && (maxLanguageScore > 0.75)) {
          sourceLanguage = batLanguageToString(maxLanguage);
        }
      }
    }

    sendState("status", `Loading bat`);
    bat = await BatWorker.create(
      accessKey,
      scoresCallback,
      {
        publicPath: "models/bat_params.pv",
        forceWrite: true
      }
    );

    await WebVoiceProcessor.subscribe(BufferedCheetahEngine);
    await WebVoiceProcessor.subscribe(BufferedBatEngine);
    sendState("status", `Detecting language`);
    sendState("prompt", `Detecting language`);
    sendState("listen", "right");
    sendState("detecting", "");

    while (sourceLanguage === null) {
      await new Promise(r => setTimeout(r, 1000));
    }
  }
  if (bat !== null) {
    await WebVoiceProcessor.unsubscribe(bat);
    bat.release();
    bat = null;
  }

  sendState("prompt", `Detected "${sourceLanguage}"`);

  const startListening = async () => {
    const language = sourceLanguage;
    const side = "right";
    const cheetah = BufferedCheetahEngine;

    await WebVoiceProcessor.subscribe(cheetah);
    sendState("prompt", `Listening for ${language}`);
    sendState("listen", side);
  };

  const stopListening = async () => {
    const cheetah = BufferedCheetahEngine;

    await WebVoiceProcessor.unsubscribe(cheetah);
  };

  const startTranslating = async () => {
    const language = targetLanguage;
    const side = "right";
    const zebra = object!.zebra;

    const transcript = object!.transcript;
    object!.transcript = "";

    sendState("prompt", `Translating to ${language}`);
    sendState("translate", "");
    const translation = await zebra.translate(transcript);
    object!.translation = translation;
  };

  const startSpeaking = async () => {
    const language = targetLanguage;
    const orca = object!.orca;

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

  const transcriptCallback = async (
    transcript: CheetahTranscript
  ): Promise<void> => {
    if (transcript.transcript.length > 0) {
      sendState("transcript", transcript.transcript);
      object!.transcript += transcript.transcript;
    }

    if (transcript.isEndpoint) {
      sendState("transcript", ' ');
      object!.transcript += ' '

      const cheetah = object!.cheetah;
      cheetah.flush();
    }

    if (transcript.isFlushed && object!.transcript.length > 0) {
      await stopListening();
      await startTranslating();
      await startSpeaking();
      await startListening();
    }
  }

  sendState("status", `Loading cheetah_${sourceLanguage}`);
  const cheetah = await CheetahWorker.create(
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

  sendState("status", `Loading zebra_${sourceLanguage}_${targetLanguage}`);
  const zebra = await ZebraWorker.create(
    accessKey,
    {
      publicPath: `models/zebra_params_${sourceLanguage}_${targetLanguage}.pv`,
      customWritePath: `zebra_${sourceLanguage}_${targetLanguage}`
    }
  );

  sendState("status", `Loading orca_${targetLanguage}`);
  const orca = await OrcaWorker.create(
    accessKey,
    {
      publicPath: `models/orca_params_${targetLanguage}_male.pv`,
      customWritePath: `orca_${targetLanguage}`
    },
    {}
  )
  const audio = new AudioStream(orca.sampleRate);

  object = {
    audio: audio,
    cheetah: cheetah,
    zebra: zebra,
    orca: orca,
    transcript: "",
    translation: "",
  };

  return startListening;
};

const release = async () => {
  WebVoiceProcessor.reset();
  object!.audio.clear();
  object!.cheetah.release();
  object!.zebra.release();
  object!.orca.release();

  object = null;

  cheetahPcmBuffer = new Int16Array();
  batPcmBuffer = new Int16Array();
}

export default {
  init,
  release
};
