/*
    Copyright 2026 Picovoice Inc.

    You may not use this file except in compliance with the license. A copy of the license is
    located in the "LICENSE" file accompanying this source.

    Unless required by applicable law or agreed to in writing, software distributed under the
    License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
    express or implied. See the License for the specific language governing permissions and
    limitations under the License.
*/

package ai.picovoice.speakerawarewakeword;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.util.ArrayList;
import java.util.List;

import ai.picovoice.android.voiceprocessor.VoiceProcessor;
import ai.picovoice.android.voiceprocessor.VoiceProcessorArgumentException;
import ai.picovoice.android.voiceprocessor.VoiceProcessorErrorListener;
import ai.picovoice.android.voiceprocessor.VoiceProcessorFrameListener;
import ai.picovoice.eagle.Eagle;
import ai.picovoice.eagle.EagleException;
import ai.picovoice.eagle.EagleProfile;
import ai.picovoice.eagle.EagleProfiler;
import ai.picovoice.porcupine.Porcupine;
import ai.picovoice.porcupine.PorcupineException;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "PICOVOICE";
    private static final String ACCESS_KEY = "${YOUR_ACCESS_KEY_HERE}";
    private static final String WAKE_WORD_FILE = "${YOUR_WAKE_WORD_HERE}.ppn";
    private static final float EAGLE_THRESHOLD = 0.75f;

    // UI Elements
    private TextView titleText, statusText;
    private View layoutGreeting, layoutDashboard;
    private LinearLayout chipContainer;
    private TextView tvGreetingPrefix, tvSpeakerName, tvGreetingSuffix, btnAddSpeaker;
    private ProgressBar enrollProgressBar;
    private Button btnStartEnroll, btnStartTest, btnClearAll, btnCancel;
    private View buttonContainer;
    private VolumeMeterView volumeMeterView;

    // Multi-Speaker Data
    private final List<EagleProfile> speakerProfiles = new ArrayList<>();
    private final List<String> speakerNames = new ArrayList<>();
    private String pendingSpeakerName = "";

    private AppState currentState = AppState.IDLE;

    private enum AppState {
        IDLE,
        ENROLLING,
        TESTING
    }

    private Porcupine porcupine;
    private EagleProfiler eagleProfiler;
    private Eagle eagle;

    private VoiceProcessor voiceProcessor;

    private short[] enrollSlidingBuffer;
    private int enrollMaxSamples;
    private int enrollValidSamples = 0;
    private short[] slidingBuffer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        titleText = findViewById(R.id.titleText);
        statusText = findViewById(R.id.statusText);

        layoutDashboard = findViewById(R.id.layoutDashboard);
        chipContainer = findViewById(R.id.chipContainer);
        btnAddSpeaker = findViewById(R.id.btnAddSpeaker);

        layoutGreeting = findViewById(R.id.layoutGreeting);
        tvGreetingPrefix = findViewById(R.id.tvGreetingPrefix);
        tvSpeakerName = findViewById(R.id.tvSpeakerName);
        tvGreetingSuffix = findViewById(R.id.tvGreetingSuffix);

        enrollProgressBar = findViewById(R.id.enrollProgressBar);

        btnStartEnroll = findViewById(R.id.btnStartEnroll);
        btnStartTest = findViewById(R.id.btnStartTest);
        btnClearAll = findViewById(R.id.btnClearAll);
        btnCancel = findViewById(R.id.btnCancel);

        buttonContainer = findViewById(R.id.buttonContainer);
        volumeMeterView = findViewById(R.id.volumeMeterView);

        btnStartEnroll.setOnClickListener(v -> promptForSpeakerName());
        btnAddSpeaker.setOnClickListener(v -> promptForSpeakerName()); // New stylish Add chip
        btnStartTest.setOnClickListener(v -> checkPermissionsAndStart(AppState.TESTING));
        btnClearAll.setOnClickListener(v -> clearAllProfiles());
        btnCancel.setOnClickListener(v -> cancelActiveSession());

        setupVoiceProcessor();
    }

    private void promptForSpeakerName() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Speaker Name");

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("Speaker " + (speakerProfiles.size() + 1));
        builder.setView(input);

        builder.setPositiveButton("Enroll", (dialog, which) -> {
            pendingSpeakerName = input.getText().toString().trim();
            if (pendingSpeakerName.isEmpty()) {
                pendingSpeakerName = "Speaker " + (speakerProfiles.size() + 1);
            }
            checkPermissionsAndStart(AppState.ENROLLING);
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());

        builder.show();
    }

    private void clearAllProfiles() {
        for (EagleProfile profile : speakerProfiles) {
            profile.delete(); // Free memory
        }
        speakerProfiles.clear();
        speakerNames.clear();
        updateUIForState();
    }

    private void setupVoiceProcessor() {
        voiceProcessor = VoiceProcessor.getInstance();

        VoiceProcessorFrameListener frameListener = frame -> {
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
                        int numEagleFrames = enrollValidSamples / eagleFrameLength;

                        for (int i = 0; i < numEagleFrames; i++) {
                            short[] eagleFrame = new short[eagleFrameLength];
                            System.arraycopy(
                                    enrollSlidingBuffer,
                                    startIndex + (i * eagleFrameLength),
                                    eagleFrame,
                                    0,
                                    eagleFrameLength);
                            progress = eagleProfiler.enroll(eagleFrame);
                        }
                        enrollValidSamples = 0;

                        float finalProgress = progress;
                        runOnUiThread(() -> {
                            enrollProgressBar.setProgress((int) finalProgress);
                            if (finalProgress >= 100f) {
                                finishEnrollment();
                            }
                        });
                    }
                } else if (currentState == AppState.TESTING) {
                    int minProcessSamples = eagle.getMinProcessSamples();
                    System.arraycopy(slidingBuffer, frame.length, slidingBuffer, 0, minProcessSamples - frame.length);
                    System.arraycopy(frame, 0, slidingBuffer, minProcessSamples - frame.length, frame.length);

                    int keywordIndex = porcupine.process(frame);
                    if (keywordIndex == 0) {
                        EagleProfile[] profilesArray = speakerProfiles.toArray(new EagleProfile[0]);
                        float[] scores = eagle.process(slidingBuffer, profilesArray);

                        float bestScore = 0f;
                        int bestIndex = -1;

                        if (scores != null) {
                            for (int i = 0; i < scores.length; i++) {
                                if (scores[i] > bestScore) {
                                    bestScore = scores[i];
                                    bestIndex = i;
                                }
                            }
                        }

                        final String recognizedName = (bestScore >= EAGLE_THRESHOLD && bestIndex != -1) ? speakerNames.get(bestIndex) : null;
                        if (recognizedName != null) {
                            runOnUiThread(() -> showGreeting(recognizedName));
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Audio processing error: ", e);
            }
        };

        VoiceProcessorErrorListener errorListener = error -> Log.e(TAG, "Audio Error: ", error);
        voiceProcessor.addFrameListener(frameListener);
        voiceProcessor.addErrorListener(errorListener);
    }

    private void checkPermissionsAndStart(AppState targetState) {
        if (!voiceProcessor.hasRecordAudioPermission(this)) {
            currentState = targetState;
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    1);
        } else {
            if (targetState == AppState.ENROLLING) {
                startEnrollment();
            } else {
                startTesting();
            }
        }
    }

    private void startEnrollment() {
        if (checkDefaultArgs()) {
            currentState = AppState.IDLE;
            return;
        }

        try {
            stopAudio();
            currentState = AppState.ENROLLING;

            porcupine = new Porcupine.Builder()
                    .setAccessKey(ACCESS_KEY)
                    .setKeywordPath(WAKE_WORD_FILE)
                    .setSensitivity(0.5f)
                    .build(this);

            eagleProfiler = new EagleProfiler.Builder()
                    .setAccessKey(ACCESS_KEY)
                    .setMinEnrollmentChunks(4)
                    .setVoiceThreshold(0.1f)
                    .build(this);

            enrollMaxSamples = eagleProfiler.getFrameLength() * 64;
            enrollSlidingBuffer = new short[enrollMaxSamples];
            enrollValidSamples = 0;

            updateUIForState();
            voiceProcessor.start(porcupine.getFrameLength(), porcupine.getSampleRate());

        } catch (PorcupineException | EagleException e) {
            statusText.setText("Engine init error: " + e.getMessage());
            Log.e(TAG, "Init error", e);
        } catch (VoiceProcessorArgumentException e) {
            statusText.setText("Audio error: " + e.getMessage());
            Log.e(TAG, "Audio error", e);
        }
    }

    private void startTesting() {
        if (checkDefaultArgs()) {
            currentState = AppState.IDLE;
            return;
        }

        try {
            stopAudio();
            currentState = AppState.TESTING;

            porcupine = new Porcupine.Builder()
                    .setAccessKey(ACCESS_KEY)
                    .setKeywordPath(WAKE_WORD_FILE)
                    .setSensitivity(0.5f)
                    .build(this);

            eagle = new Eagle.Builder()
                    .setAccessKey(ACCESS_KEY)
                    .setVoiceThreshold(0.0f)
                    .build(this);

            slidingBuffer = new short[eagle.getMinProcessSamples()];

            updateUIForState();
            voiceProcessor.start(porcupine.getFrameLength(), porcupine.getSampleRate());

        } catch (PorcupineException | EagleException e) {
            statusText.setText("Engine init error: " + e.getMessage());
            Log.e(TAG, "Init error", e);
        } catch (VoiceProcessorArgumentException e) {
            statusText.setText("Audio error: " + e.getMessage());
            Log.e(TAG, "Audio error", e);
        }
    }

    private boolean checkDefaultArgs() {
        if (ACCESS_KEY.equals("${YOUR_ACCESS_KEY_HERE}")) {
            statusText.setText("Please set your Picovoice AccessKey in MainActivity.java");
            return true;
        }

        if (WAKE_WORD_FILE.equals("${YOUR_WAKE_WORD_HERE}.ppn")) {
            statusText.setText("Please set your Porcupine Wake Word file in MainActivity.java");
            return true;
        }
        return false;
    }

    private void finishEnrollment() {
        try {
            EagleProfile newProfile = eagleProfiler.export();
            speakerProfiles.add(newProfile);
            speakerNames.add(pendingSpeakerName);
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

    private void showGreeting(String speakerName) {
        volumeMeterView.setVisibility(View.GONE);
        statusText.setVisibility(View.GONE);
        btnCancel.setVisibility(View.GONE);

        layoutGreeting.setVisibility(View.VISIBLE);

        tvGreetingPrefix.setVisibility(View.VISIBLE);
        tvGreetingSuffix.setVisibility(View.VISIBLE);
        tvSpeakerName.setText(speakerName);
        tvSpeakerName.setBackground(getResources().getDrawable(R.drawable.speaker_pill_bg, getTheme()));
        tvSpeakerName.setTextColor(getResources().getColor(R.color.white, getTheme()));

        layoutGreeting.setScaleX(0.8f);
        layoutGreeting.setScaleY(0.8f);
        layoutGreeting.setAlpha(0f);
        layoutGreeting.animate()
                .scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(300)
                .setInterpolator(new OvershootInterpolator())
                .start();

        layoutGreeting.postDelayed(() -> {
            layoutGreeting.setVisibility(View.GONE);
            statusText.setVisibility(View.VISIBLE);
            volumeMeterView.setVisibility(View.VISIBLE);
            btnCancel.setVisibility(View.VISIBLE);
        }, 2500);
    }

    /**
     * Dynamically builds the horizontal list of speaker pills.
     */
    private void renderSpeakerChips() {
        chipContainer.removeAllViews();

        float density = getResources().getDisplayMetrics().density;
        int paddingH = (int) (16 * density);
        int paddingV = (int) (8 * density);
        int marginEnd = (int) (8 * density);

        for (String name : speakerNames) {
            TextView chip = new TextView(this);
            chip.setText(name);
            chip.setTextColor(getResources().getColor(R.color.white, getTheme()));
            chip.setBackground(getResources().getDrawable(R.drawable.speaker_pill_bg, getTheme()));
            chip.setTextSize(16f);
            chip.setTypeface(null, android.graphics.Typeface.BOLD);
            chip.setPadding(paddingH, paddingV, paddingH, paddingV);

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            params.setMarginEnd(marginEnd);
            chip.setLayoutParams(params);

            chipContainer.addView(chip);
        }
    }

    private void updateUIForState() {
        boolean hasProfiles = !speakerProfiles.isEmpty();
        if (currentState == AppState.ENROLLING) {
            titleText.setVisibility(View.GONE);
            statusText.setVisibility(View.VISIBLE);
            statusText.setText("Hi " + pendingSpeakerName + "!\nSay the wake word until\nthe circle is full");
            enrollProgressBar.setVisibility(View.VISIBLE);
            enrollProgressBar.setProgress(0);

            layoutDashboard.setVisibility(View.GONE);
            buttonContainer.setVisibility(View.GONE);
            layoutGreeting.setVisibility(View.GONE);
            volumeMeterView.setVisibility(View.VISIBLE);

            btnCancel.setText("Cancel");
            btnCancel.setVisibility(View.VISIBLE);

        } else if (currentState == AppState.TESTING) {
            titleText.setVisibility(View.GONE);
            statusText.setVisibility(View.VISIBLE);
            statusText.setText("Listening for wake word...");
            enrollProgressBar.setVisibility(View.GONE);

            layoutDashboard.setVisibility(View.GONE);
            buttonContainer.setVisibility(View.GONE);
            layoutGreeting.setVisibility(View.GONE);
            volumeMeterView.setVisibility(View.VISIBLE);

            btnCancel.setText("Stop Testing");
            btnCancel.setVisibility(View.VISIBLE);

        } else {
            titleText.setVisibility(hasProfiles ? View.GONE : View.VISIBLE);
            statusText.setVisibility(hasProfiles ? View.GONE : View.VISIBLE);
            statusText.setText("Ready to Enroll");

            enrollProgressBar.setVisibility(View.GONE);
            volumeMeterView.setVisibility(View.GONE);
            btnCancel.setVisibility(View.GONE);
            layoutGreeting.setVisibility(View.GONE);

            buttonContainer.setVisibility(View.VISIBLE);

            if (hasProfiles) {
                layoutDashboard.setVisibility(View.VISIBLE);
                renderSpeakerChips();

                btnStartEnroll.setVisibility(View.GONE);
                btnStartTest.setVisibility(View.VISIBLE);
                btnClearAll.setVisibility(View.VISIBLE);
            } else {
                layoutDashboard.setVisibility(View.GONE);

                btnStartEnroll.setVisibility(View.VISIBLE);
                btnStartTest.setVisibility(View.GONE);
                btnClearAll.setVisibility(View.GONE);
            }
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

        if (porcupine != null) {
            porcupine.delete();
            porcupine = null;
        }
        if (eagleProfiler != null) {
            eagleProfiler.delete();
            eagleProfiler = null;
        }
        if (eagle != null) {
            eagle.delete();
            eagle = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopAudio();
        clearAllProfiles();
        if (voiceProcessor != null) {
            voiceProcessor.clearFrameListeners();
            voiceProcessor.clearErrorListeners();
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults.length == 0 || grantResults[0] == PackageManager.PERMISSION_DENIED) {
            currentState = AppState.IDLE;
            statusText.setText("Microphone permission is required for this demo");
        } else {
            if (currentState == AppState.ENROLLING) {
                startEnrollment();
            } else if (currentState == AppState.TESTING) {
                startTesting();
            }
        }
    }
}