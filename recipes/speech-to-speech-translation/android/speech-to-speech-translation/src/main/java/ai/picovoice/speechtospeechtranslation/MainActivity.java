package ai.picovoice.speechtospeechtranslation;

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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ai.picovoice.android.voiceprocessor.VoiceProcessor;
import ai.picovoice.android.voiceprocessor.VoiceProcessorException;
import ai.picovoice.bat.Bat;
import ai.picovoice.bat.BatException;
import ai.picovoice.bat.BatLanguages;
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

    private static final String[] sourceLanguages = {
        "automatic",
        "de",
        "en",
        "es",
        "fr",
        "it"
    };

    private static final String[] targetLanguages = {
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
        LISTENING,
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
    private Bat bat = null;
    private Cheetah cheetah = null;
    private Zebra zebra = null;
    private Orca orca = null;

    private State currentState = State.OTHER;
    private String currentTranscript = "";
    private int batOffset = 0;
    private final float batThreshold = 0.75f;
    private final ArrayList<Short> pcmBuffer = new ArrayList<Short>();

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

    private void sendNewline(Boolean recolor) {
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

            renderText();
        });
    }

    private void setStatus(String text) {
        mainHandler.post(() -> {
            statusProgress.setVisibility(View.VISIBLE);
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
            sendNewline(true);
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
        startButton.setEnabled(false);
        startButton.invalidate();

        ArrayAdapter<String> sourceLanguageAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_list_item_1,
                sourceLanguages
        );
        sourceLanguageSpinner.setAdapter(sourceLanguageAdapter);

        ArrayAdapter<String> targetLanguageAdapter = new ArrayAdapter<String>(
                this,
                android.R.layout.simple_list_item_1,
                targetLanguages
        ) {
            @Override
            public boolean isEnabled(int position) {
                if (sourceLanguage == null) {
                    return position > 0;
                } else if (position > 0) {
                    String pair = String.format("%s-%s", sourceLanguage, targetLanguages[position]);
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
                    sourceLanguage = sourceLanguages[position];
                    targetLanguageSpinner.setSelection(0);
                } else {
                    this.onNothingSelected(parentView);
                }

            }

            @Override
            public void onNothingSelected(AdapterView<?> parentView) {
                sourceLanguage = null;
            }
        });

        targetLanguageSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parentView, View selectedItemView, int position, long id) {
                if (position > 0) {
                    targetLanguage = targetLanguages[position];
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
            startButton.setEnabled(false);
            sourceLanguageSpinner.invalidate();
            targetLanguageSpinner.invalidate();
            startButton.invalidate();

            try {
                voiceProcessor.stop();
            } catch (VoiceProcessorException e) {
                setError(e.getMessage());
                return;
            }

            if (bat != null) {
                bat.delete();
                bat = null;
            }

            if (cheetah != null) {
                cheetah.delete();
                cheetah = null;
            }

            if (zebra != null) {
                zebra.delete();
                zebra = null;
            }

            if (orca != null) {
                orca.delete();
                orca = null;
            }

            selectLanguageLayout.setVisibility(View.VISIBLE);
            chatLayout.setVisibility(View.GONE);
        });
    }

    private void initEngines() {
        setStatus(String.format("Loading Orca %s", targetLanguage));

        try {
            String model_path = String.format("orca_params_%s_female.pv", targetLanguage);
            orca = new Orca.Builder()
                    .setAccessKey(ACCESS_KEY)
                    .setModelPath(model_path)
                    .build(getApplicationContext());
        } catch (OrcaException e) {
            setError(e.getMessage());
            return;
        }

        pcmBuffer.clear();

        if (sourceLanguage == null) {
            setStatus("Loading Bat");

            try {
                bat = new Bat.Builder()
                        .setAccessKey(ACCESS_KEY)
                        .build(getApplicationContext());
                batOffset = 0;
            } catch (BatException e) {
                setError(e.getMessage());
            }
        } else {
            initEnginesDelayed();
        }
    }

    private void initEnginesDelayed() {
        setStatus(String.format("Loading Zebra %s-%s", sourceLanguage, targetLanguage));

        try {
            String model_path = String.format("zebra_params_%s_%s.pv", sourceLanguage, targetLanguage);
            zebra = new Zebra.Builder()
                    .setAccessKey(ACCESS_KEY)
                    .setModelPath(model_path)
                    .build(getApplicationContext());
        } catch (ZebraException e) {
            setError(e.getMessage());
            return;
        }

        setStatus(String.format("Loading Cheetah %s", sourceLanguage));

        try {
            String model_path = String.format("cheetah_params_%s.pv", sourceLanguage);
            cheetah = new Cheetah.Builder()
                    .setAccessKey(ACCESS_KEY)
                    .setModelPath(model_path)
                    .setEnableAutomaticPunctuation(true)
                    .build(getApplicationContext());
        } catch (CheetahException e) {
            setError(e.getMessage());
            return;
        }

        setStatus(String.format("Listening for %s", sourceLanguage));
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
                if (bat != null) {
                    voiceProcessor.start(512, bat.getSampleRate());
                } else {
                    voiceProcessor.start(cheetah.getFrameLength(), cheetah.getSampleRate());
                }
            } catch (VoiceProcessorException e) {
                setError(e.getMessage());
            }
        } else {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    0);
        }

        startListening();
    }

    private void frameListener(short[] frame) {
        if (currentState != State.LISTENING) {
            return;
        }

        for (short elem : frame) {
            pcmBuffer.add(elem);
        }

        if (bat != null) {
            short[] batFrame = new short[bat.getFrameLength()];

            while (pcmBuffer.size() - batOffset >= bat.getFrameLength()) {
                for (int i = 0; i < bat.getFrameLength(); i++) {
                    batFrame[i] = pcmBuffer.get(batOffset + i);
                }
                batOffset += bat.getFrameLength();

                try {
                    HashMap<BatLanguages, Float> languages = bat.process(batFrame);
                    if (languages != null) {
                        languages.forEach((key, value) -> {
                            if (value >= batThreshold) {
                                sourceLanguage = key.toString().toLowerCase();
                            }
                        });
                    }
                } catch (BatException e) {
                    setError(e.getMessage());
                    return;
                }
            }

            if (sourceLanguage != null) {
                String pair = String.format("%s-%s", sourceLanguage, targetLanguage);
                if (!Arrays.asList(languagePairs).contains(pair)) {
                    setStatus(String.format("Cannot translate %s to %s", sourceLanguage, targetLanguage));

                    sourceLanguage = null;
                }
            }

            if (sourceLanguage != null) {
                setStatus(String.format("Detected %s", sourceLanguage));

                bat.delete();
                bat = null;

                engineExecutor.submit(this::initEnginesDelayed);
            }

            return;
        }

        if (cheetah != null) {
            listen();
        }
    }

    private void startListening() {
        if (currentState == State.ERROR) {
            return;
        }

        if (bat != null) {
            setStatus("Detecting source language");
        } else {
            setStatus(String.format("Listening for %s", sourceLanguage));
        }

        currentState = State.LISTENING;
    }

    private void listen() {
        if (currentState == State.ERROR) {
            return;
        }

        try {
            boolean isFlushed = false;
            while (pcmBuffer.size() >= cheetah.getFrameLength()) {
                short[] frame = new short[cheetah.getFrameLength()];
                for (int i = 0; i < cheetah.getFrameLength(); i++) {
                    frame[i] = pcmBuffer.get(i);
                }

                pcmBuffer.subList(0, cheetah.getFrameLength()).clear();

                CheetahTranscript transcript = cheetah.process(frame);
                currentTranscript += transcript.getTranscript();
                sendText(transcript.getTranscript());
                if (!transcript.getTranscript().isEmpty()) {
                    isFlushed = false;
                }

                if (transcript.getIsEndpoint()) {
                    CheetahTranscript flush = cheetah.flush();
                    currentTranscript += flush.getTranscript() + " ";
                    sendText(flush.getTranscript() + " ");
                    if (!currentTranscript.isEmpty()) {
                        isFlushed = true;
                    }
                }
            }

            if (isFlushed) {
                sendNewline(true);
                startTranslating();
                pcmBuffer.clear();
            }
        } catch (CheetahException e) {
            setError(e.getMessage());
        }
    }

    private void startTranslating() {
        if (currentState == State.ERROR) {
            return;
        }

        setStatus(String.format("Translating to %s", targetLanguage));
        currentState = State.OTHER;

        String transcript = currentTranscript;
        currentTranscript = "";

        engineExecutor.submit(() -> {
            try {
                String translation = zebra.translate(
                        transcript.substring(0, Math.min(
                                transcript.length(),
                                zebra.getMaxCharacterLimit())));

                startSpeaking(translation);
            } catch (ZebraException e) {
                setError(e.getMessage());
            }
        });
    }

    private void startSpeaking(String text) {
        if (currentState == State.ERROR) {
            return;
        }

        setStatus(String.format("Synthesizing %s speech", sourceLanguage));
        currentState = State.OTHER;

        try {
            OrcaSynthesizeParams params = new OrcaSynthesizeParams.Builder()
                    .build();
            OrcaAudio audio = orca.synthesize(text, params);

            setStatus(String.format("Speaking in %s", sourceLanguage));

            playAudio(audio);

            startListening();
        } catch (OrcaException e) {
            setError(e.getMessage());
        }
    }

    private void playAudio(OrcaAudio audio) {
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
            if (i < words.length - 1 && !words[i + 1].getWord().matches(".*\\p{Punct}.*")) {
                suffix = " ";
            }

            String text = words[i].getWord() + suffix;

            mainHandler.postDelayed(() -> {
                sendText(text);
            }, (long) (words[i].getStartSec() * 1000));
        }

        mainHandler.postDelayed(() -> {
            sendNewline(false);
            sendNewline(false);
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