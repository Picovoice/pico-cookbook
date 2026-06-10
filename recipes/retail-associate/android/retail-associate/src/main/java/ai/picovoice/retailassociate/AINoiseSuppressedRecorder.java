/*
    Copyright 2026 Picovoice Inc.

    You may not use this file except in compliance with the license. A copy of the license is
    located in the "LICENSE" file accompanying this source.

    Unless required by applicable law or agreed to in writing, software distributed under the
    License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
    express or implied. See the License for the specific language governing permissions and
    limitations under the License.
*/

package ai.picovoice.retailassociate;

import android.content.Context;
import android.util.Log;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import ai.picovoice.android.voiceprocessor.VoiceProcessor;
import ai.picovoice.android.voiceprocessor.VoiceProcessorException;
import ai.picovoice.koala.Koala;

public class AINoiseSuppressedRecorder {
    private final Koala koala;
    private final LinkedBlockingQueue<short[]> rawFrames = new LinkedBlockingQueue<>();
    private short[] leftoverBuffer = new short[4096];
    private int leftoverCount = 0;

    private final int frameLength;

    private final Object lock = new Object();
    private volatile boolean isSessionActive = false;

    public AINoiseSuppressedRecorder(
            Context context,
            WorkflowListener listener,
            String accessKey,
            String modelPath) throws Exception {
        koala = new Koala.Builder()
                .setAccessKey(accessKey)
                .setModelPath(modelPath)
                .build(context);
        frameLength = koala.getFrameLength();

        VoiceProcessor.getInstance().addFrameListener(frame -> {
            if (isSessionActive) {
                rawFrames.offer(frame);
                listener.onVolumeFrame(frame);
            }
        });
    }

    public void start() throws Exception {
        synchronized (lock) {
            if (isSessionActive) {
                Log.w("PICOVOICE", "Recorder is already running. Ignoring start request.");
                return;
            }
            rawFrames.clear();
            leftoverCount = 0;
            koala.reset();
            isSessionActive = true;
            VoiceProcessor.getInstance().start(frameLength, koala.getSampleRate());
        }
    }

    public void stop() throws VoiceProcessorException {
        synchronized (lock) {
            isSessionActive = false;
            VoiceProcessor.getInstance().stop();
            rawFrames.clear();
        }
    }

    public short[] read(int numSamples) throws Exception {
        short[] result = new short[numSamples];
        int resultIndex = 0;

        synchronized (lock) {
            if (!isSessionActive) {
                return null;
            }

            int numFromBuffer = Math.min(numSamples, leftoverCount);
            if (numFromBuffer > 0) {
                System.arraycopy(leftoverBuffer, 0, result, 0, numFromBuffer);
                resultIndex += numFromBuffer;
                leftoverCount -= numFromBuffer;

                if (leftoverCount > 0) {
                    System.arraycopy(leftoverBuffer, numFromBuffer, leftoverBuffer, 0, leftoverCount);
                }
            }
        }

        while (resultIndex < numSamples && isSessionActive) {
            short[] raw = rawFrames.poll(50, TimeUnit.MILLISECONDS);
            if (raw == null) {
                continue;
            }

            synchronized (lock) {
                if (!isSessionActive) {
                    return null;
                }

                short[] enhanced = koala.process(raw);
                int remaining = numSamples - resultIndex;
                int toCopy = Math.min(enhanced.length, remaining);

                System.arraycopy(enhanced, 0, result, resultIndex, toCopy);
                resultIndex += toCopy;

                if (enhanced.length > remaining) {
                    int excess = enhanced.length - remaining;

                    if (leftoverCount + excess > leftoverBuffer.length) {
                        short[] newBuffer = new short[(leftoverCount + excess) * 2];
                        System.arraycopy(leftoverBuffer, 0, newBuffer, 0, leftoverCount);
                        leftoverBuffer = newBuffer;
                    }

                    System.arraycopy(enhanced, remaining, leftoverBuffer, leftoverCount, excess);
                    leftoverCount += excess;
                }
            }
        }

        if (!isSessionActive) {
            return null;
        }

        return result;
    }

    public void delete() {
        try {
            stop();
        } catch (VoiceProcessorException e) {
            Log.e("PICOVOICE", "Failed to stop VoiceProcessor during cleanup", e);
        }

        synchronized (lock) {
            if (koala != null) {
                koala.delete();
            }
        }
    }
}