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
import ai.picovoice.foodordering.PvSpeaker;
import ai.picovoice.orca.Orca;
import ai.picovoice.orca.OrcaAudio;
import ai.picovoice.orca.OrcaSynthesizeParams;
import ai.picovoice.foodordering.WorkflowListener;


public class OrcaStep extends Step {
    private final Orca orca;
    private final PvSpeaker speaker;
    private final OrcaSynthesizeParams synthesizeParams;

    public OrcaStep(
            Context context,
            AINoiseSuppressedRecorder r,
            WorkflowListener listener,
            String accessKey,
            String modelPath) throws Exception {
        super(r, listener);
        orca = new Orca.Builder()
                .setAccessKey(accessKey)
                .setModelPath(modelPath)
                .build(context);
        synthesizeParams = new OrcaSynthesizeParams.Builder().build();
        speaker = new PvSpeaker(orca.getSampleRate());
    }

    public void run(String prompt) throws Exception {
        OrcaAudio res = orca.synthesize(prompt, synthesizeParams);
        speaker.play(res.getPcm(), () -> listener.isRunning());

        while (listener.isRunning() && speaker.isPlaying()) {
            Thread.sleep(5);
        }

        speaker.stop();
    }

    public void delete() {
        if (speaker != null) {
            speaker.delete();
        }
        if (orca != null) {
            orca.delete();
        }
    }
}