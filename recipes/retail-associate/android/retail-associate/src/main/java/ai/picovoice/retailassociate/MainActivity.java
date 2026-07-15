/*
    Copyright 2026 Picovoice Inc.

    You may not use this file except in compliance with the license. A copy of the license is
    located in the "LICENSE" file accompanying this source.

    Unless required by applicable law or agreed to in writing, software distributed under the
    License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
    express or implied. See the License for the specific language governing permissions and
    limitations under the License.
*/

package ai.picovoice.retailassociate;

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

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import ai.picovoice.android.voiceprocessor.VoiceProcessor;
import ai.picovoice.porcupine.Porcupine;
import ai.picovoice.rhino.RhinoInference;

import ai.picovoice.retailassociate.Steps.OrcaStep;
import ai.picovoice.retailassociate.Steps.PorcupineStep;
import ai.picovoice.retailassociate.Steps.RhinoStep;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "PICOVOICE";

    private static final String ACCESS_KEY = "${YOUR_ACCESS_KEY_HERE}";
    private static final String KEYWORD_MODEL = "retail_associate_android.ppn";
    private static final String CONTEXT_MODEL = "retail_associate_android.rhn";

    private static final String TTS_MODEL = "orca_params_en_female.pv";

    private LinearLayout startScreen, workflowScreen, errorView;
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

        initGlobals();
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

    private void initGlobals() {
        Random random = new Random();
        for (String coworker : COWORKER_LIST) {
            COWORKER_DATA.put(
                coworker,
                new CoworkerData(
                    LOCATION_LIST.get(random.nextInt(LOCATION_LIST.size())),
                    SHIFT_STATUS_LIST.get(random.nextInt(SHIFT_STATUS_LIST.size()))
                )
            );

            if (COWORKER_DATA.get(coworker).shiftStatus == "off duty") {
                COWORKER_DATA.get(coworker).location = "";
            } else if (COWORKER_DATA.get(coworker).shiftStatus == "on break") {
                COWORKER_DATA.get(coworker).location = "the back room";
            }
        }

        for (int i = 0; i < Math.min(Product.PRODUCT_DB.size(), COWORKER_LIST.size()); i++) {
            Product item = Product.PRODUCT_DB.get(i);
            TASK_LIST.add(String.format("Restock %s %s in aisle %d.", item.brand, item.productName, item.aisle));
        }

        for (Map.Entry<String, CoworkerData> kv : COWORKER_DATA.entrySet()) {
            String name = kv.getKey();
            String location = kv.getValue().location;
            TASK_LIST.add(String.format("Check if %s needs help in %s.", name, location));
        }

        Collections.shuffle(TASK_LIST);
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
            btnCancel.setText("End Demo");

            animationContainer.setVisibility(View.INVISIBLE);
        });
    }

    private void preloadDemo() {
        errorView.setVisibility(View.GONE);
        btnStart.setVisibility(View.INVISIBLE);
        startSpinner.setVisibility(View.VISIBLE);

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

    static final Map<String, String> PRONUNCIATION_MAP = new HashMap<String, String>(
        Map.ofEntries(
            Map.entry("Buddig", "bud dig"),
            Map.entry("Kerrygold", "Kerry gold"),
            Map.entry("Marketside", "Market side"),
            Map.entry("Kool-Aid", "cool aid"),
            Map.entry("Rockstar", "Rock star"),
            Map.entry("Fleischmann's", "Flesh men's"),
            Map.entry("Krusteaz", "Crust tea's"),
            Map.entry("Pillsbury", "Pills bury"),
            Map.entry("Gardein", "Guard dean"),
            Map.entry("Hillshire Farm", "Hill shire Farm"),
            Map.entry("Gudu", "Goo do"),
            Map.entry("Tostitos", "Toast eat toes"),
            Map.entry("Bridgford", "Bridge ford"),
            Map.entry("SkinnyPop", "Skinny Pop"),
            Map.entry("Land O'Lakes", "Land Oh Lakes"),
            Map.entry("Coffeemate", "Coffee mate"),
            Map.entry("Yoplait", "Yo plate"),
            Map.entry("Wish-Bone", "Wish Bone"),
            Map.entry("Daiya", "Die yeah"),
            Map.entry("Steak-umm", "Steak umm"),
            Map.entry("DiGiorno", "Di Giorno"),
            Map.entry("Litehouse", "Lighthouse")
        )
    );

    static final ArrayList<String> SHIFT_STATUS_LIST = new ArrayList(
        Arrays.asList("on duty", "on break", "off duty")
    );

    static final ArrayList<String> LOCATION_LIST = new ArrayList(
        Arrays.asList(
            "Produce",
            "Dairy",
            "Frozen",
            "Bakery",
            "Deli",
            "Meat",
            "Electronics",
            "Pharmacy",
            "Apparel",
            "Home and Furniture",
            "Lawn and Garden",
            "Sports and Outdoors",
            "Health and Beauty",
            "Auto Care",
            "Grocery Pickup",
            "Self Checkout",
            "Customer Service",
            "the front"
        )
    );

    static final ArrayList<String> COWORKER_LIST = new ArrayList(
        Arrays.asList(
            "Anya",
            "Chen",
            "Diego",
            "Elena",
            "Jose",
            "Lee",
            "Mohamad",
            "Pablo",
            "Patel",
            "Pepe",
            "Priya",
            "Singh",
            "Tomas",
            "Wei",
            "Wong",
            "Zhang",
            "Jane",
            "Mary",
            "James",
            "John",
            "Ali",
            "Michael",
            "Diana"
        )
    );

    static Map<String, CoworkerData> COWORKER_DATA = new HashMap<String, CoworkerData>();

    class CoworkerData {
        String location;
        String shiftStatus;

        public CoworkerData(String location, String shiftStatus) {
            this.location = location;
            this.shiftStatus = shiftStatus;
        }
    }

    static ArrayList<String> TASK_LIST = new ArrayList();

    ArrayList<Product> getProducts(ArrayList<Product> db, String productName, String brand) {
        ArrayList<Product> items = new ArrayList<Product>();

        for (Product item : db) {
            if (item.lookupName.equals(productName)) {
                if (brand == null || item.lookupBrand == brand) {
                    items.add(item);
                }
            }
        }

        return items;
    }

    Map<String, ArrayList<Product>> getBrandProductBuckets(ArrayList<Product> targetRows) {
        Map<String, ArrayList<Product>> brandProductBuckets = new HashMap<String, ArrayList<Product>>();

        for (Product item : targetRows) {
            String ident = String.format("%s %s", item.brand, item.productName);
            if (!brandProductBuckets.containsKey(ident)) {
                brandProductBuckets.put(ident, new ArrayList<Product>());
            }

            brandProductBuckets.get(ident).add(item);
        }

        return brandProductBuckets;
    }

    String listToSpoken(ArrayList<String> items) {
        String result = "";
        if (items.size() == 1) {
            return items.get(0) + ".";
        } else if (items.size() == 2) {
            return String.format("%s and %s.", items.get(0), items.get(1));
        }

        for (int i = 0; i < items.size(); i++) {
            String item = items.get(i);
            result += item;

            if (i == items.size() - 2) {
                result += ", and ";
            } else if (i != items.size() - 1) {
                result += ", ";
            }
        }

        return result + ".";
    }

    enum RecipeStates {
        STANDBY,
        WELCOME_PROMPT,
        LISTEN_COMMAND,
        SPEAK_PROMPT,
        SHIFT_OVER,
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
            nextArgs.put("nextTaskIndex", 0);
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
            int nextTaskIndex = (int) args.get("nextTaskIndex");

            String prompt = "Walmart retail associate activated.";
            listener.onStatusChanged(prompt);
            step.run(prompt);

            Map<String, Object> nextArgs = new HashMap<>();
            nextArgs.put("nextTaskIndex", nextTaskIndex);
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
            int nextTaskIndex = (int) args.get("nextTaskIndex");

            while (isRunning) {
                RhinoInference inference = step.run("Listening for command", false, new long[]{ 0 }, 0, 0.0f);

                if (inference == null) {
                    return new Transition(null);
                } else if (!inference.getIsUnderstood()) {
                    continue;
                }

                if (inference.getIntent().equals("findProduct")) {
                    String brand = inference.getSlots().get("brand");
                    String product = inference.getSlots().get("product");
                    ArrayList<Product> products = getProducts(Product.PRODUCT_DB, product, brand);
                    Map<String, ArrayList<Product>> brandProductBuckets = getBrandProductBuckets(products);

                    ArrayList<String> promptList = new ArrayList<String>();
                    for (Map.Entry<String, ArrayList<Product>> kv : brandProductBuckets.entrySet()) {
                        String ident = kv.getKey();
                        ArrayList<Product> bucket = kv.getValue();

                        String prompt = String.format("%s is in ", ident);

                        ArrayList<String> items = new ArrayList<String>();
                        for (Product item : bucket) {
                            String plural = (item.stock == 1) ? "" : "s";
                            items.add(
                                    String.format("%s, aisle %d. ", item.department, item.aisle) +
                                    String.format("%d item%s left (at %s)", item.stock, plural, item.size));
                        }

                        promptList.add(prompt + listToSpoken(items));
                    }

                    if (brandProductBuckets.size() == 0) {
                        continue;
                    }

                    Map<String, Object> nextArgs = new HashMap<>();
                    nextArgs.put("nextTaskIndex", nextTaskIndex);
                    nextArgs.put("promptList", promptList);
                    nextArgs.put("nextState", RecipeStates.LISTEN_COMMAND);
                    return new Transition(RecipeStates.SPEAK_PROMPT, nextArgs);

                } else if (inference.getIntent().equals("checkStock")) {
                    String brand = inference.getSlots().get("brand");
                    String product = inference.getSlots().get("product");
                    ArrayList<Product> products = getProducts(Product.PRODUCT_DB, product, brand);

                    String prompt;
                    if (products.size() == 1) {
                        prompt = String.format("We have %d units of ", products.get(0).stock)
                               + String.format("%s %s ", products.get(0).brand, products.get(0).productName)
                               + String.format("(Only in %s) ", products.get(0).size);
                    } else {
                        int total = 0;
                        for (Product item : products) {
                            total += item.stock;
                        }

                        prompt = String.format("We have %d total units of ", total)
                               + String.format("%s ", products.get(0).productName);

                        ArrayList<String> words = new ArrayList<String>();
                        for (Product item : products) {
                            words.add(String.format("(%d items at %s)", item.stock, item.size));
                        }
                        prompt += listToSpoken(words);
                    }

                    Map<String, Object> nextArgs = new HashMap<>();
                    nextArgs.put("nextTaskIndex", nextTaskIndex);
                    nextArgs.put("prompt", prompt);
                    nextArgs.put("nextState", RecipeStates.LISTEN_COMMAND);
                    return new Transition(RecipeStates.SPEAK_PROMPT, nextArgs);

                } else if (inference.getIntent().equals("checkPrice")) {
                    String brand = inference.getSlots().get("brand");
                    String product = inference.getSlots().get("product");
                    ArrayList<Product> products = getProducts(Product.PRODUCT_DB, product, brand);
                    Map<String, ArrayList<Product>> brandProductBuckets = getBrandProductBuckets(products);

                    ArrayList<String> promptList = new ArrayList<String>();
                    for (Map.Entry<String, ArrayList<Product>> kv : brandProductBuckets.entrySet()) {
                        String ident = kv.getKey();
                        ArrayList<Product> bucket = kv.getValue();

                        String prompt = String.format("%s costs ", ident);

                        ArrayList<String> items = new ArrayList<String>();
                        for (Product item : bucket) {
                            items.add(String.format("$%.2f (at %s)", item.price, item.size));
                        }

                        promptList.add(prompt + listToSpoken(items));
                    }

                    Map<String, Object> nextArgs = new HashMap<>();
                    nextArgs.put("nextTaskIndex", nextTaskIndex);
                    nextArgs.put("promptList", promptList);
                    nextArgs.put("nextState", RecipeStates.LISTEN_COMMAND);
                    return new Transition(RecipeStates.SPEAK_PROMPT, nextArgs);

                } else if (inference.getIntent().equals("findAssociate")) {
                    String coworker = inference.getSlots().get("coworker");
                    CoworkerData data = COWORKER_DATA.get(coworker);

                    String prompt;
                    if (data.shiftStatus.equals("off duty")) {
                        prompt = String.format("%s is off duty.", coworker);
                    } else if (data.shiftStatus.equals("on break")) {
                        prompt = String.format("%s is in %s, on break.", coworker, data.location);
                    } else {
                        prompt = String.format("%s is in %s.", coworker, data.location);
                    }

                    Map<String, Object> nextArgs = new HashMap<>();
                    nextArgs.put("nextTaskIndex", nextTaskIndex);
                    nextArgs.put("prompt", prompt);
                    nextArgs.put("nextState", RecipeStates.LISTEN_COMMAND);
                    return new Transition(RecipeStates.SPEAK_PROMPT, nextArgs);

                } else if (inference.getIntent().equals("messageAssociate")) {
                    String coworker = inference.getSlots().get("coworker");
                    String toLocation = inference.getSlots().get("location");
                    String toAisleNumber = inference.getSlots().get("aisleNumber");
                    String toRegisterNumber = inference.getSlots().get("registerNumber");
                    String bringBrand = inference.getSlots().get("brand");
                    String bringProduct = inference.getSlots().get("product");

                    String toString = "";
                    if (toLocation != null) {
                        toString = toLocation;
                    } else if (toAisleNumber != null) {
                        toString = String.format("aisle %s", toAisleNumber);
                    } else if (toRegisterNumber != null) {
                        toString = String.format("register %s", toRegisterNumber);
                    } else {
                        continue;
                    }

                    String initialPrompt;
                    if (bringBrand != null) {
                        assert bringProduct != null;
                        initialPrompt = String.format(
                                "Requesting %s to bring %s %s to %s.",
                                coworker,
                                bringBrand,
                                bringProduct,
                                toString);
                    } else if (bringProduct != null) {
                        initialPrompt = String.format(
                                "Requesting %s to bring any %s to %s.",
                                coworker,
                                bringProduct,
                                toString);
                    } else {
                        initialPrompt = String.format("Requesting %s to come to %s.", coworker, toString);
                    }

                    ArrayList<String> promptList = new ArrayList<String>();
                    promptList.add(initialPrompt);
                    promptList.add("Message sent.");

                    Map<String, Object> nextArgs = new HashMap<>();
                    nextArgs.put("nextTaskIndex", nextTaskIndex);
                    nextArgs.put("promptList", promptList);
                    nextArgs.put("nextState", RecipeStates.LISTEN_COMMAND);
                    return new Transition(RecipeStates.SPEAK_PROMPT, nextArgs);

                } else if (inference.getIntent().equals("callForHelp")) {
                    String toLocation = inference.getSlots().get("location");
                    String toAisleNumber = inference.getSlots().get("aisleNumber");
                    String toRegisterNumber = inference.getSlots().get("registerNumber");

                    String prompt;
                    if (toLocation != null) {
                        prompt = String.format("Requesting for help in %s.", toLocation);
                    } else if (toAisleNumber != null) {
                        prompt = String.format("Requesting for help in aisle %s.", toAisleNumber);
                    } else if (toRegisterNumber != null) {
                        prompt = String.format("Requesting for help at register %s.", toRegisterNumber);
                    } else {
                        continue;
                    }

                    Map<String, Object> nextArgs = new HashMap<>();
                    nextArgs.put("nextTaskIndex", nextTaskIndex);
                    nextArgs.put("prompt", prompt);
                    nextArgs.put("nextState", RecipeStates.LISTEN_COMMAND);
                    return new Transition(RecipeStates.SPEAK_PROMPT, nextArgs);

                } else if (inference.getIntent().equals("getNextTask")) {
                    String prompt;
                    if (nextTaskIndex > TASK_LIST.size()) {
                        prompt = "You have no tasks left.";
                    } else {
                        prompt = "Starting next task: " + TASK_LIST.get(nextTaskIndex);
                    }

                    Map<String, Object> nextArgs = new HashMap<>();
                    nextArgs.put("nextTaskIndex", nextTaskIndex + 1);
                    nextArgs.put("prompt", prompt);
                    nextArgs.put("nextState", RecipeStates.LISTEN_COMMAND);
                    return new Transition(RecipeStates.SPEAK_PROMPT, nextArgs);

                } else if (inference.getIntent().equals("startShift")) {
                    Map<String, Object> nextArgs = new HashMap<>();
                    nextArgs.put("nextTaskIndex", nextTaskIndex);
                    nextArgs.put("prompt", "Status updated to \"on shift\".");
                    nextArgs.put("nextState", RecipeStates.LISTEN_COMMAND);
                    return new Transition(RecipeStates.SPEAK_PROMPT, nextArgs);

                } else if (inference.getIntent().equals("onBreak")) {
                    Map<String, Object> nextArgs = new HashMap<>();
                    nextArgs.put("nextTaskIndex", nextTaskIndex);
                    nextArgs.put("prompt", "Status updated to \"on break\".");
                    nextArgs.put("nextState", RecipeStates.LISTEN_COMMAND);
                    return new Transition(RecipeStates.SPEAK_PROMPT, nextArgs);

                } else if (inference.getIntent().equals("endShift")) {
                    Map<String, Object> nextArgs = new HashMap<>();
                    nextArgs.put("nextTaskIndex", nextTaskIndex);
                    nextArgs.put("prompt", "Status updated to \"completed shift\".");
                    nextArgs.put("nextState", RecipeStates.SHIFT_OVER);
                    return new Transition(RecipeStates.SPEAK_PROMPT, nextArgs);
                }
            }

            return new Transition(null);
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
            int nextTaskIndex = (int) args.get("nextTaskIndex");
            String prompt = (String) args.get("prompt");
            ArrayList<String> promptList = (ArrayList<String>) args.get("promptList");
            RecipeStates nextState = (RecipeStates) args.get("nextState");
            Map<String, Object> nextArgs = (Map<String, Object>) args.get("nextArgs");

            if (prompt != null) {
                listener.onStatusChanged(prompt);
                step.run(prompt);
            }

            if (promptList != null) {
                for (String prompt0 : promptList) {
                    listener.onStatusChanged(prompt0);
                    step.run(prompt0);
                }
            }

            if (nextArgs == null) {
                nextArgs = new HashMap<>();
            }

            nextArgs.put("nextTaskIndex", nextTaskIndex);
            return new Transition(nextState, nextArgs);
        }
    }

    class ShiftOver extends State {
        OrcaStep step;

        public ShiftOver(WorkflowListener listener, OrcaStep step) {
            super(listener);
            this.step = step;
        }

        @Override
        public Transition run(Map<String, Object> args) throws Exception {
            String prompt = "Assistant powering off.";
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
            states.put(RecipeStates.STANDBY, new Standby(listener, porcupineStep));
            states.put(RecipeStates.WELCOME_PROMPT, new WelcomePrompt(listener, orcaStep));
            states.put(RecipeStates.LISTEN_COMMAND, new ListenCommand(listener, rhinoStep));
            states.put(RecipeStates.SPEAK_PROMPT, new SpeakPrompt(listener, orcaStep));
            states.put(RecipeStates.SHIFT_OVER, new ShiftOver(listener, orcaStep));
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
