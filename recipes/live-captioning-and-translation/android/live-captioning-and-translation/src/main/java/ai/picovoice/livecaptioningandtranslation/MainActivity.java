package ai.picovoice.livecaptioningandtranslation;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
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
import androidx.constraintlayout.widget.ConstraintLayout;
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

    private enum State {
        IDLE,
        LISTENING,
        ERROR
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final ExecutorService engineExecutor = Executors.newSingleThreadExecutor();

    private String sourceLanguage;

    private String targetLanguage;

    private State currentState = State.IDLE;

    private int translationIndex = 0;

    private final VoiceProcessor voiceProcessor = VoiceProcessor.getInstance();

    private Cheetah cheetah = null;

    private Zebra zebra = null;

    private View rootView;

    private Spinner sourceLanguageSpinner;

    private Spinner targetLanguageSpinner;

    private LinearLayout statusLayout;

    private TextView statusText;

    private ScrollView scrollArea;

    private LinearLayout chatArea;

    private Button useMicButton;

    private Button useFileButton;

    private Button backButton;

    private VolumeMeterView volumeMeterView;

    private ConstraintLayout volumeMeterLayout;

    private LinearLayout spinnerLayout;

    private LinearLayout buttonLayout;

    private final ArrayList<LinearLayout> chatBubbles = new ArrayList<>();

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

        initStatusLayout();
        initSpinners();
        initChatArea();

        rootView.invalidate();
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
            rootView.invalidate();
        });
    }

    private void setError(String text) {
        currentState = State.ERROR;
        mainHandler.post(() -> {
            int colorDanger = getResources().getColor(R.color.colorDanger);
            statusText.setText(text);
            statusText.setTextColor(colorDanger);
            statusLayout.setVisibility(View.VISIBLE);
            rootView.invalidate();
        });
    }

    private void initSpinners() {
        sourceLanguageSpinner = findViewById(R.id.sourceLanguage);
        targetLanguageSpinner = findViewById(R.id.targetLanguage);
        useMicButton = findViewById(R.id.useMicButton);
        useFileButton = findViewById(R.id.useFileButton);
        backButton = findViewById(R.id.backButton);
        volumeMeterView = findViewById(R.id.volumeMeterView);

        volumeMeterLayout = findViewById(R.id.volumeMeterLayout);
        spinnerLayout = findViewById(R.id.spinnerLayout);
        buttonLayout = findViewById(R.id.buttonLayout);

        targetLanguageSpinner.setEnabled(false);
        useMicButton.setEnabled(false);
        useFileButton.setEnabled(false);
        volumeMeterLayout.setVisibility(View.GONE);

        ArrayAdapter<String> sourceLanguageAdapter = new ArrayAdapter<String>(
                this,
                android.R.layout.simple_list_item_1,
                displayLanguages
        ) {
            @Override
            public boolean isEnabled(int position) {
                return position > 0;
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
                useMicButton.setEnabled(false);
                useFileButton.setEnabled(false);
                rootView.invalidate();
            }
        });

        targetLanguageSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parentView, View selectedItemView, int position, long id) {
                if (position > 0) {
                    targetLanguage = languages[position];
                    rootView.invalidate();

                    useMicButton.setEnabled(true);
                    useFileButton.setEnabled(true);
                } else {
                    this.onNothingSelected(parentView);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parentView) {
                targetLanguage = null;
                useMicButton.setEnabled(false);
                useFileButton.setEnabled(false);
                rootView.invalidate();
            }
        });

        useMicButton.setOnClickListener(v -> {
            startDemo();
        });

        backButton.setOnClickListener(v -> {
            stopDemo();
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
            if (!chatBubbles.isEmpty()) {
                LinearLayout chatBubble = chatBubbles.get(chatBubbles.size() - 1);
                LinearLayout top = chatBubble.findViewById(R.id.top);
                TextView topText = chatBubble.findViewById(R.id.topText);
                TextView bottomText = chatBubble.findViewById(R.id.bottomText);
                String text = bottomText.getText().toString();

                if (text.isEmpty()) {
                    return;
                }

                topText.setText(text);
                bottomText.setText("");
                top.setVisibility(View.VISIBLE);
                rootView.invalidate();

                scrollArea.post(() -> {
                    scrollArea.fullScroll(View.FOCUS_DOWN);
                });
            }

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
            LinearLayout chatBubble = chatBubbles.get(chatBubbles.size() - 1);
            TextView bottomText = chatBubble.findViewById(R.id.bottomText);
            String prevText = bottomText.getText().toString();

            if (prevText.isEmpty()) {
                bottomText.setText(String.format("%s%s", prevText, text.stripLeading()));
            } else {
                bottomText.setText(String.format("%s%s", prevText, text));
            }

            scrollArea.post(() -> {
                scrollArea.fullScroll(View.FOCUS_DOWN);
            });
        });
    }

    private void startDemo() {
        setStatus("Loading...");
        clearChatArea();
        translationIndex = 0;
        engineExecutor.submit(this::initEngines);
    }

    private void stopDemo() {
        volumeMeterLayout.setVisibility(View.GONE);
        clearChatArea();
        engineExecutor.submit(this::deleteEngines);
    }

    private void initEngines() {
        mainHandler.post(() -> {
            sourceLanguageSpinner.setEnabled(false);
            targetLanguageSpinner.setEnabled(false);
            useMicButton.setEnabled(false);
            useFileButton.setEnabled(false);
            rootView.invalidate();
        });

        deleteEngines();

        setStatus(String.format("Loading Cheetah %s...", sourceLanguage));

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

        setStatus(String.format("Loading Zebra %s-%s...", sourceLanguage, targetLanguage));

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

        startRecording();
        startListeningSource();

        mainHandler.post(() -> {
            statusLayout.setVisibility(View.GONE);
            sourceLanguageSpinner.setEnabled(true);
            targetLanguageSpinner.setEnabled(true);
            useMicButton.setEnabled(true);
            useFileButton.setEnabled(true);
            volumeMeterLayout.setVisibility(View.VISIBLE);
            spinnerLayout.setVisibility(View.GONE);
            buttonLayout.setVisibility(View.GONE);
            rootView.invalidate();
        });
    }

    private void deleteEngines() {
        stopRecording();

        currentState = State.IDLE;

        if (cheetah != null) {
            cheetah.delete();
            cheetah = null;
        }

        if (zebra != null) {
            zebra.delete();
            zebra = null;
        }

        mainHandler.post(() -> {
            volumeMeterLayout.setVisibility(View.GONE);
            spinnerLayout.setVisibility(View.VISIBLE);
            buttonLayout.setVisibility(View.VISIBLE);
            rootView.invalidate();
        });
    }

    private void startRecording() {
        if (voiceProcessor.hasRecordAudioPermission(this)) {
            engineExecutor.submit(() -> {
                try {
                    voiceProcessor.start(cheetah.getFrameLength(), cheetah.getSampleRate());
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
        volumeMeterView.processFrame(frame);

        if (currentState == State.LISTENING) {
            engineExecutor.submit(() -> listenSource(frame));
        }
    }

    private void startListeningSource() {
        appendChatBubble();
        currentState = State.LISTENING;
    }

    private void listenSource(short[] frame) {
        try {
            CheetahTranscript transcript = cheetah.process(frame);
            sendText(transcript.getTranscript());

            if (transcript.getIsEndpoint()) {
                flushSource();
            }
        } catch (CheetahException e) {
            setError(e.getMessage());
        }
    }

    private void flushSource() {
        try {
            CheetahTranscript flush = cheetah.flush();
            sendText(flush.getTranscript());
            appendChatBubble();
            translateSource();
        } catch (CheetahException e) {
            setError(e.getMessage());
        }
    }

    private void translateSource() {
        mainHandler.post(() -> {
            if (translationIndex < chatBubbles.size() - 1) {
                int index = translationIndex;
                translationIndex += 1;

                LinearLayout chatBubble = chatBubbles.get(index);
                TextView topText = chatBubble.findViewById(R.id.topText);
                TextView bottomText = chatBubble.findViewById(R.id.bottomText);
                String currentTranscript = topText.getText().toString();

                engineExecutor.submit(() -> {
                    try {
                        String outputText = zebra.translate(
                                currentTranscript.substring(0, Math.min(
                                        currentTranscript.length(),
                                        zebra.getMaxCharacterLimit())));
    
                        mainHandler.post(() -> {
                            bottomText.setText(outputText);
                        });
                    } catch (ZebraException e) {
                        setError(e.getMessage());
                    }
                });
            }
        });
    }
}