import { CheetahTranscript, CheetahWorker } from '@picovoice/cheetah-web';
import { OrcaWorker, OrcaAlignment, Orca } from '@picovoice/orca-web';
import { PorcupineDetection, PorcupineWorker } from '@picovoice/porcupine-web';
import { RhinoInference, RhinoWorker } from '@picovoice/rhino-web';

import { Interrupter, AINoiseSuppressedRecorder } from './ai_noise_suppressed_recorder';
import { AudioStream } from './audio_stream';

import { callbacks, isRunning } from './config';

export enum Steps {
    ORCA = "Orca",
    PORCUPINE = "Porcupine",
    RHINO = "Rhino",
    CHEETAH = "Cheetah",
}

export type StepOptions =
  | { 
    step: Steps.ORCA;
    publicPath?: string
  }
  | {
    step: Steps.PORCUPINE;
    publicPath?: string;
    keywordPath: string;
  }
  | {
    step: Steps.RHINO;
    publicPath?: string;
    contextPath: string;
  }
  | {
    step: Steps.CHEETAH;
    publicPath?: string;
  };

export async function createStep(
    accessKey: string,
    recorder: AINoiseSuppressedRecorder,
    audio: AudioStream,
    options: StepOptions
): Promise<Step> {
    switch (options.step) {
        case Steps.ORCA:
            return await OrcaStep.create(
                accessKey,
                audio,
                options.publicPath);
        case Steps.PORCUPINE:
            return await PorcupineStep.create(
                accessKey,
                recorder,
                options.keywordPath,
                options.publicPath);
        case Steps.RHINO:
            return await RhinoStep.create(
                accessKey,
                recorder,
                options.contextPath,
                options.publicPath);
        case Steps.CHEETAH:
            return await CheetahStep.create(
                accessKey,
                recorder,
                options.publicPath);
    }
}

export abstract class Step {
    abstract release(): Promise<void>;
    abstract toString(): string;
}

export class OrcaStep extends Step {
    private audio: AudioStream;
    private orca: OrcaWorker;

    private constructor(audio: AudioStream, orca: OrcaWorker) {
        super();
        this.audio = audio;
        this.orca = orca;
    }

    static async create(
        accessKey: string,
        audio: AudioStream,
        modelPath?: string,
    ): Promise<OrcaStep> {
        callbacks.setStatusText("Loading Orca");
        const orca = await OrcaWorker.create(
            accessKey,
            {
                publicPath: modelPath,
                forceWrite: true,
            },
            {}
        );

        return new OrcaStep(audio, orca);
    }

    async run(
        prompt: string,
        onSynthesis?: (alignments: OrcaAlignment[]) => void,
    ) {
        try {
            const { pcm, alignments } = await this.orca.synthesize(prompt);
            if (onSynthesis) {
                onSynthesis(alignments);
            }

            this.audio.stream(pcm);
            this.audio.play();

        } finally {
            await this.audio.waitPlayback(() => !isRunning);
        }
    }

    async release() {
        await this.orca.release();
    }

    toString(): string {
        return `${this.constructor.name} {
    ${this.orca.constructor.name}[V${this.orca.version}]
}`;
    }
}

export class PorcupineStep extends Step {
    private recorder: AINoiseSuppressedRecorder;
    private porcupine: PorcupineWorker;

    private interrupter: Interrupter;
    private isDetected: boolean;

    private constructor (
        recorder: AINoiseSuppressedRecorder,
        porcupine: PorcupineWorker,
    ) {
        super();
        this.recorder = recorder;
        this.porcupine = porcupine;
        this.interrupter = {};
        this.isDetected = false;
    }

    static async create(
        accessKey: string,
        recorder: AINoiseSuppressedRecorder,
        keywordPath: string,
        modelPath?: string,
        sensitivity: number = 0.5,
    ) {
        callbacks.setStatusText("Loading Porcupine");
        const porcupine = await PorcupineWorker.create(
            accessKey,
            {
                label: "voice-guided-maintenance-and-inspection-keyword",
                sensitivity,
                publicPath: keywordPath,
                forceWrite: true,
            },
            (detection: PorcupineDetection) => step.keywordCallback(detection),
            {
                publicPath: modelPath,
                forceWrite: true
            },
            {}
        );

        const step = new PorcupineStep(recorder, porcupine);
        return step;
    }

    private keywordCallback(detection: PorcupineDetection) {
        if (!this.isDetected && detection.index == 0) {
            this.isDetected = true;

            if (this.interrupter.onInterrupt) {
                this.interrupter.onInterrupt();
            }
        }
    }

    async run(listeningPrompt: string) {
        callbacks.setStatusText(listeningPrompt);
        callbacks.onListening(true);

        try {
            this.isDetected = false;

            await this.recorder.start();

            while (isRunning) {
                this.interrupter = {};
                const frame = await this.recorder.read(this.porcupine.frameLength, this.interrupter);
                if (this.isDetected) {
                    break;
                }

                this.interrupter = {};
                await this.porcupine.process(frame);
                if (this.isDetected) {
                    break;
                }
            }
        } finally {
            callbacks.onListening(false);
            await this.recorder.stop();
        }
    }

    async release() {
        await this.porcupine.release();
    }

    toString(): string {
        return `${this.constructor.name} {
    ${this.porcupine.constructor.name}[V${this.porcupine.version}]
}`;
    }
}

export class RhinoStep extends Step {
    private recorder: AINoiseSuppressedRecorder;
    private rhino: RhinoWorker;

    private interrupter: Interrupter;
    private inference?: RhinoInference;

    private constructor(recorder: AINoiseSuppressedRecorder, rhino: RhinoWorker) {
        super();
        this.recorder = recorder;
        this.rhino = rhino;
        this.interrupter = {};
    }

    static async create(
        accessKey: string,
        recorder: AINoiseSuppressedRecorder,
        contextPath: string,
        modelPath?: string,
        sensitivity: number = 0.5,
        endpointDurationSec: number = 0.5,
        requireEndpoint: boolean = false
    ) {
        callbacks.setStatusText("Loading Rhino");
        const rhino = await RhinoWorker.create(
            accessKey,
            {
                sensitivity,
                publicPath: contextPath,
                forceWrite: true
            },
            (inference: RhinoInference) => step.inferenceCallback(inference),
            {
                publicPath: modelPath,
                forceWrite: true
            },
            {
                endpointDurationSec: endpointDurationSec,
                requireEndpoint: requireEndpoint,
            }
        );

        const step = new RhinoStep(recorder, rhino);
        return step;
    }

    private inferenceCallback(inference: RhinoInference) {
        if (!this.inference && inference.isFinalized) {
            this.inference = inference;

            if (this.interrupter.onInterrupt) {
                this.interrupter.onInterrupt();
            }
        }
    }

    async run(listeningPrompt: string): Promise<RhinoInference | undefined> {
        callbacks.setStatusText(listeningPrompt);
        callbacks.onListening(true);
        
        try {
            this.inference = undefined;

            await this.recorder.start();

            while (isRunning) {
                this.interrupter = {};
                const frame = await this.recorder.read(this.rhino.frameLength, this.interrupter);
                if (this.inference) {
                    break;
                }

                this.interrupter = {};
                await this.rhino.process(frame);
                if (this.inference) {
                    break;
                }
            }
    
            return this.inference;
        } finally {
            callbacks.onListening(false);
            await this.recorder.stop();
        }
    }

    async release() {
        await this.rhino.release();
    }

    toString(): string {
        return `${this.constructor.name} {
    ${this.rhino.constructor.name}[V${this.rhino.version}]
}`;
    }
}

export class CheetahStep extends Step {
    private recorder: AINoiseSuppressedRecorder;
    private cheetah: CheetahWorker;

    private interrupter: Interrupter;
    private transcript: string;
    private isFinished: boolean;

    private onPartial: (partial: string) => void;

    private constructor(recorder: AINoiseSuppressedRecorder, cheetah: CheetahWorker) {
        super();
        this.recorder = recorder;
        this.cheetah = cheetah;
        this.interrupter = {};
        this.transcript = "";
        this.isFinished = false;
        this.onPartial = (partial: string) => {};
    }

    static async create(
        accessKey: string,
        recorder: AINoiseSuppressedRecorder,
        modelPath?: string,
        endpointDurationSec: number = 1.0,
        enableAutomaticPunctuation: boolean = true,
        enableTextNormalization: boolean = true,
    ) {
        callbacks.setStatusText("Loading Cheetah");
        const cheetah = await CheetahWorker.create(
            accessKey,
            (cheetahTranscript: CheetahTranscript) => step.transcriptCallback(cheetahTranscript),
            {
                publicPath: modelPath,
                forceWrite: true
            },
            {
                endpointDurationSec: endpointDurationSec,
                enableAutomaticPunctuation: enableAutomaticPunctuation,
                enableTextNormalization: enableTextNormalization,
            }
        );

        const step = new CheetahStep(recorder, cheetah);
        return step;
    }

    private transcriptCallback(cheetahTranscript: CheetahTranscript) {
        this.transcript += cheetahTranscript.transcript;
        this.onPartial(cheetahTranscript.transcript);

        if (cheetahTranscript.isEndpoint) {
            this.isFinished = true;

            if (this.interrupter.onInterrupt) {
                this.interrupter.onInterrupt();
            }
        }
    }

    async run(listeningPrompt: string, onPartial: (partial: string) => void): Promise<string> {
        callbacks.setStatusText(listeningPrompt);
        callbacks.onListening(true);

        try {
            this.transcript = "";
            this.onPartial = onPartial;

            await this.recorder.start();

            while (isRunning) {
                this.interrupter = {};
                const frame = await this.recorder.read(this.cheetah.frameLength, this.interrupter);
                if (this.isFinished) {
                    break;
                }

                this.interrupter = {};
                await this.cheetah.process(frame);
                if (this.isFinished) {
                    break;
                }
            }

            return this.transcript;
        } finally {
            callbacks.onListening(false);
            await this.recorder.stop();
        }
    }

    async release() {
        await this.cheetah.release();
    }

    toString(): string {
        return `${this.constructor.name} {
    ${this.cheetah.constructor.name}[V${this.cheetah.version}]
}`;
    }
}