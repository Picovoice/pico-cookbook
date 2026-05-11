import { CheetahTranscript, CheetahWorker } from '@picovoice/cheetah-web';
import { OrcaWorker } from '@picovoice/orca-web';
import { RhinoInference, RhinoWorker } from '@picovoice/rhino-web';
import { WebVoiceProcessor } from '@picovoice/web-voice-processor';
import { AudioStream } from './audio_stream';
import { randomInt, randomUUID } from 'crypto';

type PvObject = {
  audio: AudioStream,
  cheetah: CheetahWorker,
  orca: OrcaWorker,
  rhino: RhinoWorker | null,
  transcript: string,
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

/*
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
*/

async function fetchBase64(url:string): Promise<string> {
  const response = await fetch(url);
  const bytes = new Uint8Array(await response.arrayBuffer());

  let binary = '';
  for (const b of bytes) {
    binary += String.fromCharCode(b);
  }

  return btoa(binary);
}

let interruptFlag = false;
const setInterruptFlag = async () => {
  interruptFlag = true;
};

const init = async (
  accessKey: string,
  sendState: (mode: string, text: string) => void,
): Promise<null | (() => Promise<void>)> => {

  interruptFlag = false;

  sendState("loading", "");

  const speakToCaller = async () => {
    // TODO: orca
  };

  const listenForCaller = async () => {
    const cheetah = BufferedCheetahEngine;
    await WebVoiceProcessor.subscribe(cheetah);
    
    sendState("prompt", "Listening for caller");
    // sendState("listen", side);
  };

  const giveUserOptions = async () => {
    // TODO: rhino

  };

  /*const startListening = async () => {
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
  };*/

  // TODO: this will be good for the orca part!
  /*const startSpeaking = async () => {
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
  };*/

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

    /*
    if (transcript.isFlushed && object!.transcript.length > 0) {
      await stopListening();
      await startTranslating();
      await startSpeaking();
      await startListening();
    }*/
  }

  const ATTEMPT = 100 * 1001;

  const inferenceCallback = async (
    inference: RhinoInference
  ): Promise<void> => {
    // TODO: double check types for this
  };

  sendState("status", "Loading Cheetah");
  const cheetah = await CheetahWorker.create(
    accessKey,
    transcriptCallback,
    {
      publicPath: "models/cheetah_params.pv",
      version: ATTEMPT,
    },
    {
      endpointDurationSec: 1.0,
      enableAutomaticPunctuation: true,
      enableTextNormalization: true
    }
  );

  sendState("status", "Loading Orca");
  const orca = await OrcaWorker.create(
    accessKey,
    {
      publicPath: "models/orca_params_en_female.pv",
      version: ATTEMPT,
    },
    {}
  );

  sendState("status", "Loading Rhino");
  const rhino = await RhinoWorker.create(
    accessKey,
    { publicPath: 'models/call_screen_demo_web.rhn',
      version: ATTEMPT },
    inferenceCallback,
    { publicPath: 'models/rhino_params.pv',
      version: ATTEMPT },
  );

  sendState("status", "Loading Audio Stream");
  const audio = new AudioStream(orca.sampleRate);

  sendState("new caller bubble", "");
  sendState("add to bubble", "Loading Orca A");
  sendState("new ai bubble", "");
  sendState("add to bubble", "Loading Orca B");

  object = {
    audio: audio,
    cheetah: cheetah,
    orca: orca,
    rhino: rhino,
    transcript: "",
  };

  return speakToCaller;
};

const release = async () => {
  WebVoiceProcessor.reset();

  if (object && object!.audio) {
    object!.audio.clear();
  }

  if (object && object!.cheetah) {
    await object!.cheetah.release();
  }

  if (object && object!.orca) {
    await object!.orca.release();
  }

  if (object && object!.rhino) {
    await object!.rhino.release();
  }

  object = null;

  cheetahPcmBuffer = new Int16Array();
};

export default {
  init,
  release,
  setInterruptFlag
};
