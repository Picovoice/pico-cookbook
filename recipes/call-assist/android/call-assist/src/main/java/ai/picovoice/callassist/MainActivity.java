package ai.picovoice.callassist;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ai.picovoice.android.voiceprocessor.VoiceProcessor;
import ai.picovoice.android.voiceprocessor.VoiceProcessorException;
import ai.picovoice.cheetah.Cheetah;
import ai.picovoice.cheetah.CheetahException;
import ai.picovoice.cheetah.CheetahTranscript;
import ai.picovoice.orca.Orca;
import ai.picovoice.orca.OrcaAudio;
import ai.picovoice.orca.OrcaException;
import ai.picovoice.orca.OrcaSynthesizeParams;
import ai.picovoice.orca.OrcaWord;
import ai.picovoice.picollm.PicoLLM;
import ai.picovoice.picollm.PicoLLMCompletion;
import ai.picovoice.picollm.PicoLLMDialog;
import ai.picovoice.picollm.PicoLLMException;
import ai.picovoice.picollm.PicoLLMGenerateParams;
import ai.picovoice.rhino.Rhino;
import ai.picovoice.rhino.RhinoException;
import ai.picovoice.rhino.RhinoInference;

public class MainActivity extends AppCompatActivity {

    private static final String ACCESS_KEY = "${YOUR_ACCESS_KEY_HERE}";

    private static final String USERNAME = "${YOUR_USERNAME_HERE}";

    private static final String RHN_MODEL_FILE = "rhino_model.rhn";

    private static final String LLM_MODEL_FILE = "picollm_model.pllm";

    private static final String STT_MODEL_FILE = "cheetah_params.pv";

    private static final String TTS_MODEL_FILE = "orca_params_en_female.pv";

    private static final String[] dots = {
        " .  ",
        " .. ",
        " ...",
        "  ..",
        "   .",
        "    "
    };

    private static final String systemPrompt =
        "Extract call information.\n\n" +
        "Return exactly two lines:\n" +
        "caller: <one short value>\n" +
        "reason: <one short value>\n\n" +
        "Rules:\n" +
        "- Use exactly one value for caller.\n" +
        "- Use exactly one value for reason.\n" +
        "- Do not list alternatives.\n" +
        "- Do not use commas.\n" +
        "- Do not explain.\n" +
        "- If the caller says a company or organization, use that as caller.\n" +
        "- If the caller says only a generic role like customer service, use that as caller.\n" +
        "- If the caller does not say who they are, use unknown.\n" +
        "- If the caller does not say why they are calling, use unknown.\n" +
        "- Use lowercase unless the caller gives a proper name.\n\n" +
        "Examples:\n" +
        "Caller said: \"I'm calling from the bank.\"\n" +
        "caller: bank\n" +
        "reason: unknown\n\n" +
        "Caller said: \"This is UPS with a package delivery.\"\n" +
        "caller: UPS\n" +
        "reason: package delivery\n\n" +
        "Caller said: \"This is customer service.\"\n" +
        "caller: customer service\n" +
        "reason: unknown\n\n" +
        "Caller said: \"I'm calling about your credit card.\"\n" +
        "caller: unknown\n" +
        "reason: credit card\n\n" +
        "Caller said: \"Hello, can you hear me?\"\n" +
        "caller: unknown\n" +
        "reason: unknown\n";

    private static final String[] stopPhrases = {
            "<|eot_id|>"
    };

    private enum State {
        IDLE,
        TRANSCRIBE,
        COMMAND,
    };

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final ExecutorService engineExecutor = Executors.newSingleThreadExecutor();

    private final VoiceProcessor voiceProcessor = VoiceProcessor.getInstance();

    private Rhino rhino;

    private Cheetah cheetah;

    private PicoLLM picollm;

    private Orca orca;

    private State currentState = State.IDLE;

    private String activeText = "";

    private String callerTranscript = "";

    private int dotIndex = 0;

    private int retryCount = 0;

    private final int retryLimit = 2;

    private TextView activeTextView = null;

    private LinearLayout loadingLayout;

    private TextView loadingText;

    private ProgressBar loadingProgress;

    private Button startButton;

    private LinearLayout mainLayout;

    private LinearLayout chatLayout;

    private ScrollView scrollArea;

    private LinearLayout chatArea;

    private TextView summaryView;

    private LinearLayout tooltipView;

    private TextView userResponseView;

    private VolumeMeterView volumeMeterView;

    private ProgressBar progressBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        initLayout();
        animateDots();

        voiceProcessor.addFrameListener(this::frameListener);
        engineExecutor.submit(this::initEngines);
    }

    private void initLayout() {
        loadingLayout = findViewById(R.id.loadingLayout);
        loadingText = findViewById(R.id.loadingText);
        loadingProgress = findViewById(R.id.loadingProgress);
        startButton = findViewById(R.id.startButton);

        mainLayout= findViewById(R.id.mainLayout);
        chatLayout = findViewById(R.id.chatLayout);
        scrollArea = findViewById(R.id.scrollArea);
        chatArea = findViewById(R.id.chatArea);
        summaryView = findViewById(R.id.summaryView);
        tooltipView = findViewById(R.id.tooltipView);
        userResponseView = findViewById(R.id.userResponseView);
        volumeMeterView = findViewById(R.id.volumeMeterView);
        progressBar = findViewById(R.id.statusProgress);

        mainLayout.setVisibility(View.GONE);
        startButton.setVisibility(View.GONE);

        startButton.setOnClickListener(v -> startDemo());

        scrollArea.getChildAt(0).getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            int prevHeight = 0;

            @Override
            public void onGlobalLayout() {
                int currentHeight = scrollArea.getChildAt(0).getHeight();
                if (currentHeight != prevHeight) {
                    prevHeight = currentHeight;
                    scrollArea.post(() -> scrollArea.fullScroll(View.FOCUS_DOWN));
                }
            }
        });
    }

    private void startDemo() {
        mainHandler.post(() -> {
            startButton.setEnabled(false);
            chatArea.removeAllViews();
            chatArea.setVisibility(View.VISIBLE);
            summaryView.setVisibility(View.GONE);
            tooltipView.setVisibility(View.GONE);
            userResponseView.setVisibility(View.GONE);
            progressBar.setVisibility(View.GONE);
            volumeMeterView.setVisibility(View.GONE);

            loadingLayout.setAlpha(1f);
            mainLayout.setAlpha(0f);
            mainLayout.setVisibility(View.VISIBLE);

            loadingLayout.animate().alpha(0f).setDuration(400);
            mainLayout.animate().alpha(1f).setDuration(400);

            mainHandler.postDelayed(() -> {
                loadingLayout.setVisibility(View.GONE);
                engineExecutor.submit(this::actionGreet);
            }, 400);
        });
    }

    private void stopDemo() {
        mainHandler.post(() -> {
            summaryView.setVisibility(View.GONE);
            tooltipView.setVisibility(View.GONE);
            userResponseView.setVisibility(View.GONE);
            progressBar.setVisibility(View.GONE);
            volumeMeterView.setVisibility(View.GONE);
        });
        mainHandler.postDelayed(() -> {
            loadingLayout.setAlpha(0f);
            chatLayout.setAlpha(1f);
            loadingLayout.setVisibility(View.VISIBLE);

            loadingLayout.animate().alpha(1f).setDuration(400);
            mainLayout.animate().alpha(0f).setDuration(400);

            mainHandler.postDelayed(() -> {
                mainLayout.setVisibility(View.GONE);
                startButton.setEnabled(true);
            }, 400);
        }, 1600);
    }

    private void setStatus(String text) {
        mainHandler.post(() -> {
            loadingText.setText(text);
        });
    }

    private void setError(String text) {
        mainHandler.post(() -> {
            int colorDanger = getResources().getColor(R.color.colorDanger);
            loadingText.setText(text);
            loadingText.setTextColor(colorDanger);
            startButton.setVisibility(View.GONE);
            progressBar.setVisibility(View.GONE);
            mainLayout.setVisibility(View.GONE);
            loadingLayout.setAlpha(1f);
            loadingLayout.setVisibility(View.VISIBLE);
            loadingLayout.invalidate();
        });
    }

    private void setVolumeMeterState(boolean active) {
        mainHandler.post(() -> {
            if (active) {
                volumeMeterView.setVisibility(View.VISIBLE);
                progressBar.setVisibility(View.GONE);
            } else {
                volumeMeterView.setVisibility(View.GONE);
                progressBar.setVisibility(View.VISIBLE);
            }
        });
    }

    private void animateDots() {
        dotIndex = (dotIndex + 1) % dots.length;
        mainHandler.postDelayed(this::animateDots, 100);

        renderActiveText();
    }

    private void renderActiveText() {
        mainHandler.post(() -> {
            if (activeTextView != null) {
                activeTextView.setText(String.format("%s%s", activeText, dots[dotIndex]));
                activeTextView.setVisibility(View.VISIBLE);
            }
        });
    }

    private void renderCallerTranscript() {
        mainHandler.post(() -> {
            activeText = String.format("[Caller] %s", callerTranscript);
            renderActiveText();
        });
    }

    private void sendText(String text) {
        mainHandler.post(() -> activeText += text);
        renderActiveText();
    }

    private void flushText(TextView nextView) {
        mainHandler.post(() -> {
            if (activeTextView != null) {
                activeTextView.setText(activeText);
            }

            activeText = "";
            activeTextView = nextView;
        });
    }

    private TextView spawnText(String text, int colorId) {
        int color = getResources().getColor(colorId);
        TextView tv = new TextView(getApplicationContext());
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        tv.setText(text);
        tv.setTextColor(color);

        mainHandler.post(() -> {
            chatArea.addView(tv);
        });

        return tv;
    }

    private void initEngines() {
        setStatus("Loading Rhino...");
        try {
            rhino = new Rhino.Builder()
                    .setAccessKey(ACCESS_KEY)
                    .setContextPath(RHN_MODEL_FILE)
                    .build(getApplicationContext());
        } catch (RhinoException e) {
            setError(e.getMessage());
            return;
        }

        setStatus("Loading Cheetah...");
        try {
            cheetah = new Cheetah.Builder()
                    .setAccessKey(ACCESS_KEY)
                    .setModelPath(STT_MODEL_FILE)
                    .setEnableAutomaticPunctuation(true)
                    .build(getApplicationContext());
        } catch (CheetahException e) {
            setError(e.getMessage());
            return;
        }

        setStatus("Loading picoLLM...");
        File llmModelFile = extractModelFile(LLM_MODEL_FILE);
        try {
            picollm = new PicoLLM.Builder()
                    .setAccessKey(ACCESS_KEY)
                    .setModelPath(llmModelFile.getAbsolutePath())
                    .setDevice("cpu:2")
                    .build();
        } catch (PicoLLMException e) {
            setError(e.getMessage());
            return;
        }

        setStatus("Loading Orca...");
        try {
            orca = new Orca.Builder()
                    .setAccessKey(ACCESS_KEY)
                    .setModelPath(TTS_MODEL_FILE)
                    .build(getApplicationContext());
        } catch (OrcaException e) {
            setError(e.getMessage());
            return;
        }

        startRecording();

        setStatus("Press the Start Demo button to begin.");
        mainHandler.post(() -> {
            loadingProgress.setVisibility(View.GONE);
            startButton.setVisibility(View.VISIBLE);
        });
    }

    private File extractModelFile(String filename) {
        File modelFile = new File(getApplicationContext().getFilesDir(), "model.pllm");

        try (InputStream is = getApplicationContext().getAssets().open(filename);
             OutputStream os = new FileOutputStream(modelFile)) {
            byte[] buffer = new byte[8192];
            int numBytesRead;
            while ((numBytesRead = is.read(buffer)) != -1) {
                os.write(buffer, 0, numBytesRead);
            }
        } catch (IOException e) {
            return null;
        }

        return modelFile;
    }

    private void startRecording() {
        if (voiceProcessor.hasRecordAudioPermission(this)) {
            try {
                voiceProcessor.start(cheetah.getFrameLength(), cheetah.getSampleRate());
            } catch (VoiceProcessorException e) {
                setError(e.getMessage());
            }
        } else {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    0);
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
            setError("Recording permission not granted");
        } else {
            startRecording();
        }
    }

    private void frameListener(short[] frame) {
        volumeMeterView.processFrame(frame);

        if (currentState == State.TRANSCRIBE) {
            engineExecutor.submit(() -> listenCaller(frame));
        } else if (currentState == State.COMMAND) {
            engineExecutor.submit(() -> listenUser(frame));
        }
    }

    private void actionGreet() {
        setVolumeMeterState(false);
        String outputText = Action.GREET.prompt(USERNAME);
        TextView speakingView = spawnText("", R.color.colorSecondary);
        flushText(speakingView);

        sendText("[AI] ");
        speak(outputText, () -> {
            flushText(null);
            engineExecutor.submit(this::transcribeCaller);
        });
    }

    private void actionAskForDetails() {
        setVolumeMeterState(false);
        String outputText = Action.ASK_FOR_DETAILS.prompt(USERNAME);
        TextView speakingView = spawnText("", R.color.colorSecondary);
        flushText(speakingView);

        sendText("[AI] ");
        speak(outputText, () -> {
            flushText(null);
            engineExecutor.submit(this::transcribeCaller);
        });
    }

    private void actionTerminal(Action action) {
        setVolumeMeterState(false);
        String outputText = action.prompt(USERNAME);
        TextView speakingView = spawnText("", R.color.colorSecondary);
        flushText(speakingView);

        sendText("[AI] ");
        speak(outputText, () -> {
            flushText(null);
            stopDemo();
        });
    }

    private void transcribeCaller() {
        setVolumeMeterState(true);
        TextView callerView = spawnText("", R.color.colorCaller);
        flushText(callerView);

        sendText("[CALLER] ");
        setVolumeMeterState(true);
        callerTranscript = "";
        currentState = State.TRANSCRIBE;
    }

    private void processCaller() {
        setVolumeMeterState(false);
        flushText(summaryView);
        sendText("[AI] ");

        String transcript = callerTranscript;

        try {
            PicoLLMDialog dialog = picollm.getDialogBuilder()
                    .setSystem(systemPrompt)
                    .build();
            dialog.addHumanRequest(String.format("Caller Said: \"%s\"\n", transcript));
            String prompt = dialog.getPrompt();
            PicoLLMCompletion completion = picollm.generate(
                    prompt,
                    new PicoLLMGenerateParams.Builder()
                            .setStopPhrases(stopPhrases)
                            .setNumTopChoices(1)
                            .build());
            String completionText = completion.getCompletion().strip().replace("<|eot_id|>", "");
            String[] caller_reason = extractCallerReason(completionText);

            engineExecutor.submit(() -> processCallerReason(caller_reason[0], caller_reason[1]));
        } catch (PicoLLMException e) {
            setError(e.getMessage());
        }
    }

    private String[] extractCallerReason(String text) {
        String[] lines = text.split("\n");
        if (lines.length != 2) {
            return new String[] { "unknown", "unknown" };
        }

        if (!lines[0].toLowerCase().startsWith("caller: ")) {
            return new String[] { "unknown", "unknown" };
        }

        if (!lines[1].toLowerCase().startsWith("reason: ")) {
            return new String[] { "unknown", "unknown" };
        }

        String caller = lines[0].substring(8).strip();
        String reason = lines[1].substring(8).strip();

        if (caller.isEmpty() || reason.isEmpty()) {
            return new String[] { "unknown", "unknown" };
        }

        return new String[] { caller, reason };
    }

    private void processCallerReason(String caller, String reason) {
        if (caller.equals("unknown") || reason.equals("unknown")) {
            if (retryCount < retryLimit) {
                String responseText = formatAskForDetails(caller, reason);
                sendText(responseText);

                retryCount += 1;
                engineExecutor.submit(this::actionAskForDetails);
            } else {
                retryCount = 0;
                String outputText = String.format(
                        "Couldn't understand caller's identity and agenda after %d inquiries. Declining their call.",
                        retryLimit);
                speak(outputText, () -> {
                    flushText(null);
                    engineExecutor.submit(() -> actionTerminal(Action.DECLINE_CALL));
                });
            }
        } else {
            String outputText = String.format(
                    "%s is trying to speak to you about %s.",
                    caller,
                    reason);
            speak(outputText, () -> {
                flushText(null);
                setVolumeMeterState(true);
                tooltipView.setVisibility(View.VISIBLE);
                flushText(userResponseView);
                sendText(String.format("[%s]", USERNAME));
                retryCount = 0;
                currentState = State.COMMAND;
            });
        }
    }

    private String formatAskForDetails(String caller, String reason) {
        if (caller.equals("unknown") && reason.equals("unknown")) {
            return "Unknown caller with no specific reason. I will ask for more information.";
        } else if (caller.equals("unknown")) {
            return String.format(
                    "Unknown caller is trying to speak with you about `%s`. I will ask for their identity.",
                    reason);
        } else if (reason.equals("unknown")) {
            return String.format(
                    "`%s` is trying to speak with you. I will ask for their reason.",
                    caller);
        } else {
            return null;
        }
    }

    private void listenCaller(short[] frame) {
        try {
            CheetahTranscript transcript = cheetah.process(frame);
            callerTranscript += transcript.getTranscript();
            renderCallerTranscript();

            if (transcript.getIsEndpoint()) {
                currentState = State.IDLE;
                CheetahTranscript flush = cheetah.flush();
                callerTranscript += flush.getTranscript();
                renderCallerTranscript();

                engineExecutor.submit(this::processCaller);
            }
        } catch (CheetahException e) {
            setError(e.getMessage());
        }
    }

    private void listenUser(short[] frame) {
        try {
            boolean isComplete = rhino.process(frame);
            if (isComplete) {
                RhinoInference inference = rhino.getInference();
                if (inference.getIsUnderstood() && inference.getIntent().equals("chooseAction")) {
                    Action action = Action.fromString(inference.getSlots().get("action"));
                    currentState = State.IDLE;
                    flushText(null);
                    mainHandler.post(() -> {
                        summaryView.setVisibility(View.GONE);
                        tooltipView.setVisibility(View.GONE);
                        userResponseView.setVisibility(View.GONE);
                    });

                    if (action.equals(Action.GREET)) {
                        engineExecutor.submit(this::actionGreet);
                    } else if (action.equals(Action.CONNECT_CALL)) {
                        engineExecutor.submit(() -> actionTerminal(action));
                    } else if (action.equals(Action.DECLINE_CALL)) {
                        engineExecutor.submit(() -> actionTerminal(action));
                    } else if (action.equals(Action.ASK_FOR_DETAILS)) {
                        engineExecutor.submit(this::actionAskForDetails);
                    } else if (action.equals(Action.ASK_TO_TEXT)) {
                        engineExecutor.submit(() -> actionTerminal(action));
                    } else if (action.equals(Action.ASK_TO_EMAIL)) {
                        engineExecutor.submit(() -> actionTerminal(action));
                    } else if (action.equals(Action.ASK_TO_CALL_BACK)) {
                        engineExecutor.submit(() -> actionTerminal(action));
                    } else if (action.equals(Action.BLOCK_CALLER)) {
                        engineExecutor.submit(() -> actionTerminal(action));
                    }
                }
            }
        } catch (RhinoException e) {
            setError(e.getMessage());
        }
    }

    private void speak(String outputText, Runnable after) {
        try {
            OrcaSynthesizeParams params = new OrcaSynthesizeParams.Builder()
                    .build();

            OrcaAudio audio = orca.synthesize(outputText, params);

            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();

            AudioFormat audioFormat = new AudioFormat.Builder()
                    .setSampleRate(orca.getSampleRate())
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build();

            AudioTrack ttsOutput = new AudioTrack(
                    audioAttributes,
                    audioFormat,
                    AudioTrack.getMinBufferSize(
                            orca.getSampleRate(),
                            AudioFormat.CHANNEL_OUT_MONO,
                            AudioFormat.ENCODING_PCM_16BIT),
                    AudioTrack.MODE_STREAM,
                    0);

            ttsOutput.play();

            OrcaWord[] words = audio.getWordArray();
            for (int i = 0; i < words.length; i++) {
                String suffix = "";
                if (i < words.length - 1 && !words[i + 1].getWord().matches("\\p{Punct}")) {
                    suffix = " ";
                }

                String text = words[i].getWord() + suffix;

                mainHandler.postDelayed(() -> {
                    sendText(text);
                }, (long) (words[i].getStartSec() * 1000));
            }

            if (after != null) {
                OrcaWord lastWord = words[words.length - 1];
                mainHandler.postDelayed(
                        after,
                        (long) (lastWord.getEndSec() * 1000));
            }

            short[] pcm = audio.getPcm();
            ttsOutput.write(pcm, 0, pcm.length);

            if (ttsOutput.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                ttsOutput.flush();
                ttsOutput.stop();
            }

            ttsOutput.release();
        } catch (OrcaException e) {
            setError(e.getMessage());
        }
    }
}