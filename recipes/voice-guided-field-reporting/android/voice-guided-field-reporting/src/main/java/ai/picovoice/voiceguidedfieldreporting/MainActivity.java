/*
    Copyright 2026 Picovoice Inc.

    You may not use this file except in compliance with the license. A copy of the license is
    located in the "LICENSE" file accompanying this source.

    Unless required by applicable law or agreed to in writing, software distributed under the
    License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
    express or implied. See the License for the specific language governing permissions and
    limitations under the License.
*/

package ai.picovoice.voiceguidedfieldreporting;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;

import ai.picovoice.android.voiceprocessor.VoiceProcessor;
import ai.picovoice.android.voiceprocessor.VoiceProcessorException;
import ai.picovoice.cheetah.Cheetah;
import ai.picovoice.cheetah.CheetahTranscript;
import ai.picovoice.koala.Koala;
import ai.picovoice.orca.Orca;
import ai.picovoice.orca.OrcaAudio;
import ai.picovoice.orca.OrcaSynthesizeParams;
import ai.picovoice.porcupine.Porcupine;
import ai.picovoice.rhino.Rhino;
import ai.picovoice.rhino.RhinoInference;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "PICOVOICE";

    private static final String ACCESS_KEY = "${YOUR_ACCESS_KEY_HERE}";
    private static final String KEYWORD_MODEL = "keyword.ppn";
    private static final String CONTEXT_MODEL = "context.rhn";

    private static final String STT_MODEL = "cheetah_params.pv";
    private static final String TTS_MODEL = "orca_params_en_female.pv";
    private static final String NS_MODEL = "koala_params.pv";

    private final Map<String, String> HOUR_MAP = new HashMap<String, String>() {{
        put("one", "1");
        put("two", "2");
        put("three", "3");
        put("four", "4");
        put("five", "5");
        put("six", "6");
        put("seven", "7");
        put("eight", "8");
        put("nine", "9");
        put("ten", "10");
        put("eleven", "11");
        put("twelve", "12");
    }};

    private LinearLayout startScreen, workflowScreen, reportContainer, errorView;
    private TextView startStatusText, workflowStatusText, errorText;
    private Button btnStart, btnCancel;
    private VolumeMeterView volumeMeterView;
    private ProgressBar startSpinner, processingSpinner;
    private View animationContainer;
    private ImageView successIcon;

    private Workflow workflow;
    private volatile boolean isRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        startScreen = findViewById(R.id.startScreen);
        workflowScreen = findViewById(R.id.workflowScreen);
        reportContainer = findViewById(R.id.reportContainer);
        errorView = findViewById(R.id.errorView);

        startStatusText = findViewById(R.id.startStatusText);
        workflowStatusText = findViewById(R.id.workflowStatusText);
        errorText = findViewById(R.id.errorText);

        volumeMeterView = findViewById(R.id.volumeMeterView);
        btnStart = findViewById(R.id.btnStart);
        btnCancel = findViewById(R.id.btnCancel);
        startSpinner = findViewById(R.id.startSpinner);
        processingSpinner = findViewById(R.id.processingSpinner);
        animationContainer = findViewById(R.id.animationContainer);
        successIcon = findViewById(R.id.successIcon);

        btnStart.setOnClickListener(v -> checkPermissionsAndStart());
        btnCancel.setOnClickListener(v -> stopDemo());

        resetUIState();
        preloadDemo();
    }

    private void checkPermissionsAndStart() {
        if (!VoiceProcessor.getInstance().hasRecordAudioPermission(this)) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    1);
        } else {
            startDemo();
        }
    }

    private void scrollToBottom() {
        final View scrollView = (View) findViewById(R.id.reportContainer).getParent();
        if (scrollView instanceof android.widget.ScrollView) {
            scrollView.post(() -> ((android.widget.ScrollView) scrollView).fullScroll(View.FOCUS_DOWN));
        }
    }

    private void resetUIState() {
        runOnUiThread(() -> {
            startScreen.setVisibility(View.VISIBLE);
            workflowScreen.setVisibility(View.GONE);
            errorView.setVisibility(View.GONE);

            btnStart.setVisibility(View.VISIBLE);
            startSpinner.setVisibility(View.GONE);
            successIcon.setVisibility(View.INVISIBLE);
            startStatusText.setText("Ready to Start");
            btnCancel.setVisibility(View.VISIBLE);
            btnCancel.setText("Cancel Report");

            reportContainer.removeAllViews();
            animationContainer.setVisibility(View.INVISIBLE);
        });
    }

    private void showError(String message) {
        runOnUiThread(() -> {
            errorView.setVisibility(View.VISIBLE);
            errorText.setText("Error: " + message);

            if (startScreen.getVisibility() == View.VISIBLE) {
                btnStart.setVisibility(View.VISIBLE);
                startSpinner.setVisibility(View.GONE);
                startStatusText.setText("Initialization Failed");
            }
        });
    }

    private void setListeningUI(boolean isListening) {
        runOnUiThread(() -> {
            if (!isRunning) {
                return;
            }
            animationContainer.setVisibility(View.VISIBLE);
            if (isListening) {
                volumeMeterView.setVisibility(View.VISIBLE);
                processingSpinner.setVisibility(View.INVISIBLE);
            } else {
                volumeMeterView.setVisibility(View.INVISIBLE);
                processingSpinner.setVisibility(View.VISIBLE);
            }
        });
    }

    private void preloadDemo() {
        errorView.setVisibility(View.GONE);
        btnStart.setVisibility(View.INVISIBLE);
        startSpinner.setVisibility(View.VISIBLE);
        isRunning = true;

        new Thread(() -> {
            try {
                workflow = new Workflow(status -> runOnUiThread(() -> startStatusText.setText(status)));
                runOnUiThread(() -> {
                    btnStart.setVisibility(View.VISIBLE);
                    startSpinner.setVisibility(View.GONE);
                    startStatusText.setText("Ready to Start");
                });
            } catch (Exception e) {
                Log.e(TAG, "Init error", e);
                showError(e.getMessage());
                isRunning = false;
            }
        }).start();
    }

    private void startDemo() {
        errorView.setVisibility(View.GONE);
        btnStart.setVisibility(View.INVISIBLE);
        startSpinner.setVisibility(View.VISIBLE);
        isRunning = true;

        new Thread(() -> {
            try {
                runOnUiThread(() -> {
                    startScreen.setVisibility(View.GONE);
                    workflowScreen.setVisibility(View.VISIBLE);
                });

                workflow.run();
            } catch (Exception e) {
                Log.e(TAG, "Init error", e);
                showError(e.getMessage());
                isRunning = false;
            }
        }).start();
    }

    private void stopDemo() {
        isRunning = false;
        resetUIState();
    }

    private void transitionToHome() {
        runOnUiThread(() -> {
            startScreen.setAlpha(0f);
            startScreen.setVisibility(View.VISIBLE);

            startSpinner.setVisibility(View.GONE);
            btnStart.setVisibility(View.VISIBLE);
            startStatusText.setText("Ready to Start");

            workflowScreen.animate()
                    .alpha(0f)
                    .setDuration(500)
                    .withEndAction(() -> {
                        workflowScreen.setVisibility(View.GONE);
                        workflowScreen.setAlpha(1f);
                        resetUIState();
                    })
                    .start();

            startScreen.animate()
                    .alpha(1f)
                    .setDuration(500)
                    .start();
            isRunning = false;
        });
    }

    class AINoiseSuppressedRecorder {
        private final Koala koala;
        private final LinkedBlockingQueue<short[]> rawFrames = new LinkedBlockingQueue<>();
        private short[] leftoverBuffer = new short[4096];
        private int leftoverCount = 0;

        private final int frameLength;

        public AINoiseSuppressedRecorder() throws Exception {
            koala = new Koala.Builder()
                    .setAccessKey(ACCESS_KEY)
                    .setModelPath(NS_MODEL)
                    .build(MainActivity.this);
            frameLength = koala.getFrameLength();

            VoiceProcessor.getInstance().addFrameListener(frame -> {
                if (isRunning) {
                    rawFrames.offer(frame);
                    runOnUiThread(() -> volumeMeterView.processFrame(frame));
                }
            });
        }

        public void start() throws Exception {
            rawFrames.clear();
            leftoverCount = 0;
            VoiceProcessor.getInstance().start(frameLength, koala.getSampleRate());
        }

        public void stop() throws VoiceProcessorException {
            VoiceProcessor.getInstance().stop();
        }

        public short[] read(int numSamples) throws Exception {
            short[] result = new short[numSamples];
            int resultIndex = 0;

            int numFromBuffer = Math.min(numSamples, leftoverCount);
            if (numFromBuffer > 0) {
                System.arraycopy(leftoverBuffer, 0, result, 0, numFromBuffer);
                resultIndex += numFromBuffer;
                leftoverCount -= numFromBuffer;

                if (leftoverCount > 0) {
                    System.arraycopy(leftoverBuffer, numFromBuffer, leftoverBuffer, 0, leftoverCount);
                }
            }

            while (resultIndex < numSamples && isRunning) {
                short[] raw = rawFrames.poll();
                if (raw == null) {
                    continue;
                }

                short[] enhanced = koala.process(raw);
                int remaining = numSamples - resultIndex;
                int toCopy = Math.min(enhanced.length, remaining);

                System.arraycopy(enhanced, 0, result, resultIndex, toCopy);
                resultIndex += toCopy;

                if (enhanced.length > remaining) {
                    int excess = enhanced.length - remaining;

                    if (leftoverCount + excess > leftoverBuffer.length) {
                        short[] newBuffer = new short[(leftoverCount + excess) * 2];
                        System.arraycopy(leftoverBuffer, 0, newBuffer, 0, leftoverCount);
                        leftoverBuffer = newBuffer;
                    }

                    System.arraycopy(enhanced, remaining, leftoverBuffer, leftoverCount, excess);
                    leftoverCount += excess;
                }
            }

            if (!isRunning) {
                koala.reset();
                return null;
            }

            return result;
        }

        public void delete() {
            if (koala != null) {
                koala.delete();
            }
        }
    }

    static class PvSpeaker {
        private final AudioTrack audioTrack;

        public PvSpeaker(int sampleRate) {
            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();

            AudioFormat audioFormat = new AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build();

            audioTrack = new AudioTrack(
                    audioAttributes,
                    audioFormat,
                    AudioTrack.getMinBufferSize(
                            sampleRate,
                            AudioFormat.CHANNEL_OUT_MONO,
                            AudioFormat.ENCODING_PCM_16BIT),
                    AudioTrack.MODE_STREAM,
                    0);
        }

        public void play(short[] pcm) {
            audioTrack.play();
            audioTrack.write(pcm, 0, pcm.length);
            audioTrack.stop();
        }

        public void delete() {
            audioTrack.release();
        }
    }

    abstract class Step {
        protected AINoiseSuppressedRecorder recorder;
        public Step(AINoiseSuppressedRecorder recorder) { this.recorder = recorder; }
        public abstract void delete();
    }

    class PorcupineStep extends Step {
        private final Porcupine porcupine;

        public PorcupineStep(AINoiseSuppressedRecorder r) throws Exception {
            super(r);
            porcupine = new Porcupine.Builder()
                    .setAccessKey(ACCESS_KEY)
                    .setKeywordPath(KEYWORD_MODEL)
                    .build(MainActivity.this);
        }

        public void run() throws Exception {
            recorder.start();
            setListeningUI(true);
            boolean isDetected = false;
            while (!isDetected && isRunning) {
                short[] frame = recorder.read(porcupine.getFrameLength());
                if (frame != null && frame.length == porcupine.getFrameLength()) {
                    isDetected = porcupine.process(frame) == 0;
                }
            }
            setListeningUI(false);
            recorder.stop();
        }

        public void delete() {
            if (porcupine != null) {
                porcupine.delete();
            }
        }
    }

    class OrcaStep extends Step {
        private final Orca orca;
        private final PvSpeaker speaker;
        private final OrcaSynthesizeParams synthesizeParams;

        public OrcaStep(AINoiseSuppressedRecorder r) throws Exception {
            super(r);
            orca = new Orca.Builder()
                    .setAccessKey(ACCESS_KEY)
                    .setModelPath(TTS_MODEL)
                    .build(MainActivity.this);
            synthesizeParams = new OrcaSynthesizeParams.Builder().build();
            speaker = new PvSpeaker(orca.getSampleRate());
        }

        public void run(String prompt) throws Exception {
            OrcaAudio res = orca.synthesize(prompt, synthesizeParams);
            speaker.play(res.getPcm());
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

    class RhinoStep extends Step {
        private final Rhino rhino;

        public RhinoStep(AINoiseSuppressedRecorder r) throws Exception {
            super(r);
            rhino = new Rhino.Builder()
                    .setAccessKey(ACCESS_KEY)
                    .setContextPath(CONTEXT_MODEL)
                    .setEndpointDurationSec(0.5f)
                    .setRequireEndpoint(false)
                    .build(MainActivity.this);
        }

        public RhinoInference run() throws Exception {
            recorder.start();
            setListeningUI(true);
            boolean isFinalized = false;
            while (!isFinalized && isRunning) {
                short[] frame = recorder.read(rhino.getFrameLength());
                if (frame != null && frame.length == rhino.getFrameLength()) {
                    isFinalized = rhino.process(frame);
                }
            }
            setListeningUI(false);
            recorder.stop();
            return isRunning ? rhino.getInference() : null;
        }

        public void delete() {
            if (rhino != null) {
                rhino.delete();
            }
        }
    }

    public interface PartialCallback {
        void onPartial(String transcript);
    }

    class CheetahStep extends Step {
        private final Cheetah cheetah;

        public CheetahStep(AINoiseSuppressedRecorder r) throws Exception {
            super(r);
            cheetah = new Cheetah.Builder()
                    .setAccessKey(ACCESS_KEY)
                    .setModelPath(STT_MODEL)
                    .setEnableAutomaticPunctuation(true)
                    .setEnableTextNormalization(true)
                    .build(MainActivity.this);
        }

        public String run(PartialCallback callback) throws Exception {
            recorder.start();
            setListeningUI(true);
            StringBuilder transcript = new StringBuilder();
            boolean isEndpoint = false;
            while (!isEndpoint && isRunning) {
                short[] frame = recorder.read(cheetah.getFrameLength());
                if (frame != null && frame.length == cheetah.getFrameLength()) {
                    CheetahTranscript partial = cheetah.process(frame);
                    transcript.append(partial.getTranscript());
                    if (!partial.getTranscript().isEmpty()) {
                        callback.onPartial(transcript.toString());
                    }
                    isEndpoint = partial.getIsEndpoint();
                    if (isEndpoint) {
                        transcript.append(cheetah.flush().getTranscript());
                    }
                }
            }
            setListeningUI(false);
            recorder.stop();
            return transcript.toString();
        }

        public void delete() {
            if (cheetah != null) {
                cheetah.delete();
            }
        }
    }

    enum RecipeStates {
        STANDBY,
        IDENTIFY_UNIT_PROMPT,
        IDENTIFY_UNIT_REPORT,
        INCIDENT_TYPE_PROMPT,
        INCIDENT_TYPE_REPORT,
        PATIENT_CONDITION_PROMPT,
        PATIENT_CONDITION_REPORT,
        DESTINATION_PROMPT,
        DESTINATION_REPORT,
        HANDOFF_STATUS_PROMPT,
        HANDOFF_STATUS_REPORT,
        HANDOFF_TIME_PROMPT,
        HANDOFF_TIME_REPORT,
        FINAL_NOTE_PROMPT,
        FINAL_NOTE_REPORT,
        COMPLETE_PROMPT
    }

    class Transition {
        RecipeStates nextState;
        Map<String, Object> nextArgs;
        public Transition(RecipeStates nextState) {
            this.nextState = nextState;
        }

        public Transition(RecipeStates nextState, Map<String, Object> nextArgs) {
            this.nextState = nextState;
            this.nextArgs = nextArgs;
        }
    }

    abstract class State {
        public abstract Transition run(Map<String, Object> args) throws Exception;

        protected void setStatus(String text) {
            runOnUiThread(() -> workflowStatusText.setText(text));
        }

        protected TextView addPendingCard(String title) throws Exception {
            FutureTask<TextView> task = new FutureTask<>(() -> {
                View card = getLayoutInflater().inflate(
                        R.layout.item_report_card,
                        reportContainer,
                        false);
                TextView titleView = card.findViewById(R.id.cardTitle);
                titleView.setText(title);

                TextView cardText = card.findViewById(R.id.cardValue);
                cardText.setText("...");

                reportContainer.addView(card);
                scrollToBottom();

                return cardText;
            });

            runOnUiThread(task);
            return task.get();
        }

        protected void updateCardValue(TextView cardText, String text) {
            runOnUiThread(() -> {
                cardText.setText(text);
                scrollToBottom();
            });
        }
    }

    class PromptState extends State {
        OrcaStep step;
        String defaultPrompt;
        RecipeStates nextState;

        public PromptState(OrcaStep step, String prompt, RecipeStates nextState) {
            this.step = step;
            this.defaultPrompt = prompt;
            this.nextState = nextState;
        }

        @Override
        public Transition run(Map<String, Object> args) throws Exception {
            String prompt = (args != null && args.containsKey("prompt"))
                    ? (String) args.get("prompt")
                    : defaultPrompt;
            if (nextState == null) {
                setStatus(prompt);
            } else {
                setStatus("Speaking: " + prompt);
            }

            step.run(prompt);
            return new Transition(nextState);
        }
    }

    interface PromptGenerator {
        String generate(RhinoInference inference);
    }

    class ReportState extends State {
        RhinoStep step;
        String listeningPrompt, cardTitle, expectedIntent;
        RecipeStates successNextState, failureNextState;
        PromptGenerator successLogGen, failurePromptGen;

        private TextView currentCardValueView = null;

        public ReportState(
                RhinoStep step,
                String listenPrompt,
                String cardTitle,
                String intent,
                PromptGenerator successGen,
                RecipeStates successState,
                PromptGenerator failGen,
                RecipeStates failState) {
            this.step = step;
            this.listeningPrompt = listenPrompt;
            this.cardTitle = cardTitle;
            this.expectedIntent = intent;
            this.successLogGen = successGen;
            this.successNextState = successState;
            this.failurePromptGen = failGen;
            this.failureNextState = failState;
        }

        @Override
        public Transition run(Map<String, Object> args) throws Exception {
            if (currentCardValueView == null) {
                currentCardValueView = addPendingCard(cardTitle);
            } else {
                updateCardValue(currentCardValueView, "...");
            }

            setStatus(listeningPrompt);
            RhinoInference inference = step.run();
            if (inference != null && inference.getIsUnderstood() && inference.getIntent().equals(expectedIntent)) {
                updateCardValue(currentCardValueView, successLogGen.generate(inference));
                return new Transition(successNextState);
            }

            Map<String, Object> nextArgs = new HashMap<>();
            nextArgs.put("prompt", failurePromptGen.generate(inference));
            return new Transition(failureNextState, nextArgs);
        }
    }

    public interface InitCallback { void onProgress(String status); }

    class Workflow {
        AINoiseSuppressedRecorder recorder;
        PorcupineStep porcupineStep;
        OrcaStep orcaStep;
        RhinoStep rhinoStep;
        CheetahStep cheetahStep;

        Map<RecipeStates, State> states = new HashMap<>();

        public Workflow(InitCallback initCallback) throws Exception {
            initCallback.onProgress("Loading Koala Noise Suppression...");
            recorder = new AINoiseSuppressedRecorder();

            initCallback.onProgress("Loading Porcupine Wake Word...");
            porcupineStep = new PorcupineStep(recorder);

            initCallback.onProgress("Loading Orca Text-to-Speech...");
            orcaStep = new OrcaStep(recorder);

            initCallback.onProgress("Loading Rhino Speech-to-Intent...");
            rhinoStep = new RhinoStep(recorder);

            initCallback.onProgress("Loading Cheetah Speech-to-Text...");
            cheetahStep = new CheetahStep(recorder);

            buildStates();
        }

        private void buildStates() {
            states.put(RecipeStates.STANDBY, new State() {
                public Transition run(Map<String, Object> args) throws Exception {
                    setStatus("Listening for wake word...");
                    porcupineStep.run();
                    return new Transition(RecipeStates.IDENTIFY_UNIT_PROMPT);
                }
            });

            states.put(
                    RecipeStates.IDENTIFY_UNIT_PROMPT,
                    new PromptState(
                            orcaStep,
                            "What is the unit ID?",
                            RecipeStates.IDENTIFY_UNIT_REPORT));
            states.put(
                    RecipeStates.IDENTIFY_UNIT_REPORT,
                    new ReportState(
                            rhinoStep,
                            "Listening for unit ID...",
                            "UNIT ID",
                            "identifyUnit",
                            inf -> inf.getSlots().get("unitId"),
                            RecipeStates.INCIDENT_TYPE_PROMPT,
                            inf -> "I'm sorry. What is the unit ID again?",
                            RecipeStates.IDENTIFY_UNIT_PROMPT));

            states.put(
                    RecipeStates.INCIDENT_TYPE_PROMPT,
                    new PromptState(
                            orcaStep,
                            "What was the incident type?",
                            RecipeStates.INCIDENT_TYPE_REPORT));
            states.put(
                    RecipeStates.INCIDENT_TYPE_REPORT,
                    new ReportState(
                            rhinoStep,
                            "Listening for incident type...",
                            "INCIDENT TYPE",
                            "reportIncidentType",
                            inf -> inf.getSlots().get("incidentType"),
                            RecipeStates.PATIENT_CONDITION_PROMPT,
                            inf -> "I'm sorry. What was the incident type again?",
                            RecipeStates.INCIDENT_TYPE_PROMPT));

            states.put(
                    RecipeStates.PATIENT_CONDITION_PROMPT,
                    new PromptState(
                            orcaStep,
                            "What is the patient condition?",
                            RecipeStates.PATIENT_CONDITION_REPORT));
            states.put(
                    RecipeStates.PATIENT_CONDITION_REPORT,
                    new ReportState(
                            rhinoStep,
                            "Listening for patient condition...",
                            "PATIENT CONDITION",
                            "reportPatientCondition",
                            inf -> inf.getSlots().get("patientCondition"),
                            RecipeStates.DESTINATION_PROMPT,
                            inf -> "I'm sorry. What is the patient condition again?",
                            RecipeStates.PATIENT_CONDITION_PROMPT));

            states.put(
                    RecipeStates.DESTINATION_PROMPT,
                    new PromptState(
                            orcaStep,
                            "What was the destination?",
                            RecipeStates.DESTINATION_REPORT));
            states.put(
                    RecipeStates.DESTINATION_REPORT,
                    new ReportState(
                            rhinoStep,
                            "Listening for destination...",
                            "DESTINATION",
                            "reportDestination",
                            inf -> inf.getSlots().get("destination"),
                            RecipeStates.HANDOFF_STATUS_PROMPT,
                            inf -> "I'm sorry. What was the destination again?",
                            RecipeStates.DESTINATION_PROMPT));

            states.put(
                    RecipeStates.HANDOFF_STATUS_PROMPT,
                    new PromptState(
                            orcaStep,
                            "What is the handoff status?",
                            RecipeStates.HANDOFF_STATUS_REPORT));
            states.put(
                    RecipeStates.HANDOFF_STATUS_REPORT,
                    new ReportState(
                            rhinoStep,
                            "Listening for handoff status...",
                            "HANDOFF STATUS",
                            "reportHandoffStatus",
                            inf -> inf.getSlots().get("handoffStatus"),
                            RecipeStates.HANDOFF_TIME_PROMPT,
                            inf -> "I'm sorry. What is the handoff status again?",
                            RecipeStates.HANDOFF_STATUS_PROMPT));

            states.put(
                    RecipeStates.HANDOFF_TIME_PROMPT,
                    new PromptState(
                            orcaStep,
                            "What was the handoff time?",
                            RecipeStates.HANDOFF_TIME_REPORT));
            states.put(
                    RecipeStates.HANDOFF_TIME_REPORT,
                    new ReportState(
                            rhinoStep,
                            "Listening for handoff time...",
                            "HANDOFF TIME",
                            "reportHandoffTime",
                            inf -> {
                                String hour = HOUR_MAP.get(inf.getSlots().get("hour"));
                                String minute = inf.getSlots().get("minute");
                                String meridiem = inf.getSlots().get("meridiem");
                                return String.format("%s:%s %s", hour, minute, meridiem.toUpperCase());
                            },
                            RecipeStates.FINAL_NOTE_PROMPT,
                            inf -> "I'm sorry. What was the handoff time again?",
                            RecipeStates.HANDOFF_TIME_PROMPT));

            states.put(
                    RecipeStates.FINAL_NOTE_PROMPT,
                    new PromptState(
                            orcaStep,
                            "Please provide additional notes.",
                            RecipeStates.FINAL_NOTE_REPORT));
            states.put(
                    RecipeStates.FINAL_NOTE_REPORT,
                    new State() {
                        public Transition run(Map<String, Object> args) throws Exception {
                            setStatus("Listening for notes...");

                            FutureTask<TextView> task = new FutureTask<>(() -> {
                                View card = getLayoutInflater().inflate(R.layout.item_report_card, reportContainer, false);
                                TextView titleView = card.findViewById(R.id.cardTitle);
                                titleView.setText("NOTES");

                                TextView cardText = card.findViewById(R.id.cardValue);
                                cardText.setText("...");

                                reportContainer.addView(card);
                                scrollToBottom();

                                return cardText;
                            });

                            runOnUiThread(task);

                            TextView cardValueView = task.get();

                            String finalNotes = cheetahStep.run(transcript -> {
                                runOnUiThread(() -> cardValueView.setText(transcript));
                            });

                            runOnUiThread(() -> cardValueView.setText(finalNotes.trim()));

                            return new Transition(RecipeStates.COMPLETE_PROMPT);
                        }
                    });

            states.put(
                    RecipeStates.COMPLETE_PROMPT,
                    new PromptState(
                            orcaStep,
                            "Field report recorded.",
                            null));
        }

        public void run() throws Exception {
            RecipeStates currentState = RecipeStates.STANDBY;
            Map<String, Object> currentArgs = null;

            while (currentState != null && isRunning) {
                State state = states.get(currentState);
                if (state != null) {
                    Transition transition = state.run(currentArgs);
                    currentState = transition.nextState;
                    currentArgs = transition.nextArgs;
                }
            }

            if (isRunning) {
                runOnUiThread(() -> {
                    volumeMeterView.setVisibility(View.INVISIBLE);
                    processingSpinner.setVisibility(View.INVISIBLE);
                    successIcon.setVisibility(View.VISIBLE);
                    btnCancel.setVisibility(View.GONE);
                });

                Thread.sleep(2000);
                transitionToHome();
            }
        }

        public void delete() {
            if (porcupineStep != null) {
                porcupineStep.delete();
            }
            if (orcaStep != null) {
                orcaStep.delete();
            }
            if (rhinoStep != null) {
                rhinoStep.delete();
            }
            if (cheetahStep != null) {
                cheetahStep.delete();
            }
            if (recorder != null) {
                recorder.delete();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startDemo();
        }
    }
}