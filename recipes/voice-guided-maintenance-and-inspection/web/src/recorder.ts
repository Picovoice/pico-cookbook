import { WebVoiceProcessor } from '@picovoice/web-voice-processor';

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

export class BufferedRecorder {
    private processedBuffer: Int16Array;

    private eventQueue: {
        numSamples: number;
        resolve: (samples: Int16Array) => void;
    }[];
    private engine: PvEngine;

    private constructor(engine: PvEngine) {
        this.processedBuffer = new Int16Array();
        this.eventQueue = [];
        this.engine = engine;
    }

    static create() {
        const onmessage = (e: any) => {
            switch (e.data.command) {
                case 'process':
                    recorder.processedBuffer = concat(recorder.processedBuffer, e.data.inputFrame);

                    while (recorder.eventQueue.length > 0) {
                        let request = recorder.eventQueue[0];
                        if (recorder.processedBuffer.length >= request.numSamples) {
                            recorder.eventQueue[0].resolve(recorder.processedBuffer.slice(0, request.numSamples));
                            recorder.processedBuffer = recorder.processedBuffer.slice(request.numSamples);

                            recorder.eventQueue = recorder.eventQueue.slice(1);
                        } else {
                            break;
                        }
                    }
                    return;
            }
        };

        const recorder = new BufferedRecorder({ onmessage });
        return recorder;
    }

    async start() {
        await WebVoiceProcessor.subscribe(this.engine);
    }

    async stop() {
        await WebVoiceProcessor.unsubscribe(this.engine);
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
    }
}
