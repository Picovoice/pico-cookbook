package ai.picovoice.callscreen;

import android.os.Handler;
import android.os.Looper;
import android.text.style.ForegroundColorSpan;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.util.Log;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;

public class SpannableTextAnimation {

    private static final String[] DOTS = { ".  ", ".. ", "...", " ..", "  .", "   " };

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final ExecutorService animationExecutor = Executors.newSingleThreadExecutor();

    private boolean exitThread = false;
    private Future<?> animationThread;

    private SpannableStringBuilder stringBuilder;
    private TextView targetView;
    private ScrollView scrollView;
    private int refreshMs;

    private String appendString = "";
    private long endMs = -1;

    public SpannableTextAnimation(SpannableStringBuilder stringBuilder, TextView targetView, ScrollView scrollView, int refreshMs) {
        this.stringBuilder = stringBuilder;
        this.targetView = targetView;
        this.scrollView = scrollView;
        this.refreshMs = refreshMs;
    }

    public SpannableTextAnimation(SpannableStringBuilder stringBuilder, TextView targetView, ScrollView scrollView) {
        this(stringBuilder, targetView, scrollView, 100);
    }

    public SpannableTextAnimation(SpannableStringBuilder stringBuilder, TextView targetView) {
        this(stringBuilder, targetView, null, 100);
    }

    public void start() {
        animationThread = animationExecutor.submit(() -> {
            int[] i = new int[1];
            while (!exitThread) {
                SpannableStringBuilder stringBuilderCopy = new SpannableStringBuilder(stringBuilder);

                ForegroundColorSpan spanOverLastChar = null;
                ForegroundColorSpan[] spans = stringBuilderCopy.getSpans(
                        stringBuilderCopy.length() - 1,
                        stringBuilderCopy.length(),
                        ForegroundColorSpan.class);
                for (ForegroundColorSpan span : spans) {
                    spanOverLastChar = span;
                    break;
                }

                String dots = "";
                if (System.currentTimeMillis() < endMs) {
                    dots += " " + this.appendString;
                }
                dots += " " + DOTS[i[0] % DOTS.length];

                stringBuilderCopy.append(dots);

                if (spanOverLastChar != null) {
                    stringBuilderCopy.setSpan(
                            new ForegroundColorSpan(spanOverLastChar.getForegroundColor()),
                            stringBuilderCopy.length() - dots.length(),
                            stringBuilderCopy.length(),
                            Spannable.SPAN_INCLUSIVE_EXCLUSIVE);
                }

                mainHandler.post(() -> {
                    targetView.setText(stringBuilderCopy);

                    if (scrollView != null) {
                        scrollView.fullScroll(ScrollView.FOCUS_DOWN);
                    }
                });

                try {
                    Thread.sleep(refreshMs);
                } catch (InterruptedException e) { }

                i[0]++;
            }
        });
    }

    public void appendTimed(String appendString, int delayMs) {
        this.appendString = appendString;
        this.endMs = System.currentTimeMillis() + (long)delayMs;
    }

    public void clearTimed() {
        this.appendString = "";
        this.endMs = -1;
    }

    /// @brief returns a string if an error occurred.
    public void end() {
        exitThread = true;

        try {
            animationThread.get();
        } catch (ExecutionException | InterruptedException e) {
            Log.e("PICOVOICE", "Animation thread failed with: ", e);
        }

        animationExecutor.shutdownNow();

        mainHandler.post(() -> {
            targetView.setText(stringBuilder);

            if (scrollView != null) {
                scrollView.fullScroll(ScrollView.FOCUS_DOWN);
            }
        });
    }
}
