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
import java.util.HashMap;
import java.util.Map;

import ai.picovoice.android.voiceprocessor.VoiceProcessor;
import ai.picovoice.porcupine.Porcupine;
import ai.picovoice.rhino.RhinoInference;

import ai.picovoice.selfcheckout.Steps.OrcaStep;
import ai.picovoice.selfcheckout.Steps.PorcupineStep;
import ai.picovoice.selfcheckout.Steps.RhinoStep;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "PICOVOICE";

    private static final String ACCESS_KEY = "${YOUR_ACCESS_KEY_HERE}";
    private static final String KEYWORD_MODEL = "self_checkout_android.ppn";
    private static final String CONTEXT_MODEL = "self_checkout_android.rhn";

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

    private void scrollToBottom() {
        CardUI activeCard = cards.get(cards.size() - 1);
        View parent = (View) findViewById(R.id.reportContainer).getParent();
        if (parent instanceof android.widget.ScrollView) {
            android.widget.ScrollView sv = (android.widget.ScrollView) parent;
            sv.post(() -> {
                sv.smoothScrollTo(0, activeCard.root.getTop() - 32);
            });
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
                    public void addCard(String title, String contents) {
                        runOnUiThread(() -> {
                            if (cards.size() == 0) {
                                toggleEmptyOrderSuggestion();
                            }

                            cards.add(CardUI.create(getLayoutInflater(), reportContainer, title, contents));
                            scrollToBottom();
                        });
                    }

                    @Override
                    public void addOptionCard(String title, ArrayList<String> options) {
                        runOnUiThread(() -> {
                            if (cards.size() == 0) {
                                toggleEmptyOrderSuggestion();
                            }

                            cards.add(CardUI.fromOptions(getLayoutInflater(), reportContainer, title, options));
                            scrollToBottom();
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
                    public OrcaStep getOrcaStep() {
                        return workflow.orcaStep;
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
            new Product("Great Value Dark Chocolate Bar, 3.52 oz", 1.00f),
            new Product("SunChips Whole Grain Snacks, Original, 7 oz", 3.68f),
            new Product("V8 +ENERGY Pomegranate Blueberry Energy Drink, 8 oz (Pack of 12)", 9.38f),
            new Product("Alcatel Alcatel One Touch Idol 3, 16GB Unlocked Smartphone, Black", 99.47f),
            new Product("Impossible Plant Based Ground, Brick, 12oz", 5.96f),
            new Product("Fresh Cravings Roasted Red Pepper Hummus 10oz", 2.67f)
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
            RecipeStates nextNextState) {
        Map<String, Object> nextNextArgs = new HashMap<>();
        return parseAccessibilityIntent(intent, orcaStep, nextItemIndex, cart, nextNextState, nextNextArgs);
    }

    Transition parseAccessibilityIntent(
            String intent,
            OrcaStep orcaStep,
            int nextItemIndex,
            ArrayList<Product> cart,
            RecipeStates nextNextState,
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
                orcaStep.speed = Math.max(orcaStep.speed - 0.3f, MIN_ORCA_SPEED);

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
            orcaStep.volume = 1.0f;

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
            nextArgs.put("nextState", RecipeStates.CHECKOUT_COMPLETE_PROMPT);
            nextNextArgs.put("checkoutSuccessful", false);
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

        public Standby(WorkflowListener listener, PorcupineStep step) {
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

            String prompt0 = "Welcome to Walmart's self-checkout!";
            String prompt1 = "If you need me to change my speed, volume, or to repeat myself, " +
                             "let me know whenever I'm listening.";

            listener.onStatusChanged(prompt0);
            step.run(prompt0);
            listener.onStatusChanged(prompt1);
            step.run(prompt1);

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

            String prompt =
                    "Scan item\n"
                    + "Remove (last item)\n"
                    + "(What's) my total\n"
                    + "Start over\n"
                    + "Checkout (now)";

            while (isRunning) {
                RhinoInference inference = step.run(prompt, false, new long[]{ 0 }, 0, 0.0f);

                if (inference == null) {
                    return new Transition(null);
                } else if (!inference.getIsUnderstood()) {
                    continue;
                }

                if (inference.getIntent().equals("scanNext")) {
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

                } else if (inference.getIntent().equals("removeItem")) {
                    if (cart.size() == 0) {
                        Map<String, Object> nextArgs = new HashMap<>();
                        nextArgs.put("nextItemIndex", nextItemIndex);
                        nextArgs.put("cart", cart);
                        nextArgs.put("prompt", "No item to remove. Please start by scanning an item.");
                        nextArgs.put("nextState", RecipeStates.LISTEN_COMMAND);
                        return new Transition(RecipeStates.SPEAK_PROMPT, nextArgs);
                    }

                    ArrayList<Product> newCart = new ArrayList<Product>(cart);
                    listener.removeCard(cart.size() - 1);
                    newCart.remove(cart.size() - 1);

                    Map<String, Object> nextArgs = new HashMap<>();
                    nextArgs.put("nextItemIndex", nextItemIndex - 1);
                    nextArgs.put("cart", newCart);
                    nextArgs.put("prompt", String.format("Removed %s from scanned items.", cart.get(cart.size() - 1).name));
                    nextArgs.put("nextState", RecipeStates.LISTEN_COMMAND);
                    return new Transition(RecipeStates.SPEAK_PROMPT, nextArgs);

                } else if (inference.getIntent().equals("getTotal")) {
                    float total = 0;
                    for (Product item : cart) {
                        total += item.price;
                    }

                    Map<String, Object> nextArgs = new HashMap<>();
                    nextArgs.put("nextItemIndex", nextItemIndex);
                    nextArgs.put("cart", cart);
                    nextArgs.put("prompt", String.format("Your current total is $%.2f.", total));
                    nextArgs.put("nextState", RecipeStates.LISTEN_COMMAND);
                    return new Transition(RecipeStates.SPEAK_PROMPT, nextArgs);

                } else if (inference.getIntent().equals("startOver")) {
                    cart = new ArrayList<Product>();
                    resetReportCards();

                    Map<String, Object> nextArgs = new HashMap<>();
                    nextArgs.put("nextItemIndex", nextItemIndex);
                    nextArgs.put("cart", cart);
                    nextArgs.put("prompt", "Restarting your session.");
                    nextArgs.put("nextState", RecipeStates.STANDBY);
                    return new Transition(RecipeStates.SPEAK_PROMPT, nextArgs);

                } else if (inference.getIntent().equals("payNow")) {
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
                        listener.getOrcaStep(),
                        nextItemIndex,
                        cart,
                        RecipeStates.LISTEN_COMMAND);
                if (maybeTransition != null) {
                    return maybeTransition;
                }
            }

            return new Transition(null);
        }
    }

    class ScanItemPrompt extends State {
        OrcaStep step;

        public ScanItemPrompt(WorkflowListener listener, OrcaStep step) {
            super(listener);
            this.step = step;
        }

        public Transition run(Map<String, Object> args) throws Exception {
            int nextItemIndex = (int) args.get("nextItemIndex");
            ArrayList<Product> cart = (ArrayList<Product>) args.get("cart");

            Product item = SHOPPING_CART.get(nextItemIndex);

            String prompt = String.format("Scanned: %s. Price: $%.2f.", item.name, item.price);
            listener.addCard(String.format("$%.2f", item.price), item.name);
            listener.onStatusChanged(prompt);
            step.run(prompt);

            ArrayList<Product> newCart = new ArrayList<Product>(cart);
            newCart.add(item);

            Map<String, Object> nextArgs = new HashMap<>();
            nextArgs.put("nextItemIndex", nextItemIndex + 1);
            nextArgs.put("cart", newCart);
            return new Transition(RecipeStates.LISTEN_COMMAND, nextArgs);
        }
    }

    class DecideOnBagging extends State {
        RhinoStep step;

        public DecideOnBagging(WorkflowListener listener, RhinoStep step) {
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
                nextNextArgs.put("alreadySpoke", true);
                nextArgs.put("nextArgs", nextNextArgs);

                return new Transition(RecipeStates.SPEAK_PROMPT, nextArgs);
            }

            while (isRunning) {
                RhinoInference inference = step.run("Do you need a bag for 50¢?", false, new long[]{ 0 }, 0, 0.0f);

                if (inference == null) {
                    return new Transition(null);
                } else if (!inference.getIsUnderstood()) {
                    continue;
                }

                if (inference.getIntent().equals("confirmation")) {
                    ArrayList<Product> newCart = new ArrayList<Product>(cart);
                    Product item = new Product("Plastic bag", 0.5f);
                    newCart.add(item);
                    listener.addCard(String.format("$%.2f", item.price), item.name);

                    Map<String, Object> nextArgs = new HashMap<>();
                    nextArgs.put("nextItemIndex", nextItemIndex);
                    nextArgs.put("cart", newCart);
                    nextArgs.put("prompt", "A bag has been added to your total.");
                    nextArgs.put("nextState", RecipeStates.LIST_ITEMS_PROMPT);
                    return new Transition(RecipeStates.SPEAK_PROMPT, nextArgs);

                } else if (inference.getIntent().equals("skipBagging")) {
                    Map<String, Object> nextArgs = new HashMap<>();
                    nextArgs.put("nextItemIndex", nextItemIndex);
                    nextArgs.put("cart", cart);
                    return new Transition(RecipeStates.LIST_ITEMS_PROMPT, nextArgs);

                } else if (inference.getIntent().equals("goBack")) {
                    Map<String, Object> nextArgs = new HashMap<>();
                    nextArgs.put("nextItemIndex", nextItemIndex);
                    nextArgs.put("cart", cart);
                    nextArgs.put("prompt", "Going back.");
                    nextArgs.put("nextState", RecipeStates.LISTEN_COMMAND);
                    return new Transition(RecipeStates.SPEAK_PROMPT, nextArgs);
                }

                Transition maybeTransition = parseAccessibilityIntent(
                        inference.getIntent(),
                        listener.getOrcaStep(),
                        nextItemIndex,
                        cart,
                        RecipeStates.LISTEN_COMMAND);
                if (maybeTransition != null) {
                    return maybeTransition;
                }
            }

            return new Transition(null);
        }
    }

    class ListItemsPrompt extends State {
        OrcaStep step;

        public ListItemsPrompt(WorkflowListener listener, OrcaStep step) {
            super(listener);
            this.step = step;
        }

        public Transition run(Map<String, Object> args) throws Exception {
            int nextItemIndex = (int) args.get("nextItemIndex");
            ArrayList<Product> cart = (ArrayList<Product>) args.get("cart");

            if (cart.size() == 0) {
                String prompt = "Your cart is currently empty.";
                listener.onStatusChanged(prompt);
                step.run(prompt);
            } else {
                String plural = cart.size() != 1 ? "s" : "";
                String prompt0 = String.format("Your cart has %d item%s", cart.size(), plural);
                listener.onStatusChanged(prompt0);
                step.run(prompt0);

                for (int i = 0; i < cart.size(); i++) {
                    Product item = cart.get(i);
                    String prompt = String.format("Item %d. %s at $%.2f", i+1, item.name, item.price);
                    listener.onStatusChanged(prompt);
                    step.run(prompt);
                }

                float total = 0;
                for (Product item : cart) {
                    total += item.price;
                }

                String prompt1 = String.format("Running total: $%.2f.", total);
                listener.onStatusChanged(prompt1);
                step.run(prompt1);
            }

            Map<String, Object> nextArgs = new HashMap<>();
            nextArgs.put("nextItemIndex", nextItemIndex);
            nextArgs.put("cart", cart);
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
                nextArgs.put("nextState", RecipeStates.SELECT_PAYMENT_METHOD);

                Map<String, Object> nextNextArgs = new HashMap<>();
                nextNextArgs.put("alreadySpoke", true);
                nextArgs.put("nextArgs", nextNextArgs);

                return new Transition(RecipeStates.SPEAK_PROMPT, nextArgs);
            }

            listener.addOptionCard("Payment Methods", PAYMENT_METHODS);

            while (isRunning) {
                RhinoInference inference = step.run("Listening for payment method...", false, new long[]{ 0 }, 0, 0.0f);

                if (inference == null) {
                    return new Transition(null);
                } else if (!inference.getIsUnderstood()) {
                    continue;
                }

                if (inference.getIntent().equals("choosePayment")) {
                    String paymentMethod = inference.getSlots().get("payment");
                    String paymentMethodCapitalized = String.valueOf(paymentMethod.charAt(0)).toUpperCase() + paymentMethod.substring(1);

                    Map<String, Object> nextArgs = new HashMap<>();
                    nextArgs.put("nextItemIndex", nextItemIndex);
                    nextArgs.put("cart", cart);
                    nextArgs.put("prompt", String.format("%s selected.", paymentMethodCapitalized));
                    nextArgs.put("nextState", RecipeStates.CHECKOUT_COMPLETE_PROMPT);

                    Map<String, Object> nextNextArgs = new HashMap<>();
                    nextNextArgs.put("checkoutSuccessful", true);
                    nextArgs.put("nextArgs", nextNextArgs);

                    return new Transition(RecipeStates.SPEAK_PROMPT, nextArgs);

                } else if (inference.getIntent().equals("goBack")) {
                    Map<String, Object> nextArgs = new HashMap<>();
                    nextArgs.put("nextItemIndex", nextItemIndex);
                    nextArgs.put("cart", cart);
                    nextArgs.put("prompt", "Going back.");
                    nextArgs.put("nextState", RecipeStates.DECIDE_ON_BAGGING);
                    return new Transition(RecipeStates.SPEAK_PROMPT, nextArgs);
                }

                Transition maybeTransition = parseAccessibilityIntent(
                        inference.getIntent(),
                        listener.getOrcaStep(),
                        nextItemIndex,
                        cart,
                        RecipeStates.LISTEN_COMMAND);
                if (maybeTransition != null) {
                    return maybeTransition;
                }
            }

            return new Transition(null);
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

            step.repeatLast();

            nextArgs.put("nextItemIndex", nextItemIndex);
            nextArgs.put("cart", cart);
            return new Transition(nextState, nextArgs);
        }
    }

    class SpeakPrompt extends State {
        OrcaStep step;

        public SpeakPrompt(WorkflowListener listener, OrcaStep step) {
            super(listener);
            this.step = step;
        }

        @Override
        public Transition run(Map<String, Object> args) throws Exception {
            int nextItemIndex = (int) args.get("nextItemIndex");
            ArrayList<Product> cart = (ArrayList<Product>) args.get("cart");
            String prompt = (String) args.get("prompt");
            RecipeStates nextState = (RecipeStates) args.get("nextState");
            Map<String, Object> nextArgs = (Map<String, Object>) args.get("nextArgs");

            if (nextArgs == null) {
                nextArgs = new HashMap<>();
            }

            listener.onStatusChanged(prompt);
            step.run(prompt);

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
                float total = 0.0f;
                for (Product item : cart) {
                    total += item.price;
                }

                String plural = (cart.size() == 1) ? "" : "s";

                String prompt0 = String.format("Transaction complete. You purchased %d item%s.", cart.size(), plural);
                String prompt1 = String.format("Your total was $%.2f.", total);
                String prompt2 = "Thank you for shopping with us. Goodbye!";

                listener.onStatusChanged(prompt0);
                step.run(prompt0);
                listener.onStatusChanged(prompt1);
                step.run(prompt1);
                listener.onStatusChanged(prompt2);
                step.run(prompt2);
            } else {
                String prompt = "Checkout ended.";
                listener.onStatusChanged(prompt);
                step.run(prompt);
            }

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
            states.put(RecipeStates.STANDBY, new Standby(listener, porcupineStep));
            states.put(RecipeStates.WELCOME_PROMPT, new WelcomePrompt(listener, orcaStep));
            states.put(RecipeStates.LISTEN_COMMAND, new ListenCommand(listener, rhinoStep));
            states.put(RecipeStates.SCAN_ITEM_PROMPT, new ScanItemPrompt(listener, orcaStep));
            states.put(RecipeStates.DECIDE_ON_BAGGING, new DecideOnBagging(listener, rhinoStep));
            states.put(RecipeStates.SELECT_PAYMENT_METHOD, new SelectPaymentMethod(listener, rhinoStep));
            states.put(RecipeStates.LIST_ITEMS_PROMPT, new ListItemsPrompt(listener, orcaStep));
            states.put(RecipeStates.REPEAT_LAST_PROMPT, new RepeatLastPrompt(listener, orcaStep));
            states.put(RecipeStates.SPEAK_PROMPT, new SpeakPrompt(listener, orcaStep));
            states.put(RecipeStates.CHECKOUT_COMPLETE_PROMPT, new CheckoutComplete(listener, orcaStep));
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
