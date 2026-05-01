/*
    Copyright 2026 Picovoice Inc.

    You may not use this file except in compliance with the license. A copy of the license is
    located in the "LICENSE" file accompanying this source.

    Unless required by applicable law or agreed to in writing, software distributed under the
    License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
    express or implied. See the License for the specific language governing permissions and
    limitations under the License.
*/

package ai.picovoice.documentqa;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;

public class VolumeMeterView extends LinearLayout {
    private View bar1;
    private View bar2;
    private View bar3;

    final double MIN_DB = -40.0;
    final double MAX_DB = 0.0;

    public VolumeMeterView(Context context) {
        super(context);
        init(context);
    }

    public VolumeMeterView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public VolumeMeterView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        setOrientation(HORIZONTAL);
        setGravity(android.view.Gravity.CENTER);

        LayoutInflater.from(context).inflate(R.layout.view_volume_meter, this, true);

        bar1 = findViewById(R.id.bar1);
        bar2 = findViewById(R.id.bar2);
        bar3 = findViewById(R.id.bar3);
    }

    public void processFrame(short[] frame) {
        float volume = calculateVolume(frame);

        post(() -> animateVolumeBars(volume));
    }

    private float calculateVolume(short[] frame) {
        double sum = 0;
        for (short sample : frame) {
            sum += Math.pow(sample, 2);
        }
        double rms = (sum / frame.length) / Math.pow(Short.MAX_VALUE, 2);
        double db = 10 * Math.log10(Math.max(rms, 1e-9));
        double normalized = (db - MIN_DB) / (MAX_DB - MIN_DB);
        return (float) Math.max(0.0, Math.min(1.0, normalized));
    }

    private void animateVolumeBars(float volume) {
        float scale1 = 1f + (volume * 4f);
        float scale2 = 1f + (volume * 9f);
        float scale3 = 1f + (volume * 6f);

        bar1.animate().scaleY(scale1).setDuration(50).start();
        bar2.animate().scaleY(scale2).setDuration(50).start();
        bar3.animate().scaleY(scale3).setDuration(50).start();
    }
}