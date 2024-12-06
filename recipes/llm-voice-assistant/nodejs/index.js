#! /usr/bin/env node
'use strict';

const { Porcupine, BuiltinKeyword } = require('@picovoice/porcupine-node');
const { Cheetah } = require('@picovoice/cheetah-node');
const { PicoLLM } = require('@picovoice/picollm-node');
const { Orca } = require('@picovoice/orca-node');
const { PvRecorder } = require('@picovoice/pvrecorder-node');
const { PvSpeaker } = require('@picovoice/pvspeaker-node');

const { program } = require('commander');
const { performance } = require('perf_hooks');
const process = require('process');


class RTFProfiler {
  constructor(sampleRate) {
    this._sampleRate = sampleRate;
    this._computeSec = 0;
    this._audioSec = 0;
    this._tickMillisec = 0;
  }

  tick() {
    this._tickMillisec = performance.now();
  }

  tock(audio) {
    const elapsedTimeMillisec = performance.now() - this._tickMillisec;
    this._computeSec += (elapsedTimeMillisec / 1000);
    if (audio !== null && audio !== undefined) {
      this._audioSec += audio.length / this._sampleRate;
    }
  }

  rtf() {
    const rtf = this._computeSec / this._audioSec;
    this._computeSec = 0;
    this._audioSec = 0;
    return rtf.toFixed(3);
  }
}

class TPSProfiler {
  constructor() {
    this._numTokens = 0;
    this._startMillisec = 0;
  }

  tock() {
    if (this._startMillisec === 0) {
      this._startMillisec = performance.now();
    } else {
      this._numTokens += 1;
    }
  }

  tps() {
    const elapsedTimeMillisec = performance.now() - this._startMillisec;
    const tps = this._numTokens / (elapsedTimeMillisec / 1000);
    this._numTokens = 0;
    this._startMillisec = 0;
    return tps.toFixed(3);
  }
}

class CompletionText {
  constructor(stopPhrases) {
    this._stopPhrases = stopPhrases;
    this._start = 0;
    this._text = '';
    this._newTokens = '';
  }
  reset() {
    this._start = 0;
    this._text = '';
    this._newTokens = '';
  }
  append(text) {
    this._text += text;
    let end = this._text.length;

    for (let stopPhrase of this._stopPhrases) {
      let contains = this._text.indexOf(stopPhrase);
      if (contains >= 0) {
        if (end > contains) {
          end = contains;
        }
      }
      for (let i = stopPhrase.length - 1; i > 0; i--) {
        if (this._text.endsWith(stopPhrase.slice(0, i))) {
          let ends = this._text.length - i;
          if (end > ends) {
            end = ends;
          }
          break;
        }
      }
    }

    let start = this._start;
    this._start = end;
    this._newTokens = this._text.slice(start, end);
  }
  getNewTokens() {
    return this._newTokens;
  }
}

let isInterrupted = false;

program
  .requiredOption(
    '-a, --access_key <string>',
    '`AccessKey` obtained from the `Picovoice Console` (https://console.picovoice.ai/).',
  )
  .requiredOption(
    '-p, --picollm_model_path <string>',
    'Absolute path to the file containing LLM parameters (`.pllm`).',
  )
  .option(
    '--keyword_model_path <string>',
    'Absolute path to the keyword model file (`.ppn`). If not set, `Picovoice` will be the wake phrase.',
  )
  .option(
    '--cheetah_endpoint_duration_sec <number>',
    'Duration of silence (pause) after the user\'s utterance to consider it the end of the utterance.',
    '1',
  )
  .option(
    '--picollm_device <string>',
    'String representation of the device (e.g., CPU or GPU) to use for inference. If set to `best`, picoLLM \n' +
    'picks the most suitable device. If set to `gpu`, the engine uses the first available GPU device. To \n' +
    'select a specific GPU device, set this argument to `gpu:${GPU_INDEX}`, where `${GPU_INDEX}` is the index \n' +
    'of the target GPU. If set to `cpu`, the engine will run on the CPU with the default number of threads. \n' +
    'To specify the number of threads, set this argument to `cpu:${NUM_THREADS}`, where `${NUM_THREADS}` is \n' +
    'the desired number of threads.',
  )
  .option(
    '--picollm_completion_token_limit <number>',
    'Maximum number of tokens in the completion. Set to `None` to impose no limit.',
    '256',
  )
  .option(
    '--picollm_presence_penalty <number>',
    'It penalizes logits already appearing in the partial completion if set to a positive value. If set to \n' +
    '`0.0`, it has no effect.',
    '0',
  )
  .option(
    '--picollm_frequency_penalty <number>',
    'If set to a positive floating-point value, it penalizes logits proportional to the frequency of their \n' +
    'appearance in the partial completion. If set to `0.0`, it has no effect.',
    '0',
  )
  .option(
    '--picollm_temperature <number>',
    'Sampling temperature. Temperature is a non-negative floating-point value that controls the randomness of \n' +
    'the sampler. A higher temperature smoothens the samplers\' output, increasing the randomness. In \n' +
    'contrast, a lower temperature creates a narrower distribution and reduces variability. Setting it to \n' +
    '`0` selects the maximum logit during sampling.',
    '0',
  )
  .option(
    '--picollm_top_p <number>',
    'A positive floating-point number within (0, 1]. It restricts the sampler\'s choices to high-probability \n' +
    'logits that form the `top_p` portion of the probability mass. Hence, it avoids randomly selecting \n' +
    'unlikely logits. A value of `1.` enables the sampler to pick any token with non-zero probability, \n' +
    'turning off the feature.',
    '1',
  )
  .option(
    '--orca_warmup_sec <number>',
    'Duration of the synthesized audio to buffer before streaming it out. A higher value helps slower \n' +
    '(e.g., Raspberry Pi) to keep up with real-time at the cost of increasing the initial delay.',
    '0',
  )
  .option('--profile', 'Show runtime profiling information.')
  .option('--short_answers');

if (process.argv.length < 2) {
  program.help();
}
program.parse(process.argv);

async function llmVoiceAssistant() {
  const accessKey = program.access_key;
  const picollmModelPath = program.picollm_model_path;
  const keywordModelPath = program.keyword_model_path;
  const endpointDurationSec = Number(program.cheetah_endpoint_duration_sec);
  const picollmDevice = program.picollm_device;
  const picollmCompletionTokenLimit = Number(program.picollm_completion_token_limit);
  const picollmPresencePenalty = Number(program.picollm_presence_penalty);
  const picollmFrequencyPenalty = Number(program.picollm_frequency_penalty);
  const picollmTemperature = Number(program.picollm_temperature);
  const picollmTopP = Number(program.picollm_top_p);
  const orcaWarmupSec = Number(program.orca_warmup_sec);
  const profile = program.profile;
  const isShortAnswers = program.short_answers;

  let porcupine = new Porcupine(accessKey, [keywordModelPath ?? BuiltinKeyword.PICOVOICE], [0.5]);
  process.stdout.write(`→ Porcupine v${porcupine.version}\n`);

  const cheetah = new Cheetah(accessKey, { endpointDurationSec, enableAutomaticPunctuation: true });
  process.stdout.write(`→ Cheetah v${cheetah.version}\n`);

  const pllm = new PicoLLM(accessKey, picollmModelPath, { device: picollmDevice });
  process.stdout.write(`→ picoLLM v${pllm.version} <${pllm.model}>\n`);

  const orca = new Orca(accessKey);
  process.stdout.write(`→ Orca v${orca.version}\n`);

  const dialog = pllm.getDialog();
  const orcaStream = orca.streamOpen();
  const speaker = new PvSpeaker(orca.sampleRate, 16, { 'bufferSizeSecs': 1 });
  const recorder = new PvRecorder(porcupine.frameLength);
  recorder.start();

  const ppnPrompt = `$ Say ${keywordModelPath ? 'the wake word' : '`Picovoice`'} ...`;
  process.stdout.write(`${ppnPrompt}\n`);

  const picollmProfiler = new TPSProfiler();
  const porcupineProfiler = new RTFProfiler(porcupine.sampleRate);
  const cheetahProfiler = new RTFProfiler(cheetah.sampleRate);
  const orcaProfiler = new RTFProfiler(orca.sampleRate);
  let utteranceEndMillisec = 0;
  let delaySec = -1;
  const stopPhrases = [
    '</s>', // Llama-2, Mistral, and Mixtral
    '<end_of_turn>', // Gemma
    '<|endoftext|>', // Phi-2
    '<|eot_id|>', // Llama-3
    '<|end|>', '<|user|>', '<|assistant|>' // Phi-3
  ];

  let listening = false;
  let generating = false;
  let synthesizing = false;
  let speaking = false;
  let flush = false;

  let completionText = new CompletionText(stopPhrases);
  let userRequest = '';
  let textQueue = [];
  let pcmQueue = [];

  function streamCallback(text) {
    picollmProfiler.tock();
    completionText.append(text);
    let newTokens = completionText.getNewTokens();
    if (newTokens.length > 0 && generating) {
      textQueue.push(newTokens);
    }
  }

  let generateResult = null;
  function onGenerateComplete(res) {
    generateResult = res;
  }

  try {
    while (!isInterrupted) {
      let pcm = await recorder.read();
      if (!listening) {
        porcupineProfiler.tick();
        let isWakeWordDetected = porcupine.process(pcm) === 0;
        porcupineProfiler.tock(pcm);
        if (isWakeWordDetected) {
          if (generating) {
            pllm.interrupt();
            process.stdout.write('\n');
            textQueue = [];
            generating = false;
            if (profile) {
              process.stdout.write(`[picoLLM TPS: ${picollmProfiler.tps()}]\n`);
            }
          }
          if (synthesizing) {
            orcaProfiler.tick();
            const flushedPcm = orcaStream.flush();
            orcaProfiler.tock(flushedPcm);
            pcmQueue = [];
            if (profile) {
              process.stdout.write(`[Orca RTF: ${orcaProfiler.rtf()}]\n`);
              process.stdout.write(`[Delay: ${delaySec.toFixed(3)} sec]\n`);
            }
            synthesizing = false;
          }
          if (speaking) {
            speaker.flush();
            speaker.stop();
            await recorder.read();
            speaking = false;
          }
          if (flush) {
            flush = false;
          }

          if (profile) {
            process.stdout.write(`[Porcupine RTF: ${porcupineProfiler.rtf()}]\n`);
          }

          listening = true;

          process.stdout.write('$ Wake word detected, utter your request or question ...\n');
          process.stdout.write('User > ');
        }
      } else {
        cheetahProfiler.tick();
        const [partialTranscript, isEndpoint] = cheetah.process(pcm);
        cheetahProfiler.tock(pcm);
        process.stdout.write(partialTranscript);
        userRequest += partialTranscript;
        if (isEndpoint) {
          utteranceEndMillisec = performance.now();
          delaySec = -1;
          cheetahProfiler.tick();
          const remainingTranscript = cheetah.flush();
          cheetahProfiler.tock(pcm);
          userRequest += remainingTranscript;
          process.stdout.write(`${remainingTranscript}\n`);
          if (profile) {
            process.stdout.write(`[Cheetah RTF: ${cheetahProfiler.rtf()}]\n`);
          }

          listening = false;
          generating = true;

          completionText.reset();
          dialog.addHumanRequest(isShortAnswers ? `You are a voice assistant and your answers are very short but informative. ${userRequest}` : userRequest);
          pllm.generate(
            dialog.prompt(),
            {
              completionTokenLimit: picollmCompletionTokenLimit,
              stopPhrases: stopPhrases,
              presencePenalty: picollmPresencePenalty,
              frequencyPenalty: picollmFrequencyPenalty,
              temperature: picollmTemperature,
              topP: picollmTopP,
              streamCallback: streamCallback,
            },
          ).then(onGenerateComplete);
        }
      }

      if (generateResult !== null) {
        if (generating) {
          process.stdout.write('\n');
          if (profile) {
            process.stdout.write(`[picoLLM TPS: ${picollmProfiler.tps()}]\n`);
          }
          flush = true;
          generating = false;
        }
        dialog.addLLMResponse(generateResult.completion);
        generateResult = null;
      }

      if (!synthesizing && textQueue.length > 0) {
        synthesizing = true;
      }

      while (textQueue.length > 0) {
        let text = textQueue.shift();
        process.stdout.write(text);

        orcaProfiler.tick();
        const pcm = orcaStream.synthesize(text.replace('\n', ' . '));
        orcaProfiler.tock(pcm);
        if (pcm !== null) {
          if (delaySec === -1) {
            delaySec = (performance.now() - utteranceEndMillisec) / 1000;
          }
          pcmQueue.push(...pcm);
        }
      }

      if (synthesizing && flush) {
        orcaProfiler.tick();
        const flushedPcm = orcaStream.flush();
        orcaProfiler.tock(flushedPcm);
        if (pcm !== null) {
          pcmQueue.push(...flushedPcm);
        }
        if (profile) {
          process.stdout.write(`[Orca RTF: ${orcaProfiler.rtf()}]\n`);
          process.stdout.write(`[Delay: ${delaySec.toFixed(3)} sec]\n`);
        }
        synthesizing = false;
      }

      if (!speaking && pcmQueue.length >= orcaWarmupSec * orca.sampleRate) {
        speaking = true;
        speaker.start();
      }

      if (speaking && pcmQueue.length > 0) {
        const arrayBuffer = new Int16Array(pcmQueue).buffer;
        const written = speaker.write(arrayBuffer);
        pcmQueue = pcmQueue.slice(written);
      }

      if (speaking && flush && pcmQueue.length === 0) {
        const arrayBuffer = new Int16Array(pcmQueue).buffer;
        speaker.flush(arrayBuffer);
        speaker.stop();
        process.stdout.write(`${ppnPrompt}\n`);
        speaking = false;
        flush = false;
      }
    }
  } finally {
    recorder.stop();
    recorder.release();
    speaker.stop();
    speaker.release();
    porcupine.release();
    cheetah.release();
    pllm.release();
    orcaStream.close();
    orca.release();
  }
}

process.on('SIGINT', function () {
  isInterrupted = true;
});

(async function () {
  try {
    await llmVoiceAssistant();
  } catch (e) {
    process.stderr.write(`\n${e.toString()}\n`);
  }
})();
