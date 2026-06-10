/*
    Copyright 2026 Picovoice Inc.

    You may not use this file except in compliance with the license. A copy of the license is
    located in the "LICENSE" file accompanying this source.

    Unless required by applicable law or agreed to in writing, software distributed under the
    License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
    express or implied. See the License for the specific language governing permissions and
    limitations under the License.
*/

package ai.picovoice.retailassociate.Steps;

import android.content.Context;
import ai.picovoice.retailassociate.AINoiseSuppressedRecorder;
import ai.picovoice.retailassociate.PvSpeaker;
import ai.picovoice.orca.Orca;
import ai.picovoice.orca.OrcaAudio;
import ai.picovoice.orca.OrcaSynthesizeParams;
import ai.picovoice.retailassociate.WorkflowListener;

public class OrcaStep extends Step {
    private final Orca orca;
    private final PvSpeaker speaker;

    public float volume;
    public float speed;
    public String lastPrompt;

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
        speaker = new PvSpeaker(orca.getSampleRate());

        volume = 1.0f;
        speed = 1.0f;
        lastPrompt = "There is nothing to repeat.";
    }

    public void run(String prompt) throws Exception {
        OrcaSynthesizeParams params = new OrcaSynthesizeParams.Builder()
                .setSpeechRate(Math.min(Math.max(speed, 0.7f), 1.3f))
                .build();

        volume = Math.max(Math.min(volume, 100.0f), 0.0f);

        OrcaAudio res = orca.synthesize(prompt, params);

        short[] pcm = res.getPcm();
        for (int i = 0; i < pcm.length; i++) {
            int s16Max = (1 << 15) - 1;
            int s16Min = -(1 << 15);
            pcm[i] = (short) Math.max(Math.min((int) (pcm[i] * volume), s16Max), s16Min);
        }

        speaker.play(pcm, () -> listener.isRunning());

        while (listener.isRunning() && speaker.isPlaying()) {
            Thread.sleep(5);
        }

        speaker.stop();
        lastPrompt = prompt;
    }

    public void repeatLast() throws Exception {
        listener.onStatusChanged(lastPrompt);
        this.run(lastPrompt);
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