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

Examples:
Caller said: "I'm calling from the bank."
caller: bank
reason: unknown

Caller said: "This is ups with a package delivery."
caller: UPS
reason: package delivery

Caller said: "This is customer service."
caller: customer service
reason: unknown

Caller said: "I'm calling about your credit card."
caller: unknown
reason: credit card

Caller said: "Hello, can you hear me?"
caller: unknown
reason: unknown
`;

const sleep = (ms: number) => new Promise(resolve => setTimeout(resolve, ms));

const extractCallerAndReasonFromLLMInference = (inference: string): [string, string] => {
    const inferenceLines = inference.split("\n");
    if (inferenceLines.length !== 2)
        return ["unknown", "unknown"];

    const callerLine = inferenceLines[0];
    const reasonLine = inferenceLines[1];

    if (!callerLine.startsWith("caller: "))
        return ["unknown", "unknown"];
    const caller = callerLine.slice("caller: ".length);
    if (caller.length == 0)
        return ["unknown", "unknown"];

    if (!reasonLine.startsWith("reason: "))
        return ["unknown", "unknown"];
    const reason = reasonLine.slice("reason: ".length);
    if (reason.length == 0)
        return ["unknown", "unknown"];

    return [caller, reason];
}

// ------------------------------------------------------------------------- //

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

// ------------------------------------------------------------------------- //

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

// ------------------------------------------------------------------------- //

const init = async (
  accessKey: string,
  username: string,
  sendMessage: (message: string, obj: any) => void,
  makeRequest: (message: string) => any,
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

    sendMessage("ai state", "");

    await stopListenForCaller();
    await stopGiveUserOptions();

    sendMessage("restart demo", null);
  };

  const speakToCaller = async () => {
    const orca = object!.orca;

    const synthesis = await orca.synthesize(
      promptFromAction(object!.action, object!.username), {});
    const alignments = synthesis.alignments;

    object!.audio.stream(synthesis.pcm);
    object!.audio.play();

    sendMessage("ai state", "AI: Speaking to caller");
    sendMessage("new ai bubble", "[AI] ");

    for (let alignment of alignments) {
      const index = alignments.indexOf(alignment);
      let word = alignment.word;
      if (index < alignments.length - 1 &&
          !(/[.,:;!?]/.test(alignments[index + 1].word))
      ) {
        word += " ";
      }

      setTimeout(() => {
        sendMessage("add to bubble", word);
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
    sendMessage("new caller bubble", "[CALLER] ");
    sendMessage("start listening", null);
    sendMessage("ai state", "AI: Listening to caller");

    const cheetah = BufferedCheetahEngine;
    await WebVoiceProcessor.subscribe(cheetah);
  };
  const stopListenForCaller = async () => {
    sendMessage("stop listening", null);
    const cheetah = BufferedCheetahEngine;
    await WebVoiceProcessor.unsubscribe(cheetah);
  };
  const transcriptCallback = async (
    transcript: CheetahTranscript
  ): Promise<void> => {
    if (transcript.transcript.length > 0) {
      sendMessage("add to bubble", transcript.transcript);
    }

    if (transcript.isEndpoint) {
      sendMessage("add to bubble", " ");
      const cheetah = object!.cheetah;
      cheetah.flush();
    }

    if (transcript.isFlushed && makeRequest("bubble length") > 0) {
      await stopListenForCaller();

      console.log("LLM PROCESS CALL");

      let callerText = makeRequest("bubble contents");
      await llmProcessCall(callerText);
    }
  }

  const llmProcessCall = async (callerText: string) => {
    let dialog = await object!.llm.getDialog(undefined, undefined, SYSTEM);
    dialog.addHumanRequest(`Caller said: \"${callerText}\"\n`);

    console.log(callerText);

    const completion = await llm.generate(
        dialog.prompt(),
        { stopPhrases: ['<|eot_id|>'] });

    const inference = completion.completion.trim().replace('<|eot_id|>', '');
    console.log(`inference = ${inference}`);
    const [caller, reason] = extractCallerAndReasonFromLLMInference(inference);

    if (caller != 'unknown' && reason != 'unknown') {
      sendMessage("ai report", `[AI] \`${caller}\` is trying to speak with you about \`${reason}\`.`);
      console.log("GIVE OPTIONS");
      setTimeout(() => { giveUserOptions(); }, 200);
      return;
    }

    if (object!.askForDetailsRetryCount < ASK_FOR_DETAILS_RETRY_LIMIT) {
      object!.action = Action.ASK_FOR_DETAILS;

      if (caller == 'unknown' && reason == 'unknown') {
        sendMessage("ai report", "[AI] Unknown caller with no specific reason. I will ask for more information.");
      } else if (caller == 'unknown') {
        sendMessage(
            "ai report",
            `[AI] Unknown caller is trying to speak with you about \`${reason}\`. I will ask for their identity.`);
      } else {
        sendMessage(
            "ai report",
            `[AI] \`${caller}\` is trying to speak with you. I will ask for their reason.`);
      }
    } else {
      object!.action = Action.DECLINE_CALL;
      
      sendMessage(
          "ai report",
          `[AI] Couldn't understand caller's identity and agenda after ${ASK_FOR_DETAILS_RETRY_LIMIT} ` +
          "inquiries. Declining their call.");
    }

    object!.askForDetailsRetryCount += 1;
    setTimeout(() => { speakToCaller(); }, 200);
  };

  const giveUserOptions = async () => {
    sendMessage("give user options", allActions());
    sendMessage("ai state", "AI: Listening for " + object!.username + "'s command");

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
      sendMessage("select option", object!.action);

      setTimeout(() => { speakToCaller(); }, 600);
    } else {
      sendMessage("unknown user option", 1000);
    }
  };

  // If you update your model, you may want to enable force write to update the model in storage.
  const FORCE_WRITE = false;

  sendMessage("status", "Loading Cheetah");
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

  sendMessage("status", "Loading PicoLLM");
  const llm = await PicoLLMWorker.create(
    accessKey,
    { modelFile: 'models/llama-3.2-1b-instruct-385.pllm', cacheFileOverwrite: FORCE_WRITE },
    {}
  );

  sendMessage("status", "Loading Orca");
  const orca = await OrcaWorker.create(
    accessKey,
    { publicPath: "models/orca_params_en_female.pv", forceWrite: FORCE_WRITE },
    {}
  );

  sendMessage("status", "Loading Rhino");
  const rhino = await RhinoWorker.create(
    accessKey,
    { publicPath: 'models/call_assist_demo_web.rhn', forceWrite: FORCE_WRITE },
    inferenceCallback,
    { publicPath: 'models/rhino_params.pv', forceWrite: FORCE_WRITE },
  );

  sendMessage("ai state", "AI: Connecting caller");
  sendMessage("status", "Loading Audio Stream");
  const audio = new AudioStream(orca.sampleRate);

  object = {
    audio: audio,
    cheetah: cheetah,
    llm: llm,
    orca: orca,
    rhino: rhino,
    action: Action.GREET,
    username: username,

    askForDetailsRetryCount: 0,
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
  sleep,
  init,
  release
};
