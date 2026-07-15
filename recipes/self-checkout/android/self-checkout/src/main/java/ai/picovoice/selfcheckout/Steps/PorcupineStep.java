/*
    Copyright 2026 Picovoice Inc.

    You may not use this file except in compliance with the license. A copy of the license is
    located in the "LICENSE" file accompanying this source.

    Unless required by applicable law or agreed to in writing, software distributed under the
    License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
    express or implied. See the License for the specific language governing permissions and
    limitations under the License.
*/

package ai.picovoice.selfcheckout.Steps;

import ai.picovoice.selfcheckout.BufferedRecorder;
import ai.picovoice.selfcheckout.WorkflowListener;
import ai.picovoice.porcupine.Porcupine;

public class PorcupineStep extends Step {
    private final Porcupine porcupine;

    public PorcupineStep(
            BufferedRecorder r,
            WorkflowListener listener,
            Porcupine porcupine) {
        super(r, listener);
        this.porcupine = porcupine;
    }

    public void run(String listeningPrompt) throws Exception {
        recorder.start();
        listener.setListeningUI(true, listeningPrompt);
        boolean isDetected = false;
        while (!isDetected && listener.isRunning()) {
            short[] frame = recorder.read(porcupine.getFrameLength());
            if (frame != null && frame.length == porcupine.getFrameLength()) {
                isDetected = porcupine.process(frame) == 0;
            }
        }
        listener.setListeningUI(false, listeningPrompt);
        recorder.stop();
    }

    public void delete() {
        if (porcupine != null) {
            porcupine.delete();
        }
    }
}
