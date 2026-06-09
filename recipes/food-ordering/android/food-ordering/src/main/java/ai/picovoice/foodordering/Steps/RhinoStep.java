/*
    Copyright 2026 Picovoice Inc.

    You may not use this file except in compliance with the license. A copy of the license is
    located in the "LICENSE" file accompanying this source.

    Unless required by applicable law or agreed to in writing, software distributed under the
    License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
    express or implied. See the License for the specific language governing permissions and
    limitations under the License.
*/

package ai.picovoice.foodordering.Steps;

import android.content.Context;
import ai.picovoice.foodordering.AINoiseSuppressedRecorder;
import ai.picovoice.foodordering.Steps.Step;
import ai.picovoice.foodordering.WorkflowListener;
import ai.picovoice.rhino.Rhino;
import ai.picovoice.rhino.RhinoInference;

public class RhinoStep extends Step {
    private final Rhino rhino;

    public RhinoStep(
            Context context,
            AINoiseSuppressedRecorder r,
            WorkflowListener listener,
            String accessKey,
            String modelPath) throws Exception {
        super(r, listener);
        rhino = new Rhino.Builder()
                .setAccessKey(accessKey)
                .setContextPath(modelPath)
                .setEndpointDurationSec(0.5f)
                .setRequireEndpoint(true)
                .build(context);
    }

    private static float rms(short[] frame) {
        float total = 0.0f;
        for (short sample : frame) {
            total += (sample / 32768.0f) * (sample / 32768.0f);
        }

        return (float) Math.sqrt(total / frame.length);
    }

    /// Returns null on timeout
    public RhinoInference run(
            String listeningPrompt,
            boolean checkForSilence,
            long[] silenceStart,
            long silenceTimeout,
            float volumeThreshold) throws Exception {
        recorder.start();
        listener.setListeningUI(true, listeningPrompt);

        boolean isFinalized = false;

        if (checkForSilence) {
            long runningSilenceStart = silenceStart[0];

            while (!isFinalized && listener.isRunning()) {
                short[] frame = recorder.read(rhino.getFrameLength());

                if (frame != null && frame.length == rhino.getFrameLength()) {
                    float volume = RhinoStep.rms(frame);
                    if (volume > volumeThreshold) {
                        runningSilenceStart = System.currentTimeMillis();
                    } else if ((System.currentTimeMillis() - runningSilenceStart) > silenceTimeout) {
                        listener.setListeningUI(false, listeningPrompt);
                        recorder.stop();
                        return null;
                    }

                    isFinalized = rhino.process(frame);
                }
            }

            silenceStart[0] = runningSilenceStart;
        } else {
            while (!isFinalized && listener.isRunning()) {
                short[] frame = recorder.read(rhino.getFrameLength());
                if (frame != null && frame.length == rhino.getFrameLength()) {
                    isFinalized = rhino.process(frame);
                }
            }
        }

        listener.setListeningUI(false, listeningPrompt);
        recorder.stop();
        return listener.isRunning() ? rhino.getInference() : null;
    }

    public void delete() {
        if (rhino != null) {
            rhino.delete();
        }
    }
}