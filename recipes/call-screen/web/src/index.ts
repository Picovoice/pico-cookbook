import { CheetahTranscript, CheetahWorker } from '@picovoice/cheetah-web';
import { OrcaWorker } from '@picovoice/orca-web';
import { RhinoInference, RhinoWorker } from '@picovoice/rhino-web';
import { WebVoiceProcessor } from '@picovoice/web-voice-processor';
import { AudioStream } from './audio_stream';
import { Action, promptFromAction, isTerminalFromAction, allActions } from './action';

const MIN_DB = -40.0;
const MAX_DB = 0.0;

const sleep = (ms: number) => new Promise(resolve => setTimeout(resolve, ms));

type Message =
  "SET_STATUS"
  | "ADD_TO_BUBBLE"
  | "NEW_CALLER_BUBBLE"
  | "NEW_AI_BUBBLE"
  | "START_LISTENING"
  | "STOP_LISTENING"
  | "GIVE_USER_OPTIONS"
  | "SELECT_OPTION"
  | "SET_AI_STATE"
  | "RESTART_DEMO";

type Request = "BUBBLE_LENGTH";

type PvObject = {
  audio: AudioStream,
  cheetah: CheetahWorker,
  orca: OrcaWorker,
  rhino: RhinoWorker,
  action: Action,
  username: string,
};

let object: PvObject | null = null;

var triggerAudioCallback = (_: Int16Array) => {};

var cheetahPcmBuffer = new Int16Array();
const BufferedCheetahEngine = {
  onmessage: function(e: any) {
    switch (e.data.command) {
      case 'process':
        triggerAudioCallback(e.data.inputFrame);

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
        triggerAudioCallback(e.data.inputFrame);

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
  name: string,
  sendMessage: (message: Message, obj: any) => void,
  makeRequest: (message: Request) => any,
  onVolumeCallback: (volume: number) => void,
): Promise<null | (() => Promise<void>)> => {

  triggerAudioCallback = (frame: Int16Array) => {
    let sum = 0;
    for (let i = 0; i < frame.length; i++) {
      sum += Math.pow(frame[i], 2);
    }
    const rms = sum / frame.length / Math.pow(32767, 2);
    const db = 10 * Math.log10(Math.max(rms, 1e-9));
    const normalized = (db - MIN_DB) / (MAX_DB - MIN_DB);
    const normalizedVolume = Math.max(0.0, Math.min(1.0, normalized));

    onVolumeCallback(normalizedVolume);
  }

  const resetDemo = async () => {
    object!.audio.clear();

    sendMessage("SET_AI_STATE", "");

    await stopListenForCaller();
    await stopGiveUserOptions();

    sendMessage("RESTART_DEMO", null);
  };

  const speakToCaller = async () => {
    const orca = object!.orca;

    const synthesis = await orca.synthesize(
      promptFromAction(object!.action, object!.username), {});
    const alignments = synthesis.alignments;

    object!.audio.stream(synthesis.pcm);
    object!.audio.play();

    sendMessage("SET_AI_STATE", "AI: Speaking to caller");
    sendMessage("NEW_AI_BUBBLE", "[AI] ");

    for (let alignment of alignments) {
      const index = alignments.indexOf(alignment);
      let word = alignment.word;
      if (index < alignments.length - 1 &&
          !(/[.,:;!?]/.test(alignments[index + 1].word))
      ) {
        word += " ";
      }

      setTimeout(() => {
        sendMessage("ADD_TO_BUBBLE", word);
      }, alignment.startSec * 1000);
    }

    await object!.audio.waitPlayback();

    if (isTerminalFromAction(object!.action)) {
      await sleep(1700);
      resetDemo();
    } else {
      listenForCaller();
    }
  };

  const listenForCaller = async () => {
    sendMessage("NEW_CALLER_BUBBLE", "[CALLER] ");
    sendMessage("START_LISTENING", null);
    sendMessage("SET_AI_STATE", "AI: Listening to caller");

    const cheetah = BufferedCheetahEngine;
    await WebVoiceProcessor.subscribe(cheetah);
  };
  const stopListenForCaller = async () => {
    sendMessage("STOP_LISTENING", null);
    const cheetah = BufferedCheetahEngine;
    await WebVoiceProcessor.unsubscribe(cheetah);
  };

  const giveUserOptions = async () => {
    sendMessage("GIVE_USER_OPTIONS", allActions());
    sendMessage("SET_AI_STATE", "AI: Listening for " + object!.username + "'s command");

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
      sendMessage("ADD_TO_BUBBLE", transcript.transcript);
    }

    if (transcript.isEndpoint) {
      sendMessage("ADD_TO_BUBBLE", " ");
      const cheetah = object!.cheetah;
      cheetah.flush();
    }

    if (transcript.isFlushed && makeRequest("BUBBLE_LENGTH") > 0) {
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
      sendMessage("SELECT_OPTION", object!.action);

      setTimeout(() => { speakToCaller(); }, 600);
    } else {
      console.log("unknown user option");
    }
  };

  if (object == null) {
    const FORCE_WRITE = true;

    sendMessage("SET_STATUS", "Loading Cheetah");
    const cheetah = await CheetahWorker.create(
      accessKey,
      transcriptCallback,
      { publicPath: "models/cheetah_params.pv", forceWrite: FORCE_WRITE },
      {
        endpointDurationSec: 1.0,
        enableAutomaticPunctuation: true,
        enableTextNormalization: true
      }
    );

    sendMessage("SET_STATUS", "Loading Orca");
    const orca = await OrcaWorker.create(
      accessKey,
      { publicPath: "models/orca_params_en_female.pv", forceWrite: FORCE_WRITE },
      {}
    );

    sendMessage("SET_STATUS", "Loading Rhino");
    const rhino = await RhinoWorker.create(
      accessKey,
      { publicPath: 'models/call_screen_demo_web.rhn', forceWrite: FORCE_WRITE },
      inferenceCallback,
      { publicPath: 'models/rhino_params.pv', forceWrite: FORCE_WRITE },
    );

    sendMessage("SET_AI_STATE", "AI: Connecting caller");
    sendMessage("SET_STATUS", "Loading Audio Stream");
    const audio = new AudioStream(orca.sampleRate);

    object = {
      audio: audio,
      cheetah: cheetah,
      orca: orca,
      rhino: rhino,
      action: Action.GREET,
      username: name,
    };
  }

  return () => speakToCaller();
};

const updateStartParameters = async (name: string): Promise<void> => {
  if (object) {
    object!.username = name;
    object!.action = Action.GREET;
  }
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
  sleep,
  init,
  updateStartParameters,
  release
};
