/*
    Copyright 2026 Picovoice Inc.

    You may not use this file except in compliance with the license. A copy of the license is
    located in the "LICENSE" file accompanying this source.

    Unless required by applicable law or agreed to in writing, software distributed under the
    License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
    express or implied. See the License for the specific language governing permissions and
    limitations under the License.
*/

package ai.picovoice.speakerawarevoiceassistant;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ai.picovoice.android.voiceprocessor.VoiceProcessor;
import ai.picovoice.android.voiceprocessor.VoiceProcessorArgumentException;
import ai.picovoice.android.voiceprocessor.VoiceProcessorErrorListener;
import ai.picovoice.android.voiceprocessor.VoiceProcessorFrameListener;
import ai.picovoice.eagle.Eagle;
import ai.picovoice.eagle.EagleException;
import ai.picovoice.eagle.EagleProfile;
import ai.picovoice.eagle.EagleProfiler;
import ai.picovoice.orca.Orca;
import ai.picovoice.orca.OrcaAudio;
import ai.picovoice.orca.OrcaException;
import ai.picovoice.orca.OrcaSynthesizeParams;
import ai.picovoice.porcupine.Porcupine;
import ai.picovoice.porcupine.PorcupineException;
import ai.picovoice.rhino.Rhino;
import ai.picovoice.rhino.RhinoException;
import ai.picovoice.rhino.RhinoInference;

enum UserRole {
    ADMIN,
    USER
}

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "PICOVOICE";
    private static final String ACCESS_KEY = "${YOUR_ACCESS_KEY_HERE}";
    private static final String WAKE_WORD_FILE = "keyword.ppn";
    private static final String CONTEXT_FILE = "context.rhn";
    private static final String ORCA_MODEL_FILE = "orca_params_en_female.pv";
    private static final float EAGLE_THRESHOLD = 0.75f;

    private static final int EAGLE_MIN_ENROLLMENT_CHUNKS = 6;
    private static final int MAX_SPEAKERS = 10;

    private TextView titleText, statusText, tooltipText;
    private View layoutGreeting, layoutEnroll;
    private ViewGroup speakerChipContainer;
    private TextView greetingPrefixText, greetingSpeakerNameText, btnAddSpeaker;
    private ProgressBar enrollProgressBar;
    private Button btnStartEnroll, btnStartTest, btnClearAll, btnCancel;
    private View buttonContainer;
    private VolumeMeterView volumeMeterView;

    private final List<EagleProfile> speakerProfiles = new ArrayList<>();
    private final List<String> speakerNames = new ArrayList<>();

    private final List<UserRole> speakerRoles = new ArrayList<>();
    private String pendingSpeakerName = "";
    private UserRole pendingUserRole = UserRole.USER;

    private AppState currentState = AppState.IDLE;
    private TestingState testingState = TestingState.PPN;

    private final ExecutorService ttsPlaybackExecutor = Executors.newSingleThreadExecutor();

    private enum AppState {
        IDLE,
        ENROLLING,
        TESTING
    }

    private enum TestingState {
        PPN,
        RHN,
        ORCA
    }

    private Porcupine porcupine;

    private Rhino rhino;

    private Orca orca;

    private EagleProfiler eagleProfiler;
    private Eagle eagle;

    private VoiceProcessor voiceProcessor;

    private short[] enrollSlidingBuffer;
    private int enrollMaxSamples;
    private int enrollValidSamples = 0;
    private short[] slidingBuffer;

    private ArrayList<Short> inferenceBuffer;

    private static final String[] SPEAKER_PALETTE = {
            "#377dff", // Blue
            "#10B981", // Emerald Green
            "#8B5CF6", // Violet
            "#EC4899", // Pink
            "#F59E0B", // Amber
            "#06B6D4", // Cyan
            "#EF4444", // Red
            "#84CC16", // Lime
            "#6366F1", // Indigo
            "#F43F5E"  // Rose
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        titleText = findViewById(R.id.titleText);
        statusText = findViewById(R.id.statusText);
        tooltipText = findViewById(R.id.tooltipText);

        layoutEnroll = findViewById(R.id.layoutEnroll);
        speakerChipContainer = findViewById(R.id.speakerChipContainer);
        btnAddSpeaker = findViewById(R.id.btnAddSpeaker);

        layoutGreeting = findViewById(R.id.layoutGreeting);
        greetingPrefixText = findViewById(R.id.greetingPrefixText);
        greetingSpeakerNameText = findViewById(R.id.greetingSpeakerNameText);

        enrollProgressBar = findViewById(R.id.enrollProgressBar);

        btnStartEnroll = findViewById(R.id.btnStartEnroll);
        btnStartTest = findViewById(R.id.btnStartTest);
        btnClearAll = findViewById(R.id.btnClearAll);
        btnCancel = findViewById(R.id.btnCancel);

        buttonContainer = findViewById(R.id.buttonContainer);
        volumeMeterView = findViewById(R.id.volumeMeterView);

        btnStartEnroll.setOnClickListener(v -> promptForSpeakerName());
        btnAddSpeaker.setOnClickListener(v -> promptForSpeakerName());
        btnStartTest.setOnClickListener(v -> checkPermissionsAndStart(AppState.TESTING));
        btnClearAll.setOnClickListener(v -> clearAllProfiles());
        btnCancel.setOnClickListener(v -> cancelActiveSession());

        setupVoiceProcessor();
    }

    private void promptForSpeakerName() {
        if (speakerProfiles.size() >= MAX_SPEAKERS) {
            Toast.makeText(
                    this,
                    "Maximum of " + MAX_SPEAKERS + " speakers reached.",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Speaker Name");

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setHint("Speaker " + (speakerProfiles.size() + 1));

        android.widget.LinearLayout container = new android.widget.LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        android.widget.LinearLayout.LayoutParams params = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        );
        int margin = (int) (12 * getResources().getDisplayMetrics().density);
        params.setMargins(margin, 0, margin, 0);
        input.setLayoutParams(params);
        container.addView(input);

        android.widget.LinearLayout adminContainer = new android.widget.LinearLayout(this);
        android.widget.LinearLayout.LayoutParams adminParams = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        );
        adminParams.setMargins(margin, 0, margin, 0);
        adminContainer.setLayoutParams(adminParams);

        final TextView adminText = new TextView(this);
        adminText.setText("Admin Permissions:");
        adminContainer.addView(adminText);

        final Switch adminSwitch = new Switch(this);
        adminContainer.addView(adminSwitch);

        container.addView(adminContainer);
        builder.setView(container);

        builder.setPositiveButton("Enroll", (dialog, which) -> {
            pendingSpeakerName = input.getText().toString().trim();
            if (pendingSpeakerName.isEmpty()) {
                pendingSpeakerName = "Speaker " + (speakerProfiles.size() + 1);
            }
            pendingUserRole = adminSwitch.isChecked() ? UserRole.ADMIN : UserRole.USER;
            checkPermissionsAndStart(AppState.ENROLLING);
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());

        builder.show();
    }

    private void clearAllProfiles() {
        for (EagleProfile profile : speakerProfiles) {
            profile.delete();
        }
        speakerProfiles.clear();
        speakerNames.clear();
        speakerRoles.clear();
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
                } else if (currentState == AppState.TESTING && testingState == TestingState.PPN) {
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
                            Log.e(TAG, String.format("scores %f", scores[0]));

                            for (int i = 0; i < scores.length; i++) {
                                if (scores[i] >= bestScore) {
                                    bestScore = scores[i];
                                    bestIndex = i;
                                }
                            }
                        }

                        final String speakerName = (bestScore >= EAGLE_THRESHOLD) ? speakerNames.get(bestIndex) : null;
                        final int speakerColour = Color.parseColor(SPEAKER_PALETTE[bestIndex]);

                        runOnUiThread(() -> showGreeting(speakerName, speakerColour));

                        testingState = TestingState.RHN;
                        inferenceBuffer = new ArrayList<>();
                    }
                } else if (currentState == AppState.TESTING && testingState == TestingState.RHN) {
                    for (short pcm : frame) {
                        inferenceBuffer.add(pcm);
                    }

                    Boolean is_complete = rhino.process(frame);
                    if (is_complete) {
                        RhinoInference inference = rhino.getInference();
                        if (inference.getIsUnderstood()) {
                            short[] inferenceArray = new short[Math.max(inferenceBuffer.size(), eagle.getMinProcessSamples())];
                            for (int i = 0; i < inferenceBuffer.size(); i++) {
                                inferenceArray[i] = inferenceBuffer.get(i);
                            }
                            for (int i = inferenceBuffer.size(); i < inferenceArray.length; i++) {
                                inferenceArray[i] = 0;
                            }

                            EagleProfile[] profilesArray = speakerProfiles.toArray(new EagleProfile[0]);
                            float[] scores = eagle.process(inferenceArray, profilesArray);

                            float bestScore = 0f;
                            int bestIndex = -1;

                            if (scores != null) {
                                Log.e(TAG, String.format("scores %f", scores[0]));

                                for (int i = 0; i < scores.length; i++) {
                                    if (scores[i] >= bestScore) {
                                        bestScore = scores[i];
                                        bestIndex = i;
                                    }
                                }
                            } else {
                                Log.e(TAG, "SCORES NULL");
                            }

                            Log.e(TAG, String.format("%f %d", bestScore, bestIndex));

                            if (Objects.equals(inference.getIntent(), "adminOnly")) {
                                if (bestScore >= EAGLE_THRESHOLD) {
                                    final String speakerName = speakerNames.get(bestIndex);
                                    final UserRole speakerRole = speakerRoles.get(bestIndex);

                                    if (speakerRole == UserRole.ADMIN) {
                                        synthesizeAndPlayback("Admin command approved.");
                                    } else {
                                        synthesizeAndPlayback("Permission denied. This command requires an admin.");
                                    }
                                } else {
                                    synthesizeAndPlayback("Sorry, I could not verify your voice.");
                                }
                            } else if (Objects.equals(inference.getIntent(), "speakerPersonalized")) {
                                final String speakerName = speakerNames.get(bestIndex);
                                synthesizeAndPlayback(String.format("Hi %s. I will personalize this command for you.", speakerName));
                            } else if (Objects.equals(inference.getIntent(), "generic")) {
                                synthesizeAndPlayback("Okay. This command is available to everyone.");
                            }
                        } else {
                            synthesizeAndPlayback("Sorry, I did not understand that command.");
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

    private void synthesizeAndPlayback(String text) {
        testingState = TestingState.ORCA;
        volumeMeterView.setVisibility(View.INVISIBLE);

        ttsPlaybackExecutor.submit(() -> {
            OrcaAudio audio;
            try {
                audio = orca.synthesize(
                        text,
                        new OrcaSynthesizeParams.Builder().build());
            } catch (OrcaException e) {
                Log.e(TAG, "Audio synthesize error: ", e);
                runOnUiThread(this::hideGreeting);
                return;
            }

            AudioTrack ttsOutput;
            try {
                AudioAttributes audioAttributes = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build();

                AudioFormat audioFormat = new AudioFormat.Builder()
                        .setSampleRate(orca.getSampleRate())
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build();

                ttsOutput = new AudioTrack(
                        audioAttributes,
                        audioFormat,
                        AudioTrack.getMinBufferSize(
                                orca.getSampleRate(),
                                AudioFormat.CHANNEL_OUT_MONO,
                                AudioFormat.ENCODING_PCM_16BIT),
                        AudioTrack.MODE_STREAM,
                        0);

                ttsOutput.play();
            } catch (Exception e) {
                Log.e(TAG, "Audio synthesize error: ", e);
                runOnUiThread(this::hideGreeting);
                return;
            }

            short[] pcm = audio.getPcm();
            if (pcm != null && pcm.length > 0) {
                ttsOutput.write(pcm, 0, pcm.length);
            }

            if (ttsOutput.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                ttsOutput.flush();
                ttsOutput.stop();
            }
            ttsOutput.release();

            runOnUiThread(this::hideGreeting);
        });
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
                    .setMinEnrollmentChunks(EAGLE_MIN_ENROLLMENT_CHUNKS)
                    .build(this);

            enrollMaxSamples = eagleProfiler.getFrameLength() * 96;
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
        try {
            stopAudio();
            currentState = AppState.TESTING;
            testingState = TestingState.PPN;

            porcupine = new Porcupine.Builder()
                    .setAccessKey(ACCESS_KEY)
                    .setKeywordPath(WAKE_WORD_FILE)
                    .setSensitivity(0.5f)
                    .build(this);

            rhino = new Rhino.Builder()
                    .setAccessKey(ACCESS_KEY)
                    .setContextPath(CONTEXT_FILE)
                    .build(this);

            orca = new Orca.Builder()
                    .setAccessKey(ACCESS_KEY)
                    .setModelPath(ORCA_MODEL_FILE)
                    .build(this);

            eagle = new Eagle.Builder()
                    .setAccessKey(ACCESS_KEY)
                    .setVoiceThreshold(0.1f)
                    .build(this);

            slidingBuffer = new short[eagle.getMinProcessSamples()];

            updateUIForState();
            voiceProcessor.start(porcupine.getFrameLength(), porcupine.getSampleRate());

        } catch (PorcupineException | RhinoException | OrcaException | EagleException e) {
            statusText.setText("Engine init error: " + e.getMessage());
            Log.e(TAG, "Init error", e);
        } catch (VoiceProcessorArgumentException e) {
            statusText.setText("Audio error: " + e.getMessage());
            Log.e(TAG, "Audio error", e);
        }
    }

    private void finishEnrollment() {
        try {
            EagleProfile newProfile = eagleProfiler.export();
            speakerProfiles.add(newProfile);
            speakerNames.add(pendingSpeakerName);
            speakerRoles.add(pendingUserRole);
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

    @SuppressLint("SetTextI18n")
    private void showGreeting(String speakerName, int speakerColour) {
        volumeMeterView.setVisibility(View.VISIBLE);
        statusText.setVisibility(View.GONE);
        tooltipText.setVisibility(View.VISIBLE);
        tooltipText.setText(
                "Try one of the following commands:" +
                        "\n\"do something that requires admin permission\"" +
                        "\n\"do something just for me\"" +
                        "\n\"do something anyone can do\"");
        btnCancel.setVisibility(View.GONE);
        layoutGreeting.setVisibility(View.VISIBLE);

        greetingPrefixText.setVisibility(View.VISIBLE);
        GradientDrawable bg = (GradientDrawable) getResources()
                .getDrawable(R.drawable.speaker_pill_bg, getTheme())
                .mutate();
        bg.setColor(speakerColour);
        greetingSpeakerNameText.setBackground(bg);
        greetingSpeakerNameText.setText(" " + speakerName + " ");
        greetingSpeakerNameText.setTextColor(getResources().getColor(R.color.white, getTheme()));
        greetingSpeakerNameText.setVisibility(speakerName != null ? View.VISIBLE : View.GONE);

        layoutGreeting.setScaleX(0.8f);
        layoutGreeting.setScaleY(0.8f);
        layoutGreeting.setAlpha(0f);
        layoutGreeting.animate()
                .scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(300)
                .setInterpolator(new OvershootInterpolator())
                .start();
    }

    private void hideGreeting() {
        testingState = TestingState.PPN;

        layoutGreeting.setVisibility(View.GONE);
        statusText.setVisibility(View.VISIBLE);
        tooltipText.setVisibility(View.GONE);
        volumeMeterView.setVisibility(View.VISIBLE);
        btnCancel.setVisibility(View.VISIBLE);
    }

    private void renderSpeakerChips() {
        speakerChipContainer.removeAllViews();

        float density = getResources().getDisplayMetrics().density;
        int paddingH = (int) (16 * density);
        int paddingV = (int) (8 * density);

        for (int i = 0; i < speakerNames.size(); i++) {
            final String chipText = String.format("%s [%s]", speakerNames.get(i), speakerRoles.get(i));

            TextView chip = new TextView(this);
            chip.setText(chipText);
            chip.setTextColor(getResources().getColor(R.color.white, getTheme()));

            GradientDrawable bg = (GradientDrawable) getResources()
                    .getDrawable(R.drawable.speaker_pill_bg, getTheme())
                    .mutate();
            bg.setColor(Color.parseColor(SPEAKER_PALETTE[i]));
            chip.setBackground(bg);

            chip.setTextSize(16f);
            chip.setTypeface(null, android.graphics.Typeface.BOLD);
            chip.setPadding(paddingH, paddingV, paddingH, paddingV);

            speakerChipContainer.addView(chip);
        }

        if (speakerProfiles.size() < MAX_SPEAKERS) {
            speakerChipContainer.addView(btnAddSpeaker);
        }
    }

    @SuppressLint("SetTextI18n")
    private void updateUIForState() {
        boolean hasProfiles = !speakerProfiles.isEmpty();

        if (currentState == AppState.ENROLLING) {
            titleText.setVisibility(View.GONE);
            statusText.setVisibility(View.VISIBLE);
            statusText.setText("Hello " + pendingSpeakerName + "\n\nSpeak until the circle is full");
            tooltipText.setVisibility(View.VISIBLE);
            tooltipText.setText(
                    "Try the following phrases:" +
                    "\n\"The quick brown fox jumps over the lazy dog.\"" +
                    "\n\"I am recording my voice for speaker enrollment.\"" +
                    "\n\"This is my normal speaking voice in a quiet room.\"" +
                    "\n\"The assistant should recognize me when I speak.\"" +
                    "\n\"Voice recognition works best with clean and natural speech.\"");
            enrollProgressBar.setVisibility(View.VISIBLE);
            enrollProgressBar.setProgress(0);

            layoutEnroll.setVisibility(View.GONE);
            buttonContainer.setVisibility(View.GONE);
            layoutGreeting.setVisibility(View.GONE);
            volumeMeterView.setVisibility(View.VISIBLE);

            btnCancel.setText("Cancel");
            btnCancel.setVisibility(View.VISIBLE);

        } else if (currentState == AppState.TESTING) {
            titleText.setVisibility(View.GONE);
            statusText.setVisibility(View.VISIBLE);
            statusText.setText("Listening for wake word...");
            tooltipText.setVisibility(View.GONE);
            enrollProgressBar.setVisibility(View.GONE);

            layoutEnroll.setVisibility(View.GONE);
            buttonContainer.setVisibility(View.GONE);
            layoutGreeting.setVisibility(View.GONE);
            volumeMeterView.setVisibility(View.VISIBLE);

            btnCancel.setText("Stop Testing");
            btnCancel.setVisibility(View.VISIBLE);

        } else {
            titleText.setVisibility(View.VISIBLE);
            statusText.setVisibility(hasProfiles ? View.GONE : View.VISIBLE);
            statusText.setText("Ready to Enroll");

            tooltipText.setVisibility(View.GONE);
            enrollProgressBar.setVisibility(View.GONE);
            volumeMeterView.setVisibility(View.GONE);
            btnCancel.setVisibility(View.GONE);
            layoutGreeting.setVisibility(View.GONE);

            buttonContainer.setVisibility(View.VISIBLE);

            if (hasProfiles) {
                layoutEnroll.setVisibility(View.VISIBLE);
                renderSpeakerChips();

                btnStartEnroll.setVisibility(View.GONE);
                btnStartTest.setVisibility(View.VISIBLE);
                btnClearAll.setVisibility(View.VISIBLE);
            } else {
                layoutEnroll.setVisibility(View.GONE);

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
        if (rhino != null) {
            rhino.delete();
            rhino = null;
        }
        if (orca != null) {
            orca.delete();
            orca = null;
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
        ttsPlaybackExecutor.shutdownNow();
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
