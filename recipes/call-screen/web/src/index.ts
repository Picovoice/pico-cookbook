import { CheetahTranscript, CheetahWorker } from '@picovoice/cheetah-web';
import { OrcaWorker } from '@picovoice/orca-web';
import { RhinoInference, RhinoWorker } from '@picovoice/rhino-web';
import { WebVoiceProcessor } from '@picovoice/web-voice-processor';
import { AudioStream } from './audio_stream';
import { Action, promptFromAction, isTerminalFromAction, allActions } from './action';

const sleep = (ms: number) => new Promise(resolve => setTimeout(resolve, ms));

type PvObject = {
  audio: AudioStream,
  cheetah: CheetahWorker,
  orca: OrcaWorker,
  rhino: RhinoWorker,
  action: Action,
  username: string,
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

var rhinoPcmBuffer = new Int16Array();
const BufferedRhinoEngine = {
  onmessage: function(e: any) {
    switch (e.data.command) {
        case 'process':
            var tempPcmBuffer = new Int16Array(rhinoPcmBuffer.length + e.data.inputFrame.length);
            tempPcmBuffer.set(rhinoPcmBuffer);
            tempPcmBuffer.set(e.data.inputFrame, rhinoPcmBuffer.length);
            rhinoPcmBuffer = tempPcmBuffer

            if (object !== null && rhinoPcmBuffer.length >= object!.rhino.frameLength) {
              object!.rhino.process(rhinoPcmBuffer.slice(0, object!.rhino.frameLength));
              rhinoPcmBuffer = rhinoPcmBuffer.slice(object!.rhino.frameLength)
            }
            break;
    }
  }
}

const init = async (
  accessKey: string,
  username: string,
  sendState: (message: string, obj: any) => void,
  makeRequest: (message: string) => any,
): Promise<null | (() => Promise<void>)> => {

  sendState("ai state", "AI: Connecting caller");

  const resetDemo = async () => {
    object!.audio.clear();

    sendState("ai state", "");

    await stopListenForCaller();
    await stopGiveUserOptions();

    sendState("restart demo", null);
  };

  const speakToCaller = async () => {
    const orca = object!.orca;

    const synthesis = await orca.synthesize(
      promptFromAction(object!.action, object!.username), {});
    const alignments = synthesis.alignments;

    object!.audio.stream(synthesis.pcm);
    object!.audio.play();

    sendState("ai state", "AI: Speaking to caller");
    sendState("new ai bubble", "[AI] ");

    for (let alignment of alignments) {
      const index = alignments.indexOf(alignment);
      let word = alignment.word;
      if (index < alignments.length - 1 &&
          !(/[.,:;!?]/.test(alignments[index + 1].word))
      ) {
        word += " ";
      }

      setTimeout(() => {
        sendState("add to bubble", word);
      }, alignment.startSec * 1000);
    }

    await object!.audio.waitPlayback();

    if (isTerminalFromAction(object!.action)) {
      await sleep(1500);
      resetDemo();
    } else {
      listenForCaller();
    }
  };

  const listenForCaller = async () => {
    sendState("new caller bubble", "[CALLER] ");
    sendState("ai state", "AI: Listening to caller");

    const cheetah = BufferedCheetahEngine;
    await WebVoiceProcessor.subscribe(cheetah);
  };

  const stopListenForCaller = async () => {
    const cheetah = BufferedCheetahEngine;
    await WebVoiceProcessor.unsubscribe(cheetah);
  };

  const giveUserOptions = async () => {
    sendState("give user options", allActions());
    sendState("ai state", "AI: Listening for " + object!.username + "'s command");

    const rhino = BufferedRhinoEngine;
    await WebVoiceProcessor.subscribe(rhino);
  };

  const stopGiveUserOptions = async () => {
    const rhino = BufferedRhinoEngine;
    await WebVoiceProcessor.unsubscribe(rhino);
  };

  const transcriptCallback = async (
    transcript: CheetahTranscript
  ): Promise<void> => {
    if (transcript.transcript.length > 0) {
      sendState("add to bubble", transcript.transcript);
    }

    if (transcript.isEndpoint) {
      sendState("add to bubble", " ");
      const cheetah = object!.cheetah;
      cheetah.flush();
    }

    if (transcript.isFlushed && makeRequest("bubble length") > 0) {
      await stopListenForCaller();

      setTimeout(() => { giveUserOptions(); }, 600);
    }
  }

  const inferenceCallback = async (
    inference: RhinoInference
  ): Promise<void> => {
    if (!inference.isFinalized) {
      return;
    }

    if (inference.isUnderstood && inference.intent === "chooseAction") {
      object!.action = inference.slots!.action as Action;

      await stopGiveUserOptions();
      sendState("select option", object!.action);

      setTimeout(() => { speakToCaller(); }, 600);
    } else {
      sendState("unknown user option", 1000);
    }
  };

  const ATTEMPT = 100 * 1001;

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

  object = {
    audio: audio,
    cheetah: cheetah,
    orca: orca,
    rhino: rhino,
    action: Action.GREET,
    username: username,
    transcript: "",
  };

  return () => speakToCaller();
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
  release
};
