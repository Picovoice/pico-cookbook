
import { OrcaWorker, OrcaAlignment } from '@picovoice/orca-web';
import { PorcupineDetection, PorcupineWorker } from '@picovoice/porcupine-web';
import { RhinoInference, RhinoWorker } from '@picovoice/rhino-web';

import { Interrupter, AINoiseSuppressedRecorder } from './ai_noise_suppressed_recorder';
import { AudioStream } from './audio_stream';

export enum Steps {
    ORCA = "Orca",
    PORCUPINE = "Porcupine",
    RHINO = "Rhino",
}

export type StepOptions =
  | { step: Steps.ORCA }
  | { step: Steps.PORCUPINE; keywordPath: string }
  | { step: Steps.RHINO; contextPath: string };

export abstract class Step {
    abstract release(): Promise<void>;
    abstract toString(): string;

    static create(
        accessKey: string,
        recorder: AINoiseSuppressedRecorder,
        audio: AudioStream,
        options: StepOptions
    ): Step {
        switch (options.step) {
            case Steps.ORCA:
                return new OrcaStep(accessKey, recorder, audio);
            case Steps.PORCUPINE:
                return new PorcupineStep(accessKey, recorder, audio, options.keywordPath);
            case Steps.RHINO:
                return new RhinoStep(accessKey, recorder, audio, options.contextPath);
        }
    }
}

export class OrcaStep extends Step {
    private audio: AudioStream;
    private orca: OrcaWorker;

    constructor(
        accessKey: string,
        _recorder: AINoiseSuppressedRecorder,
        audio: AudioStream,
        modelPath?: string,
    ) {
        super();
        this.audio = audio;

        this.orca = OrcaWorker.create(accessKey, {
            publicPath: modelPath,
            forceWrite: true,
        });
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
            await this.audio.waitPlayback();
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

    constructor(
        accessKey: string,
        recorder: AINoiseSuppressedRecorder,
        _audio: AudioStream,
        keywordPath: string,
        modelPath?: string,
        sensitivity: number = 0.5,
    ) {
        super();
        this.recorder = recorder;

        this.porcupine = PorcupineWorker.create(
            accessKey,
            {
                sensitivity,
                publicPath: keywordPath,
                forceWrite: true
            },
            (detection: PorcupineDetection) => this.keywordCallback(detection),
            {
                publicPath: modelPath,
                forceWrite: true
            },
            {}
        );

        this.isDetected = false;
        this.interrupter = {};
    }

    private keywordCallback(detection: PorcupineDetection) {
        if (!this.isDetected && detection.index == 0) {
            this.isDetected = true;

            if (this.interrupter.onInterrupt) {
                this.interrupter.onInterrupt();
            }
        }
    }

    async run() {
        try {
            this.isDetected = false;

            await this.recorder.start();

            while (true) {
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

    constructor(
        accessKey: string,
        recorder: AINoiseSuppressedRecorder,
        _audio: AudioStream,
        contextPath: string,
        modelPath?: string,
        sensitivity: number = 0.5,
        endpointDurationSec: number = 0.5,
        requireEndpoint: boolean = false
    ) {
        super();
        this.recorder = recorder;

        this.rhino = RhinoWorker.create(
            accessKey,
            { 
                sensitivity,
                publicPath: contextPath,
                forceWrite: true
            },
            (inference: RhinoInference) => this.inferenceCallback(inference),
            {
                publicPath: modelPath,
                forceWrite: true
            },
            {
                endpointDurationSec: endpointDurationSec,
                requireEndpoint: requireEndpoint,
            }
        );

        this.interrupter = {};
    }

    private inferenceCallback(inference: RhinoInference) {
        if (!this.inference && inference.isFinalized) {
            this.inference = inference;

            if (this.interrupter.onInterrupt) {
                this.interrupter.onInterrupt();
            }
        }
    }

    async run(): Promise<Record<string, any>> {
        try {
            this.inference = undefined;

            await this.recorder.start();

            while (true) {
                this.interrupter = {};
                const frame = await this.recorder.read(this.rhino.frame_length);
                if (this.inference) {
                    break;
                }

                this.interrupter = {};
                await this.rhino.process(frame);
                if (this.inference) {
                    break;
                }
            }
    
            return {
                isUnderstood: this.inference.isUnderstood,
                intent: this.inference.intent,
                slots: this.inference.slots,
            };
        } finally {
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