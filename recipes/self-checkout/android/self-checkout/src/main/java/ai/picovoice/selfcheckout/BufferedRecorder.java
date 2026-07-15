/*
    Copyright 2026 Picovoice Inc.

    You may not use this file except in compliance with the license. A copy of the license is
    located in the "LICENSE" file accompanying this source.

    Unless required by applicable law or agreed to in writing, software distributed under the
    License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
    express or implied. See the License for the specific language governing permissions and
    limitations under the License.
*/

package ai.picovoice.selfcheckout;

import android.util.Log;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import ai.picovoice.android.voiceprocessor.VoiceProcessor;
import ai.picovoice.android.voiceprocessor.VoiceProcessorException;

public class BufferedRecorder {
    private final LinkedBlockingQueue<short[]> rawFrames = new LinkedBlockingQueue<>();

    private final int frameLength;
    private final int sampleRate;

    private final Object lock = new Object();
    private volatile boolean isSessionActive = false;

    public BufferedRecorder(
            WorkflowListener listener,
            int frameLength,
            int sampleRate) {
        this.frameLength = frameLength;
        this.sampleRate = sampleRate;

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
            isSessionActive = true;
            VoiceProcessor.getInstance().start(frameLength, sampleRate);
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

        while (resultIndex < numSamples && isSessionActive) {
            short[] frame = rawFrames.poll(50, TimeUnit.MILLISECONDS);
            if (frame == null) {
                continue;
            }

            synchronized (lock) {
                if (!isSessionActive) {
                    return null;
                }

                int toCopy = Math.min(frame.length, numSamples - resultIndex);
                System.arraycopy(frame, 0, result, resultIndex, toCopy);
                resultIndex += toCopy;
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
    }
}
