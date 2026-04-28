package ai.picovoice.personalizedwakeword;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import ai.picovoice.android.voiceprocessor.VoiceProcessor;
import ai.picovoice.android.voiceprocessor.VoiceProcessorErrorListener;
import ai.picovoice.android.voiceprocessor.VoiceProcessorFrameListener;
import ai.picovoice.eagle.Eagle;
import ai.picovoice.eagle.EagleProfile;
import ai.picovoice.eagle.EagleProfiler;
import ai.picovoice.porcupine.Porcupine;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "PersonalWakeWord";
    private static final String ACCESS_KEY = "${YOUR_ACCESS_KEY_HERE}";
    private static final float EAGLE_THRESHOLD = 0.75f;

    private TextView tvTitle, tvStatus, tvResult;
    private ProgressBar progressBar;
    private Button btnStartEnroll, btnStartTest, btnCancel;
    private View layoutButtons;
    private ImageView ivResultIcon;
    private VolumeMeterView volumeMeterView;

    private boolean hasEnrolled = false;
    private AppState currentState = AppState.IDLE;
    private enum AppState { IDLE, ENROLLING, TESTING }

    private Porcupine porcupine;
    private EagleProfiler eagleProfiler;
    private Eagle eagle;
    private EagleProfile speakerProfile;

    private VoiceProcessor voiceProcessor;
    private VoiceProcessorFrameListener frameListener;
    private VoiceProcessorErrorListener errorListener;

    private short[] enrollSlidingBuffer;
    private int enrollMaxSamples;
    private int enrollValidSamples = 0;
    private short[] slidingBuffer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvTitle = findViewById(R.id.tvTitle);
        tvStatus = findViewById(R.id.tvStatus);
        tvResult = findViewById(R.id.tvResult);
        progressBar = findViewById(R.id.progressBar);
        ivResultIcon = findViewById(R.id.ivResultIcon);

        btnStartEnroll = findViewById(R.id.btnStartEnroll);
        btnStartTest = findViewById(R.id.btnStartTest);
        btnCancel = findViewById(R.id.btnCancel);

        layoutButtons = findViewById(R.id.layoutButtons);
        volumeMeterView = findViewById(R.id.volumeMeterView);

        btnStartEnroll.setOnClickListener(v -> checkPermissionsAndStart(AppState.ENROLLING));
        btnStartTest.setOnClickListener(v -> checkPermissionsAndStart(AppState.TESTING));
        btnCancel.setOnClickListener(v -> cancelActiveSession());

        setupVoiceProcessor();
    }

    private void setupVoiceProcessor() {
        voiceProcessor = VoiceProcessor.getInstance();

        frameListener = frame -> {
            try {
                volumeMeterView.processFrame(frame);

                if (currentState == AppState.ENROLLING) {
                    System.arraycopy(enrollSlidingBuffer, frame.length, enrollSlidingBuffer, 0, enrollMaxSamples - frame.length);
                    System.arraycopy(frame, 0, enrollSlidingBuffer, enrollMaxSamples - frame.length, frame.length);
                    enrollValidSamples = Math.min(enrollMaxSamples, enrollValidSamples + frame.length);

                    int keywordIndex = porcupine.process(frame);
                    if (keywordIndex == 0) {
                        int eagleFrameLength = eagleProfiler.getFrameLength();
                        float progress = 0f;

                        int startIndex = enrollMaxSamples - enrollValidSamples;
                        int numChunks = enrollValidSamples / eagleFrameLength;

                        for (int i = 0; i < numChunks; i++) {
                            short[] chunk = new short[eagleFrameLength];
                            System.arraycopy(enrollSlidingBuffer, startIndex + (i * eagleFrameLength), chunk, 0, eagleFrameLength);
                            progress = eagleProfiler.enroll(chunk);
                        }
                        enrollValidSamples = 0;

                        float finalProgress = progress;
                        runOnUiThread(() -> {
                            progressBar.setProgress((int) finalProgress);
                            if (finalProgress >= 100f) finishEnrollment();
                        });
                    }
                } else if (currentState == AppState.TESTING) {
                    int minProcessSamples = eagle.getMinProcessSamples();
                    System.arraycopy(slidingBuffer, frame.length, slidingBuffer, 0, minProcessSamples - frame.length);
                    System.arraycopy(frame, 0, slidingBuffer, minProcessSamples - frame.length, frame.length);

                    int keywordIndex = porcupine.process(frame);
                    if (keywordIndex == 0) {
                        EagleProfile[] profiles = new EagleProfile[]{ speakerProfile };
                        float[] scores = eagle.process(slidingBuffer, profiles);

                        if (scores != null && scores.length > 0) {
                            boolean isVerified = scores[0] >= EAGLE_THRESHOLD;
                            runOnUiThread(() -> showTestResult(isVerified, scores[0]));
                        } else {
                            runOnUiThread(() -> showTestResult(false, 0.0f));
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Audio processing error: ", e);
            }
        };

        errorListener = error -> Log.e(TAG, "VoiceProcessor Error: ", error);
        voiceProcessor.addFrameListener(frameListener);
        voiceProcessor.addErrorListener(errorListener);
    }

    private void checkPermissionsAndStart(AppState targetState) {
        if (!voiceProcessor.hasRecordAudioPermission(this)) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        } else {
            if (targetState == AppState.ENROLLING) {
                startEnrollment();
            } else {
                startTesting();
            }
        }
    }

    private void startEnrollment() {
        try {
            stopAudio();
            currentState = AppState.ENROLLING;

            porcupine = new Porcupine.Builder()
                    .setAccessKey(ACCESS_KEY)
                    .setKeyword(Porcupine.BuiltInKeyword.PORCUPINE)
                    .build(this);

            eagleProfiler = new EagleProfiler.Builder()
                    .setAccessKey(ACCESS_KEY)
                    .setMinEnrollmentChunks(3)
                    .build(this);

            enrollMaxSamples = eagleProfiler.getFrameLength() * 64;
            enrollSlidingBuffer = new short[enrollMaxSamples];
            enrollValidSamples = 0;

            updateUIForState();
            voiceProcessor.start(porcupine.getFrameLength(), porcupine.getSampleRate());

        } catch (Exception e) {
            tvStatus.setText("Init Error: " + e.getMessage());
            Log.e(TAG, "Init Error", e);
        }
    }

    private void startTesting() {
        try {
            stopAudio();
            currentState = AppState.TESTING;

            porcupine = new Porcupine.Builder()
                    .setAccessKey(ACCESS_KEY)
                    .setKeyword(Porcupine.BuiltInKeyword.PORCUPINE)
                    .build(this);

            eagle = new Eagle.Builder()
                    .setAccessKey(ACCESS_KEY)
                    .build(this);

            slidingBuffer = new short[eagle.getMinProcessSamples()];

            updateUIForState();
            voiceProcessor.start(porcupine.getFrameLength(), porcupine.getSampleRate());

        } catch (Exception e) {
            tvStatus.setText("Init Error: " + e.getMessage());
            Log.e(TAG, "Init Error", e);
        }
    }

    private void finishEnrollment() {
        try {
            if (speakerProfile != null) {
                speakerProfile.delete();
            }
            speakerProfile = eagleProfiler.export();
            hasEnrolled = true;
            cancelActiveSession();

        } catch (Exception e) {
            Log.e(TAG, "Failed to export profile", e);
        }
    }

    private void cancelActiveSession() {
        stopAudio();
        currentState = AppState.IDLE;
        updateUIForState();
    }

    private void showTestResult(boolean isVerified, float score) {
        tvResult.setVisibility(View.VISIBLE);
        ivResultIcon.setVisibility(View.VISIBLE);

        if (isVerified) {
            ivResultIcon.setImageResource(R.drawable.ic_check_circle);
            tvResult.setText(String.format("User Verified\nConfidence: %.2f", score));
            tvResult.setTextColor(getResources().getColor(R.color.success_green, getTheme()));
        } else {
            ivResultIcon.setImageResource(R.drawable.ic_cancel);
            tvResult.setText(String.format("User Rejected\nConfidence: %.2f", score));
            tvResult.setTextColor(getResources().getColor(R.color.error_red, getTheme()));
        }

        ivResultIcon.setScaleX(0.3f);
        ivResultIcon.setScaleY(0.3f);
        ivResultIcon.setAlpha(0f);
        ivResultIcon.animate()
                .scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(300)
                .setInterpolator(new OvershootInterpolator())
                .start();

        ivResultIcon.postDelayed(() -> {
            tvResult.setVisibility(View.GONE);
            ivResultIcon.setVisibility(View.GONE);
        }, 2500);
    }

    private void updateUIForState() {
        tvTitle.setVisibility(hasEnrolled ? View.GONE : View.VISIBLE);

        if (currentState == AppState.ENROLLING) {
            tvStatus.setText("Say the wake word 'Porcupine'...");
            progressBar.setVisibility(View.VISIBLE);
            progressBar.setProgress(0);

            layoutButtons.setVisibility(View.GONE);
            volumeMeterView.setVisibility(View.VISIBLE);

            btnCancel.setText("Cancel");
            btnCancel.setVisibility(View.VISIBLE);

        } else if (currentState == AppState.TESTING) {
            tvStatus.setText("Listening for wake word...");
            progressBar.setVisibility(View.GONE);

            layoutButtons.setVisibility(View.GONE);
            volumeMeterView.setVisibility(View.VISIBLE);

            btnCancel.setText("Stop Testing");
            btnCancel.setVisibility(View.VISIBLE);

        } else {
            tvStatus.setText(hasEnrolled ? "Ready to Test or Re-Enroll" : "Ready to Enroll");
            progressBar.setVisibility(View.GONE);
            volumeMeterView.setVisibility(View.GONE);
            btnCancel.setVisibility(View.GONE);

            layoutButtons.setVisibility(View.VISIBLE);
            btnStartEnroll.setText(hasEnrolled ? "Re-Enroll" : "Start Enrollment");
            btnStartEnroll.setBackgroundTintList(getResources().getColorStateList(hasEnrolled ? R.color.gray_light : R.color.brand_primary, getTheme()));
            btnStartEnroll.setTextColor(hasEnrolled ? 0xFF333333 : getResources().getColor(R.color.white, getTheme()));

            btnStartTest.setVisibility(hasEnrolled ? View.VISIBLE : View.GONE);
        }
    }

    private void stopAudio() {
        try {
            if (voiceProcessor != null && voiceProcessor.getIsRecording()) {
                voiceProcessor.stop();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to stop VoiceProcessor", e);
        }

        if (porcupine != null) { porcupine.delete(); porcupine = null; }
        if (eagleProfiler != null) { eagleProfiler.delete(); eagleProfiler = null; }
        if (eagle != null) { eagle.delete(); eagle = null; }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopAudio();
        if (speakerProfile != null) {
            speakerProfile.delete();
        }
        if (voiceProcessor != null) {
            voiceProcessor.clearFrameListeners();
            voiceProcessor.clearErrorListeners();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            checkPermissionsAndStart(currentState == AppState.IDLE ? AppState.ENROLLING : currentState);
        }
    }
}