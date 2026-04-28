package ai.picovoice.personalizedwakeword;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;

public class VolumeMeterView extends LinearLayout {
    private View bar1;
    private View bar2;
    private View bar3;

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
        long sum = 0;
        for (short sample : frame) {
            sum += sample * sample;
        }
        double rms = Math.sqrt(sum / (double) frame.length);

        float normalizedVolume = (float) (rms / 10000.0);
        return Math.min(1.0f, normalizedVolume);
    }

    private void animateVolumeBars(float volume) {
        float scale1 = 1f + (volume * 4.5f);
        float scale2 = 1f + (volume * 9f);
        float scale3 = 1f + (volume * 6f);

        bar1.animate().scaleY(scale1).setDuration(50).start();
        bar2.animate().scaleY(scale2).setDuration(50).start();
        bar3.animate().scaleY(scale3).setDuration(50).start();
    }
}