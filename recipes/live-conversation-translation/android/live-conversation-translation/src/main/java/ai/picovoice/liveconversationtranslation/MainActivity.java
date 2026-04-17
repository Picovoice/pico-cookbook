package ai.picovoice.liveconversationtranslation;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.AlignmentSpan;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
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
import ai.picovoice.zebra.Zebra;
import ai.picovoice.zebra.ZebraException;

public class MainActivity extends AppCompatActivity {

    private static final String ACCESS_KEY = "${YOUR_ACCESS_KEY_HERE}";

    private static final String[] languages = {
        "--",
        "de",
        "en",
        "es",
        "fr",
        "it"
    };

    private static final String[] languagePairs = {
        "de-en",
        "de-es",
        "de-fr",
        "de-it",
        "en-de",
        "en-es",
        "en-fr",
        "en-it",
        "es-de",
        "es-en",
        "es-fr",
        "es-it",
        "fr-de",
        "fr-en",
        "fr-es",
        "it-de",
        "it-en",
        "it-es"
    };

    private static final String[] dots = {
        " .  ",
        " .. ",
        " ...",
        "  ..",
        "   .",
        "    "
    };

    private enum State {
        LISTENING_SOURCE,
        LISTENING_TARGET,
        OTHER,
        ERROR
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final ExecutorService engineExecutor = Executors.newSingleThreadExecutor();

    private LinearLayout selectLanguageLayout;

    private ConstraintLayout chatLayout;

    private Spinner sourceLanguageSpinner;

    private Spinner targetLanguageSpinner;

    private Button startButton;

    private Button changeLanguageButton;

    private TextView statusText;

    private ProgressBar statusProgress;

    private TextView chatText;

    private ScrollView chatTextScrollView;

    private SpannableStringBuilder chatTextBuilder = null;

    private int chatLastNewline = 0;

    private int dotIndex = 0;

    private String sourceLanguage = null;

    private String targetLanguage = null;

    private final VoiceProcessor voiceProcessor = VoiceProcessor.getInstance();
    private Cheetah cheetah0;
    private Cheetah cheetah1;
    private Zebra zebra0;
    private Zebra zebra1;
    private Orca orca0;
    private Orca orca1;

    private State currentState = State.OTHER;
    private String currentTranscript = "";

    private void renderText() {
        if (currentState == State.ERROR) {
            return;
        }

        mainHandler.post(() -> {
            if (chatTextBuilder != null) {
                if (currentState != State.OTHER) {
                    int start = chatTextBuilder.length();
                    chatTextBuilder.append(dots[dotIndex]);
                    chatText.setText(chatTextBuilder);
                    chatTextBuilder.delete(start, start + dots[dotIndex].length());
                    chatTextScrollView.fullScroll(ScrollView.FOCUS_DOWN);
                } else {
                    chatText.setText(chatTextBuilder);
                    chatTextScrollView.fullScroll(ScrollView.FOCUS_DOWN);
                }
            }
        });
    }

    private void animateDots() {
        renderText();
        dotIndex = (dotIndex + 1) % dots.length;
        mainHandler.postDelayed(this::animateDots, 100);
    }

    private void sendText(String text) {
        if (!text.isEmpty()) {
            mainHandler.post(() -> {
                int start = chatTextBuilder.length();
                int end = start + text.length();
                int spanColour = ContextCompat.getColor(this, R.color.colorPrimary);
                chatTextBuilder.append(text);

                chatTextBuilder.setSpan(
                        new ForegroundColorSpan(spanColour),
                        start,
                        end,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                renderText();
            });
        }
    }

    private void sendNewline(boolean alignRight, Boolean recolor) {
        mainHandler.post(() -> {
            chatTextBuilder.append("\n");
            int start = chatTextBuilder.length();

            if (recolor) {
                int spanColour = ContextCompat.getColor(this, R.color.colorSecondary);
                chatTextBuilder.setSpan(
                        new ForegroundColorSpan(spanColour),
                        chatLastNewline,
                        start,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            chatLastNewline = start;

            if (alignRight) {
                chatTextBuilder.append(" ");
                chatTextBuilder.setSpan(
                        new AlignmentSpan.Standard(Layout.Alignment.ALIGN_OPPOSITE),
                        start,
                        start + 1,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }

            renderText();
        });
    }

    private void setStatus(String text) {
        mainHandler.post(() -> {
            statusText.setVisibility(View.VISIBLE);
            statusText.setText(text);
        });
    }

    private void setError(String text) {
        currentState = State.ERROR;
        mainHandler.post(() -> {
            setStatus("Error");
            sendText(text);
            statusProgress.setVisibility(View.GONE);
            sendNewline(false, true);
        });
    }

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

        selectLanguageLayout = findViewById(R.id.selectLanguageLayout);
        chatLayout = findViewById(R.id.chatLayout);
        sourceLanguageSpinner = findViewById(R.id.sourceLanguage);
        targetLanguageSpinner = findViewById(R.id.targetLanguage);
        startButton = findViewById(R.id.startButton);
        changeLanguageButton = findViewById(R.id.changeLanguageButton);
        statusText = findViewById(R.id.statusText);
        statusProgress = findViewById(R.id.statusProgress);
        chatText = findViewById(R.id.chatText);
        chatTextScrollView = findViewById(R.id.chatScrollView);
        chatTextBuilder = new SpannableStringBuilder();

        voiceProcessor.addFrameListener(this::frameListener);
        targetLanguageSpinner.setEnabled(false);
        targetLanguageSpinner.invalidate();
        startButton.setEnabled(false);
        startButton.invalidate();

        ArrayAdapter<String> sourceLanguageAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_list_item_1,
                languages
        );
        sourceLanguageSpinner.setAdapter(sourceLanguageAdapter);

        ArrayAdapter<String> targetLanguageAdapter = new ArrayAdapter<String>(
                this,
                android.R.layout.simple_list_item_1,
                languages
        ) {
            @Override
            public boolean isEnabled(int position) {
                if (position > 0 && sourceLanguage != null) {
                    String pair = String.format("%s-%s", sourceLanguage, languages[position]);
                    return Arrays.asList(languagePairs).contains(pair);
                } else {
                    return false;
                }
            }

            @Override
            public View getDropDownView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                if (!this.isEnabled(position)) {
                    TextView tv = new TextView(getContext());
                    tv.setVisibility(View.GONE);
                    tv.setHeight(0);
                    return tv;
                }

                return super.getDropDownView(position, null, parent);
            }
        };
        targetLanguageSpinner.setAdapter(targetLanguageAdapter);

        animateDots();

        sourceLanguageSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parentView, View selectedItemView, int position, long id) {
                if (position > 0) {
                    sourceLanguage = languages[position];
                    targetLanguageSpinner.setSelection(0);
                    targetLanguageSpinner.setEnabled(true);
                    targetLanguageSpinner.invalidate();
                } else {
                    this.onNothingSelected(parentView);
                }

            }

            @Override
            public void onNothingSelected(AdapterView<?> parentView) {
                sourceLanguage = null;
                targetLanguageSpinner.setSelection(0);
                targetLanguageSpinner.setEnabled(false);
                targetLanguageSpinner.invalidate();
                startButton.setEnabled(false);
                startButton.invalidate();
            }
        });

        targetLanguageSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parentView, View selectedItemView, int position, long id) {
                if (position > 0) {
                    targetLanguage = languages[position];
                    startButton.setEnabled(true);
                    startButton.invalidate();
                } else {
                    targetLanguage = null;
                    startButton.setEnabled(false);
                    startButton.invalidate();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parentView) {
                targetLanguage = null;
            }

        });

        startButton.setOnClickListener(parent -> {
            selectLanguageLayout.setVisibility(View.GONE);
            chatLayout.setVisibility(View.VISIBLE);
            changeLanguageButton.setEnabled(false);

            voiceProcessor.addErrorListener(error -> {
                setError(error.getMessage());
            });

            engineExecutor.submit(() -> {
                initEngines();
                start();
                mainHandler.post(() -> {
                    changeLanguageButton.setEnabled(true);
                    changeLanguageButton.invalidate();
                });
            });
        });

        changeLanguageButton.setOnClickListener(view -> {
            mainHandler.post(() -> chatText.setText(""));
            currentState = State.OTHER;
            currentTranscript = "";
            chatTextBuilder.clear();
            chatTextBuilder.clearSpans();
            chatLastNewline = 0;
            sourceLanguage = null;
            targetLanguage = null;

            sourceLanguageSpinner.setSelection(0);
            targetLanguageSpinner.setSelection(0);
            targetLanguageSpinner.setEnabled(false);
            startButton.setEnabled(false);
            sourceLanguageSpinner.invalidate();
            targetLanguageSpinner.invalidate();
            startButton.invalidate();

            cheetah0.delete();
            cheetah1.delete();
            zebra0.delete();
            zebra1.delete();
            orca0.delete();
            orca1.delete();

            try {
                voiceProcessor.stop();
            } catch (VoiceProcessorException e) {
                setError(e.getMessage());
                return;
            }

            selectLanguageLayout.setVisibility(View.VISIBLE);
            chatLayout.setVisibility(View.GONE);
        });
    }

    private void initEngines() {
        setStatus(String.format("Loading Cheetah %s", sourceLanguage));

        try {
            String model_path = String.format("cheetah_params_%s.pv", sourceLanguage);
            cheetah0 = new Cheetah.Builder()
                    .setAccessKey(ACCESS_KEY)
                    .setModelPath(model_path)
                    .setEnableAutomaticPunctuation(true)
                    .build(getApplicationContext());
        } catch (CheetahException e) {
            setError(e.getMessage());
            return;
        }

        setStatus(String.format("Loading Cheetah %s", targetLanguage));

        try {
            String model_path = String.format("cheetah_params_%s.pv", targetLanguage);
            cheetah1 = new Cheetah.Builder()
                    .setAccessKey(ACCESS_KEY)
                    .setModelPath(model_path)
                    .setEnableAutomaticPunctuation(true)
                    .build(getApplicationContext());
        } catch (CheetahException e) {
            setError(e.getMessage());
            return;
        }

        setStatus(String.format("Loading Zebra %s-%s", sourceLanguage, targetLanguage));

        try {
            String model_path = String.format("zebra_params_%s_%s.pv", sourceLanguage, targetLanguage);
            zebra0 = new Zebra.Builder()
                    .setAccessKey(ACCESS_KEY)
                    .setModelPath(model_path)
                    .build(getApplicationContext());
        } catch (ZebraException e) {
            setError(e.getMessage());
            return;
        }

        setStatus(String.format("Loading Zebra %s-%s", targetLanguage, sourceLanguage));

        try {
            String model_path = String.format("zebra_params_%s_%s.pv", targetLanguage, sourceLanguage);
            zebra1 = new Zebra.Builder()
                    .setAccessKey(ACCESS_KEY)
                    .setModelPath(model_path)
                    .build(getApplicationContext());
        } catch (ZebraException e) {
            setError(e.getMessage());
            return;
        }

        setStatus(String.format("Loading Orca %s", sourceLanguage));

        try {
            String model_path = String.format("orca_params_%s_female.pv", sourceLanguage);
            orca0 = new Orca.Builder()
                    .setAccessKey(ACCESS_KEY)
                    .setModelPath(model_path)
                    .build(getApplicationContext());
        } catch (OrcaException e) {
            setError(e.getMessage());
            return;
        }

        setStatus(String.format("Loading Orca %s", targetLanguage));

        try {
            String model_path = String.format("orca_params_%s_female.pv", targetLanguage);
            orca1 = new Orca.Builder()
                    .setAccessKey(ACCESS_KEY)
                    .setModelPath(model_path)
                    .build(getApplicationContext());
        } catch (OrcaException e) {
            setError(e.getMessage());
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
            start();
        }
    }

    private void start() {
        if (currentState == State.ERROR) {
            return;
        }

        if (voiceProcessor.hasRecordAudioPermission(this)) {
            try {
                voiceProcessor.start(cheetah0.getFrameLength(), cheetah0.getSampleRate());
            } catch (VoiceProcessorException e) {
                setError(e.getMessage());
            }
        } else {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    0);
        }

        startListeningSource();
    }

    private void frameListener(short[] frame) {
        if (currentState == State.LISTENING_SOURCE) {
            listenSource(frame);
        } else if (currentState == State.LISTENING_TARGET) {
            listenTarget(frame);
        }
    }

    private void startListeningSource() {
        if (currentState == State.ERROR) {
            return;
        }

        setStatus(String.format("Listening for %s", sourceLanguage));
        currentState = State.LISTENING_SOURCE;
    }

    private void startListeningTarget() {
        if (currentState == State.ERROR) {
            return;
        }

        setStatus(String.format("Listening for %s", targetLanguage));
        currentState = State.LISTENING_TARGET;
    }

    private void listenSource(short[] frame) {
        if (currentState == State.ERROR) {
            return;
        }

        try {
            CheetahTranscript transcript = cheetah0.process(frame);
            currentTranscript += transcript.getTranscript();
            sendText(transcript.getTranscript());

            if (transcript.getIsEndpoint()) {
                CheetahTranscript flush = cheetah0.flush();
                currentTranscript += flush.getTranscript();
                sendText(flush.getTranscript());
                if (!currentTranscript.isEmpty()) {
                    sendNewline(false, true);

                    startTranslatingSource();
                }
            }
        } catch (CheetahException e) {
            setError(e.getMessage());
        }
    }

    private void listenTarget(short[] frame) {
        if (currentState == State.ERROR) {
            return;
        }

        try {
            CheetahTranscript transcript = cheetah1.process(frame);
            currentTranscript += transcript.getTranscript();
            sendText(transcript.getTranscript());

            if (transcript.getIsEndpoint()) {
                CheetahTranscript flush = cheetah1.flush();
                currentTranscript += flush.getTranscript();
                sendText(flush.getTranscript());
                if (!currentTranscript.isEmpty()) {
                    sendNewline(true, true);

                    startTranslatingTarget();
                }
            }
        } catch (CheetahException e) {
            setError(e.getMessage());
        }
    }

    private void startTranslatingSource() {
        if (currentState == State.ERROR) {
            return;
        }

        setStatus(String.format("Translating to %s", targetLanguage));
        currentState = State.OTHER;

        String transcript = currentTranscript;
        currentTranscript = "";

        engineExecutor.submit(() -> {
            try {
                String translation = zebra0.translate(transcript);

                startSpeakingTarget(translation);
            } catch (ZebraException e) {
                setError(e.getMessage());
            }
        });
    }

    private void startTranslatingTarget() {
        if (currentState == State.ERROR) {
            return;
        }

        setStatus(String.format("Translating to %s", sourceLanguage));
        currentState = State.OTHER;

        String transcript = currentTranscript;
        currentTranscript = "";

        engineExecutor.submit(() -> {
            try {
                String translation = zebra1.translate(transcript);

                startSpeakingSource(translation);
            } catch (ZebraException e) {
                setError(e.getMessage());
            }
        });
    }

    private void startSpeakingTarget(String text) {
        if (currentState == State.ERROR) {
            return;
        }

        setStatus(String.format("Synthesizing %s speech", targetLanguage));
        currentState = State.OTHER;

        try {
            OrcaSynthesizeParams params = new OrcaSynthesizeParams.Builder()
                    .build();
            OrcaAudio audio = orca1.synthesize(text, params);

            setStatus(String.format("Speaking in %s", targetLanguage));

            playAudio(audio, true);

            startListeningTarget();
        } catch (OrcaException e) {
            setError(e.getMessage());
        }
    }

    private void startSpeakingSource(String text) {
        if (currentState == State.ERROR) {
            return;
        }

        setStatus(String.format("Synthesizing %s speech", sourceLanguage));
        currentState = State.OTHER;

        try {
            OrcaSynthesizeParams params = new OrcaSynthesizeParams.Builder()
                    .build();
            OrcaAudio audio = orca0.synthesize(text, params);

            setStatus(String.format("Speaking in %s", sourceLanguage));

            playAudio(audio, false);

            startListeningSource();
        } catch (OrcaException e) {
            setError(e.getMessage());
        }
    }

    private void playAudio(OrcaAudio audio, boolean alignRight) {
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();

        AudioFormat audioFormat = new AudioFormat.Builder()
                .setSampleRate(orca0.getSampleRate())
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build();

        AudioTrack ttsOutput = new AudioTrack(
                audioAttributes,
                audioFormat,
                AudioTrack.getMinBufferSize(
                        orca0.getSampleRate(),
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT),
                AudioTrack.MODE_STREAM,
                0);

        ttsOutput.play();

        OrcaWord[] words = audio.getWordArray();
        for (int i = 0; i < words.length; i++) {
            String suffix = "";
            if (i < words.length - 1 && !words[i + 1].getWord().matches(".*\\p{Punct}.*")) {
                suffix = " ";
            }

            String text = words[i].getWord() + suffix;

            mainHandler.postDelayed(() -> {
                sendText(text);
            }, (long) (words[i].getStartSec() * 1000));
        }

        mainHandler.postDelayed(() -> {
            sendNewline(alignRight, false);
        }, (long) (words[words.length - 1].getEndSec() * 1000));

        short[] pcm = audio.getPcm();
        ttsOutput.write(pcm, 0, pcm.length);

        if (ttsOutput.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
            ttsOutput.flush();
            ttsOutput.stop();
        }

        ttsOutput.release();
    }
}