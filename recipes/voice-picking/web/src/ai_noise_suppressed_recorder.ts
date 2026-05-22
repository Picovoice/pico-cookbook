import { KoalaWorker } from '@picovoice/koala-web';
import { WebVoiceProcessor } from '@picovoice/web-voice-processor';

import { callbacks } from './config';

type PvEngine = {
    onmessage?: ((e: MessageEvent) => any) | null;
}

const concat = (frame1: Int16Array, frame2: Int16Array): Int16Array => {
  const tempPcmBuffer = new Int16Array(frame1.length + frame2.length);
  tempPcmBuffer.set(frame1);
  tempPcmBuffer.set(frame2, frame1.length);
  return tempPcmBuffer;
}

export type Interrupter = {
    onInterrupt?: () => void;
}

export class AINoiseSuppressedRecorder {
    private koala: KoalaWorker;
    private rawBuffer: Int16Array;
    private processedBuffer: Int16Array;

    private eventQueue: {
        numSamples: number;
        resolve: (samples: Int16Array) => void;
    }[];
    private aiNoiseSuppressedEngine: PvEngine;

    private constructor(koala: KoalaWorker, engine: PvEngine) {
        this.koala = koala;
        this.rawBuffer = new Int16Array();
        this.processedBuffer = new Int16Array();

        this.eventQueue = [];
        this.aiNoiseSuppressedEngine = engine;
    }

    static async create(accessKey: string) {
        callbacks.setStatusText("Loading Koala");

        const processCallback = (enhancedPcm: Int16Array) => {
            recorder.processedBuffer = concat(recorder.processedBuffer, enhancedPcm);

            while (recorder.eventQueue.length >= 0) {
                let request = recorder.eventQueue[0];
                if (recorder.processedBuffer.length >= request.numSamples) {
                    recorder.eventQueue[0].resolve(recorder.processedBuffer.slice(0, request.numSamples));
                    recorder.processedBuffer = recorder.processedBuffer.slice(request.numSamples);

                    recorder.eventQueue = recorder.eventQueue.slice(1);
                } else {
                    break;
                }
            }
        }

        const koala = await KoalaWorker.create(
            accessKey,
            processCallback,
            {
                publicPath: "models/koala_params.pv",
                forceWrite: true,
            },
            {}
        );

        const onmessage = (e: any) => {
            switch (e.data.command) {
                case 'process':
                    recorder.rawBuffer = concat(recorder.rawBuffer, e.data.inputFrame);

                    while (recorder.rawBuffer.length > recorder.koala.frameLength) {
                        recorder.koala.process(recorder.rawBuffer.slice(0, recorder.koala.frameLength));
                        recorder.rawBuffer = recorder.rawBuffer.slice(recorder.koala.frameLength);
                    }
                    return;
            }
        };

        const recorder = new AINoiseSuppressedRecorder(koala, { onmessage });
        return recorder;
    }

    async start() {
        await WebVoiceProcessor.subscribe(this.aiNoiseSuppressedEngine);
    }

    async stop() {
        await WebVoiceProcessor.unsubscribe(this.aiNoiseSuppressedEngine);
        this.koala.reset();
        this.rawBuffer = new Int16Array();
        this.processedBuffer = new Int16Array();
        this.eventQueue = [];
    }

    read(numSamples: number, interrupter?: Interrupter): Promise<Int16Array> {
        return new Promise((resolve) => {
            if (interrupter) {
                interrupter.onInterrupt = () => {
                    resolve(new Int16Array());
                };
            }

            this.eventQueue.push({ numSamples, resolve });
        });
    }

    async release() {
        await this.stop();
        this.koala.release();
    }
}