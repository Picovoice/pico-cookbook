import { KoalaWorker } from '@picovoice/koala-web';
import { WebVoiceProcessor, PvEngine } from '@picovoice/web-voice-processor';

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

    constructor(accessKey: string) {
        this.koala = KoalaWorker.create(accessKey);
        this.rawBuffer = new Int16Array();
        this.processedBuffer = new Int16Array();

        this.eventQueue = [];

        const onmessage = (e: any) => {
            switch (e.data.command) {
                case 'process':
                    this.rawBuffer = concat(this.rawBuffer, e.data.inputFrame);

                    while (this.rawBuffer.length > this.koala.frameLength) {
                        const frame = this.koala.process(this.rawBuffer.slice(0, this.koala.frameLength));
                        this.rawBuffer = this.rawBuffer.slice(this.koala.frameLength);

                        this.processedBuffer = concat(this.processedBuffer, frame);
                    }

                    while (this.eventQueue.length >= 0) {
                        let request = this.eventQueue[0];
                        if (this.processedBuffer.length >= request.numSamples) {
                            this.eventQueue[0].resolve(this.processedBuffer.slice(0, request.numSamples));
                            this.processedBuffer = this.processedBuffer.slice(request.numSamples);
                            this.eventQueue = this.eventQueue.slice(1);
                        } else {
                            break;
                        }
                    }
                    break;
            }
        };
        this.aiNoiseSuppressedEngine = { onmessage };
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