/*
    Copyright 2026 Picovoice Inc.

    You may not use this file except in compliance with the license. A copy of the license is
    located in the "LICENSE" file accompanying this source.

    Unless required by applicable law or agreed to in writing, software distributed under the
    License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
    express or implied. See the License for the specific language governing permissions and
    limitations under the License.
*/

package ai.picovoice.selfcheckout;

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
    private static final String KEYWORD_MODEL = "self_checkout_android.ppn";
    private static final String CONTEXT_MODEL = "self_checkout_android.rhn";

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
    private TextView emptyOrderSuggestion;

    private Workflow workflow;
    private volatile boolean isRunning = false;

    private final ArrayList<CardUI> cards = new ArrayList<>();

    interface WorkflowListener {
        void onInitProgress(String status);
        void onStatusChanged(String status);
        void addCard(String title);
        void removeCard(int index);
        void updateCard(int index, String title);
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
        emptyOrderSuggestion = findViewById(R.id.emptyOrderSuggestion);

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
            btnCancel.setText("Cancel Order");

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

    private void toggleEmptyOrderSuggestion() {
        if (emptyOrderSuggestion.getVisibility() == View.VISIBLE) {
            emptyOrderSuggestion.setVisibility(View.GONE);
        } else {
            emptyOrderSuggestion.setVisibility(View.VISIBLE);
        }
    }

    private void preloadDemo() {
        errorView.setVisibility(View.GONE);
        btnStart.setVisibility(View.INVISIBLE);
        startSpinner.setVisibility(View.VISIBLE);

        resetReportCards();

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
                    public void addCard(String title) {
                        runOnUiThread(() -> {
                            if (cards.size() == 0) {
                                toggleEmptyOrderSuggestion();
                            }

                            cards.add(createCard(title));

                            CardUI activeCard = cards.get(cards.size() - 1);
                            View parent = (View) findViewById(R.id.reportContainer).getParent();
                            if (parent instanceof android.widget.ScrollView) {
                                android.widget.ScrollView sv = (android.widget.ScrollView) parent;
                                sv.post(() -> {
                                    sv.smoothScrollTo(0, activeCard.root.getTop() - 32);
                                });
                            }
                        });
                    }

                    @Override
                    public void removeCard(int index) {
                        runOnUiThread(() -> {
                            CardUI activeCard = cards.get(index);
                            scrollView.smoothScrollTo(0, activeCard.root.getTop());

                            runOnUiThread(() -> {
                                cards.remove(index);
                                reportContainer.removeViewAt(index);

                                if (cards.size() == 0) {
                                    toggleEmptyOrderSuggestion();
                                }
                            });
                        });
                    }

                    @Override
                    public void updateCard(int index, String title) {
                        runOnUiThread(() -> {
                            CardUI activeCard = cards.get(index);
                            scrollView.smoothScrollTo(0, activeCard.root.getTop());

                            runOnUiThread(() -> {
                                activeCard.valueView.setText(title);
                            });
                        });
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
        TextView valueView;
    }

    private void resetReportCards() {
        runOnUiThread(() -> {
            reportContainer.removeAllViews();
            cards.clear();

            emptyOrderSuggestion.setVisibility(View.VISIBLE);
        });
    }

    private CardUI createCard(String title) {
        View root;
        root = getLayoutInflater().inflate(R.layout.item_report_card, reportContainer, false);

        CardUI card = new CardUI();
        card.root = root;
        card.leftContainer = root;
        card.valueView = root.findViewById(R.id.cardValue);
        card.valueView.setText(title);

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

        public float volume;
        public float speed;
        public String lastPrompt;

        public OrcaStep(Context context, AINoiseSuppressedRecorder r) throws Exception {
            super(r);
            orca = new Orca.Builder()
                    .setAccessKey(ACCESS_KEY)
                    .setModelPath(TTS_MODEL)
                    .build(context);
            speaker = new PvSpeaker(orca.getSampleRate());

            volume = 1.0;
            speed = 1.0;
            lastPrompt = "There is nothing to repeat.";
        }

        public void run(String prompt) throws Exception {
            OrcaSynthesizeParams params = new OrcaSynthesizeParams.Builder()
                    .setSpeechRate(Math.min(Math.max(self.speed, 0.7f), 1.3f))
                    .build();

            volume = Math.max(Math.min(self.volume, 100.0f), 0.0f);

            OrcaAudio res = orca.synthesize(prompt, params);

            short[] pcm = res.getPcm();
            for (int i = 0; i < pcm.length; i++) {
                int s16Max = (1 << 15) - 1;
                int s16Min = -(1 << 15);
                pcm[i] = (short) Math.max(Math.min((int) (pcm[i] * self.volume), s16Max), s16Min);
            }

            speaker.play(pcm, () -> isRunning);

            while (isRunning && speaker.isPlaying()) {
                Thread.sleep(5);
            }

            speaker.stop();
            lastPrompt = prompt;
        }

        public void repeatLast() {
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

    private static float rms(short[] frame) {
        float total = 0.0f;
        for (short sample : frame) {
            total += (sample / 32768.0f) * (sample / 32768.0f);
        }

        return (float) Math.sqrt(total / frame.length);
    }

    class RhinoStep extends Step {
        private final Rhino rhino;

        public RhinoStep(Context context, AINoiseSuppressedRecorder r) throws Exception {
            super(r);
            rhino = new Rhino.Builder()
                    .setAccessKey(ACCESS_KEY)
                    .setContextPath(CONTEXT_MODEL)
                    .setEndpointDurationSec(0.5f)
                    .setRequireEndpoint(true)
                    .build(context);
        }

        /// Returns null on timeout
        public RhinoInference run(
                String listeningPrompt,
                boolean checkForSilence,
                long[] silenceStart,
                long silenceTimeout,
                float volumeThreshold) throws Exception {
            recorder.start();
            setListeningUI(true, listeningPrompt);

            boolean isFinalized = false;

            if (checkForSilence) {
                long runningSilenceStart = silenceStart[0];

                while (!isFinalized && isRunning) {
                    short[] frame = recorder.read(rhino.getFrameLength());

                    if (frame != null && frame.length == rhino.getFrameLength()) {
                        float volume = rms(frame);
                        if (volume > volumeThreshold) {
                            runningSilenceStart = System.currentTimeMillis();
                        } else if ((System.currentTimeMillis() - runningSilenceStart) > silenceTimeout) {
                            setListeningUI(false, listeningPrompt);
                            recorder.stop();
                            return null;
                        }

                        isFinalized = rhino.process(frame);
                    }
                }

                silenceStart[0] = runningSilenceStart;
            } else {
                while (!isFinalized && isRunning) {
                    short[] frame = recorder.read(rhino.getFrameLength());
                    if (frame != null && frame.length == rhino.getFrameLength()) {
                        isFinalized = rhino.process(frame);
                    }
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

    static class Product {
        String name;
        float price;

        public Product(String name, float price) {
            this.name = name;
            this.price = price;
        }
    }

    ArrayList<Product> SHOPPING_CART = new ArrayList(
        Arrays.asList(
            new Product("Great Value Dark Chocolate Bar, 3.52 oz", 1.00),
            new Product("SunChips Whole Grain Snacks, Original, 7 oz", 3.68),
            new Product("V8 +ENERGY Pomegranate Blueberry Energy Drink, 8 oz Can (Pack of 12)", 9.38),
            new Product("Alcatel Alcatel One Touch Idol 3, 16GB Unlocked Smartphone, Black", 99.47),
            new Product("Impossible Plant Based Ground, Brick, 12oz", 5.96),
            new Product("Fresh Cravings Roasted Red Pepper Hummus 10oz", 2.67),
        )
    );

    final float MAX_ORCA_SPEED = 1.3f;
    final float MIN_ORCA_SPEED = 0.7f;

    final float MAX_VOLUME = 4.0f;
    final float MIN_VOLUME = 0.25f;

    Transition parseAccessibilityIntent(
            String intent,
            OrcaStep orcaStep,
            int nextItemIndex,
            ArrayList<Product> cart,
            State nextNextState,
            Map<String, Object> nextNextArgs) {
        if (intent.equals("speedUp")) {
            if (orcaStep.speed < MAX_ORCA_SPEED) {
                orcaStep.speed = Math.min(orcaStep.speed + 0.3f, MAX_ORCA_SPEED);

                Map<String, Object> nextArgs = new HashMap<>();
                nextArgs.put("nextItemIndex", nextItemIndex);
                nextArgs.put("cart", cart);
                nextArgs.put("prompt", "Voice speed increased.");
                nextArgs.put("nextState", nextNextState);
                nextArgs.put("nextArgs", nextNextArgs);
                return new Transition(RecipeStates.SPEAK_PROMPT, nextArgs);
            } else {
                Map<String, Object> nextArgs = new HashMap<>();
                nextArgs.put("nextItemIndex", nextItemIndex);
                nextArgs.put("cart", cart);
                nextArgs.put("prompt", "Voice speed already at maximum.");
                nextArgs.put("nextState", nextNextState);
                nextArgs.put("nextArgs", nextNextArgs);
                return new Transition(RecipeStates.SPEAK_PROMPT, nextArgs);
            }
        } else if (intent.equals("slowDown")) {
            if (orcaStep.speed > MIN_ORCA_SPEED) {
                orcaStep.speed = Math.max(orca_step.speed - 0.3f, MIN_ORCA_SPEED);

                Map<String, Object> nextArgs = new HashMap<>();
                nextArgs.put("nextItemIndex", nextItemIndex);
                nextArgs.put("cart", cart);
                nextArgs.put("prompt", "Voice speed decreased.");
                nextArgs.put("nextState", nextNextState);
                nextArgs.put("nextArgs", nextNextArgs);
                return new Transition(RecipeStates.SPEAK_PROMPT, nextArgs);
            } else {
                Map<String, Object> nextArgs = new HashMap<>();
                nextArgs.put("nextItemIndex", nextItemIndex);
                nextArgs.put("cart", cart);
                nextArgs.put("prompt", "Voice speed already at minimum.");
                nextArgs.put("nextState", nextNextState);
                nextArgs.put("nextArgs", nextNextArgs);
                return new Transition(RecipeStates.SPEAK_PROMPT, nextArgs);
            }
        } else if (intent.equals("normalSpeed")) {
            orcaStep.speed = 1.0f;

            Map<String, Object> nextArgs = new HashMap<>();
            nextArgs.put("nextItemIndex", nextItemIndex);
            nextArgs.put("cart", cart);
            nextArgs.put("prompt", "Voice speed reset.");
            nextArgs.put("nextState", nextNextState);
            nextArgs.put("nextArgs", nextNextArgs);
            return new Transition(RecipeStates.SPEAK_PROMPT, nextArgs);
        } else if (intent.equals("speakLouder")) {
            if (orcaStep.volume < MAX_VOLUME) {
                orcaStep.volume = Math.min(orcaStep.volume * 2f, MAX_VOLUME);

                Map<String, Object> nextArgs = new HashMap<>();
                nextArgs.put("nextItemIndex", nextItemIndex);
                nextArgs.put("cart", cart);
                nextArgs.put("prompt", "Voice volume increased.");
                nextArgs.put("nextState", nextNextState);
                nextArgs.put("nextArgs", nextNextArgs);
                return new Transition(RecipeStates.SPEAK_PROMPT, nextArgs);
            } else {
                Map<String, Object> nextArgs = new HashMap<>();
                nextArgs.put("nextItemIndex", nextItemIndex);
                nextArgs.put("cart", cart);
                nextArgs.put("prompt", "Voice volume already at maximum.");
                nextArgs.put("nextState", nextNextState);
                nextArgs.put("nextArgs", nextNextArgs);
                return new Transition(RecipeStates.SPEAK_PROMPT, nextArgs);
            }
        } else if (intent.equals("speakQuieter")) {
            if (orcaStep.volume > MIN_VOLUME) {
                orcaStep.volume = Math.max(orcaStep.volume * 0.5f, MIN_VOLUME);

                Map<String, Object> nextArgs = new HashMap<>();
                nextArgs.put("nextItemIndex", nextItemIndex);
                nextArgs.put("cart", cart);
                nextArgs.put("prompt", "Voice volume decreased.");
                nextArgs.put("nextState", nextNextState);
                nextArgs.put("nextArgs", nextNextArgs);
                return new Transition(RecipeStates.SPEAK_PROMPT, nextArgs);
            } else {
                Map<String, Object> nextArgs = new HashMap<>();
                nextArgs.put("nextItemIndex", nextItemIndex);
                nextArgs.put("cart", cart);
                nextArgs.put("prompt", "Voice volume already at minimum.");
                nextArgs.put("nextState", nextNextState);
                nextArgs.put("nextArgs", nextNextArgs);
                return new Transition(RecipeStates.SPEAK_PROMPT, nextArgs);
            }
        } else if (intent.equals("normalVolume")) {
            orcaStep.volume = 1.0;

            Map<String, Object> nextArgs = new HashMap<>();
            nextArgs.put("nextItemIndex", nextItemIndex);
            nextArgs.put("cart", cart);
            nextArgs.put("prompt", "Voice volume reset.");
            nextArgs.put("nextState", nextNextState);
            nextArgs.put("nextArgs", nextNextArgs);
            return new Transition(RecipeStates.SPEAK_PROMPT, nextArgs);

        } else if (intent.equals("repeat")) {
            Map<String, Object> nextArgs = new HashMap<>();
            nextArgs.put("nextItemIndex", nextItemIndex);
            nextArgs.put("cart", cart);
            nextArgs.put("nextState", nextNextState);
            nextArgs.put("nextArgs", nextNextArgs);
            return new Transition(RecipeStates.REPEAT_LAST_PROMPT, nextArgs);

        } else if (intent.equals("help")) {
            Map<String, Object> nextArgs = new HashMap<>();
            nextArgs.put("nextItemIndex", nextItemIndex);
            nextArgs.put("cart", cart);
            nextArgs.put("prompt", "A staff member has been notified and is on their way.");
            nextArgs.put("nextState", nextNextState);
            nextArgs.put("nextArgs", nextNextArgs);
            return new Transition(RecipeStates.SPEAK_PROMPT, nextArgs);
        }

        return null;
    }

    enum RecipeStates {
        STANDBY,
        WELCOME_PROMPT,
        LISTEN_COMMAND,
        SCAN_ITEM_PROMPT,
        DECIDE_ON_BAGGING,
        SELECT_PAYMENT_METHOD,
        LIST_ITEMS_PROMPT,
        REPEAT_LAST_PROMPT,
        SPEAK_PROMPT,
        CHECKOUT_COMPLETE_PROMPT,
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

    class Standby extends State {
        private final PorcupineStep step;

        public StandbyState(WorkflowListener listener, PorcupineStep step) {
            super(listener);
            this.step = step;
        }

        @Override
        public Transition run(Map<String, Object> args) throws Exception {
            step.run("Listening for wake word...");

            Map<String, Object> nextArgs = new HashMap<>();
            nextArgs.put("nextItemIndex", 0);
            nextArgs.put("cart", new ArrayList<Product>());

            return new Transition(RecipeStates.WELCOME_PROMPT, nextArgs);
        }
    }

    class WelcomePrompt extends State {
        OrcaStep step;

        public WelcomePrompt(WorkflowListener listener, OrcaStep step) {
            super(listener);
            this.step = step;
        }

        public Transition run(Map<String, Object> args) throws Exception {
            int nextItemIndex = (int) args.get("nextItemIndex");
            ArrayList<Product> cart = (ArrayList<Product>) args.get("cart");

            step.run("Welcome to Walmart's self-checkout!");
            step.run("I will announce when you scan each item.");
            step.run(
                "If you need me to change my speed, volume, or to repeat myself, " +
                "let me know whenever I'm listening.");

            Map<String, Object> nextArgs = new HashMap<>();
            nextArgs.put("nextItemIndex", nextItemIndex);
            nextArgs.put("cart", cart);
            return new Transition(RecipeStates.LISTEN_COMMAND, nextArgs);
        }
    }

    class ListenCommand extends State {
        RhinoStep step;

        public ListenCommand(WorkflowListener listener, RhinoStep step) {
            super(listener);
            this.step = step;
        }

        @Override
        public Transition run(Map<String, Object> args) throws Exception {
            int nextItemIndex = (int) args.get("nextItemIndex");
            ArrayList<Product> cart = (ArrayList<Product>) args.get("cart");

            setListeningUI(
                    false,
                    "** Scan item **\n"
                    + "** Remove (last item) **\n"
                    + "** (What's) my total **\n"
                    + "** Start over **\n"
                    + "** Checkout (now) **");

            while (isRunning) {
                RhinoInference inference = step.run("Listening for order...", false, { 0 }, 0.0f, 0.0f);

                if (inference == null) {
                    return new Transition(null);
                }

                boolean understood = inference.getIsUnderstood();
                if (understood && inference.getIntent().equals("scanNext")) {
                    if (nextItemIndex < SHOPPING_CART.size()) {
                        Map<String, Object> nextArgs = new HashMap<>();
                        nextArgs.put("nextItemIndex", nextItemIndex);
                        nextArgs.put("cart", cart);
                        return new Transition(RecipeStates.SCAN_ITEM_PROMPT, nextArgs);

                    } else {
                        Map<String, Object> nextArgs = new HashMap<>();
                        nextArgs.put("nextItemIndex", nextItemIndex);
                        nextArgs.put("cart", cart);
                        nextArgs.put("prompt", "You did not scan an item. Are you ready to pay?");
                        nextArgs.put("nextState", RecipeStates.LISTEN_COMMAND);
                        return new Transition(RecipeStates.SPEAK_PROMPT, nextArgs);
                    }

                } else if (understood && inference.getIntent().equals("removeItem")) {
                    if (cart.size() == 0) {
                        Map<String, Object> nextArgs = new HashMap<>();
                        nextArgs.put("nextItemIndex", nextItemIndex);
                        nextArgs.put("cart", cart);
                        nextArgs.put("prompt", "No item to remove. Please start by scanning an item.");
                        nextArgs.put("nextState", RecipeStates.LISTEN_COMMAND);
                        return new Transition(RecipeStates.SPEAK_PROMPT, nextArgs);
                    }

                    ArrayList<Product> newCart = new ArrayList<Product>(cart);
                    newCart.remove(list.size() - 1);

                    Map<String, Object> nextArgs = new HashMap<>();
                    nextArgs.put("nextItemIndex", nextItemIndex - 1);
                    nextArgs.put("cart", newCart);
                    nextArgs.put("prompt", String.format("Removed %s from scanned items.", cart[cart.size() - 1].name));
                    nextArgs.put("nextState", RecipeStates.LISTEN_COMMAND);
                    return new Transition(RecipeStates.SPEAK_PROMPT, nextArgs);

                } else if (understood && inference.getIntent().equals("getTotal")) {
                    float total = 0;
                    for (Product item : cart) {
                        total += item.price;
                    }

                    Map<String, Object> nextArgs = new HashMap<>();
                    nextArgs.put("nextItemIndex", nextItemIndex);
                    nextArgs.put("cart", cart);
                    nextArgs.put("prompt", String.format("Your current total is %.2f.", total));
                    nextArgs.put("nextState", RecipeStates.LISTEN_COMMAND);
                    return new Transition(RecipeStates.SPEAK_PROMPT, nextArgs);

                } else if (understood && inference.getIntent().equals("startOver")) {
                    Map<String, Object> nextArgs = new HashMap<>();
                    nextArgs.put("nextItemIndex", nextItemIndex);
                    nextArgs.put("cart", cart);
                    nextArgs.put("prompt", "Restarting your session.");
                    nextArgs.put("nextState", RecipeStates.STANDBY);
                    return new Transition(RecipeStates.SPEAK_PROMPT, nextArgs);

                } else if (understood && inference.getIntent().equals("payNow")) {
                    if (cart.size() == 0) {
                        Map<String, Object> nextArgs = new HashMap<>();
                        nextArgs.put("nextItemIndex", nextItemIndex);
                        nextArgs.put("cart", cart);
                        nextArgs.put("prompt", "Your cart is empty. Please start by scanning an item.");
                        nextArgs.put("nextState", RecipeStates.LISTEN_COMMAND);
                        return new Transition(RecipeStates.SPEAK_PROMPT, nextArgs);
                    }

                    Map<String, Object> nextArgs = new HashMap<>();
                    nextArgs.put("nextItemIndex", nextItemIndex);
                    nextArgs.put("cart", cart);
                    return new Transition(RecipeStates.DECIDE_ON_BAGGING, nextArgs);

                }

                Transition maybeTransition = parseAccessibilityIntent(
                        inference.getIntent(),
                        this.step,
                        nextItemIndex,
                        cart,
                        RecipeStates.LISTEN_COMMAND);
                if (maybeTransition != null) {
                    return maybeTransition;
                }
            }
        }
    }

    class ScanItemPrompt extends State {
        OrcaStep step;

        public WelcomePrompt(WorkflowListener listener, OrcaStep step) {
            super(listener);
            this.step = step;
        }

        public Transition run(Map<String, Object> args) throws Exception {
            int nextItemIndex = (int) args.get("nextItemIndex");
            ArrayList<Product> cart = (ArrayList<Product>) args.get("cart");

            step.run(String.format("Scanned: %s. Price: %.2f.", item.name, item.price));

            ArrayList<Product> newCart = new ArrayList<Product>(cart);
            newCart.add(SHOPPING_CART.get(nextItemIndex));

            Map<String, Object> nextArgs = new HashMap<>();
            nextArgs.put("nextItemIndex", nextItemIndex + 1);
            nextArgs.put("cart", newCart);
            return new Transition(RecipeStates.LISTEN_COMMAND, nextArgs);
        }
    }

    class DecideOnBagging extends State {
        RhinoStep step;

        public ListenCommand(WorkflowListener listener, RhinoStep step) {
            super(listener);
            this.step = step;
        }

        @Override
        public Transition run(Map<String, Object> args) throws Exception {
            int nextItemIndex = (int) args.get("nextItemIndex");
            ArrayList<Product> cart = (ArrayList<Product>) args.get("cart");
            boolean alreadySpoke = ((Boolean) args.get("alreadySpoke")) == null
                    ? false
                    : ((boolean) args.get("alreadySpoke"));

            if (!alreadySpoke) {
                Map<String, Object> nextArgs = new HashMap<>();
                nextArgs.put("nextItemIndex", nextItemIndex);
                nextArgs.put("cart", cart);
                nextArgs.put("prompt", "Do you need a bag for 50¢?");
                nextArgs.put("nextState", RecipeStates.DECIDE_ON_BAGGING);

                Map<String, Object> nextNextArgs = new HashMap<>();
                nextNextArgs.put("already_spoke", true);
                nextArgs.put("nextArgs", nextNextArgs);

                return new Transition(RecipeStates.SPEAK_PROMPT, nextArgs);
            }

            while (isRunning) {
                RhinoInference inference = step.run("Listening...", false, { 0 }, 0.0f, 0.0f);

                boolean understood = inference.getIsUnderstood();
                if (understood && inference.getIntent().equals("confirmation")) {
                    ArrayList<Product> newCart = new ArrayList<Product>(cart);
                    newCart.add(Product("Plastic bag", 0.5));

                    Map<String, Object> nextArgs = new HashMap<>();
                    nextArgs.put("nextItemIndex", nextItemIndex);
                    nextArgs.put("cart", cart);
                    nextArgs.put("prompt", "A bag has been added to your total.");
                    nextArgs.put("nextState", RecipeStates.LIST_ITEMS_PROMPT);
                    return new Transition(RecipeStates.SPEAK_PROMPT, nextArgs);

                } else if (understood && inference.getIntent().equals("skipBagging")) {
                    Map<String, Object> nextArgs = new HashMap<>();
                    nextArgs.put("nextItemIndex", nextItemIndex);
                    nextArgs.put("cart", cart);
                    return new Transition(RecipeStates.LIST_ITEMS_PROMPT, nextArgs);

                } else if (understood && inference.getIntent().equals("goBack")) {
                    Map<String, Object> nextArgs = new HashMap<>();
                    nextArgs.put("nextItemIndex", nextItemIndex);
                    nextArgs.put("cart", cart);
                    nextArgs.put("prompt", "Going back.");
                    nextArgs.put("nextState", RecipeStates.LISTEN_COMMAND);
                    return new Transition(RecipeStates.SPEAK_PROMPT, nextArgs);
                }

                Transition maybeTransition = parseAccessibilityIntent(
                        inference.getIntent(),
                        this.step,
                        nextItemIndex,
                        cart,
                        RecipeStates.LISTEN_COMMAND);
                if (maybeTransition != null) {
                    return maybeTransition;
                }
            }
        }
    }

    class ListItemsPrompt extends State {
        OrcaStep step;

        public WelcomePrompt(WorkflowListener listener, OrcaStep step) {
            super(listener);
            this.step = step;
        }

        public Transition run(Map<String, Object> args) throws Exception {
            int nextItemIndex = (int) args.get("nextItemIndex");
            ArrayList<Product> cart = (ArrayList<Product>) args.get("cart");

            if (cart.size() == 0) {
                step.run("Your cart is currently empty.");
            } else {
                String plural = cart.size() != 1 ? "s" : "";
                step.run(String.format("Your cart has %d item%s", cart.size(), plural));

                for (int i = 0; in enumerate(cart)) {
                    Product item = cart.get(i);
                    step.run(String.format("Item %d. %s at $%.2f", i+1, item.name, item.price));
                }

                float total = 0;
                for (Product item : cart) {
                    total += item.price;
                }

                step.run(String.format("Running total: $%.2f.", total));
            }

            Map<String, Object> nextArgs = new HashMap<>();
            nextArgs.put("nextItemIndex", nextItemIndex + 1);
            nextArgs.put("cart", newCart);
            return new Transition(RecipeStates.SELECT_PAYMENT_METHOD, nextArgs);
        }
    }

    static final ArrayList<String> PAYMENT_METHODS = new ArrayList<String>(
        Arrays.asList(
            "credit",
            "debit",
            "cash",
            "target circle",
            "apple pay"
        )
    );

    class SelectPaymentMethod extends State {
        RhinoStep step;

        public SelectPaymentMethod(WorkflowListener listener, RhinoStep step) {
            super(listener);
            this.step = step;
        }

        @Override
        public Transition run(Map<String, Object> args) throws Exception {
            int nextItemIndex = (int) args.get("nextItemIndex");
            ArrayList<Product> cart = (ArrayList<Product>) args.get("cart");
            boolean alreadySpoke = ((Boolean) args.get("alreadySpoke")) == null
                    ? false
                    : ((boolean) args.get("alreadySpoke"));

            if (!alreadySpoke) {
                Map<String, Object> nextArgs = new HashMap<>();
                nextArgs.put("nextItemIndex", nextItemIndex);
                nextArgs.put("cart", cart);
                nextArgs.put("prompt", "Please choose a payment method.");
                nextArgs.put("nextState", RecipeStates.DECIDE_ON_BAGGING);

                Map<String, Object> nextNextArgs = new HashMap<>();
                nextNextArgs.put("already_spoke", true);
                nextArgs.put("nextArgs", nextNextArgs);

                return new Transition(RecipeStates.SPEAK_PROMPT, nextArgs);
            }

            // TODO: display a special card with no outline & some options, to denote that this is question
            listener.addOptionCard(PAYMENT_METHODS);

            while (isRunning) {
                RhinoInference inference = step.run("Listening...", false, { 0 }, 0.0f, 0.0f);

                boolean understood = inference.getIsUnderstood();
                if (understood && inference.getIntent().equals("choosePayment")) {
                    ArrayList<Product> newCart = new ArrayList<Product>(cart);
                    newCart.add(Product("Plastic bag", 0.5));

                    String paymentMethod = inference.getSlots().get("payment");
                    String paymentMethodCapitalized = paymentMethod.charAt(0) + paymentMethod.substring(1);

                    Map<String, Object> nextArgs = new HashMap<>();
                    nextArgs.put("nextItemIndex", nextItemIndex);
                    nextArgs.put("cart", cart);
                    nextArgs.put("prompt", String.format("%s selected.", paymentMethodCapitalized));
                    nextArgs.put("nextState", RecipeStates.CHECKOUT_COMPLETE_PROMPT);

                    Map<String, Object> nextNextArgs = new HashMap<>();
                    nextNextArgs.put("checkoutSuccessful", true);

                    return new Transition(RecipeStates.SPEAK_PROMPT, nextArgs);

                } else if (understood && inference.getIntent().equals("goBack")) {
                    Map<String, Object> nextArgs = new HashMap<>();
                    nextArgs.put("nextItemIndex", nextItemIndex);
                    nextArgs.put("cart", cart);
                    nextArgs.put("prompt", "Going back.");
                    nextArgs.put("nextState", RecipeStates.DECIDE_ON_BAGGING);
                    return new Transition(RecipeStates.SPEAK_PROMPT, nextArgs);
                }

                Transition maybeTransition = parseAccessibilityIntent(
                        inference.getIntent(),
                        this.step,
                        nextItemIndex,
                        cart,
                        RecipeStates.LISTEN_COMMAND);
                if (maybeTransition != null) {
                    return maybeTransition;
                }
            }
        }
    }

    class RepeatLastPrompt extends State {
        OrcaStep step;

        public RepeatLastPrompt(WorkflowListener listener, OrcaStep step) {
            super(listener);
            this.step = step;
        }

        public Transition run(Map<String, Object> args) throws Exception {
            int nextItemIndex = (int) args.get("nextItemIndex");
            ArrayList<Product> cart = (ArrayList<Product>) args.get("cart");
            RecipeStates nextState = (RecipeStates) args.get("nextState");
            Map<String, Object> nextArgs = (Map<String, Object>) args.get("nextArgs");

            // TODO: implement this step
            step.repeatLast();

            nextArgs.put("nextItemIndex", nextItemIndex);
            nextArgs.put("cart", cart);
            return new Transition(nextState, nextArgs);
        }
    }

    class CheckoutComplete extends State {
        OrcaStep step;

        public CheckoutComplete(WorkflowListener listener, OrcaStep step) {
            super(listener);
            this.step = step;
        }

        @Override
        public Transition run(Map<String, Object> args) throws Exception {
            ArrayList<Product> cart = (ArrayList<Product>) args.get("cart");
            boolean checkoutSuccessful = ((Boolean) args.get("checkoutSuccessful")) == null
                    ? false
                    : ((boolean) args.get("checkoutSuccessful"));

            if (checkoutSuccessful) {
                float total = 0.0;
                for (Product item : cart) {
                    total += item.price;
                }

                String plural = (cart.size() == 1) ? "" : "s";

                step.run(String.format("Transaction complete. You purchased %d item%s.", cart.size(), plural));
                step.run(String.format("Your total was %.2f.", total));
                step.run("Thank you for shopping with us. Goodbye!");
            } else {
                step.run("Checkout ended.");
            }

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
            states.put(RecipeStates.STANDBY, new Standby(listener, porcupineStep));
            states.put(RecipeStates.WELCOME_PROMPT, new WelcomePrompt(listener, orcaStep));
            states.put(RecipeStates.LISTEN_COMMAND, new ListenCommand(listener, rhinoStep));
            states.put(RecipeStates.SCAN_ITEM_PROMPT, new ScanItemPrompt(listener, orcaStep));
            states.put(RecipeStates.DECIDE_ON_BAGGING, new DecideOnBagging(listener, rhinoStep));
            states.put(RecipeStates.SELECT_PAYMENT_METHOD, new SelectPaymentMethod(listener, rhinoStep));
            states.put(RecipeStates.LIST_ITEMS_PROMPT, new ListItemsPrompt(listener, orcaStep));
            states.put(RecipeStates.REPEAT_LAST_PROMPT, new RepeatLastPrompt(listener, orcaStep));
            states.put(RecipeStates.SPEAK_PROMPT, new SpeakPrompt(listener, orcaStep));
            states.put(RecipeStates.CHECKOUT_COMPLETE_PROMPT, new CheckoutCompletePrompt(listener, orcaStep));
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
