package ai.picovoice.liveconversationtranslation;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
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

    private static final String[] displayLanguages = {
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
        LISTENING_SOURCE,
        LISTENING_TARGET,
        ERROR
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final ExecutorService engineExecutor = Executors.newSingleThreadExecutor();

    private String sourceLanguage;

    private String targetLanguage;

    private State currentState = State.IDLE;

    private String currentTranscript = "";

    private String activeText = "";

    private final VoiceProcessor voiceProcessor = VoiceProcessor.getInstance();

    private Cheetah cheetah0 = null;

    private Cheetah cheetah1 = null;

    private Zebra zebra0 = null;

    private Zebra zebra1 = null;

    private Orca orca0 = null;

    private Orca orca1 = null;

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
        Log.d("PICOVOICE", text);
        mainHandler.post(() -> {
            statusText.setText(text);
            statusLayout.setVisibility(View.VISIBLE);
            rootView.invalidate();
        });
    }

    private void setError(String text) {
        Log.e("PICOVOICE", text);
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
                displayLanguages
        ) {
            @Override
            public boolean isEnabled(int position) {
                return position > 0;
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
        sourceLanguageSpinner.setAdapter(sourceLanguageAdapter);

        ArrayAdapter<String> targetLanguageAdapter = new ArrayAdapter<String>(
                this,
                android.R.layout.simple_list_item_1,
                displayLanguages
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

        sourceLanguageSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parentView, View selectedItemView, int position, long id) {
                if (position > 0) {
                    sourceLanguage = languages[position];
                    targetLanguageSpinner.setSelection(0);
                    targetLanguageSpinner.setEnabled(true);
                    rootView.invalidate();

                    engineExecutor.submit(() -> {
                        clearChatArea();
                        deleteEngines();
                    });
                } else {
                    this.onNothingSelected(parentView);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parentView) {
                sourceLanguage = null;
                targetLanguageSpinner.setSelection(0);
                targetLanguageSpinner.setEnabled(false);
                startButton.setEnabled(false);
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
                startButton.setEnabled(false);
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

    private void appendChatBubble(boolean alignRight) {
        mainHandler.post(() -> {
            LayoutInflater inflater = LayoutInflater.from(this);
            LinearLayout chatBubble = (LinearLayout) inflater.inflate(
                    R.layout.chat_bubble,
                    chatArea,
                    false);
            LinearLayout top = chatBubble.findViewById(R.id.top);
            LinearLayout bottom = chatBubble.findViewById(R.id.bottom);
            int gravity = alignRight ? Gravity.RIGHT : Gravity.LEFT;
            top.setGravity(gravity);
            bottom.setGravity(gravity);

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
        engineExecutor.submit(() -> {
            if (voiceProcessor.getIsRecording()) {
                stopRecording();
                flush();
            } else {
                startRecording();
            }
        });
    }

    private void initEngines() {
        mainHandler.post(() -> {
            sourceLanguageSpinner.setEnabled(false);
            targetLanguageSpinner.setEnabled(false);
            startButton.setEnabled(false);
            rootView.invalidate();
        });

        deleteEngines();

        setStatus(String.format("Loading Cheetah %s", sourceLanguage));

        try {
            String model_path = String.format("cheetah_params_%s.pv", sourceLanguage);
            cheetah0 = new Cheetah.Builder()
                    .setAccessKey(ACCESS_KEY)
                    .setModelPath(model_path)
                    .setEnableAutomaticPunctuation(true)
                    .setEnableTextNormalization(true)
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
                    .setEnableTextNormalization(true)
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
            return;
        }

        startRecording();
        startListeningSource();

        mainHandler.post(() -> {
            statusLayout.setVisibility(View.GONE);
            sourceLanguageSpinner.setEnabled(true);
            targetLanguageSpinner.setEnabled(true);
            startButton.setEnabled(true);
            rootView.invalidate();
        });
    }

    private void deleteEngines() {
        stopRecording();

        currentState = State.IDLE;

        if (cheetah0 != null) {
            cheetah0.delete();
            cheetah0 = null;
        }

        if (cheetah1 != null) {
            cheetah1.delete();
            cheetah1 = null;
        }

        if (zebra0 != null) {
            zebra0.delete();
            zebra0 = null;
        }

        if (zebra1 != null) {
            zebra1.delete();
            zebra1 = null;
        }

        if (orca0 != null) {
            orca0.delete();
            orca0 = null;
        }

        if (orca1 != null) {
            orca1.delete();
            orca1 = null;
        }
    }

    private void startRecording() {
        if (voiceProcessor.hasRecordAudioPermission(this)) {
            try {
                voiceProcessor.start(cheetah0.getFrameLength(), cheetah0.getSampleRate());
                mainHandler.post(() -> startButton.setActivated(true));
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

    private void stopRecording() {
        try {
            voiceProcessor.stop();
            mainHandler.post(() -> startButton.setActivated(false));
        } catch (VoiceProcessorException e) {
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
            startRecording();
        }
    }

    private void frameListener(short[] frame) {
        if (currentState == State.LISTENING_SOURCE) {
            mainHandler.post(() -> listenSource(frame));
        } else if (currentState == State.LISTENING_TARGET) {
            mainHandler.post(() -> listenTarget(frame));
        }
    }

    private void startListeningSource() {
        appendChatBubble(true);
        currentState = State.LISTENING_SOURCE;
    }

    private void startListeningTarget() {
        appendChatBubble(false);
        currentState = State.LISTENING_TARGET;
    }

    private void listenSource(short[] frame) {
        try {
            CheetahTranscript transcript = cheetah0.process(frame);
            currentTranscript += transcript.getTranscript();
            sendText(transcript.getTranscript());

            if (transcript.getIsEndpoint()) {
                flushSource();
            }
        } catch (CheetahException e) {
            setError(e.getMessage());
        }
    }

    private void listenTarget(short[] frame) {
        try {
            CheetahTranscript transcript = cheetah1.process(frame);
            currentTranscript += transcript.getTranscript();
            sendText(transcript.getTranscript());

            if (transcript.getIsEndpoint()) {
                flushTarget();
            }
        } catch (CheetahException e) {
            setError(e.getMessage());
        }
    }

    private void flush() {
        if (currentState == State.LISTENING_SOURCE) {
            engineExecutor.submit(this::flushSource);
        } else {
            engineExecutor.submit(this::flushTarget);
        }
    }

    private void flushSource() {
        try {
            CheetahTranscript flush = cheetah0.flush();
            currentTranscript += flush.getTranscript();
            sendText(flush.getTranscript());

            if (!currentTranscript.isEmpty()) {
                swapText();
                currentState = State.IDLE;
                engineExecutor.submit(this::translateSource);
            }
        } catch (CheetahException e) {
            setError(e.getMessage());
        }
    }

    private void flushTarget() {
        try {
            CheetahTranscript flush = cheetah1.flush();
            currentTranscript += flush.getTranscript();
            sendText(flush.getTranscript());

            if (!currentTranscript.isEmpty()) {
                swapText();
                currentState = State.IDLE;
                engineExecutor.submit(this::translateTarget);
            }
        } catch (CheetahException e) {
            setError(e.getMessage());
        }
    }

    private void translateSource() {
        String inputText = currentTranscript;
        currentTranscript = "";

        try {
            String outputText = zebra0.translate(
                    inputText.substring(0, Math.min(
                            inputText.length(),
                            zebra0.getMaxCharacterLimit())));

            speakTarget(outputText);
        } catch (ZebraException e) {
            setError(e.getMessage());
        }
    }

    private void translateTarget() {
        String inputText = currentTranscript;
        currentTranscript = "";

        try {
            String outputText = zebra1.translate(
                    inputText.substring(0, Math.min(
                            inputText.length(),
                            zebra1.getMaxCharacterLimit())));

            speakSource(outputText);
        } catch (ZebraException e) {
            setError(e.getMessage());
        }
    }

    private void speakSource(String outputText) {
        try {
            OrcaSynthesizeParams params = new OrcaSynthesizeParams.Builder()
                    .build();

            OrcaAudio audio = orca0.synthesize(outputText, params);

            playAudio(audio, this::startListeningSource);
        } catch (OrcaException e) {
            setError(e.getMessage());
        }
    }

    private void speakTarget(String outputText) {
        try {
            OrcaSynthesizeParams params = new OrcaSynthesizeParams.Builder()
                    .build();

            OrcaAudio audio = orca1.synthesize(outputText, params);

            playAudio(audio, this::startListeningTarget);
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