/*
    Copyright 2026 Picovoice Inc.

    You may not use this file except in compliance with the license. A copy of the license is
    located in the "LICENSE" file accompanying this source.

    Unless required by applicable law or agreed to in writing, software distributed under the
    License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
    express or implied. See the License for the specific language governing permissions and
    limitations under the License.
*/

package ai.picovoice.voicememoassistant;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.app.ActivityCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import ai.picovoice.android.voiceprocessor.VoiceProcessor;
import ai.picovoice.android.voiceprocessor.VoiceProcessorException;
import ai.picovoice.cheetah.Cheetah;
import ai.picovoice.cheetah.CheetahException;
import ai.picovoice.cheetah.CheetahTranscript;
import ai.picovoice.orca.Orca;
import ai.picovoice.orca.OrcaAudio;
import ai.picovoice.orca.OrcaException;
import ai.picovoice.orca.OrcaSynthesizeParams;
import ai.picovoice.picollm.PicoLLM;
import ai.picovoice.picollm.PicoLLMCompletion;
import ai.picovoice.picollm.PicoLLMDialog;
import ai.picovoice.picollm.PicoLLMException;
import ai.picovoice.picollm.PicoLLMGenerateParams;
import ai.picovoice.porcupine.Porcupine;
import ai.picovoice.porcupine.PorcupineException;
import ai.picovoice.rhino.Rhino;
import ai.picovoice.rhino.RhinoException;
import ai.picovoice.rhino.RhinoInference;

public class MainActivity extends AppCompatActivity {

    private enum UIState {
        INIT,
        LOADING_MODEL,
        WAKE_WORD,
        VOICE_COMMAND,
        START_RECORDING,
        READ_RECORDING,
        SUMMARIZE_RECORDING,
        REWRITE_RECORDING,
    }

    private static final String ACCESS_KEY = "${YOUR_ACCESS_KEY_HERE}";

    private static final String PPN_MODEL_FILE = "porcupine_model.ppn";

    private static final String RHN_MODEL_FILE = "rhino_model.rhn";

    private static final String LLM_MODEL_FILE = "picollm_model.pllm";

    private static final String STT_MODEL_FILE = "cheetah_params.pv";

    private static final String TTS_MODEL_FILE = "orca_params_en_female.pv";


    private static final String STOP_PHRASE = "<|eot_id|>";

    private static final String NO_MEMO_ERROR_PHRASE = "You need to record a memo first.";

    private final VoiceProcessor voiceProcessor = VoiceProcessor.getInstance();

    private Porcupine porcupine;

    private Rhino rhino;

    private Cheetah cheetah;
    private PicoLLM picollm;
    private Orca orca;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final ExecutorService engineExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService ttsSynthesizeExecutor = Executors.newSingleThreadExecutor();

    private AudioTrack ttsOutput;

    private UIState currentState = UIState.INIT;

    private StringBuilder memoText = new StringBuilder();

    private String enhancedText = "";

    private ConstraintLayout loadModelLayout;
    private ConstraintLayout demoLayout;

    private TextView loadModelText;
    private ProgressBar loadModelProgress;

    private TextView originalTextView;

    private TextView modifiedTextViewTitle;
    private LinearLayout modifiedLinearLayout;
    private TextView modifiedTextView;

    private TextView tooltipTextView;

    private TextView statusText;

    private ProgressBar statusProgress;

    private VolumeMeterView volumeMeterView;

    @SuppressLint({"DefaultLocale", "SetTextI18n"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main_layout);

        loadModelLayout = findViewById(R.id.loadModelLayout);
        demoLayout = findViewById(R.id.demoLayout);

        loadModelText = findViewById(R.id.loadModelText);
        loadModelProgress = findViewById(R.id.loadModelProgress);

        updateUIState(UIState.INIT);

        originalTextView = findViewById(R.id.originalTextView);

        modifiedTextViewTitle = findViewById(R.id.modifiedTextViewTitle);
        modifiedLinearLayout = findViewById(R.id.modifiedMemoLayout);
        modifiedTextView = findViewById(R.id.modifiedTextView);

        statusText = findViewById(R.id.statusText);
        statusProgress = findViewById(R.id.statusProgress);

        volumeMeterView = findViewById(R.id.volumeMeterView);

        tooltipTextView = findViewById(R.id.tooltipTextView);

        engineExecutor.submit(this::initEngines);
    }

    private void initEngines() {
        updateUIState(UIState.LOADING_MODEL);

        mainHandler.post(() -> loadModelText.setText("Loading Porcupine..."));
        try {
            porcupine = new Porcupine.Builder()
                    .setAccessKey(ACCESS_KEY)
                    .setKeywordPath(PPN_MODEL_FILE)
                    .build(getApplicationContext());
        } catch (PorcupineException e) {
            onEngineInitError(e.getMessage());
            return;
        }

        mainHandler.post(() -> loadModelText.setText("Loading Rhino..."));
        try {
            rhino = new Rhino.Builder()
                    .setAccessKey(ACCESS_KEY)
                    .setContextPath(RHN_MODEL_FILE)
                    .build(getApplicationContext());
        } catch (RhinoException e) {
            onEngineInitError(e.getMessage());
            return;
        }

        mainHandler.post(() -> loadModelText.setText("Loading Cheetah..."));
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

        mainHandler.post(() -> loadModelText.setText("Loading picoLLM..."));
        File llmModelFile = extractModelFile(LLM_MODEL_FILE);
        try {
            picollm = new PicoLLM.Builder()
                    .setAccessKey(ACCESS_KEY)
                    .setModelPath(llmModelFile.getAbsolutePath())
                    .build();
        } catch (PicoLLMException e) {
            onEngineInitError(e.getMessage());
            return;
        }

        mainHandler.post(() -> loadModelText.setText("Loading Orca..."));
        try {
            orca = new Orca.Builder()
                    .setAccessKey(ACCESS_KEY)
                    .setModelPath(TTS_MODEL_FILE)
                    .build(getApplicationContext());
        } catch (OrcaException e) {
            onEngineInitError(e.getMessage());
            return;
        }

        updateUIState(UIState.WAKE_WORD);

        voiceProcessor.addFrameListener(this::runWakeWordSTT);

        voiceProcessor.addErrorListener(error -> {
            onEngineProcessError(error.getMessage());
        });

        startWakeWordListening();
    }

    private void runWakeWordSTT(short[] frame) {
        volumeMeterView.processFrame(frame);

        if (currentState == UIState.WAKE_WORD) {
            try {
                int keywordIndex = porcupine.process(frame);
                if (keywordIndex == 0) {
                    interrupt();

                    updateUIState(UIState.VOICE_COMMAND);
                }
            } catch (PorcupineException e) {
                onEngineProcessError(e.getMessage());
            }
        } else if (currentState == UIState.VOICE_COMMAND) {
            try {
                boolean isComplete = rhino.process(frame);
                if (isComplete) {
                    RhinoInference inference = rhino.getInference();
                    if (inference.getIsUnderstood()) {
                        if (Objects.equals(inference.getIntent(), "startRecording")) {
                            memoText = new StringBuilder();
                            enhancedText = "";
                            updateUIState(UIState.START_RECORDING);
                        } else if (Objects.equals(inference.getIntent(), "readRecording")) {
                            updateUIState(UIState.READ_RECORDING);
                            String text = !enhancedText.isEmpty() ? enhancedText : NO_MEMO_ERROR_PHRASE;
                            synthesizeAndPlayback(text);
                        } else if (Objects.equals(inference.getIntent(), "summarizeRecording")) {
                            updateUIState(UIState.SUMMARIZE_RECORDING);
                            summerizeMemo();
                        } else if (Objects.equals(inference.getIntent(), "rewriteRecording")) {
                            updateUIState(UIState.REWRITE_RECORDING);
                            rewriteMemo();
                        }
                    } else {
                        synthesizeAndPlayback("Sorry, I didn't understand that. Please try again.");
                        updateUIState(UIState.WAKE_WORD);
                    }
                }
            } catch (RhinoException e) {
                onEngineProcessError(e.getMessage());
            }
        } else if (currentState == UIState.START_RECORDING) {
            try {
                CheetahTranscript result = cheetah.process(frame);
                memoText.append(result.getTranscript());
                mainHandler.post(() -> {
                    originalTextView.setText(memoText);
                });

                if (result.getIsEndpoint()) {
                    CheetahTranscript finalResult = cheetah.flush();
                    memoText.append(finalResult.getTranscript());
                    memoText.append(" ");
                    mainHandler.post(() -> {
                        originalTextView.setText(memoText);
                    });
                }

                if (Pattern.matches(".*(stop recording)[.\\s]*$", memoText.toString().toLowerCase())) {
                    memoText.delete(memoText.length() - "stop recording.".length() - 1, memoText.length() - 1);
                    mainHandler.post(() -> {
                        originalTextView.setText(memoText);
                    });
                    enhancedText = memoText.toString();
                    updateUIState(UIState.WAKE_WORD);
                }
            } catch (CheetahException e) {
                onEngineProcessError(e.getMessage());
            }
        }
    }

    private void synthesizeAndPlayback(String text) {
        ttsSynthesizeExecutor.submit(() -> {
            try {
                OrcaAudio audio = orca.synthesize(text, new OrcaSynthesizeParams.Builder().build());

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

                ttsOutput.write(audio.getPcm(), 0, audio.getPcm().length);

                ttsOutput.stop();
                ttsOutput.release();

                updateUIState(UIState.WAKE_WORD);
            } catch (Exception e) {
                onEngineProcessError(e.getMessage());
            }
        });
    }

    private void summerizeMemo() {
        if (memoText.length() == 0) {
            synthesizeAndPlayback(NO_MEMO_ERROR_PHRASE);
            return;
        }

        engineExecutor.submit(() -> {
            try {
                PicoLLMDialog dialog = picollm.getDialogBuilder().build();
                dialog.addHumanRequest(new StringBuilder()
                        .append("Summarize the memo below. Return only the summary.\n")
                        .append("Rules:\n")
                        .append("- Do not say \"Here is the summarized memo\".\n")
                        .append("- Do not add any prefix, label, intro, explanation, or quotes.\n")
                        .append("- Do not explain your changes.\n")
                        .append("- Keep the important details.\n")
                        .append("- Fix obvious transcription errors only when the meaning is clear.\n")
                        .append("- Do not add new information.\n")
                        .append("- Use one short sentence.\n")
                        .append("\n")
                        .append("Memo:\n")
                        .append(memoText.toString())
                        .append("\n\n")
                        .append("Summarized memo:\n")
                        .toString());

                PicoLLMCompletion completion = picollm.generate(
                        dialog.getPrompt(),
                        new PicoLLMGenerateParams.Builder()
                                .setStopPhrases(new String[]{STOP_PHRASE})
                                .setStreamCallback(token -> {
                                    runOnUiThread(() -> {
                                        modifiedTextView.setText(modifiedTextView.getText() + token);
                                    });
                                })
                                .build());

                enhancedText = completion.getCompletion().replace(STOP_PHRASE, "");
                mainHandler.post(() -> {
                    modifiedTextView.setText(enhancedText);
                });
                updateUIState(UIState.WAKE_WORD);
            } catch (PicoLLMException e) {
                onEngineProcessError(e.getMessage());
            }
        });
    }

    private void rewriteMemo() {
        if (memoText.length() == 0) {
            synthesizeAndPlayback(NO_MEMO_ERROR_PHRASE);
            return;
        }

        engineExecutor.submit(() -> {
            try {
                PicoLLMDialog dialog = picollm.getDialogBuilder().build();
                dialog.addHumanRequest(new StringBuilder()
                        .append("Rewrite the memo below. Return only the rewritten memo.\n")
                        .append("Rules:\n")
                        .append("- Do not say \"Here is the rewritten memo\".\n")
                        .append("- Do not add any prefix, label, intro, explanation, or quotes.\n")
                        .append("- Do not explain your changes.\n")
                        .append("- Fix grammar, punctuation, casing, repeated words, filler words, and false starts.\n")
                        .append("- Preserve the original meaning.\n")
                        .append("- Do not summarize.\n")
                        .append("- Do not add new information.\n")
                        .append("\n")
                        .append("Memo:\n")
                        .append(memoText.toString())
                        .append("\n\n")
                        .append("Rewritten memo:\n")
                        .toString());

                PicoLLMCompletion completion = picollm.generate(
                        dialog.getPrompt(),
                        new PicoLLMGenerateParams.Builder()
                                .setStopPhrases(new String[]{STOP_PHRASE})
                                .setStreamCallback(token -> {
                                    runOnUiThread(() -> {
                                        modifiedTextView.setText(modifiedTextView.getText() + token);
                                    });
                                })
                                .build());

                enhancedText = completion.getCompletion().replace(STOP_PHRASE, "");
                mainHandler.post(() -> {
                    modifiedTextView.setText(enhancedText);
                });
                updateUIState(UIState.WAKE_WORD);
            } catch (PicoLLMException e) {
                onEngineProcessError(e.getMessage());
            }
        });
    }

    private void interrupt() {
        try {
            picollm.interrupt();
            if (ttsOutput != null && ttsOutput.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                ttsOutput.stop();
            }
        } catch (PicoLLMException e) {
            onEngineProcessError(e.getMessage());
        }
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

    private void onEngineInitError(String message) {
        updateUIState(UIState.INIT);
        mainHandler.post(() -> loadModelText.setText(message));
    }

    private void onEngineProcessError(String message) {
        updateUIState(UIState.WAKE_WORD);
        mainHandler.post(() -> originalTextView.setText(message));
        mainHandler.post(() -> modifiedTextView.setText(""));
    }

    private void startWakeWordListening() {
        if (voiceProcessor.hasRecordAudioPermission(this)) {
            try {
                voiceProcessor.start(cheetah.getFrameLength(), cheetah.getSampleRate());
            } catch (VoiceProcessorException e) {
                onEngineProcessError(e.getMessage());
            }
        } else {
            requestRecordPermission();
        }
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
            startWakeWordListening();
        }
    }

    private void updateUIState(UIState state) {
        mainHandler.post(() -> {
            switch (state) {
                case INIT:
                    loadModelLayout.setVisibility(View.VISIBLE);
                    demoLayout.setVisibility(View.INVISIBLE);
                    loadModelProgress.setVisibility(View.INVISIBLE);
                    break;
                case LOADING_MODEL:
                    loadModelLayout.setVisibility(View.VISIBLE);
                    demoLayout.setVisibility(View.INVISIBLE);
                    loadModelProgress.setVisibility(View.VISIBLE);
                    loadModelText.setText("Loading model...");
                    break;
                case WAKE_WORD:
                    loadModelLayout.setVisibility(View.INVISIBLE);
                    demoLayout.setVisibility(View.VISIBLE);

                    volumeMeterView.setVisibility(View.VISIBLE);
                    statusProgress.setVisibility(View.GONE);
                    statusText.setVisibility(View.VISIBLE);
                    statusText.setText("Say wake word!");

                    tooltipTextView.setVisibility(View.GONE);
                    break;
                case VOICE_COMMAND:
                    loadModelLayout.setVisibility(View.INVISIBLE);
                    demoLayout.setVisibility(View.VISIBLE);

                    volumeMeterView.setVisibility(View.VISIBLE);
                    statusProgress.setVisibility(View.GONE);
                    statusText.setVisibility(View.VISIBLE);
                    statusText.setText("Listening for voice command...");

                    tooltipTextView.setVisibility(View.VISIBLE);
                    if (memoText.length() == 0) {
                        tooltipTextView.setText("Say 'start memo'");
                    } else {
                        tooltipTextView.setText("Say one of the following commands:\n- 'start memo' \n- 'read memo' \n- 'summarize memo' \n- 'rewrite memo'");
                    }
                    break;
                case START_RECORDING:
                    loadModelLayout.setVisibility(View.INVISIBLE);
                    demoLayout.setVisibility(View.VISIBLE);

                    volumeMeterView.setVisibility(View.VISIBLE);
                    statusProgress.setVisibility(View.GONE);
                    statusText.setVisibility(View.VISIBLE);
                    statusText.setText("Listening...");

                    modifiedTextViewTitle.setText("Modified:");
                    modifiedTextViewTitle.setVisibility(View.GONE);
                    modifiedLinearLayout.setVisibility(View.GONE);
                    modifiedTextView.setText("");

                    tooltipTextView.setVisibility(View.VISIBLE);
                    tooltipTextView.setText("Say 'stop recording' to end memo");
                    break;
                case READ_RECORDING:
                    loadModelLayout.setVisibility(View.INVISIBLE);
                    demoLayout.setVisibility(View.VISIBLE);

                    volumeMeterView.setVisibility(View.GONE);
                    statusProgress.setVisibility(View.VISIBLE);
                    statusText.setVisibility(View.VISIBLE);
                    statusText.setText("Speaking...");

                    tooltipTextView.setVisibility(View.GONE);
                    break;
                case SUMMARIZE_RECORDING:
                    loadModelLayout.setVisibility(View.INVISIBLE);
                    demoLayout.setVisibility(View.VISIBLE);

                    volumeMeterView.setVisibility(View.GONE);
                    statusProgress.setVisibility(View.VISIBLE);
                    statusText.setVisibility(View.VISIBLE);
                    statusText.setText("Summerizing...");

                    if (memoText.length() > 0) {
                        modifiedTextViewTitle.setText("Summerized:");
                        modifiedTextViewTitle.setVisibility(View.VISIBLE);
                        modifiedLinearLayout.setVisibility(View.VISIBLE);
                        modifiedTextView.setText("");
                    }

                    tooltipTextView.setVisibility(View.GONE);
                    break;
                case REWRITE_RECORDING:
                    loadModelLayout.setVisibility(View.INVISIBLE);
                    demoLayout.setVisibility(View.VISIBLE);

                    volumeMeterView.setVisibility(View.GONE);
                    statusProgress.setVisibility(View.VISIBLE);
                    statusText.setVisibility(View.VISIBLE);
                    statusText.setText("Rewriting...");

                    if (memoText.length() > 0) {
                        modifiedTextViewTitle.setText("Rewritten:");
                        modifiedTextViewTitle.setVisibility(View.VISIBLE);
                        modifiedLinearLayout.setVisibility(View.VISIBLE);
                        modifiedTextView.setText("");
                    }

                    tooltipTextView.setVisibility(View.GONE);
                    break;
                default:
                    break;
            }

            currentState = state;
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        engineExecutor.shutdownNow();
        ttsSynthesizeExecutor.shutdownNow();

        unloadEngines();
    }

    protected void unloadEngines() {
        if (porcupine != null) {
            porcupine.delete();
            porcupine = null;
        }

        if (rhino != null) {
            rhino.delete();
            rhino = null;
        }

        if (cheetah != null) {
            cheetah.delete();
            cheetah = null;
        }

        if (picollm != null) {
            picollm.delete();
            picollm = null;
        }

        if (orca != null) {
            orca.delete();
            orca = null;
        }

        if (voiceProcessor != null) {
            voiceProcessor.clearFrameListeners();
            voiceProcessor.clearErrorListeners();
        }
    }
}
