package ai.picovoice.speechtospeechtranslation;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.ArrayList;
import java.util.Arrays;
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

    private static final String[] sourceDisplayLanguages = {
        "Automatic",
        "German",
        "English",
        "Spanish",
        "French",
        "Italian"
    };

    private static final String[] targetDisplayLanguages = {
            "Select Language",
            "German",
            "English",
            "Spanish",
            "French",
            "Italian"
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
        IDLE,
        LISTENING,
        DETECTING,
        ERROR
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final ExecutorService engineExecutor = Executors.newSingleThreadExecutor();

    private String sourceLanguage;

    private String targetLanguage;

    private State currentState = State.IDLE;

    private String currentTranscript = "";

    private String activeText = "";

    private int batOffset = 0;

    private final float batThreshold = 0.75f;

    private final ArrayList<Short> pcmBuffer = new ArrayList<Short>();

    private final VoiceProcessor voiceProcessor = VoiceProcessor.getInstance();

    private Bat bat = null;

    private Cheetah cheetah = null;

    private Zebra zebra = null;

    private Orca orca = null;

    private View rootView;

    private Spinner sourceLanguageSpinner;

    private Spinner targetLanguageSpinner;

    private Button startButton;

    private LinearLayout statusLayout;

    private TextView statusText;

    private ScrollView scrollArea;

    private LinearLayout chatArea;

    private final ArrayList<LinearLayout> chatBubbles = new ArrayList<>();

    private TextView animatedDots;
    private int dotIndex = 0;

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

        voiceProcessor.addFrameListener(this::frameListener);

        rootView = findViewById(R.id.root);

        initAnimatedDots();
        initStatusLayout();
        initSpinners();
        initChatArea();
        startRecording();

        rootView.invalidate();
    }

    private void initAnimatedDots() {
        animatedDots = findViewById(R.id.animatedDots);

        animateDots();
    }

    private void animateDots() {
        dotIndex = (dotIndex + 1) % dots.length;
        animatedDots.setText(dots[dotIndex]);
        mainHandler.postDelayed(this::animateDots, 100);

        renderActiveText();
    }

    private void renderActiveText() {
        mainHandler.post(() -> {
            if (!chatBubbles.isEmpty()) {
                LinearLayout chatBubble = chatBubbles.get(chatBubbles.size() - 1);
                TextView bottomText = chatBubble.findViewById(R.id.bottomText);

                if (voiceProcessor.getIsRecording()) {
                    bottomText.setText(String.format("%s%s", activeText, dots[dotIndex]));
                } else {
                    bottomText.setText(activeText);
                }
            }
        });
    }

    private void initStatusLayout() {
        statusLayout = findViewById(R.id.statusLayout);
        statusText = findViewById(R.id.statusText);
        statusLayout.setVisibility(View.GONE);
    }

    private void setStatus(String text) {
        mainHandler.post(() -> {
            statusText.setText(text);
            statusLayout.setVisibility(View.VISIBLE);
            animatedDots.setVisibility(View.VISIBLE);
            rootView.invalidate();
        });
    }

    private void setError(String text) {
        currentState = State.ERROR;
        mainHandler.post(() -> {
            int colorDanger = getResources().getColor(R.color.colorDanger);
            animatedDots.setVisibility(View.GONE);
            statusText.setText(text);
            statusText.setTextColor(colorDanger);
            statusLayout.setVisibility(View.VISIBLE);
            rootView.invalidate();
        });
    }

    private void initSpinners() {
        sourceLanguageSpinner = findViewById(R.id.sourceLanguage);
        targetLanguageSpinner = findViewById(R.id.targetLanguage);
        startButton = findViewById(R.id.startButton);

        ArrayAdapter<String> sourceLanguageAdapter = new ArrayAdapter<String>(
                this,
                android.R.layout.simple_list_item_1,
                sourceDisplayLanguages
        ) {
            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                TextView tv = (TextView) super.getView(position, null, parent);
                tv.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
                return tv;
            }

            @Override
            public View getDropDownView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                TextView tv = (TextView) super.getDropDownView(position, null, parent);
                tv.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
                return tv;
            }
        };
        sourceLanguageSpinner.setAdapter(sourceLanguageAdapter);

        ArrayAdapter<String> targetLanguageAdapter = new ArrayAdapter<String>(
                this,
                android.R.layout.simple_list_item_1,
                targetDisplayLanguages
        ) {
            @Override
            public boolean isEnabled(int position) {
                if (position > 0 && sourceLanguage != null) {
                    String pair = String.format("%s-%s", sourceLanguage, languages[position]);
                    return Arrays.asList(languagePairs).contains(pair);
                } else {
                    return position > 0;
                }
            }

            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                TextView tv = (TextView) super.getView(position, null, parent);
                tv.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
                return tv;
            }

            @Override
            public View getDropDownView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                if (!this.isEnabled(position)) {
                    TextView tv = new TextView(getContext());
                    tv.setVisibility(View.GONE);
                    tv.setHeight(0);
                    return tv;
                }

                TextView tv = (TextView) super.getDropDownView(position, null, parent);
                tv.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
                return tv;
            }
        };
        targetLanguageSpinner.setAdapter(targetLanguageAdapter);

        sourceLanguageSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parentView, View selectedItemView, int position, long id) {
                if (position > 0) {
                    sourceLanguage = languages[position];
                    if (currentState != State.DETECTING || targetLanguage == null) {
                        targetLanguageSpinner.setSelection(0);
                        rootView.invalidate();

                        engineExecutor.submit(() -> {
                            clearChatArea();
                            deleteEngines();
                        });
                    } else {
                        onLanguageSelected();
                    }
                } else {
                    this.onNothingSelected(parentView);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parentView) {
                sourceLanguage = null;
                targetLanguageSpinner.setSelection(0);
                clearChatArea();
                rootView.invalidate();
            }
        });

        targetLanguageSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parentView, View selectedItemView, int position, long id) {
                if (position > 0) {
                    targetLanguage = languages[position];
                    rootView.invalidate();
                    onLanguageSelected();
                } else {
                    this.onNothingSelected(parentView);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parentView) {
                targetLanguage = null;
                rootView.invalidate();
            }
        });

        startButton.setOnClickListener(v -> {
            onButtonPressed();
        });
    }

    private void initChatArea() {
        scrollArea = findViewById(R.id.scrollArea);
        chatArea = findViewById(R.id.chatArea);
    }

    private void clearChatArea() {
        mainHandler.post(() -> {
            for (LinearLayout chatBubble : chatBubbles) {
                chatArea.removeView(chatBubble);
            }

            chatBubbles.clear();
        });
    }

    private void appendChatBubble() {
        mainHandler.post(() -> {
            LayoutInflater inflater = LayoutInflater.from(this);
            LinearLayout chatBubble = (LinearLayout) inflater.inflate(
                    R.layout.chat_bubble,
                    chatArea,
                    false);
            LinearLayout top = chatBubble.findViewById(R.id.top);
            LinearLayout bottom = chatBubble.findViewById(R.id.bottom);

            top.setVisibility(View.GONE);
            bottom.setVisibility(View.VISIBLE);

            chatArea.addView(chatBubble);
            chatBubbles.add(chatBubble);
            rootView.invalidate();

            if (chatBubbles.size() > 1) {
                LinearLayout prevChatBubble = chatBubbles.get(chatBubbles.size() - 2);
                TextView bottomText = prevChatBubble.findViewById(R.id.bottomText);

                bottomText.setText(activeText);
                activeText = "";
            }

            scrollArea.post(() -> {
                scrollArea.fullScroll(View.FOCUS_DOWN);
            });
        });
    }

    private void sendText(String text) {
        if (text.isEmpty()) {
            return;
        }

        mainHandler.post(() -> {
            activeText += text;
            renderActiveText();

            scrollArea.post(() -> {
                scrollArea.fullScroll(View.FOCUS_DOWN);
            });
        });
    }

    private void swapText() {
        mainHandler.post(() -> {
            LinearLayout chatBubble = chatBubbles.get(chatBubbles.size() - 1);
            LinearLayout top = chatBubble.findViewById(R.id.top);
            TextView topText = chatBubble.findViewById(R.id.topText);

            topText.setText(activeText);
            top.setVisibility(View.VISIBLE);
            rootView.invalidate();

            activeText = "";
            renderActiveText();

            scrollArea.post(() -> {
                scrollArea.fullScroll(View.FOCUS_DOWN);
            });
        });
    }

    private void onLanguageSelected() {
            setStatus("Loading");
            clearChatArea();
            engineExecutor.submit(this::initEngines);
    }

    private void onButtonPressed() {
        if (voiceProcessor.getIsRecording()) {
            stopRecording();
            flush();
        } else {
            startRecording();
        }
    }

    private void initEngines() {
        mainHandler.post(() -> {
            sourceLanguageSpinner.setEnabled(false);
            targetLanguageSpinner.setEnabled(false);
            rootView.invalidate();
        });

        deleteEngines();

        if (sourceLanguage != null) {
            setStatus(String.format("Loading Cheetah %s", sourceLanguage));

            try {
                String model_path = String.format("cheetah_params_%s.pv", sourceLanguage);
                cheetah = new Cheetah.Builder()
                        .setAccessKey(ACCESS_KEY)
                        .setModelPath(model_path)
                        .setEnableAutomaticPunctuation(true)
                        .setEnableTextNormalization(true)
                        .build(getApplicationContext());
            } catch (CheetahException e) {
                setError(e.getMessage());
                return;
            }

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
        } else {
            setStatus("Loading Bat");

            try {
                bat = new Bat.Builder()
                        .setAccessKey(ACCESS_KEY)
                        .build(getApplicationContext());
                batOffset = 0;
                pcmBuffer.clear();
            } catch (BatException e) {
                setError(e.getMessage());
            }
        }

        startListening();

        mainHandler.post(() -> {
            if (cheetah != null) {
                statusLayout.setVisibility(View.GONE);
            } else {
                setStatus("Detecting source language");
            }
            sourceLanguageSpinner.setEnabled(true);
            targetLanguageSpinner.setEnabled(true);
            rootView.invalidate();
        });
    }

    private void deleteEngines() {
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
    }

    private void startRecording() {
        if (voiceProcessor.hasRecordAudioPermission(this)) {
            mainHandler.post(() -> startButton.setActivated(true));
            engineExecutor.submit(() -> {
                try {
                    voiceProcessor.start(512, 16000);
                } catch (VoiceProcessorException e) {
                    setError(e.getMessage());
                }
            });
        } else {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    0);
        }
    }

    private void stopRecording() {
        mainHandler.post(() -> startButton.setActivated(false));
        engineExecutor.submit(() -> {
            try {
                voiceProcessor.stop();
            } catch (VoiceProcessorException e) {
                setError(e.getMessage());
            }
        });
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
        if (currentState == State.LISTENING) {
            engineExecutor.submit(() -> listen(frame));
        } else if (currentState == State.DETECTING) {
            engineExecutor.submit(() -> detect(frame));
        }
    }

    private void detect(short[] frame) {
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
                } else {
                    int sourceSelection = Arrays.asList(languages).indexOf(sourceLanguage);
                    mainHandler.post(() -> sourceLanguageSpinner.setSelection(sourceSelection));
                }
            }
        }
    }

    private void startListening() {
        if (cheetah != null) {
            appendChatBubble();
            currentState = State.LISTENING;
        } else {
            currentState = State.DETECTING;
        }
    }

    private void listen(short[] frame) {
        try {
            boolean isFlushed = false;
            while (pcmBuffer.size() >= cheetah.getFrameLength()) {
                short[] queueFrame = new short[cheetah.getFrameLength()];
                for (int i = 0; i < cheetah.getFrameLength(); i++) {
                    queueFrame[i] = pcmBuffer.get(i);
                }

                pcmBuffer.subList(0, cheetah.getFrameLength()).clear();

                CheetahTranscript transcript = cheetah.process(queueFrame);
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

            pcmBuffer.clear();
            CheetahTranscript transcript = cheetah.process(frame);
            currentTranscript += transcript.getTranscript();
            sendText(transcript.getTranscript());

            if (transcript.getIsEndpoint() || isFlushed) {
                flush();
            }
        } catch (CheetahException e) {
            setError(e.getMessage());
        }
    }

    private void flush() {
        engineExecutor.submit(() -> {
            if (currentState != State.LISTENING) {
                return;
            }

            try {
                CheetahTranscript flush = cheetah.flush();
                currentTranscript += flush.getTranscript();
                sendText(flush.getTranscript());

                if (!currentTranscript.isEmpty()) {
                    swapText();
                    currentState = State.IDLE;
                    engineExecutor.submit(this::translate);
                }
            } catch (CheetahException e) {
                setError(e.getMessage());
            }
        });
    }

    private void translate() {
        String inputText = currentTranscript;
        currentTranscript = "";

        try {
            String outputText = zebra.translate(
                    inputText.substring(0, Math.min(
                            inputText.length(),
                            zebra.getMaxCharacterLimit())));

            speak(outputText);
        } catch (ZebraException e) {
            setError(e.getMessage());
        }
    }

    private void speak(String outputText) {
        try {
            OrcaSynthesizeParams params = new OrcaSynthesizeParams.Builder()
                    .build();

            OrcaAudio audio = orca.synthesize(outputText, params);

            playAudio(audio, this::startListening);
        } catch (OrcaException e) {
            setError(e.getMessage());
        }
    }

    private void playAudio(OrcaAudio audio, Runnable after) {
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

        OrcaWord lastWord = words[words.length - 1];
        mainHandler.postDelayed(
                after,
                (long) (lastWord.getEndSec() * 1000));

        short[] pcm = audio.getPcm();
        ttsOutput.write(pcm, 0, pcm.length);

        if (ttsOutput.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
            ttsOutput.flush();
            ttsOutput.stop();
        }

        ttsOutput.release();
    }
}