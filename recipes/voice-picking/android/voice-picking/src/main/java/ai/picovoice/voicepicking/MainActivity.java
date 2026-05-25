/*
    Copyright 2026 Picovoice Inc.

    You may not use this file except in compliance with the license. A copy of the license is
    located in the "LICENSE" file accompanying this source.

    Unless required by applicable law or agreed to in writing, software distributed under the
    License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
    express or implied. See the License for the specific language governing permissions and
    limitations under the License.
*/

package ai.picovoice.voicepicking;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

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
    private static final String KEYWORD_MODEL = "voice_picking_android.ppn";
    private static final String CONTEXT_MODEL = "voice_picking_android.rhn";

    private static final String STT_MODEL = "cheetah_params.pv";
    private static final String TTS_MODEL = "orca_params_en_female.pv";
    private static final String NS_MODEL = "koala_params.pv";

    private static final int INACTIVE_COLOUR = Color.parseColor("#7f8c8d");
    private static final int ACTIVE_COLOUR = Color.parseColor("#377dff");

    private LinearLayout startScreen, workflowScreen, reportContainer, errorView;
    private TextView startStatusText, workflowStatusText, errorText;
    private Button btnStart, btnCancel;
    private VolumeMeterView volumeMeterView;
    private ProgressBar startSpinner, processingSpinner;
    private View animationContainer;
    private ImageView successIcon;

    private Workflow workflow;
    private volatile boolean isRunning = false;

    private final Map<String, CardUI> cardMap = new HashMap<>();

    interface WorkflowListener {
        void onInitProgress(String status);
        void onStatusChanged(String status);
        void onCardActive(String cardId);
        void onCardUpdated(String cardId, String value, boolean isFinal);
        void onWorkflowComplete();
        void onVolumeFrame(short[] frame);
    }

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

    private void resetUIState() {
        runOnUiThread(() -> {
            startScreen.setVisibility(View.VISIBLE);
            workflowScreen.setVisibility(View.GONE);
            errorView.setVisibility(View.GONE);

            btnStart.setVisibility(View.VISIBLE);
            startSpinner.setVisibility(View.GONE);
            successIcon.setVisibility(View.INVISIBLE);
            workflowStatusText.setText("");
            startStatusText.setText("Ready to Start");
            btnCancel.setVisibility(View.VISIBLE);
            btnCancel.setText("Cancel Report");

            resetReportCards();

            animationContainer.setVisibility(View.INVISIBLE);
        });
    }

    private void showError(String message) {
        runOnUiThread(() -> {
            errorView.setVisibility(View.VISIBLE);
            errorText.setText("Error: " + message);

            if (startScreen.getVisibility() == View.VISIBLE) {
                btnStart.setVisibility(View.GONE);
                startStatusText.setVisibility(View.GONE);
                startSpinner.setVisibility(View.GONE);
                startStatusText.setText("Initialization Failed");
            }
        });
    }

    private void setListeningUI(boolean isListening, String listeningPrompt) {
        runOnUiThread(() -> {
            if (!isRunning) {
                return;
            }
            animationContainer.setVisibility(View.VISIBLE);
            if (isListening) {
                workflowStatusText.setText(listeningPrompt);
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

        setupReportCards();

        new Thread(() -> {
            try {
                workflow = new Workflow(MainActivity.this, new WorkflowListener() {
                    @Override
                    public void onInitProgress(String status) {
                        runOnUiThread(() -> startStatusText.setText(status));
                    }

                    @Override
                    public void onStatusChanged(String status) {
                        runOnUiThread(() -> workflowStatusText.setText(status));
                    }

                    @Override
                    public void onCardActive(String cardId) {
                        if (cardId != null) {
                            setActiveCard(cardMap.get(cardId));
                        } else {
                            setActiveCard(null);
                        }
                    }

                    @Override
                    public void onCardUpdated(String cardId, String value, boolean isFinal) {
                        CardUI card = cardMap.get(cardId);
                        if (card != null) {
                            int color = isFinal ? INACTIVE_COLOUR : ACTIVE_COLOUR;
                            card.setValue(value, color);
                        }
                    }

                    @Override
                    public void onWorkflowComplete() {
                        runOnUiThread(() -> {
                            volumeMeterView.setVisibility(View.INVISIBLE);
                            processingSpinner.setVisibility(View.INVISIBLE);
                            successIcon.setVisibility(View.VISIBLE);
                            btnCancel.setVisibility(View.GONE);
                        });

                        try {
                            Thread.sleep(2000);
                        } catch (InterruptedException ignored) {}
                        transitionToHome();
                    }

                    @Override
                    public void onVolumeFrame(short[] frame) {
                        runOnUiThread(() -> volumeMeterView.processFrame(frame));
                    }
                });
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

    class CardUI {
        View root;
        TextView titleView;
        TextView valueView;

        void setValue(String text, int colour) {
            runOnUiThread(() -> {
                valueView.setText(text);
                valueView.setTextColor(colour);
            });
        }

        void reset() {
            runOnUiThread(() -> {
                valueView.setText("-");
                valueView.setTextColor(INACTIVE_COLOUR);
                titleView.setTextColor(INACTIVE_COLOUR);
                this.root.setBackgroundResource(R.drawable.bg_report_card_inactive);
            });
        }
    }

    private void setupReportCards(List<PickTask> tasks) {
        reportContainer.removeAllViews();
        cardMap.clear();

        // TODO: ensure the rendering is done correctly
        // TODO: then, add details to the top right of the card
        // TODO: then create the PickTask type and the list, so we can use it in our state machine. Woo hoo, done!
        for (int i = 0; i < tasks.length; i++) {
            cardMap.put("location-" + String.valueOf(i), createCard("LOCATION"));
            cardMap.put("pick-" + String.valueOf(i), createCard("PICK"));
        }
    }

    private void resetReportCards() {
        for (CardUI card : cardMap.values()) {
            card.reset();
        }
    }

    private CardUI createCard(String title) {
        View root = getLayoutInflater().inflate(R.layout.item_report_card, reportContainer, false);

        CardUI card = new CardUI();
        card.root = root;
        card.titleView = root.findViewById(R.id.cardTitle);
        card.valueView = root.findViewById(R.id.cardValue);

        card.titleView.setText(title);
        card.valueView.setText("-");

        reportContainer.addView(root);
        return card;
    }

    private void setActiveCard(CardUI activeCard) {
        runOnUiThread(() -> {
            for (CardUI card : cardMap.values()) {
                boolean isActive = (card == activeCard);
                card.root.setBackgroundResource(isActive
                        ? R.drawable.bg_report_card_active
                        : R.drawable.bg_report_card_inactive);

                if (isActive && card.valueView.getText().toString().equals("-")) {
                    card.titleView.setTextColor(ACTIVE_COLOUR);
                    card.setValue("...", ACTIVE_COLOUR);
                }
            }

            if (activeCard != null) {
                View parent = (View) findViewById(R.id.reportContainer).getParent();
                if (parent instanceof android.widget.ScrollView) {
                    android.widget.ScrollView sv = (android.widget.ScrollView) parent;
                    sv.post(() -> {
                        int scrollY = sv.getScrollY();
                        int svHeight = sv.getHeight();
                        int cardTop = activeCard.root.getTop();
                        int cardScreenPosition = cardTop - scrollY;
                        if (cardScreenPosition > svHeight / 2) {
                            sv.smoothScrollTo(0, cardTop - 32);
                        }
                    });
                }
            }
        });
    }

    class AINoiseSuppressedRecorder {
        private final Koala koala;
        private final LinkedBlockingQueue<short[]> rawFrames = new LinkedBlockingQueue<>();
        private short[] leftoverBuffer = new short[4096];
        private int leftoverCount = 0;

        private final int frameLength;

        private final Object lock = new Object();
        private volatile boolean isSessionActive = false;

        public AINoiseSuppressedRecorder(Context context, WorkflowListener listener) throws Exception {
            koala = new Koala.Builder()
                    .setAccessKey(ACCESS_KEY)
                    .setModelPath(NS_MODEL)
                    .build(context);
            frameLength = koala.getFrameLength();

            VoiceProcessor.getInstance().addFrameListener(frame -> {
                if (isSessionActive) {
                    rawFrames.offer(frame);
                    listener.onVolumeFrame(frame);
                }
            });
        }

        public void start() throws Exception {
            synchronized (lock) {
                if (isSessionActive) {
                    Log.w(TAG, "Recorder is already running. Ignoring start request.");
                    return;
                }
                rawFrames.clear();
                leftoverCount = 0;
                koala.reset();
                isSessionActive = true;
                VoiceProcessor.getInstance().start(frameLength, koala.getSampleRate());
            }
        }

        public void stop() throws VoiceProcessorException {
            synchronized (lock) {
                isSessionActive = false;
                VoiceProcessor.getInstance().stop();
                rawFrames.clear();
            }
        }

        public short[] read(int numSamples) throws Exception {
            short[] result = new short[numSamples];
            int resultIndex = 0;

            synchronized (lock) {
                if (!isSessionActive) {
                    return null;
                }

                int numFromBuffer = Math.min(numSamples, leftoverCount);
                if (numFromBuffer > 0) {
                    System.arraycopy(leftoverBuffer, 0, result, 0, numFromBuffer);
                    resultIndex += numFromBuffer;
                    leftoverCount -= numFromBuffer;

                    if (leftoverCount > 0) {
                        System.arraycopy(leftoverBuffer, numFromBuffer, leftoverBuffer, 0, leftoverCount);
                    }
                }
            }

            while (resultIndex < numSamples && isSessionActive) {
                short[] raw = rawFrames.poll(50, TimeUnit.MILLISECONDS);
                if (raw == null) {
                    continue;
                }

                synchronized (lock) {
                    if (!isSessionActive) {
                        return null;
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
            }

            if (!isSessionActive) {
                return null;
            }

            return result;
        }

        public void delete() {
            try {
                stop();
            } catch (VoiceProcessorException e) {
                Log.e(TAG, "Failed to stop VoiceProcessor during cleanup", e);
            }

            synchronized (lock) {
                if (koala != null) {
                    koala.delete();
                }
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

        public PorcupineStep(Context context, AINoiseSuppressedRecorder r) throws Exception {
            super(r);
            porcupine = new Porcupine.Builder()
                    .setAccessKey(ACCESS_KEY)
                    .setKeywordPath(KEYWORD_MODEL)
                    .build(context);
        }

        public void run(String listeningPrompt) throws Exception {
            recorder.start();
            setListeningUI(true, listeningPrompt);
            boolean isDetected = false;
            while (!isDetected && isRunning) {
                short[] frame = recorder.read(porcupine.getFrameLength());
                if (frame != null && frame.length == porcupine.getFrameLength()) {
                    isDetected = porcupine.process(frame) == 0;
                }
            }
            setListeningUI(false, listeningPrompt);
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

        public OrcaStep(Context context, AINoiseSuppressedRecorder r) throws Exception {
            super(r);
            orca = new Orca.Builder()
                    .setAccessKey(ACCESS_KEY)
                    .setModelPath(TTS_MODEL)
                    .build(context);
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

        public RhinoStep(Context context, AINoiseSuppressedRecorder r) throws Exception {
            super(r);
            rhino = new Rhino.Builder()
                    .setAccessKey(ACCESS_KEY)
                    .setContextPath(CONTEXT_MODEL)
                    .setEndpointDurationSec(0.5f)
                    .setRequireEndpoint(false)
                    .build(context);
        }

        public RhinoInference run(String listeningPrompt) throws Exception {
            recorder.start();
            setListeningUI(true, listeningPrompt);
            boolean isFinalized = false;
            while (!isFinalized && isRunning) {
                short[] frame = recorder.read(rhino.getFrameLength());
                if (frame != null && frame.length == rhino.getFrameLength()) {
                    isFinalized = rhino.process(frame);
                }
            }
            setListeningUI(false, listeningPrompt);
            recorder.stop();
            return isRunning ? rhino.getInference() : null;
        }

        public void delete() {
            if (rhino != null) {
                rhino.delete();
            }
        }
    }

    // TODO: update states to the voice picking ones

    enum RecipeStates {
        STANDBY,
        TASK_LOCATION_PROMPT,
        TASK_LOCATION_REPORT,
        TASK_PICK_PROMPT,
        TASK_PICK_REPORT,
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
        protected final WorkflowListener listener;

        public State(WorkflowListener listener) {
            this.listener = listener;
        }

        public abstract Transition run(Map<String, Object> args) throws Exception;
    }

    class PromptState extends State {
        OrcaStep step;
        String defaultPrompt;
        RecipeStates nextState;
        CardType targetCardType;

        public PromptState(
                WorkflowListener listener,
                OrcaStep step,
                String prompt,
                CardType targetCardType,
                RecipeStates nextState) {
            super(listener);
            this.step = step;
            this.defaultPrompt = prompt;
            this.nextState = nextState;
            this.targetCardType = targetCardType;
        }

        @Override
        public Transition run(Map<String, Object> args) throws Exception {
            listener.onCardActive(targetCardType);

            String prompt = (args != null && args.containsKey("prompt"))
                    ? (String) args.get("prompt")
                    : defaultPrompt;
            listener.onStatusChanged(prompt);
            step.run(prompt);
            return new Transition(nextState);
        }
    }

    interface PromptGenerator {
        String generate(RhinoInference inference);
    }

    class ReportState extends State {
        RhinoStep step;
        String listeningPrompt, expectedIntent;
        RecipeStates successNextState, failureNextState;
        PromptGenerator successLogGen, failurePromptGen;
        CardType cardType;

        public ReportState(
                WorkflowListener listener,
                RhinoStep step,
                String listenPrompt,
                CardType cardType,
                String intent,
                PromptGenerator successGen,
                RecipeStates successState,
                PromptGenerator failGen,
                RecipeStates failState) {
            super(listener);
            this.step = step;
            this.listeningPrompt = listenPrompt;
            this.cardType = cardType;
            this.expectedIntent = intent;
            this.successLogGen = successGen;
            this.successNextState = successState;
            this.failurePromptGen = failGen;
            this.failureNextState = failState;
        }

        @Override
        public Transition run(Map<String, Object> args) throws Exception {
            listener.onCardUpdated(cardType, "...", false);

            RhinoInference inference = step.run(listeningPrompt);
            if (inference != null && inference.getIsUnderstood() && inference.getIntent().equals(expectedIntent)) {
                listener.onCardUpdated(cardType, successLogGen.generate(inference), true);
                return new Transition(successNextState);
            }

            listener.onCardUpdated(cardType, "...", false);

            Map<String, Object> nextArgs = new HashMap<>();
            nextArgs.put("prompt", failurePromptGen.generate(inference));
            return new Transition(failureNextState, nextArgs);
        }
    }

    // TODO: where does this get instantiated?
    class StandbyState extends State {
        private final PorcupineStep step;
        private final RecipeStates nextState;

        public StandbyState(WorkflowListener listener, PorcupineStep step, RecipeStates nextState) {
            super(listener);
            this.step = step;
            this.nextState = nextState;
        }

        @Override
        public Transition run(Map<String, Object> args) throws Exception {
            step.run("Listening for wake word...");
            return new Transition(nextState);
        }
    }

    class TaskLocationPromptState extends PromptState {
        public TaskLocationPromptState(WorkflowListener listener, OrcaStep step) {
            super(
                    listener,
                    step,
                    // TODO: what is the type of task?
                    String.format("Go to %s. Confirm location. Check digits are %s.", task.locationName, task.checkDigit)
                    CardType.UNIT_ID,
                    RecipeStates.TASK_LOCATION_REPORT);
        }

        // TODO: we need to pass taskid, or otherwise store this information globally...

        /*
        @Override
        public Transition run(Map<String, Object> args) throws Exception {
            listener.onCardActive(targetCardType);

            String prompt = (args != null && args.containsKey("prompt"))
                    ? (String) args.get("prompt")
                    : defaultPrompt;
            listener.onStatusChanged(prompt);
            step.run(prompt);
            return new Transition(nextState);
        }*/
    }

    class TaskLocationReportState extends ReportState {
        public IdentifyUnitReportState(WorkflowListener listener, RhinoStep step) {
            super(
                    listener,
                    step,
                    "Listening for unit ID...",
                    CardType.UNIT_ID,
                    "identifyUnit",
                    inf -> inf.getSlots().get("unitId"),
                    RecipeStates.INCIDENT_TYPE_PROMPT,
                    inf -> "Failed to capture unit ID. Retrying...",
                    RecipeStates.IDENTIFY_UNIT_PROMPT);
        }
    }

    class TaskPickPromptState extends PromptState {
        
    }

    class TaskPickReportState extends ReportState {
        
    }

    /*
    class IdentifyUnitPromptState extends PromptState {
        public IdentifyUnitPromptState(WorkflowListener listener, OrcaStep step) {
            super(
                    listener,
                    step,
                    "What is the unit ID?",
                    CardType.UNIT_ID,
                    RecipeStates.IDENTIFY_UNIT_REPORT);
        }
    }

    class IncidentTypePromptState extends PromptState {
        public IncidentTypePromptState(WorkflowListener listener, OrcaStep step) {
            super(
                    listener,
                    step,
                    "What was the incident type?",
                    CardType.INCIDENT_TYPE,
                    RecipeStates.INCIDENT_TYPE_REPORT);
        }
    }

    class PatientConditionPromptState extends PromptState {
        public PatientConditionPromptState(WorkflowListener listener, OrcaStep step) {
            super(
                    listener,
                    step,
                    "What is the patient condition?",
                    CardType.PATIENT_CONDITION,
                    RecipeStates.PATIENT_CONDITION_REPORT);
        }
    }

    class DestinationPromptState extends PromptState {
        public DestinationPromptState(WorkflowListener listener, OrcaStep step) {
            super(
                    listener,
                    step,
                    "What was the destination?",
                    CardType.DESTINATION,
                    RecipeStates.DESTINATION_REPORT);
        }
    }

    class HandoffStatusPromptState extends PromptState {
        public HandoffStatusPromptState(WorkflowListener listener, OrcaStep step) {
            super(
                    listener,
                    step,
                    "What is the handoff status?",
                    CardType.HANDOFF_STATUS,
                    RecipeStates.HANDOFF_STATUS_REPORT);
        }
    }

    class HandoffTimePromptState extends PromptState {
        public HandoffTimePromptState(WorkflowListener listener, OrcaStep step) {
            super(
                    listener,
                    step,
                    "What was the handoff time?",
                    CardType.HANDOFF_TIME,
                    RecipeStates.HANDOFF_TIME_REPORT);
        }
    }

    class FinalNotePromptState extends PromptState {
        public FinalNotePromptState(WorkflowListener listener, OrcaStep step) {
            super(
                    listener,
                    step,
                    "Please provide additional notes.",
                    CardType.NOTES,
                    RecipeStates.FINAL_NOTE_REPORT);
        }
    }

    class CompletePromptState extends PromptState {
        public CompletePromptState(WorkflowListener listener, OrcaStep step) {
            super(
                    listener,
                    step,
                    "Field report recorded.",
                    null,
                    null);
        }
    }

    class IdentifyUnitReportState extends ReportState {
        public IdentifyUnitReportState(WorkflowListener listener, RhinoStep step) {
            super(
                    listener,
                    step,
                    "Listening for unit ID...",
                    CardType.UNIT_ID,
                    "identifyUnit",
                    inf -> inf.getSlots().get("unitId"),
                    RecipeStates.INCIDENT_TYPE_PROMPT,
                    inf -> "Failed to capture unit ID. Retrying...",
                    RecipeStates.IDENTIFY_UNIT_PROMPT);
        }
    }

    class IncidentTypeReportState extends ReportState {
        public IncidentTypeReportState(WorkflowListener listener, RhinoStep step) {
            super(
                    listener,
                    step,
                    "Listening for incident type...",
                    CardType.INCIDENT_TYPE,
                    "reportIncidentType",
                    inf -> inf.getSlots().get("incidentType"),
                    RecipeStates.PATIENT_CONDITION_PROMPT,
                    inf -> "Failed to capture incident type. Retrying...",
                    RecipeStates.INCIDENT_TYPE_PROMPT);
        }
    }

    class PatientConditionReportState extends ReportState {
        public PatientConditionReportState(WorkflowListener listener, RhinoStep step) {
            super(
                    listener,
                    step,
                    "Listening for patient condition...",
                    CardType.PATIENT_CONDITION,
                    "reportPatientCondition",
                    inf -> inf.getSlots().get("patientCondition"),
                    RecipeStates.DESTINATION_PROMPT,
                    inf -> "Failed to capture patient condition. Retrying...",
                    RecipeStates.PATIENT_CONDITION_PROMPT);
        }
    }

    class DestinationReportState extends ReportState {
        public DestinationReportState(WorkflowListener listener, RhinoStep step) {
            super(
                    listener,
                    step,
                    "Listening for destination...",
                    CardType.DESTINATION,
                    "reportDestination",
                    inf -> inf.getSlots().get("destination"),
                    RecipeStates.HANDOFF_STATUS_PROMPT,
                    inf -> "Failed to capture destination. Retrying...",
                    RecipeStates.DESTINATION_PROMPT);
        }
    }

    class HandoffStatusReportState extends ReportState {
        public HandoffStatusReportState(WorkflowListener listener, RhinoStep step) {
            super(
                    listener,
                    step,
                    "Listening for handoff status...",
                    CardType.HANDOFF_STATUS,
                    "reportHandoffStatus",
                    inf -> inf.getSlots().get("handoffStatus"),
                    RecipeStates.HANDOFF_TIME_PROMPT,
                    inf -> "Failed to capture handoff status. Retrying...",
                    RecipeStates.HANDOFF_STATUS_PROMPT);
        }
    }

    class HandoffTimeReportState extends ReportState {
        public HandoffTimeReportState(WorkflowListener listener, RhinoStep step) {
            super(
                    listener,
                    step,
                    "Listening for handoff time...",
                    CardType.HANDOFF_TIME,
                    "reportHandoffTime",
                    inf -> {
                        String hour = HOUR_MAP.get(inf.getSlots().get("hour"));
                        int minute = Integer.parseInt(inf.getSlots().get("minute"));
                        String meridiem = inf.getSlots().get("meridiem");
                        return String.format("%s:%02d %s", hour, minute, meridiem.toUpperCase());
                    },
                    RecipeStates.FINAL_NOTE_PROMPT,
                    inf -> "I'm sorry. What was the handoff time again?",
                    RecipeStates.HANDOFF_TIME_PROMPT);
        }
    }

    class DictationState extends State {
        private final CheetahStep step;
        private final RecipeStates nextState;
        private final CardType cardType;

        public DictationState(
                WorkflowListener listener,
                CheetahStep step,
                CardType cardType,
                RecipeStates nextState) {
            super(listener);
            this.step = step;
            this.cardType = cardType;
            this.nextState = nextState;
        }

        @Override
        public Transition run(Map<String, Object> args) throws Exception {
            String finalNotes = step.run(
                    transcript -> listener.onCardUpdated(cardType, transcript, false),
                    "Listening for notes...");
            listener.onCardUpdated(cardType, finalNotes, true);
            return new Transition(nextState);
        }
    }*/

    class Workflow {
        AINoiseSuppressedRecorder recorder;
        PorcupineStep porcupineStep;
        OrcaStep orcaStep;
        RhinoStep rhinoStep;

        Map<RecipeStates, State> states = new HashMap<>();
        private final WorkflowListener listener;

        public Workflow(Context context, WorkflowListener listener) throws Exception {
            this.listener = listener;

            listener.onInitProgress("Loading Koala Noise Suppression...");
            recorder = new AINoiseSuppressedRecorder(context, listener);

            listener.onInitProgress("Loading Porcupine Wake Word...");
            porcupineStep = new PorcupineStep(context, recorder);

            listener.onInitProgress("Loading Orca Text-to-Speech...");
            orcaStep = new OrcaStep(context, recorder);

            listener.onInitProgress("Loading Rhino Speech-to-Intent...");
            rhinoStep = new RhinoStep(context, recorder);

            buildStates();
        }

        private void buildStates() {
            states.put(RecipeStates.STANDBY, new StandbyState(listener, porcupineStep, RecipeStates.IDENTIFY_UNIT_PROMPT));

            states.put(RecipeStates.IDENTIFY_UNIT_PROMPT, new IdentifyUnitPromptState(listener, orcaStep));
            states.put(RecipeStates.IDENTIFY_UNIT_REPORT, new IdentifyUnitReportState(listener, rhinoStep));

            states.put(RecipeStates.INCIDENT_TYPE_PROMPT, new IncidentTypePromptState(listener, orcaStep));
            states.put(RecipeStates.INCIDENT_TYPE_REPORT, new IncidentTypeReportState(listener, rhinoStep));

            states.put(RecipeStates.PATIENT_CONDITION_PROMPT, new PatientConditionPromptState(listener, orcaStep));
            states.put(RecipeStates.PATIENT_CONDITION_REPORT, new PatientConditionReportState(listener, rhinoStep));

            states.put(RecipeStates.DESTINATION_PROMPT, new DestinationPromptState(listener, orcaStep));
            states.put(RecipeStates.DESTINATION_REPORT, new DestinationReportState(listener, rhinoStep));

            states.put(RecipeStates.HANDOFF_STATUS_PROMPT, new HandoffStatusPromptState(listener, orcaStep));
            states.put(RecipeStates.HANDOFF_STATUS_REPORT, new HandoffStatusReportState(listener, rhinoStep));

            states.put(RecipeStates.HANDOFF_TIME_PROMPT, new HandoffTimePromptState(listener, orcaStep));
            states.put(RecipeStates.HANDOFF_TIME_REPORT, new HandoffTimeReportState(listener, rhinoStep));

            states.put(RecipeStates.FINAL_NOTE_PROMPT, new FinalNotePromptState(listener, orcaStep));
            /*states.put(
                    RecipeStates.FINAL_NOTE_REPORT,
                    new DictationState(listener, cheetahStep, CardType.NOTES, RecipeStates.COMPLETE_PROMPT));*/

            states.put(RecipeStates.COMPLETE_PROMPT, new CompletePromptState(listener, orcaStep));
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
                listener.onWorkflowComplete();
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
            if (recorder != null) {
                recorder.delete();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (workflow != null) {
            workflow.delete();
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
