/*
    Copyright 2026 Picovoice Inc.

    You may not use this file except in compliance with the license. A copy of the license is
    located in the "LICENSE" file accompanying this source.

    Unless required by applicable law or agreed to in writing, software distributed under the
    License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
    express or implied. See the License for the specific language governing permissions and
    limitations under the License.
*/

package ai.picovoice.callscreen;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.CharacterStyle;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

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
import ai.picovoice.rhino.Rhino;
import ai.picovoice.rhino.RhinoException;
import ai.picovoice.rhino.RhinoInference;

public class MainActivity extends AppCompatActivity {

    private enum UIState {
        BEFORE_DEMO,
        CALL_SCREEN,
        ERROR
    }

    private enum AppState {
        LISTEN_TO_CALLER,
        SPEAKING_TO_CALLER,
        LISTEN_TO_USER
    }

    private static final String ACCESS_KEY = "${YOUR_ACCESS_KEY_HERE}";

    private static final String STT_MODEL_FILE = "cheetah_params.pv";
    private static final String TTS_MODEL_FILE = "orca_params_female.pv";
    // TODO: add instructions that this file must be generated then moved here
    private static final String RHINO_CONTEXT_FILE = "call-screen-demo.rhn";

    private static final String USERNAME = "User";

    private static final String PUNCTUATION = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~";

    private static final int TTS_WARMUP_SECONDS = 1;

    private final VoiceProcessor voiceProcessor = VoiceProcessor.getInstance();

    private Rhino rhino;
    private Cheetah cheetah;
    private Orca orca;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final ExecutorService textExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService ttsPlaybackExecutor = Executors.newSingleThreadExecutor();

    private SpannableTextAnimation animation;

    private AudioTrack ttsOutput;

    private UIState uiState;
    private AppState appState = AppState.SPEAKING_TO_CALLER;

    private ConstraintLayout loadingLayout;
    private ConstraintLayout chatLayout;
    private ConstraintLayout errorLayout;

    private TextView loadingText;
    private Button startButton;

    private TextView callerText;
    private ScrollView callerScrollView;
    private SpannableStringBuilder callerTextBuilder;
    private TextView userText;
    private ScrollView userScrollView;
    private SpannableStringBuilder userTextBuilder;
    private TextView stateText;
    private int spanColour;
    private int callerColour;

    private TextView errorText;

    private String username;

    @SuppressLint({"DefaultLocale", "SetTextI18n"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        mainHandler.post(() -> loadingText.setText("Loading..."));

        super.onCreate(savedInstanceState);
        setContentView(R.layout.main_layout);

        loadingLayout = findViewById(R.id.loadingLayout);
        chatLayout = findViewById(R.id.chatLayout);
        errorLayout = findViewById(R.id.errorLayout);

        loadingText = findViewById(R.id.loadingText);
        startButton = findViewById(R.id.startButton);

        callerScrollView = findViewById(R.id.callerScrollView);
        callerText = findViewById(R.id.callerText);
        userScrollView = findViewById(R.id.userScrollView);
        userText = findViewById(R.id.userText);
        stateText = findViewById(R.id.stateText);
        spanColour = ContextCompat.getColor(this, R.color.colorPrimary);
        callerColour = ContextCompat.getColor(this, R.color.colorCaller);
    
        errorText = findViewById(R.id.errorText);

        if (USERNAME == "${YOUR_USERNAME_HERE}") {
            mainHandler.post(() -> errorText.setText("Invalid username " + USERNAME));
            updateUIState(UIState.ERROR);
            return;
        }
        username = USERNAME;

        updateUIState(UIState.BEFORE_DEMO);

        initEngines();
    }

    private void initEngines() {
        mainHandler.post(() -> loadingText.setText("Loading Cheetah..."));
        try {
            cheetah = new Cheetah.Builder()
                    .setAccessKey(ACCESS_KEY)
                    .setModelPath(STT_MODEL_FILE)
                    .setEnableAutomaticPunctuation(true)
                    .build(getApplicationContext());
        } catch (CheetahException e) {
            onEngineInitError(e.getMessage());
            return;
        }

        mainHandler.post(() -> loadingText.setText("Loading Orca..."));
        try {
            orca = new Orca.Builder()
                    .setAccessKey(ACCESS_KEY)
                    .setModelPath(TTS_MODEL_FILE)
                    .build(getApplicationContext());
        } catch (OrcaException e) {
            onEngineInitError(e.getMessage());
            return;
        }

        mainHandler.post(() -> loadingText.setText("Loading Rhino..."));
        try {
            rhino = new Rhino.Builder()
                    .setAccessKey(ACCESS_KEY)
                    .setContextPath(RHINO_CONTEXT_FILE)
                    .build(getApplicationContext());
        } catch (RhinoException e) {
            onEngineInitError(e.getMessage());
            return;
        }

        callerTextBuilder = new SpannableStringBuilder();
        userTextBuilder = new SpannableStringBuilder();

        mainHandler.post(() -> loadingText.setText("Loading Voice Processor..."));

        if (voiceProcessor.hasRecordAudioPermission(this)) {
            enableStartButton();
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
            onEngineProcessError("Recording permission not granted");
        } else {
            enableStartButton();
        }
    }

    private void enableStartButton() {
        voiceProcessor.addFrameListener(this::frameListener);
        voiceProcessor.addErrorListener(error -> {
            onEngineProcessError(error.getMessage());
        });

        mainHandler.post(() -> loadingText.setText("Press the `Start Demo` button to begin."));
        startButton.setOnClickListener(view -> {
            // TODO: fix double click; this is not working
            view.setEnabled(false);

            mainHandler.post(() -> {
                loadingLayout.setAlpha(1f);
                chatLayout.setAlpha(0f);
                chatLayout.setVisibility(View.VISIBLE);

                loadingLayout.animate().alpha(0f).setDuration(400);
                chatLayout.animate().alpha(1f).setDuration(400);
            });

            try {
                Thread.sleep(400);
            } catch (InterruptedException e) { }

            updateUIState(UIState.CALL_SCREEN);
            speakToCaller(Action.GREET);

            view.setEnabled(true);
        });
    }

    private void frameListener(short[] frame) {
        if (uiState == UIState.CALL_SCREEN && appState == AppState.LISTEN_TO_CALLER) {
            try {
                CheetahTranscript result = cheetah.process(frame);

                appendStyledText(
                        callerTextBuilder,
                        result.getTranscript(),
                        new ForegroundColorSpan(callerColour));

                if (result.getIsEndpoint()) {
                    CheetahTranscript finalResult = cheetah.flush();
                    appendStyledText(
                            callerTextBuilder,
                            finalResult.getTranscript() + "\n",
                            new ForegroundColorSpan(callerColour));

                    animation.end();
                    animation = null;
                    giveUserOptions();
                }
            } catch (CheetahException e) {
                onEngineProcessError(e.getMessage());
            }
        } else if (uiState == UIState.CALL_SCREEN && appState == AppState.LISTEN_TO_USER) {
            try {
                boolean finalized = rhino.process(frame);

                if (finalized) {
                    RhinoInference inference = rhino.getInference();

                    if (inference.getIsUnderstood() && (inference.getIntent().equals("chooseAction"))) {
                        Action action = Action.fromString(inference.getSlots().get("action"));

                        appendStyledText(
                                userTextBuilder,
                                action.toString() + "\n",
                                new ForegroundColorSpan(spanColour));

                        animation.end();
                        animation = null;

                        new Thread(() -> {
                            speakToCaller(action);
                        }).start();
                    } else {
                        appendStyledText(
                                userTextBuilder,
                                "Unknown Action\n" + "[" + username.toUpperCase() + "] ",
                                new ForegroundColorSpan(spanColour));
                    }
                }
            } catch (RhinoException e) {
                onEngineProcessError(e.getMessage());
            }
        } 
    }

    private void onEngineInitError(String message) {
        updateUIState(UIState.ERROR);
        mainHandler.post(() -> errorText.setText("Engine Init error: " + message));
    }

    private void onEngineProcessError(String message) {
        updateUIState(UIState.ERROR);
        mainHandler.post(() -> errorText.setText("Engine Process error: " + message));
    }

    private void speakToCaller(Action action) {
        updateAppState(AppState.SPEAKING_TO_CALLER);

        OrcaAudio audio;
        try {
            audio = orca.synthesize(
                action.prompt(username),
                new OrcaSynthesizeParams.Builder().build());
        } catch (OrcaException e) {
            onEngineProcessError(e.getMessage());
            return;
        }

        textExecutor.submit(() -> {
            double start_s = (double)System.nanoTime() / 1_000_000_000.0;

            mainHandler.post(() -> {
                callerTextBuilder.append("[AI] ");
                callerText.setText(callerTextBuilder);
                callerScrollView.fullScroll(ScrollView.FOCUS_DOWN);
            });

            OrcaWord[] words = audio.getWordArray();
            for (int i = 0; i < words.length; i++) {
                OrcaWord word = words[i];

                double now_s = (double)System.nanoTime() / 1_000_000_000.0 - start_s;
                try {
                    Thread.sleep((long)((word.getStartSec() - now_s) * 1000.0));
                } catch (InterruptedException e) {
                    return;
                }

                boolean no_trailing_space =
                    i == (words.length - 1) ||
                    (words[i+1].getWord().length() == 1 && PUNCTUATION.indexOf(words[i+1].getWord().charAt(0)) != -1);
                String suffix = no_trailing_space ? "" : " ";

                mainHandler.post(() -> {
                    callerTextBuilder.append(word.getWord() + suffix);
                    callerText.setText(callerTextBuilder);
                    callerScrollView.fullScroll(ScrollView.FOCUS_DOWN);
                });
            }

            mainHandler.post(() -> {
                callerTextBuilder.append("\n");
                callerText.setText(callerTextBuilder);
                callerScrollView.fullScroll(ScrollView.FOCUS_DOWN);
            });

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                return;
            }

            if (action.isTerminal()) {
                try {
                    Thread.sleep(700);
                } catch (InterruptedException e) { }

                mainHandler.post(() -> {
                    loadingText.setText("Press the `Start Demo` button if you'd like to try again.");

                    chatLayout.setAlpha(1f);
                    loadingLayout.setAlpha(0f);
                    loadingLayout.setVisibility(View.VISIBLE);

                    chatLayout.animate().alpha(0f).setDuration(500);
                    loadingLayout.animate().alpha(1f).setDuration(500);
                });

                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) { }

                updateUIState(UIState.BEFORE_DEMO);
            } else {
                listenForCaller();
            }
        });

        ttsPlaybackExecutor.submit(() -> {
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
                onEngineProcessError(e.getMessage());
                return;
            }

            short[] pcm = audio.getPcm();
            if (pcm != null && pcm.length > 0 && ttsOutput.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                int written = ttsOutput.write(pcm, 0, pcm.length);
            }

            if (ttsOutput.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                ttsOutput.flush();
                ttsOutput.stop();
            }
            ttsOutput.release();
        });
    }

    private void listenForCaller() {
        animation = new SpannableTextAnimation(callerTextBuilder, callerText, callerScrollView);

        appendStyledText(callerTextBuilder, "[CALLER] ", new ForegroundColorSpan(callerColour));

        mainHandler.post(() -> {
            callerScrollView.fullScroll(ScrollView.FOCUS_DOWN);
        });

        try {
            if (voiceProcessor.getIsRecording()) {
                voiceProcessor.stop();
            }

            voiceProcessor.start(cheetah.getFrameLength(), cheetah.getSampleRate());
        } catch (VoiceProcessorException e) {
            onEngineProcessError(e.getMessage());
        }

        animation.start();

        updateAppState(AppState.LISTEN_TO_CALLER);
    }

    private void giveUserOptions() {
        userTextBuilder.append("[AI] Select one of the call-assist actions below.\n");
        userTextBuilder.append(Action.all());
        appendStyledText(userTextBuilder, "[" + username.toUpperCase() + "] ", new ForegroundColorSpan(spanColour));

        animation = new SpannableTextAnimation(userTextBuilder, userText, userScrollView);

        try {
            if (voiceProcessor.getIsRecording()) {
                voiceProcessor.stop();
            }

            voiceProcessor.start(rhino.getFrameLength(), rhino.getSampleRate());
        } catch (VoiceProcessorException e) {
            onEngineProcessError(e.getMessage());
        }

        animation.start();

        updateAppState(AppState.LISTEN_TO_USER);
    }

    private void appendStyledText(SpannableStringBuilder textBuilder, String text, CharacterStyle span) {
        textBuilder.append(text);
        textBuilder.setSpan(
            span,
            textBuilder.length() - text.length(),
            textBuilder.length(),
            Spannable.SPAN_INCLUSIVE_EXCLUSIVE);
    }

    private void updateUIState(UIState state) {
        mainHandler.post(() -> {
            loadingLayout.setAlpha(1f);
            chatLayout.setAlpha(1f);
            errorLayout.setAlpha(1f);

            switch (state) {
                case BEFORE_DEMO:
                    loadingLayout.setVisibility(View.VISIBLE);
                    chatLayout.setVisibility(View.INVISIBLE);
                    errorLayout.setVisibility(View.INVISIBLE);
                    break;
                case CALL_SCREEN:
                    callerTextBuilder.clear();
                    userTextBuilder.clear();
                    callerText.setText("");
                    userText.setText("");
                    stateText.setText("");

                    loadingLayout.setVisibility(View.INVISIBLE);
                    chatLayout.setVisibility(View.VISIBLE);
                    errorLayout.setVisibility(View.INVISIBLE);
                    break;
                case ERROR:
                    loadingLayout.setVisibility(View.INVISIBLE);
                    chatLayout.setVisibility(View.INVISIBLE);
                    errorLayout.setVisibility(View.VISIBLE);
                    break;
                default:
                    break;
            }

            uiState = state;
        });
    }

    private void updateAppState(AppState state) {
        mainHandler.post(() -> {
            switch (state) {
                case LISTEN_TO_CALLER:
                    stateText.setText("AI: Listening to caller");
                    break;
                case SPEAKING_TO_CALLER:
                    stateText.setText("AI: Speaking to caller");
                    break;
                case LISTEN_TO_USER:
                    stateText.setText("AI: Listening for " + username + "'s command");
                    break;
                default:
                    stateText.setText("AI: Unknown state");
                    break;
            }

            appState = state;
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        textExecutor.shutdownNow();
        ttsPlaybackExecutor.shutdownNow();

        if (animation != null) {
            animation.end();
            animation = null;
        }

        if (cheetah != null) {
            cheetah.delete();
            cheetah = null;
        }

        if (orca != null) {
            orca.delete();
            orca = null;
        }

        if (rhino != null) {
            rhino.delete();
            rhino = null;
        }

        if (voiceProcessor != null) {
            voiceProcessor.clearFrameListeners();
            voiceProcessor.clearErrorListeners();
        }
    }
}
