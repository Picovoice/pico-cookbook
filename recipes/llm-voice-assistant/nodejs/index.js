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

class Recorder {
  constructor(listener, recorder, profile) {
    this.listener = listener;
    this.recorder = recorder;
    this.profile = profile;

    this.recorder.start();
  }
  async tick() {
    const pcm = await this.recorder.read();
    this.listener.process(pcm);
  }
}

class Listener {
  constructor(generator, porcupine, cheetah, profile) {
    this.generator = generator;
    this.porcupine = porcupine;
    this.cheetah = cheetah;
    this.profile = profile;
    this.porcupineProfiler = new RTFProfiler(porcupine.sampleRate);
    this.cheetahProfiler = new RTFProfiler(cheetah.sampleRate);
    this.sleeping = true;
    this.listening = false;
    this.userRequest = '';
  }
  process(pcm) {
    if (this.sleeping) {
      this.porcupineProfiler.tick();
      let wakeWordDetected = this.porcupine.process(pcm) === 0;
      this.porcupineProfiler.tock(pcm);
      if (wakeWordDetected) {
        this.sleeping = false;
        this.generator.interrupt();

        if (this.profile) {
          process.stdout.write(`[Porcupine RTF: ${this.porcupineProfiler.rtf()}]\n`);
        }
      }
    } else if (this.listening) {
      this.cheetahProfiler.tick();
      const [partialTranscript, isEndpoint] = this.cheetah.process(pcm);
      this.cheetahProfiler.tock(pcm);
      this.userRequest += partialTranscript;
      process.stdout.write(partialTranscript);
      if (isEndpoint) {
        this.sleeping = true;
        this.listening = false;
        this.cheetahProfiler.tick();
        const remainingTranscript = this.cheetah.flush();
        this.cheetahProfiler.tock(pcm);
        process.stdout.write(`${remainingTranscript}\n`);
        this.userRequest += remainingTranscript;
        if (this.profile) {
          process.stdout.write(`[Cheetah RTF: ${this.cheetahProfiler.rtf()}]\n`);
        }
        this.generator.process(this.userRequest, performance.now());
        this.userRequest = '';
      }
    } else {
      this.listening = true;
      process.stdout.write('$ Wake word detected, utter your request or question ...\n');
      process.stdout.write('User > ');
    }
  }
}

class Generator {
  constructor(synthesizer, picollm, dialog, picollmGenerateOptions, isShortAnswers, stopPhrases, ppnPrompt, profile) {
    this.synthesizer = synthesizer;
    this.picollm = picollm;
    this.dialog = dialog;
    this.picollmGenerateOptions = picollmGenerateOptions;
    this.isShortAnswers = isShortAnswers;
    this.ppnPrompt = ppnPrompt;
    this.profile = profile;
    this.picollmProfiler = new TPSProfiler();
    this.generating = false;
    this.isGenerateComplete = false;
    this.textQueue = [];
    this.completion = new CompletionText(stopPhrases);
    this.utteranceEndMillisec = 0;
  }
  process(prompt, utteranceEndMillisec) {
    this.utteranceEndMillisec = utteranceEndMillisec;

    let self = this;
    function streamCallback(text) {
      self.picollmProfiler.tock();
      self.textQueue.push(text);
    }
    function onGenerateComplete(res) {
      self.isGenerateComplete = true;
      self.dialog.addLLMResponse(res.completion);
    }

    process.stdout.write(`LLM (say ${this.ppnPrompt} to interrupt) > `);

    this.generating = true;
    this.dialog.addHumanRequest(this.isShortAnswers ? `You are a voice assistant and your answers are very short but informative. ${prompt}` : prompt);
    this.completion.reset();
    this.isGenerateComplete = false;
    this.picollm.generate(this.dialog.prompt(), {
      completionTokenLimit: this.picollmGenerateOptions.picollmCompletionTokenLimit,
      stopPhrases: this.picollmGenerateOptions.stopPhrases,
      presencePenalty: this.picollmGenerateOptions.picollmPresencePenalty,
      frequencyPenalty: this.picollmGenerateOptions.picollmFrequencyPenalty,
      temperature: this.picollmGenerateOptions.picollmTemperature,
      topP: this.picollmGenerateOptions.picollmTopP,
      streamCallback: streamCallback
    }).then(onGenerateComplete);
  }
  interrupt() {
    if (this.generating) {
      this.generating = false;
      this.picollm.interrupt();
      process.stdout.write('\n');
    }
    this.synthesizer.interrupt();
  }
  tick() {
    if (this.generating) {
      while (this.textQueue.length > 0) {
        const text = this.textQueue.shift();
        this.completion.append(text);
        const newTokens = this.completion.getNewTokens();
        if (newTokens && newTokens.length > 0) {
          process.stdout.write(text);
          this.synthesizer.process(text, this.utteranceEndMillisec);
        }
      }
      if (this.isGenerateComplete) {
        this.generating = false;
        this.synthesizer.flush();
        process.stdout.write('\n');

        if (this.profile) {
          process.stdout.write(`[picoLLM TPS: ${this.picollmProfiler.tps()}]\n`);
        }
      }
    } else if (this.textQueue.length > 0) {
      this.textQueue = [];
    }
  }
}

class Synthesizer {
  constructor(speaker, orca, orcaStream, profile) {
    this.speaker = speaker;
    this.orca = orca;
    this.orcaStream = orcaStream;
    this.profile = profile;
    this.orcaProfiler = new RTFProfiler(orca.sampleRate);
    this.synthesizing = false;
    this.flushing = false;
    this.textQueue = [];
    this.delaySec = -1;
    this.utteranceEndMillisec = null;
  }
  process(text, utteranceEndMillisec) {
    this.utteranceEndMillisec = utteranceEndMillisec;
    if (!this.synthesizing) {
      this.synthesizing = true;
    }
    this.textQueue.push(text);
  }
  flush() {
    if (this.synthesizing) {
      this.flushing = true;
    }
  }
  interrupt() {
    if (this.synthesizing) {
      this.synthesizing = false;
      this.flushing = false;
      this.textQueue = [];
      this.orcaStream.flush();
    }
    this.speaker.interrupt();
  }
  tick() {
    while (this.synthesizing && this.textQueue.length > 0) {
      const text = this.textQueue.shift();
      this.orcaProfiler.tick();
      const pcm = this.orcaStream.synthesize(text.replace('\n', ' . '));
      this.orcaProfiler.tock(pcm);
      this.speaker.process(pcm);

      if (pcm !== null && this.delaySec === -1) {
        this.delaySec = (performance.now() - this.utteranceEndMillisec) / 1000;
      }
    }

    if (this.synthesizing && this.flushing && this.textQueue.length === 0) {
      this.synthesizing = false;
      this.flushing = false;
      this.orcaProfiler.tick();
      const pcm = this.orcaStream.flush();
      this.orcaProfiler.tock(pcm);
      this.speaker.process(pcm);
      this.speaker.flush();

      if (this.profile) {
        process.stdout.write(`[Orca RTF: ${this.orcaProfiler.rtf()}]\n`);
        process.stdout.write(`[Delay: ${this.delaySec.toFixed(3)} sec]\n`);
      }

      this.delaySec = -1;
      this.utteranceEndMillisec = 0;
    }
  }
}

class Speaker {
  constructor(speaker, orcaWarmup, ppnPrompt, profile) {
    this.speaker = speaker;
    this.orcaWarmup = orcaWarmup;
    this.ppnPrompt = ppnPrompt;
    this.profile = profile;

    this.speaking = false;
    this.flushing = false;
    this.pcmQueue = [];
  }
  process(pcm) {
    if (pcm !== null) {
      this.pcmQueue.push(...pcm);
    }
  }
  flush() {
    if (this.speaking) {
      this.flushing = true;
    }
  }
  interrupt() {
    if (this.speaking) {
      this.speaking = false;
      this.pcmQueue = [];
      this.speaker.stop();
    }
  }
  tick() {
    if (!this.speaking && this.pcmQueue.length >= this.orcaWarmup) {
      this.speaking = true;
      this.speaker.start();
    }

    if (this.speaking && this.pcmQueue.length > 0) {
      const arrayBuffer = new Int16Array(this.pcmQueue).buffer;
      const written = this.speaker.write(arrayBuffer);
      this.pcmQueue = this.pcmQueue.slice(written);
    }

    if (this.speaking && this.flushing && this.pcmQueue.length === 0) {
      this.speaking = false;
      this.flushing = false;

      this.speaker.flush();
      this.speaker.stop();

      process.stdout.write(`$ Say ${this.ppnPrompt} ...\n`);
    }
  }
}

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

  const ppnPrompt = `${keywordModelPath ? 'the wake word' : '`Picovoice`'}`;
  const stopPhrases = [
    '</s>', // Llama-2, Mistral, and Mixtral
    '<end_of_turn>', // Gemma
    '<|endoftext|>', // Phi-2
    '<|eot_id|>', // Llama-3
    '<|end|>', '<|user|>', '<|assistant|>' // Phi-3
  ];
  const picollmGenerateOptions = {
    completionTokenLimit: picollmCompletionTokenLimit,
    stopPhrases: stopPhrases,
    presencePenalty: picollmPresencePenalty,
    frequencyPenalty: picollmFrequencyPenalty,
    temperature: picollmTemperature,
    topP: picollmTopP,
  };

  const porcupine = new Porcupine(accessKey, [keywordModelPath ?? BuiltinKeyword.PICOVOICE], [0.5]);
  process.stdout.write(`→ Porcupine v${porcupine.version}\n`);

  const cheetah = new Cheetah(accessKey, { endpointDurationSec, enableAutomaticPunctuation: true });
  process.stdout.write(`→ Cheetah v${cheetah.version}\n`);

  const picollm = new PicoLLM(accessKey, picollmModelPath, { device: picollmDevice });
  process.stdout.write(`→ picoLLM v${picollm.version} <${picollm.model}>\n`);

  const orca = new Orca(accessKey);
  process.stdout.write(`→ Orca v${orca.version}\n`);

  const dialog = picollm.getDialog();
  const orcaStream = orca.streamOpen();
  const pvSpeaker = new PvSpeaker(orca.sampleRate, 16, { 'bufferSizeSecs': 1 });
  const pvRecorder = new PvRecorder(porcupine.frameLength);

  const speaker = new Speaker(pvSpeaker, orcaWarmupSec * orca.sampleRate, ppnPrompt, profile);
  const synthesizer = new Synthesizer(speaker, orca, orcaStream, profile);
  const generator = new Generator(synthesizer, picollm, dialog, picollmGenerateOptions, isShortAnswers, stopPhrases, ppnPrompt, profile);
  const listener = new Listener(generator, porcupine, cheetah, profile);
  const recorder = new Recorder(listener, pvRecorder, profile);

  process.stdout.write(`$ Say ${ppnPrompt} ...\n`);

  let isInterrupted = false;
  process.on('SIGINT', function () {
    isInterrupted = true;
  });

  try {
    while (!isInterrupted) {
      await recorder.tick();
      generator.tick();
      synthesizer.tick();
      speaker.tick();
    }
  } finally {
    pvRecorder.stop();
    pvRecorder.release();
    pvSpeaker.stop();
    pvSpeaker.release();
    porcupine.release();
    cheetah.release();
    picollm.release();
    orcaStream.close();
    orca.release();
  }
}

(async function () {
  try {
    await llmVoiceAssistant();
  } catch (e) {
    process.stderr.write(`\n${e.toString()}\n`);
  }
})();
