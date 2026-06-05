/*
    Copyright 2026 Picovoice Inc.

    You may not use this file except in compliance with the license. A copy of the license is
    located in the "LICENSE" file accompanying this source.

    Unless required by applicable law or agreed to in writing, software distributed under the
    License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
    express or implied. See the License for the specific language governing permissions and
    limitations under the License.
*/

package ai.picovoice.foodordering;

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
import android.widget.ScrollView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import ai.picovoice.android.voiceprocessor.VoiceProcessor;
import ai.picovoice.android.voiceprocessor.VoiceProcessorException;
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
    private static final String KEYWORD_MODEL = "food_ordering_android.ppn";
    private static final String CONTEXT_MODEL = "food_ordering_android.rhn";

    private static final String TTS_MODEL = "orca_params_en_female.pv";
    private static final String NS_MODEL = "koala_params.pv";

    private static final int INACTIVE_COLOUR = Color.parseColor("#7f8c8d");
    private static final int ACTIVE_COLOUR = Color.parseColor("#377dff");

    static class Pair<A, B> {
        public A first;
        public B second;

        public Pair(A first, B second) {
            this.first = first;
            this.second = second;
        }
    }

    private LinearLayout startScreen, workflowScreen, reportContainer, errorView;
    private TextView startStatusText, workflowStatusText, errorText;
    private Button btnStart, btnCancel;
    private VolumeMeterView volumeMeterView;
    private ProgressBar startSpinner, processingSpinner;
    private View animationContainer;
    private ImageView successIcon;
    private ScrollView scrollView;

    private Workflow workflow;
    private volatile boolean isRunning = false;

    private final Map<String, CardUI> cardMap = new HashMap<>();

    interface WorkflowListener {
        void onInitProgress(String status);
        void onStatusChanged(String status);
        void createCard(String title);
        void removeCard(int index);
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

        scrollView = findViewById(R.id.scrollView);

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

            scrollView.post(() -> {
                scrollView.scrollTo(0, 0);
            });

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
                    public void createCard(String title) {
                        // TODO: implement this
                        // add to `cardMap`
                    }

                    @Override
                    public void removeCard(int index) {
                        // TODO: implement this
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
        View leftContainer;
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
                leftContainer.setBackgroundResource(R.drawable.bg_report_card_inactive);
            });
        }
    }

    private void setupReportCards() {
        reportContainer.removeAllViews();
        cardMap.clear();

        // TODO: remove this?
        /*for (int i = 0; i < tasks.size(); i++) {
            cardMap.put("location-" + String.valueOf(i), createCard("LOCATION", false));
            cardMap.put("pick-" + String.valueOf(i), createCard("PICK", true));
        }*/
    }

    private void resetReportCards() {
        for (CardUI card : cardMap.values()) {
            card.reset();
        }
    }

    private CardUI createCard(String title) {
        View root;
        root = getLayoutInflater().inflate(R.layout.item_report_card, reportContainer, false);

        CardUI card = new CardUI();
        card.root = root;
        card.leftContainer = root;
        card.titleView = root.findViewById(R.id.cardTitle);
        card.valueView = root.findViewById(R.id.cardValue);

        card.titleView.setText(title);
        card.valueView.setText("-");

        reportContainer.addView(root);
        return card;
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

    @FunctionalInterface
    interface Predicate {
        boolean call();
    }

    static class PvSpeaker {
        private final AudioTrack audioTrack;
        private int writtenSamples;

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

            writtenSamples = 0;
        }

        public void play(short[] pcm, Predicate isRunning) {
            audioTrack.play();

            int samplesWritten = 0;
            while ((samplesWritten < pcm.length) && isRunning.call()) {
                samplesWritten += audioTrack.write(
                        pcm,
                        samplesWritten,
                        pcm.length - samplesWritten,
                        AudioTrack.WRITE_NON_BLOCKING);
                this.writtenSamples = samplesWritten;
            }
        }

        public void stop() {
            audioTrack.stop();
            writtenSamples = 0;
        }

        public boolean isPlaying() {
            return audioTrack.getPlaybackHeadPosition() < this.writtenSamples;
        }

        public void delete() {
            this.stop();
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
            speaker.play(res.getPcm(), () -> isRunning);

            while (isRunning && speaker.isPlaying()) {
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

    // TODO: add support for waiting 5 seconds
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

    static class OrderChange {
        String toItem;
        String toSize;
        String toCombo;

        public OrderChange(String toItem, String toSize, String toCombo) {
            this.toItem = toItem;
            this.toSize = toSize;
            this.toCombo = toCombo;
        }
    }

    static class OrderItem {
        String size;
        String itemName;
        int quantity;

        public OrderItem(String size, String itemName, int quantity) {
            this.size = size;
            this.itemName = itemName;
            this.quantity = quantity;
        }

        public static OrderItem parseAddItemInference(RhinoInference inference) {
            String size = inference.getSlots().get("size");
            String itemName = inference.getSlots().get("item");
            String comboName = inference.getSlots().get("combo");
            String modifier = inference.getSlots().get("modifier");

            Integer quantity;
            if (inference.getSlots().get("quantity") == null) {
                quantity = 1;
            } else {
                quantity = Integer.parseInt(inference.getSlots().get("quantity"));
            }

            if (comboName != null) {
                return new ComboItem(size, itemName, quantity, comboName);
            } else {
                return new MenuItem(size, itemName, quantity, modifier);
            }
        }

        public static OrderItem parseRemoveItemInference(RhinoInference inference) {
            String size = inference.getSlots().get("size");
            String itemName = inference.getSlots().get("item");

            return new OrderItem(size, itemName, -1);
        }

        /// @brief returns null if last item
        public static Pair<OrderItem, OrderChange> parseChangeItemInference(RhinoInference inference) {
            String fromItem = inference.getSlots().get("fromItem");
            String toSize = inference.getSlots().get("toSize");
            String toItem = inference.getSlots().get("toItem");
            String toCombo = inference.getSlots().get("combo");

            return new Pair(
                (fromItem == null) ? null : new OrderItem(null, fromItem, -1),
                new OrderChange(toItem, toSize, toCombo)
            );
        }

        public Integer findFromEndIn(ArrayList<OrderItem> order) {
            for (int i = order.size(); i >= 0; i--) { // i, order_item in reversed(list(enumerate(order))) {
                Boolean sameSize = (this.size == null) || (this.size == order.get(i).size);
                Boolean sameItem = this.itemName == order.get(i).itemName;
                if (sameSize && sameItem) {
                    return i;
                }
            }

            return null;
        }

        public String toString() {
            if (this.size == null) {
                return this.itemName;
            } else {
                return String.format("%d %s %s", this.quantity, this.size, this.itemName);
            }
        }
    }

    static class ComboItem extends OrderItem {
        String comboName;

        public ComboItem(
                String size,
                String itemName,
                int quantity,
                String comboName) {
            super(size, itemName, quantity);
            this.comboName = comboName;
        }

        public String toString() {
            String response = String.format("%s %s", super.toString(), this.comboName);

            if (this.quantity != 1 && response.charAt(response.length() - 1) != 's') {
                response += "s";
            }

            return response;
        }
    }

    static class MenuItem extends OrderItem {
        int quantity;
        String modifier;

        public MenuItem(
                String size,
                String itemName,
                int quantity,
                String modifier) {
            super(size, itemName, quantity);
            this.modifier = modifier;
        }

        public String toString() {
            String response = super.toString();

            if (this.quantity != 1 && response.charAt(response.length() - 1) != 's') {
                response += "s";
            }

            if (this.modifier != null) {
                response += String.format(", %s", this.modifier);
            }

            return response;
        }
    }

    enum RecipeStates {
        STANDBY,
        LISTEN_FOR_ORDER,
        ADD_ITEM,
        REMOVE_ITEM,
        CHANGE_ITEM,
        START_OVER,
        HELP,
        REPEAT_ORDER,
        SPEAK_PROMPT,
        SILENT_USER,
        END_ORDER
    }

    static class Transition {
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

    class StandbyState extends State {
        private final PorcupineStep step;

        public StandbyState(WorkflowListener listener, PorcupineStep step) {
            super(listener);
            this.step = step;
        }

        @Override
        public Transition run(Map<String, Object> args) throws Exception {
            step.run("Listening for wake word...");

            Map<String, Object> nextArgs = new HashMap<>();
            nextArgs.put("order", new ArrayList<OrderItem>());
            nextArgs.put("justAsked", false);

            return new Transition(RecipeStates.LISTEN_FOR_ORDER, nextArgs);
        }
    }

    class ListenForOrderState extends State {
        RhinoStep step;

        public ListenForOrderState(WorkflowListener listener, RhinoStep step) {
            super(listener);
            this.step = step;
        }

        @Override
        public Transition run(Map<String, Object> args) throws Exception {
            ArrayList<OrderItem> order = (ArrayList<OrderItem>) args.get("order");
            boolean justAsked = (boolean) args.get("justAsked");

            final int silenceTimeoutSeconds = 5;
            final double volumeThreshold = 0.0001;

            while (isRunning) {
                RhinoInference inference = step.run("Listening for order...");

                // TODO: add timeout support
                if (false) {
                    Map<String, Object> nextArgs = new HashMap<>();
                    nextArgs.put("order", args.get("order"));
                    return new Transition(RecipeStates.SILENT_USER, nextArgs);
                }

                boolean understood = inference != null && inference.getIsUnderstood();
                if (understood && inference.getIntent().equals("addItem")) {
                    Map<String, Object> nextArgs = new HashMap<>();
                    nextArgs.put("order", args.get("order"));
                    nextArgs.put("item", OrderItem.parseAddItemInference(inference));
                    return new Transition(RecipeStates.ADD_ITEM, nextArgs);

                } else if (understood && inference.getIntent().equals("removeItem")) {
                    Map<String, Object> nextArgs = new HashMap<>();
                    nextArgs.put("order", args.get("order"));
                    nextArgs.put("toRemove", OrderItem.parseRemoveItemInference(inference));
                    return new Transition(RecipeStates.REMOVE_ITEM, nextArgs);

                } else if (understood && inference.getIntent().equals("changeItem")) {
                    Pair result = OrderItem.parseChangeItemInference(inference);

                    Map<String, Object> nextArgs = new HashMap<>();
                    nextArgs.put("order", args.get("order"));
                    nextArgs.put("itemFrom", result.first);
                    nextArgs.put("change", result.second);
                    return new Transition(RecipeStates.CHANGE_ITEM, nextArgs);

                } else if (understood && inference.getIntent().equals("startOver")) {
                    Map<String, Object> nextArgs = new HashMap<>();
                    return new Transition(RecipeStates.START_OVER, nextArgs);

                } else if (understood && inference.getIntent().equals("help")) {
                    Map<String, Object> nextArgs = new HashMap<>();
                    nextArgs.put("order", args.get("order"));
                    return new Transition(RecipeStates.HELP, nextArgs);

                } else if (understood && inference.getIntent().equals("repeatOrder")) {
                    Map<String, Object> nextArgs = new HashMap<>();
                    nextArgs.put("order", args.get("order"));
                    nextArgs.put("orderFinalized", false);
                    return new Transition(RecipeStates.REPEAT_ORDER, nextArgs);

                } else if (understood && inference.getIntent().equals("endOrder")) {
                    Map<String, Object> nextArgs = new HashMap<>();
                    nextArgs.put("order", args.get("order"));
                    nextArgs.put("orderFinalized", true);
                    return new Transition(RecipeStates.REPEAT_ORDER, nextArgs);

                } else if (understood && inference.getIntent().equals("confirmation") && justAsked) {
                    Map<String, Object> nextArgs = new HashMap<>();
                    nextArgs.put("order", args.get("order"));
                    nextArgs.put("orderFinalized", true);
                    return new Transition(RecipeStates.REPEAT_ORDER, nextArgs);
                }
            }

            return new Transition(null);
        }
    }

    class AddItemState extends State {
        OrcaStep step;

        public AddItemState(WorkflowListener listener, OrcaStep step) {
            super(listener);
            this.step = step;
        }

        @Override
        public Transition run(Map<String, Object> args) throws Exception {
            ArrayList<OrderItem> order = (ArrayList<OrderItem>) args.get("order");
            OrderItem item = (OrderItem) args.get("item");

            listener.createCard(item.toString());
            String prompt = String.format("Added %s to your order", item.toString());
            step.run(prompt);

            Map<String, Object> nextArgs = new HashMap<>();
            nextArgs.put("order", args.get("order"));
            return new Transition(RecipeStates.LISTEN_FOR_ORDER, nextArgs);
        }
    }

    class RemoveItemState extends State {
        OrcaStep step;

        public RemoveItemState(WorkflowListener listener, OrcaStep step) {
            super(listener);
            this.step = step;
        }

        @Override
        public Transition run(Map<String, Object> args) throws Exception {
            ArrayList<OrderItem> order = new ArrayList<OrderItem>((ArrayList<OrderItem>) args.get("order"));
            OrderItem toRemove = (OrderItem) args.get("toRemove");

            Integer matchIndex = toRemove.findFromEndIn(order);

            if (matchIndex == null) {
                String prompt = String.format("\"%s\" is not in your order.", toRemove.toString());
                step.run(prompt);
            } else {
                String prompt = String.format("Removed \"%s\" from your order.", order.get(matchIndex).toString());

                listener.removeCard(matchIndex);
                order.remove(matchIndex);

                step.run(prompt);
            }

            Map<String, Object> nextArgs = new HashMap<>();
            nextArgs.put("order", order);
            return new Transition(RecipeStates.LISTEN_FOR_ORDER, nextArgs);
        }
    }

    class ChangeItemState extends State {
        OrcaStep step;

        public ChangeItemState(WorkflowListener listener, OrcaStep step) {
            super(listener);
            this.step = step;
        }

        @Override
        public Transition run(Map<String, Object> args) throws Exception {
            ArrayList<OrderItem> order = new ArrayList<OrderItem>((ArrayList<OrderItem>) args.get("order"));
            OrderItem itemFrom = (OrderItem) args.get("itemFrom");
            OrderChange change = (OrderChange) args.get("change");

            Integer matchIndex = null;
            if (itemFrom == null) {
                matchIndex = (order.size() > 0) ? (order.size() - 1) : null;
            } else {
                matchIndex = itemFrom.findFromEndIn(order);
            }

            if (matchIndex == null) {
                String prompt;
                if (itemFrom == null) {
                    prompt = "I couldn't change anything because your order is empty.";
                } else {
                    prompt = String.format(
                            "I couldn't change anything because \"%s\" is not in your order.",
                            itemFrom.toString());
                }
                step.run(prompt);
            } else {
                String oldOrderStr = order.get(matchIndex).toString();

                if (order.get(matchIndex) instanceof ComboItem) {
                    if (change.toCombo != null) {
                        ((ComboItem) order.get(matchIndex)).comboName = change.toCombo;
                    }
                    if (change.toSize != null) {
                        order.get(matchIndex).size = change.toSize;
                    }
                    if (change.toItem != null) {
                        order.get(matchIndex).itemName = change.toItem;
                    }
                } else if (order.get(matchIndex) instanceof MenuItem) {
                    if (change.toCombo != null) {
                        OrderItem prev = order.get(matchIndex);
                        order.set(matchIndex, new ComboItem(
                                prev.size,
                                prev.itemName,
                                prev.quantity,
                                change.toCombo));
                    }

                    if (change.toSize != null) {
                        order.get(matchIndex).size = change.toSize;
                    }

                    if (change.toItem != null) {
                        order.get(matchIndex).itemName = change.toItem;
                    }
                } else {
                    throw new Error(String.format("unknown order item \"%s\"", order.get(matchIndex).toString()));
                }

                String prompt = String.format(
                        "Changing \"%s\" in your order to \"%s\"",
                        oldOrderStr,
                        order.get(matchIndex).toString());
                step.run(prompt);
            }

            Map<String, Object> nextArgs = new HashMap<>();
            nextArgs.put("order", order);
            return new Transition(RecipeStates.LISTEN_FOR_ORDER, nextArgs);
        }
    }

    class StartOverState extends State {
        OrcaStep step;

        public StartOverState(WorkflowListener listener, OrcaStep step) {
            super(listener);
            this.step = step;
        }

        @Override
        public Transition run(Map<String, Object> args) throws Exception {
            step.run("Your order has been reset.");

            Map<String, Object> nextArgs = new HashMap<>();
            nextArgs.put("order", new ArrayList<OrderItem>());
            return new Transition(RecipeStates.STANDBY, nextArgs);
        }
    }

    class HelpState extends State {
        OrcaStep step;

        public HelpState(WorkflowListener listener, OrcaStep step) {
            super(listener);
            this.step = step;
        }

        @Override
        public Transition run(Map<String, Object> args) throws Exception {
            ArrayList<OrderItem> order = (ArrayList<OrderItem>) args.get("order");
            step.run("A staff member has been notified. While help is on the way, you can continue ordering.");

            Map<String, Object> nextArgs = new HashMap<>();
            nextArgs.put("order", order);
            return new Transition(RecipeStates.LISTEN_FOR_ORDER, nextArgs);
        }
    }

    class RepeatOrderState extends State {
        OrcaStep step;

        public RepeatOrderState(WorkflowListener listener, OrcaStep step) {
            super(listener);
            this.step = step;
        }

        @Override
        public Transition run(Map<String, Object> args) throws Exception {
            ArrayList<OrderItem> order = new ArrayList<OrderItem>((ArrayList<OrderItem>) args.get("order"));
            Boolean orderFinalized = (Boolean) args.get("orderFinalized");

            ArrayList<String> promptList = new ArrayList<String>();

            if (orderFinalized != null && orderFinalized) {
                promptList.add("Alright!");
                promptList.add("While we get everything ready, here's what you ordered.");
            } else {
                promptList.add("Here's your order.");
            }

            for (int i = 0; i < order.size(); i++) {
                String prompt = String.format("Item %d. %s", (i + 1), order.get(i).toString());
                promptList.add(prompt);
            }

            if (order.isEmpty()) {
                step.run("Your order is empty. Please add an item.");

                Map<String, Object> nextArgs = new HashMap<>();
                nextArgs.put("order", order);
                return new Transition(RecipeStates.LISTEN_FOR_ORDER, nextArgs);
            }

            for (int i = 0; i < promptList.size(); i++) {
                step.run(promptList.get(i));
            }

            if (orderFinalized != null && orderFinalized) {
                try { Thread.sleep(800); } catch (InterruptedException ignored) {}

                Map<String, Object> nextArgs = new HashMap<>();
                nextArgs.put("order", order);
                return new Transition(RecipeStates.END_ORDER, nextArgs);
            } else {
                Map<String, Object> nextArgs = new HashMap<>();
                nextArgs.put("order", order);
                return new Transition(RecipeStates.LISTEN_FOR_ORDER, nextArgs);
            }
        }
    }

    class SpeakPromptState extends State {
        OrcaStep step;

        public SpeakPromptState(WorkflowListener listener, OrcaStep step) {
            super(listener);
            this.step = step;
        }

        @Override
        public Transition run(Map<String, Object> args) throws Exception {
            ArrayList<OrderItem> order = new ArrayList<OrderItem>((ArrayList<OrderItem>) args.get("order"));
            String prompt = (String) args.get("prompt");

            step.run(prompt);

            Map<String, Object> nextArgs = new HashMap<>();
            nextArgs.put("order", order);
            return new Transition(RecipeStates.LISTEN_FOR_ORDER, nextArgs);
        }
    }

    class SilentUserState extends State {
        OrcaStep step;

        public SilentUserState(WorkflowListener listener, OrcaStep step) {
            super(listener);
            this.step = step;
        }

        @Override
        public Transition run(Map<String, Object> args) throws Exception {
            ArrayList<OrderItem> order = new ArrayList<OrderItem>((ArrayList<OrderItem>) args.get("order"));

            step.run("Is that all? Do you want me to repeat your order?");

            Map<String, Object> nextArgs = new HashMap<>();
            nextArgs.put("order", order);
            nextArgs.put("justAsked", true);
            return new Transition(RecipeStates.LISTEN_FOR_ORDER, nextArgs);
        }
    }

    class EndOrderState extends State {
        OrcaStep step;

        public EndOrderState(WorkflowListener listener, OrcaStep step) {
            super(listener);
            this.step = step;
        }

        @Override
        public Transition run(Map<String, Object> args) throws Exception {
            try { Thread.sleep(400); } catch (InterruptedException ignored) {}

            step.run("Done! Your order is ready.");

            return new Transition(null);
        }
    }

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
            states.put(RecipeStates.STANDBY, new StandbyState(listener, porcupineStep));
            states.put(RecipeStates.LISTEN_FOR_ORDER, new ListenForOrderState(listener, rhinoStep));
            states.put(RecipeStates.ADD_ITEM, new AddItemState(listener, orcaStep));
            states.put(RecipeStates.REMOVE_ITEM, new RemoveItemState(listener, orcaStep));
            states.put(RecipeStates.CHANGE_ITEM, new ChangeItemState(listener, orcaStep));
            states.put(RecipeStates.START_OVER, new StartOverState(listener, orcaStep));
            states.put(RecipeStates.HELP, new HelpState(listener, orcaStep));
            states.put(RecipeStates.REPEAT_ORDER, new RepeatOrderState(listener, orcaStep));
            states.put(RecipeStates.SPEAK_PROMPT, new SpeakPromptState(listener, orcaStep));
            states.put(RecipeStates.SILENT_USER, new SilentUserState(listener, orcaStep));
            states.put(RecipeStates.END_ORDER, new EndOrderState(listener, orcaStep));
        }

        public void run() throws Exception {
            RecipeStates currentState = RecipeStates.STANDBY;
            Map<String, Object> currentArgs = new HashMap<>();

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
            if (rhinoStep != null) {
                rhinoStep.delete();
            }
            if (orcaStep != null) {
                orcaStep.delete();
            }
            if (porcupineStep != null) {
                porcupineStep.delete();
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
