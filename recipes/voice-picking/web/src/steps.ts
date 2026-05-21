
import { CheetahTranscript, CheetahWorker } from '@picovoice/cheetah-web';
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

// TODO: ensure this class is implemented correctly!
export abstract class Step {
    // TODO: unsure how to do kwargs in ts
    // abstract run(options: any): Record<string, any> | undefined;
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
            if (this.interrupter.onInterrupt) {
                this.interrupter.onInterrupt();
            }
    
            this.isDetected = true;
        }
    }

    async run(): Promise<void> {
        return new Promise(async (resolve, reject) => {
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

                resolve();
            } catch(e) {
                reject(e);
            } finally {
                await this.recorder.stop();
            }
        });
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
    private isDetected: boolean;

    // TODO: options pattern?
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

        this.isDetected = false;
        this.interrupter = {};
    }

    private inferenceCallback(inference: RhinoInference) {
        if (!this.isDetected && detection.index == 0) {
            if (this.interrupter.onInterrupt) {
                this.interrupter.onInterrupt();
            }
    
            this.isDetected = true;
        }
    }

    run(): Record<string, any> {
        try {
            await this.recorder.start();

            while (!) {
                this.rhino.process(this._recorder.read(this._rhino.frame_length)))
            }
    
            inference = this.rhino.get_inference();

            return {
                'is_understood': inference.is_understood,
                'intent': inference.intent,
                'slots': inference.slots,
            };
        } finally {
            await this.recorder.stop();
        }
    }

    release() {
        this.rhino.release()
    }

    toString(): string {
        return `${this.constructor.name} {
    ${this.rhino.constructor.name}[V${this.rhino.version}]
}`;
    }
}