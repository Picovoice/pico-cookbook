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

import ai.picovoice.android.voiceprocessor.VoiceProcessor;
import ai.picovoice.porcupine.Porcupine;
import ai.picovoice.rhino.RhinoInference;

import ai.picovoice.foodordering.Steps.OrcaStep;
import ai.picovoice.foodordering.Steps.PorcupineStep;
import ai.picovoice.foodordering.Steps.RhinoStep;
import ai.picovoice.foodordering.Order.OrderChange;
import ai.picovoice.foodordering.Order.OrderItem;
import ai.picovoice.foodordering.Order.MenuItem;
import ai.picovoice.foodordering.Order.ComboItem;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "PICOVOICE";

    private static final String ACCESS_KEY = "${YOUR_ACCESS_KEY_HERE}";
    private static final String KEYWORD_MODEL = "food_ordering_android.ppn";
    private static final String CONTEXT_MODEL = "food_ordering_android.rhn";

    private static final String TTS_MODEL = "orca_params_en_female.pv";

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

                            cards.add(CardUI.create(getLayoutInflater(), reportContainer, title));

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

                            cards.remove(index);
                            reportContainer.removeViewAt(index);

                            if (cards.size() == 0) {
                                toggleEmptyOrderSuggestion();
                            }
                        });
                    }

                    @Override
                    public void updateCard(int index, String title) {
                        runOnUiThread(() -> {
                            CardUI activeCard = cards.get(index);
                            scrollView.smoothScrollTo(0, activeCard.root.getTop());

                            activeCard.valueView.setText(title);
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

                    @Override
                    public void setListeningUI(boolean isListening, String listeningPrompt) {
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

                    @Override
                    public boolean isRunning() {
                        return isRunning;
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

    private void resetReportCards() {
        runOnUiThread(() -> {
            reportContainer.removeAllViews();
            cards.clear();

            emptyOrderSuggestion.setVisibility(View.VISIBLE);
        });
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
            boolean justAsked = ((Boolean) args.get("justAsked")) == null ? false : ((boolean) args.get("justAsked"));

            long[] startTime = { System.currentTimeMillis() };
            final long silenceTimeoutMs = 5000;
            final float volumeThreshold = 0.0001f;

            while (isRunning) {
                RhinoInference inference = step.run(
                        "Listening for order...",
                        (!justAsked) && (order.size() > 0),
                        startTime,
                        silenceTimeoutMs,
                        volumeThreshold);

                if (inference == null && !isRunning) {
                    return new Transition(null);
                } else if (inference == null) {
                    Map<String, Object> nextArgs = new HashMap<>();
                    nextArgs.put("order", order);
                    return new Transition(RecipeStates.SILENT_USER, nextArgs);
                }

                boolean understood = inference != null && inference.getIsUnderstood();
                if (understood && inference.getIntent().equals("addItem")) {
                    Map<String, Object> nextArgs = new HashMap<>();
                    nextArgs.put("order", order);
                    nextArgs.put("item", OrderItem.parseAddItemInference(inference));
                    return new Transition(RecipeStates.ADD_ITEM, nextArgs);

                } else if (understood && inference.getIntent().equals("removeItem")) {
                    Map<String, Object> nextArgs = new HashMap<>();
                    nextArgs.put("order", order);
                    nextArgs.put("toRemove", OrderItem.parseRemoveItemInference(inference));
                    return new Transition(RecipeStates.REMOVE_ITEM, nextArgs);

                } else if (understood && inference.getIntent().equals("changeItem")) {
                    Pair result = OrderItem.parseChangeItemInference(inference);

                    Map<String, Object> nextArgs = new HashMap<>();
                    nextArgs.put("order", order);
                    nextArgs.put("itemFrom", result.first);
                    nextArgs.put("change", result.second);
                    return new Transition(RecipeStates.CHANGE_ITEM, nextArgs);

                } else if (understood && inference.getIntent().equals("startOver")) {
                    Map<String, Object> nextArgs = new HashMap<>();
                    return new Transition(RecipeStates.START_OVER, nextArgs);

                } else if (understood && inference.getIntent().equals("help")) {
                    Map<String, Object> nextArgs = new HashMap<>();
                    nextArgs.put("order", order);
                    return new Transition(RecipeStates.HELP, nextArgs);

                } else if (understood && inference.getIntent().equals("repeatOrder")) {
                    Map<String, Object> nextArgs = new HashMap<>();
                    nextArgs.put("order", order);
                    nextArgs.put("orderFinalized", false);
                    return new Transition(RecipeStates.REPEAT_ORDER, nextArgs);

                } else if (understood && inference.getIntent().equals("endOrder")) {
                    Map<String, Object> nextArgs = new HashMap<>();
                    nextArgs.put("order", order);
                    nextArgs.put("orderFinalized", true);
                    return new Transition(RecipeStates.REPEAT_ORDER, nextArgs);

                } else if (understood && inference.getIntent().equals("confirmation") && justAsked) {
                    Map<String, Object> nextArgs = new HashMap<>();
                    nextArgs.put("order", order);
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
            ArrayList<OrderItem> newOrder = new ArrayList<OrderItem>((ArrayList<OrderItem>) args.get("order"));
            OrderItem item = (OrderItem) args.get("item");

            listener.addCard(item.toString());
            newOrder.add(item);

            String prompt = String.format("Added %s to your order", item.toString());
            String verbalPrompt = String.format("Added %s to your order", item.toPronunciationString());
            listener.onStatusChanged(prompt);
            step.run(verbalPrompt);

            Map<String, Object> nextArgs = new HashMap<>();
            nextArgs.put("order", newOrder);
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
            ArrayList<OrderItem> newOrder = new ArrayList<OrderItem>((ArrayList<OrderItem>) args.get("order"));
            OrderItem toRemove = (OrderItem) args.get("toRemove");

            Integer matchIndex = toRemove.findFromEndIn(newOrder);

            if (matchIndex == null) {
                String prompt = String.format("\"%s\" is not in your order.", toRemove.toString());
                String verbalPrompt = String.format("\"%s\" is not in your order.", toRemove.toPronunciationString());
                listener.onStatusChanged(prompt);
                step.run(verbalPrompt);
            } else {
                String prompt = String.format("Removed \"%s\" from your order.", newOrder.get(matchIndex).toString());
                String verbalPrompt = String.format("Removed \"%s\" from your order.", newOrder.get(matchIndex).toPronunciationString());

                listener.removeCard(matchIndex);
                newOrder.remove((int)matchIndex);

                listener.onStatusChanged(prompt);
                step.run(verbalPrompt);
            }

            Map<String, Object> nextArgs = new HashMap<>();
            nextArgs.put("order", newOrder);
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
            ArrayList<OrderItem> newOrder = new ArrayList<OrderItem>((ArrayList<OrderItem>) args.get("order"));
            OrderItem itemFrom = (OrderItem) args.get("itemFrom");
            OrderChange change = (OrderChange) args.get("change");

            Integer matchIndex = null;
            if (itemFrom == null) {
                matchIndex = (newOrder.size() > 0) ? (newOrder.size() - 1) : null;
            } else {
                matchIndex = itemFrom.findFromEndIn(newOrder);
            }

            if (matchIndex == null) {
                String prompt;
                String verbalPrompt;
                if (itemFrom == null) {
                    prompt = "I couldn't change anything because your order is empty.";
                    verbalPrompt = prompt;
                } else {
                    prompt = String.format(
                            "I couldn't change anything because \"%s\" is not in your order.",
                            itemFrom.toString());
                    verbalPrompt = String.format(
                            "I couldn't change anything because \"%s\" is not in your order.",
                            itemFrom.toPronunciationString());
                }
                listener.onStatusChanged(prompt);
                step.run(verbalPrompt);
            } else {
                String oldOrderStr = newOrder.get(matchIndex).toString();

                if (newOrder.get(matchIndex) instanceof ComboItem) {
                    if (change.toCombo != null) {
                        ((ComboItem) newOrder.get(matchIndex)).comboName = change.toCombo;
                    }
                    if (change.toSize != null) {
                        newOrder.get(matchIndex).size = change.toSize;
                    }
                    if (change.toItem != null) {
                        newOrder.get(matchIndex).itemName = change.toItem;
                    }
                } else if (newOrder.get(matchIndex) instanceof MenuItem) {
                    if (change.toCombo != null) {
                        OrderItem prev = newOrder.get(matchIndex);
                        newOrder.set(matchIndex, new ComboItem(
                                prev.size,
                                prev.itemName,
                                prev.quantity,
                                change.toCombo));
                    }

                    if (change.toSize != null) {
                        newOrder.get(matchIndex).size = change.toSize;
                    }

                    if (change.toItem != null) {
                        newOrder.get(matchIndex).itemName = change.toItem;
                    }
                } else {
                    throw new Error(String.format("unknown order item \"%s\"", newOrder.get(matchIndex).toString()));
                }

                listener.updateCard(matchIndex, newOrder.get(matchIndex).toString());

                String prompt = String.format(
                        "Changing \"%s\" in your order to \"%s\"",
                        oldOrderStr,
                        newOrder.get(matchIndex).toString());
                String verbalPrompt = String.format(
                        "Changing \"%s\" in your order to \"%s\"",
                        oldOrderStr,
                        newOrder.get(matchIndex).toPronunciationString());
                listener.onStatusChanged(prompt);
                step.run(verbalPrompt);
            }

            Map<String, Object> nextArgs = new HashMap<>();
            nextArgs.put("order", newOrder);
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
            resetReportCards();

            String prompt = "Your order has been reset.";
            listener.onStatusChanged(prompt);
            step.run(prompt);

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

            String prompt = "A staff member has been notified. While help is on the way, you can continue ordering.";
            listener.onStatusChanged(prompt);
            step.run(prompt);

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

            ArrayList<String> promptList = new ArrayList<>();

            if (orderFinalized != null && orderFinalized) {
                promptList.add("Alright!");
                promptList.add("While we get everything ready, here's what you ordered.");
            } else {
                promptList.add("Here's your order.");
            }

            ArrayList<String> verbalPromptList = new ArrayList<>(promptList);

            for (int i = 0; i < order.size(); i++) {
                String prompt = String.format("Item %d. %s", (i + 1), order.get(i).toString());
                String verbalPrompt = String.format("Item %d. %s", (i + 1), order.get(i).toPronunciationString());
                promptList.add(prompt);
                verbalPromptList.add(verbalPrompt);
            }

            if (order.isEmpty()) {
                String prompt = "Your order is empty. Please add an item.";
                listener.onStatusChanged(prompt);
                step.run(prompt);

                Map<String, Object> nextArgs = new HashMap<>();
                nextArgs.put("order", order);
                return new Transition(RecipeStates.LISTEN_FOR_ORDER, nextArgs);
            }

            for (int i = 0; i < promptList.size(); i++) {
                String prompt = promptList.get(i);
                String verbalPrompt = verbalPromptList.get(i);
                listener.onStatusChanged(prompt);
                step.run(verbalPrompt);
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

    class SilentUserState extends State {
        OrcaStep step;

        public SilentUserState(WorkflowListener listener, OrcaStep step) {
            super(listener);
            this.step = step;
        }

        @Override
        public Transition run(Map<String, Object> args) throws Exception {
            ArrayList<OrderItem> order = (ArrayList<OrderItem>) args.get("order");

            String prompt = "Is that all? Do you want me to repeat your order?";
            listener.onStatusChanged(prompt);
            step.run(prompt);

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

            String prompt = "Done! Your order is ready.";
            listener.onStatusChanged(prompt);
            step.run(prompt);

            return new Transition(null);
        }
    }

    class Workflow {
        BufferedRecorder recorder;
        PorcupineStep porcupineStep;
        OrcaStep orcaStep;
        RhinoStep rhinoStep;

        Map<RecipeStates, State> states = new HashMap<>();
        private final WorkflowListener listener;

        public Workflow(Context context, WorkflowListener listener) throws Exception {
            this.listener = listener;

            listener.onInitProgress("Loading Porcupine Wake Word...");
            Porcupine porcupine = new Porcupine.Builder()
                    .setAccessKey(ACCESS_KEY)
                    .setKeywordPath(KEYWORD_MODEL)
                    .build(context);

            recorder = new BufferedRecorder(listener, porcupine.getFrameLength(), porcupine.getSampleRate());
            porcupineStep = new PorcupineStep(recorder, listener, porcupine);

            listener.onInitProgress("Loading Orca Text-to-Speech...");
            orcaStep = new OrcaStep(context, recorder, listener, ACCESS_KEY, TTS_MODEL);

            listener.onInitProgress("Loading Rhino Speech-to-Intent...");
            rhinoStep = new RhinoStep(context, recorder, listener, ACCESS_KEY, CONTEXT_MODEL);

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
