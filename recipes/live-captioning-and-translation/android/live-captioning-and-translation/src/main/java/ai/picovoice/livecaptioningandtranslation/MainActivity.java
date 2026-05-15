package ai.picovoice.livecaptioningandtranslation;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.app.ActivityCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private final ExecutorService speakingExecutor = Executors.newSingleThreadExecutor();

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

    private ProgressBar progressBarView;

    private ConstraintLayout volumeMeterLayout;

    private LinearLayout spinnerLayout;

    private LinearLayout buttonLayout;

    private final ArrayList<LinearLayout> chatBubbles = new ArrayList<>();

    Pattern pattern = Pattern.compile("^(.*[.!?])(\\s.*)?$");

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
        progressBarView = findViewById(R.id.progressBarView);

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

        ActivityResultLauncher<Intent> fileSelectLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK) {
                        Intent intent = result.getData();
                        if (intent != null) {
                            Uri data = intent.getData();
                            if (data != null) {
                                short[] pcm = loadPCM(data);
                                if (pcm != null) {
                                    speakDemo(pcm);
                                }
                            }
                        }
                    }
                }
        );

        useFileButton.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("audio/*");
            fileSelectLauncher.launch(intent);
        });

        backButton.setOnClickListener(v -> {
            stopDemo();
        });
    }

    public short[] loadPCM(Uri file) {
        try (ParcelFileDescriptor fd = getContentResolver().openFileDescriptor(file, "r")) {
            if (fd == null) {
                return null;
            }

            MediaExtractor extractor = new MediaExtractor();
            extractor.setDataSource(fd.getFileDescriptor());

            int trackIndex = -1;
            MediaFormat format = null;

            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    trackIndex = i;
                    format = f;
                    break;
                }
            }

            if (trackIndex == -1) {
                return null;
            }

            extractor.selectTrack(trackIndex);

            format.setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_AUDIO_RAW);
            format.setInteger(MediaFormat.KEY_SAMPLE_RATE, 16000);
            format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1);
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);

            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime == null) {
                return null;
            }

            MediaCodec codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();

            ByteBuffer[] inputBuffers = codec.getInputBuffers();
            ByteBuffer[] outputBuffers = codec.getOutputBuffers();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            ByteArrayOutputStream pcmOutputStream = new ByteArrayOutputStream();
            boolean isEOS = false;

            while (!isEOS) {
                int inIndex = codec.dequeueInputBuffer(10000);
                if (inIndex >= 0) {
                    ByteBuffer buffer = inputBuffers[inIndex];
                    int sampleSize = extractor.readSampleData(buffer, 0);
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        isEOS = true;
                    } else {
                        codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.getSampleTime(), 0);
                        extractor.advance();
                    }
                }

                int outIndex = codec.dequeueOutputBuffer(info, 10000);
                if (outIndex >= 0) {
                    ByteBuffer outBuffer = outputBuffers[outIndex];
                    byte[] chunk = new byte[info.size];
                    outBuffer.get(chunk);
                    pcmOutputStream.write(chunk, 0, info.size);
                    codec.releaseOutputBuffer(outIndex, false);
                }
            }

            codec.stop();
            codec.release();
            extractor.release();

            byte[] bytes = pcmOutputStream.toByteArray();
            ByteBuffer byteBuffer = ByteBuffer.wrap(bytes);
            byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
            ShortBuffer shortBuffer = byteBuffer.asShortBuffer();
            short[] shorts = new short[shortBuffer.remaining()];
            shortBuffer.get(shorts);
            return shorts;
        } catch (IOException e) {
            return null;
        }
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
        engineExecutor.submit(() -> initEngines(true));
    }

    private void stopDemo() {
        volumeMeterLayout.setVisibility(View.GONE);
        clearChatArea();
        engineExecutor.submit(this::deleteEngines);
    }

    private void speakDemo(short[] pcm) {
        setStatus("Loading...");
        clearChatArea();
        translationIndex = 0;

        speakingExecutor.submit(() -> {
            initEngines(false);

            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();

            AudioFormat audioFormat = new AudioFormat.Builder()
                    .setSampleRate(cheetah.getSampleRate())
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build();

            AudioTrack ttsOutput = new AudioTrack(
                    audioAttributes,
                    audioFormat,
                    AudioTrack.getMinBufferSize(
                            cheetah.getSampleRate(),
                            AudioFormat.CHANNEL_OUT_MONO,
                            AudioFormat.ENCODING_PCM_16BIT),
                    AudioTrack.MODE_STREAM,
                    0);

            ttsOutput.play();
            int pcnWritten = ttsOutput.write(
                    pcm,
                    0,
                    pcm.length,
                    AudioTrack.WRITE_NON_BLOCKING);

            final int frameLength = cheetah.getFrameLength();
            long startTime = System.currentTimeMillis();
            for (int i = 0; i < pcm.length - frameLength + 1; i += frameLength) {
                short[] frame = new short[frameLength];
                System.arraycopy(pcm, i, frame, 0, frameLength);

                if (pcnWritten < pcm.length) {
                    pcnWritten += ttsOutput.write(
                            pcm,
                            pcnWritten,
                            pcm.length - pcnWritten,
                            AudioTrack.WRITE_NON_BLOCKING);
                }

                long duration = System.currentTimeMillis() - startTime;
                long expected = i * 1000L / cheetah.getSampleRate();
                if (expected > duration) {
                    try {
                        Thread.sleep(expected - duration);
                    } catch (InterruptedException ignored) {}
                }

                if (currentState != State.LISTENING) {
                    ttsOutput.stop();
                    break;
                }

                frameListener(frame);
            }

            if (ttsOutput.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                ttsOutput.flush();
                ttsOutput.stop();
            }

            ttsOutput.release();
        });
    }

    private void initEngines(boolean useMic) {
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

        if (useMic) {
            startRecording();
        }

        startListeningSource();

        mainHandler.post(() -> {
            statusLayout.setVisibility(View.GONE);
            sourceLanguageSpinner.setEnabled(true);
            targetLanguageSpinner.setEnabled(true);
            useMicButton.setEnabled(true);
            useFileButton.setEnabled(true);
            volumeMeterView.setVisibility(useMic ? View.VISIBLE : View.GONE);
            progressBarView.setVisibility(useMic ? View.GONE : View.VISIBLE);
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
            processText(transcript.getTranscript());

            if (transcript.getIsEndpoint()) {
                CheetahTranscript flush = cheetah.flush();
                processText(flush.getTranscript());
                appendChatBubble();
                translateSource();
            }
        } catch (CheetahException e) {
            setError(e.getMessage());
        }
    }

    private void processText(String text) {
        Matcher matcher = pattern.matcher(text);

        if (matcher.find()) {
            String t0 = matcher.group(1);
            String t1 = matcher.group(2);
            sendText(t0 != null ? t0 : "");
            appendChatBubble();
            translateSource();
            sendText(t1 != null ? t1 : "");
        } else {
            sendText(text);
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

                            scrollArea.post(() -> {
                                scrollArea.fullScroll(View.FOCUS_DOWN);
                            });
                        });
                    } catch (ZebraException e) {
                        setError(e.getMessage());
                    }
                });
            }
        });
    }
}