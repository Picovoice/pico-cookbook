import { CheetahTranscript, CheetahWorker } from '@picovoice/cheetah-web';
import { PicoLLMWorker } from '@picovoice/picollm-web';
import { OrcaWorker } from '@picovoice/orca-web';
import { RhinoInference, RhinoWorker } from '@picovoice/rhino-web';
import { WebVoiceProcessor } from '@picovoice/web-voice-processor';
import { AudioStream } from './audio_stream';
import { Action, promptFromAction, isTerminalFromAction, allActions } from './action';

const MIN_DB = -40.0;
const MAX_DB = 0.0;

const ASK_FOR_DETAILS_RETRY_LIMIT = 2;

const SYSTEM = `Extract call information.

Return exactly two lines:
caller: <one short value>
reason: <one short value>

Rules:
- Use exactly one value for caller.
- Use exactly one value for reason.
- Do not list alternatives.
- Do not use commas.
- Do not explain.
- If the caller says a company or organization, use that as caller.
- If the caller says only a generic role like customer service, use that as caller.
- If the caller does not say who they are, use unknown.
- If the caller does not say why they are calling, use unknown.
- Use lowercase unless the caller gives a proper name.
- Avoid responding unknown. Use context.

Examples:

Caller said: "Hello, can you hear me?"
caller: unknown
reason: unknown

Caller said: "I'm calling from the bank."
caller: bank
reason: unknown

Caller said: "This is UPS with a package delivery."
caller: UPS
reason: package delivery

Caller said: "This is customer service."
caller: customer service
reason: unknown

Caller said: "I'm george and I'm calling about your sawmill."
caller: George
reason: my sawmill
`;

const sleep = (ms: number) => new Promise(resolve => setTimeout(resolve, ms));

const extractCallerAndReasonFromLLMInference = (inference: string): [string, string] => {
  const inferenceLower = inference.toLowerCase();
  const callerIndex = inferenceLower.indexOf("caller:");
  const reasonIndex = inferenceLower.indexOf("reason:");

  if (callerIndex == -1 || reasonIndex == -1) {
      return ["unknown", "unknown"];
  }

  let callerEOL = inferenceLower.slice(callerIndex).indexOf("\n");
  let reasonEOL = inferenceLower.slice(reasonIndex).indexOf("\n");
  callerEOL = (callerEOL == -1) ? inferenceLower.length : (callerIndex + callerEOL);
  reasonEOL = (reasonEOL == -1) ? inferenceLower.length : (reasonIndex + reasonEOL);
  
  const callerContents = inference.slice(callerIndex, callerEOL).split(":")[1];
  const reasonContents = inference.slice(reasonIndex, reasonEOL).split(":")[1];

  return [callerContents.trim(), reasonContents.trim()];
}

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
  | "RESTART_DEMO"
  | "ADD_TO_AI_REPORT"
  | "START_LLM_SPINNER"
  | "STOP_LLM_SPINNER";

type Request = 
  "BUBBLE_LENGTH"
  | "BUBBLE_CONTENTS";

type PvObject = {
  audio: AudioStream,
  cheetah: CheetahWorker,
  llm: PicoLLMWorker,
  orca: OrcaWorker,
  rhino: RhinoWorker,
  action: Action,
  username: string,

  askForDetailsRetryCount: number,
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

    console.log("volume callback");

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
    console.log("start speaking to caller");
    const orca = object!.orca;

    const synthesis = await orca.synthesize(
      promptFromAction(object!.action, object!.username), {});
    const alignments = synthesis.alignments;

    object!.audio.stream(synthesis.pcm);
    object!.audio.play();

    sendMessage("SET_AI_STATE", "AI is speaking to caller");
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
    sendMessage("SET_AI_STATE", "AI is listening to caller");

    const cheetah = BufferedCheetahEngine;
    await WebVoiceProcessor.subscribe(cheetah);
  };
  const stopListenForCaller = async () => {
    sendMessage("STOP_LISTENING", null);

    const cheetah = BufferedCheetahEngine;
    await WebVoiceProcessor.unsubscribe(cheetah);
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

    let callerText = makeRequest("BUBBLE_CONTENTS");
    if (transcript.isFlushed && (callerText.trim().length > 0)) {
      await stopListenForCaller();

      await llmProcessCall(callerText.trim());
      return;
    }
  }

  const llmProcessCall = async (callerText: string) => {
    sendMessage("START_LLM_SPINNER", null);

    let dialog = await object!.llm.getDialog(undefined, undefined, SYSTEM);
    dialog.addHumanRequest(`Caller said: \"${callerText.replace("[CALLER]", "").trim()}\"\n`);

    const completion = await object!.llm.generate(
        dialog.prompt(),
        { stopPhrases: ['<|eot_id|>'] });

    const inference = completion.completion.trim().replace('<|eot_id|>', '');
    console.log(`# Inference\n${inference}`);
    const [caller, reason] = extractCallerAndReasonFromLLMInference(inference);

    sendMessage("STOP_LLM_SPINNER", null);

    if (caller != 'unknown' && reason != 'unknown') {
      sendMessage(
          "ADD_TO_AI_REPORT",
          `[AI] <b style="color: var(--brand-primary);">${caller}</b> is trying to speak with you about ` +
          `<b style="color: var(--brand-primary);">${reason}</b>.`);
      setTimeout(() => { giveUserOptions(); }, 200);
      return;
    } else if (object!.askForDetailsRetryCount < ASK_FOR_DETAILS_RETRY_LIMIT) {
      object!.action = Action.ASK_FOR_DETAILS;

      if (caller == 'unknown' && reason == 'unknown') {
        sendMessage("ADD_TO_AI_REPORT", "[AI] Unknown caller with no specific reason. I will ask for more information.");
      } else if (caller == 'unknown') {
        sendMessage(
            "ADD_TO_AI_REPORT",
            `[AI] Unknown caller is trying to speak with you about <b style="color: var(--brand-primary);">${reason}</b>. ` +
            `I will ask for their identity.`);
      } else {
        sendMessage(
            "ADD_TO_AI_REPORT",
            `[AI] <b style="color: var(--brand-primary);">${caller}</b> is trying to speak with you. I will ask for their reason.`);
      }
    } else {
      object!.action = Action.DECLINE_CALL;
      
      sendMessage(
          "ADD_TO_AI_REPORT",
          `[AI] Couldn't understand caller's identity and agenda after <b style="color: var(--brand-primary);">${ASK_FOR_DETAILS_RETRY_LIMIT}</b> ` +
          "inquiries. Declining their call.");
    }

    object!.askForDetailsRetryCount += 1;
    setTimeout(() => { speakToCaller(); }, 200);
  };

  const giveUserOptions = async () => {
    sendMessage("GIVE_USER_OPTIONS", allActions());
    sendMessage("SET_AI_STATE", "AI is listening for " + object!.username + "'s command");

    const rhino = BufferedRhinoEngine;
    await WebVoiceProcessor.subscribe(rhino);
  };
  const stopGiveUserOptions = async () => {
    const rhino = BufferedRhinoEngine;
    await WebVoiceProcessor.unsubscribe(rhino);
  };
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

  console.log("init...");

  if (object == null) {
    // If you update your model, you may want to enable force write to update the model in storage.
    const FORCE_WRITE = false;

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

    sendMessage("SET_STATUS", "Loading PicoLLM");
    const llm = await PicoLLMWorker.create(
      accessKey,
      { modelFile: 'models/llama-3.2-1b-instruct-385.pllm', cacheFileOverwrite: FORCE_WRITE },
      {}
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
      { publicPath: 'models/call_assist_demo_web.rhn', forceWrite: FORCE_WRITE },
      inferenceCallback,
      { publicPath: 'models/rhino_params.pv', forceWrite: FORCE_WRITE },
    );

    sendMessage("SET_AI_STATE", "AI is connecting caller");
    sendMessage("SET_STATUS", "Loading Audio Stream");
    const audio = new AudioStream(orca.sampleRate);

    object = {
      audio: audio,
      cheetah: cheetah,
      llm: llm,
      orca: orca,
      rhino: rhino,
      action: Action.GREET,
      username: name,
      askForDetailsRetryCount: 0,
    };
  }

  return () => speakToCaller();
};

const updateStartParameters = async (name: string): Promise<void> => {
  if (object) {
    object!.action = Action.GREET;
    object!.username = name;
    object!.askForDetailsRetryCount = 0;
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
