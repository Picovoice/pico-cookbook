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

public class MainActivity extends AppCompatActivity {

    private enum UIState {
        LOADING,
        CALL_SCREEN,
        ERROR
    }

    private enum AppState {
        LISTEN_TO_CALLER,
        SPEAKING_TO_CALLER,
        LISTEN_TO_USER
    }

    // TODO: REPLACE THIS WITH ${YOUR_ACCESS_KEY_HERE}
    private static final String ACCESS_KEY = "${YOUR_ACCESS_KEY_HERE}";

    private static final String STT_MODEL_FILE = "cheetah_params.pv";
    private static final String TTS_MODEL_FILE = "orca_params_female.pv";

    private static final String USERNAME = "Default Username";

    private static final String PUNCTUATION = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~";

    private static final int TTS_WARMUP_SECONDS = 1;

    private final VoiceProcessor voiceProcessor = VoiceProcessor.getInstance();

    // private Rhino rhino;
    private Cheetah cheetah;
    private Orca orca;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final ExecutorService textExecutor = Executors.newSingleThreadExecutor();
    // private final ExecutorService ttsSynthesizeExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService ttsPlaybackExecutor = Executors.newSingleThreadExecutor();

    private AudioTrack ttsOutput;

    private UIState uiState = UIState.LOADING;
    private AppState appState = AppState.SPEAKING_TO_CALLER;

    private ConstraintLayout loadingLayout;
    private ConstraintLayout chatLayout;
    private ConstraintLayout errorLayout;

    private TextView loadingText;

    // TODO: not sure what we'll need for this?
    private TextView chatText;
    private ScrollView chatTextScrollView;
    private TextView stateText;
    private SpannableStringBuilder textBuilder;
    private int spanColour;

    private TextView errorText;

    private String username;
    private Action action = Action.GREET;

    @SuppressLint({"DefaultLocale", "SetTextI18n"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main_layout);

        loadingLayout = findViewById(R.id.loadingLayout);
        chatLayout = findViewById(R.id.chatLayout);
        errorLayout = findViewById(R.id.errorLayout);

        loadingText = findViewById(R.id.loadingText);

        chatText = findViewById(R.id.chatText);
        chatTextScrollView = findViewById(R.id.chatScrollView);
        stateText = findViewById(R.id.stateText);
        spanColour = ContextCompat.getColor(this, R.color.colorPrimary);

        errorText = findViewById(R.id.errorText);

        username = USERNAME;

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

        // TODO: load rhino? -> we don't need it yet

        textBuilder = new SpannableStringBuilder(); // was this double init'd?

        // TODO: how large are frames? should we construct the listener after we get app permissions?
        voiceProcessor.addFrameListener(this::frameListener);
        voiceProcessor.addErrorListener(error -> {
            onEngineProcessError(error.getMessage());
        });

        if (voiceProcessor.hasRecordAudioPermission(this)) {
            updateUIState(UIState.CALL_SCREEN);
            speakToCaller();
        } else {
            requestRecordPermission();
        }
    }

    private void frameListener(short[] frame) {
        if (uiState == UIState.LOADING) {
            return;
        } else if (uiState == UIState.CALL_SCREEN) {
            // listen for user to speak, but only when app state is listening, not when app state is speaking.
            if (appState == AppState.SPEAKING_TO_CALLER) {
                
            } else if (appState == AppState.LISTEN_TO_CALLER) {
                // TODO: this
                /*
                try {
                    CheetahTranscript result = cheetah.process(frame);
                    llmPromptText.append(result.getTranscript());
                    mainHandler.post(() -> {
                        textBuilder.append(result.getTranscript());
                        chatText.setText(textBuilder);
                        chatTextScrollView.fullScroll(ScrollView.FOCUS_DOWN);
                    });

                    if (result.getIsEndpoint()) {
                        CheetahTranscript finalResult = cheetah.flush();
                        llmPromptText.append(finalResult.getTranscript());
                        mainHandler.post(() -> {
                            chatTextBuilder.append(
                                    String.format("%s\n\n", finalResult.getTranscript())
                            );
                            chatText.setText(chatTextBuilder);
                            chatTextScrollView.fullScroll(ScrollView.FOCUS_DOWN);
                        });

                        runLLM(llmPromptText.toString());
                    }
                } catch (CheetahException e) {
                    onEngineProcessError(e.getMessage());
                }
                */
            } else {
                // TODO: this!
            }
        } else if (uiState == UIState.ERROR) {
            return;
        }
    }

    private void onEngineInitError(String message) {
        updateUIState(UIState.ERROR);
        mainHandler.post(() -> errorText.setText(message));
    }

    private void onEngineProcessError(String message) {
        updateUIState(UIState.ERROR);
        mainHandler.post(() -> errorText.setText(message));
    }

    private void speakToCaller() {
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
            OrcaWord[] words = audio.getWordArray();
            for (int i = 0; i < words.length; i++) {
                OrcaWord word = words[i];

                // TODO: is this the correct now? Surely...
                double now_s = (double)System.nanoTime() / 1_000_000_000.0;

                try {
                    Thread.sleep((long)((word.getStartSec() - now_s) * 1000.0));
                } catch (InterruptedException e) {
                    return;
                }

                boolean end_in_space =
                    i == (words.length - 1) ||
                    (words[i+1].getWord().length() == 1 && PUNCTUATION.indexOf(words[i+1].getWord().charAt(0)) != -1);
                String suffix = end_in_space ? " " : "";

                mainHandler.post(() -> {
                    textBuilder.append(word.getWord() + suffix);
                    chatText.setText(textBuilder);
                });
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
                ttsOutput.write(pcm, 0, pcm.length);
            }

            if (ttsOutput.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                ttsOutput.flush();
                ttsOutput.stop();
            }
            ttsOutput.release();
        });

        // TODO: introduce a callback once printing & speaking is done, in order to trigger the listening.

        System.out.println("GABE :: Demo complete for now...\n");
        
        appState = AppState.LISTEN_TO_CALLER;
    }

    private void listenForCaller() {
        try {
            voiceProcessor.start(cheetah.getFrameLength(), cheetah.getSampleRate());
        } catch (VoiceProcessorException e) {
            onEngineProcessError(e.getMessage());
        }
    }

    private void speakToUser() {
        // TODO: implement this
    }

    private void requestRecordPermission() {
        ActivityCompat.requestPermissions(
                this,
                new String[]{Manifest.permission.RECORD_AUDIO},
                0);
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
            updateUIState(UIState.CALL_SCREEN);
            listenForCaller();
        }
    }

    private void updateUIState(UIState state) {
        mainHandler.post(() -> {
            switch (state) {
                case LOADING:
                    loadingLayout.setVisibility(View.VISIBLE);
                    chatLayout.setVisibility(View.INVISIBLE);
                    errorLayout.setVisibility(View.INVISIBLE);

                    loadingText.setText("Loading model...");
                    break;
                case CALL_SCREEN:
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

    @Override
    protected void onDestroy() {
        super.onDestroy();

        textExecutor.shutdownNow();
        // ttsSynthesizeExecutor.shutdownNow();
        ttsPlaybackExecutor.shutdownNow();

        if (cheetah != null) {
            cheetah.delete();
            cheetah = null;
        }

        if (orca != null) {
            orca.delete();
            orca = null;
        }

        // TODO: add rhino

        if (voiceProcessor != null) {
            voiceProcessor.clearFrameListeners();
            voiceProcessor.clearErrorListeners();
        }
    }
}
