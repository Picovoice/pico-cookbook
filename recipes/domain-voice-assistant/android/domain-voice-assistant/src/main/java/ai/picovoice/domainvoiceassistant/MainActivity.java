/*
    Copyright 2026 Picovoice Inc.

    You may not use this file except in compliance with the license. A copy of the license is
    located in the "LICENSE" file accompanying this source.

    Unless required by applicable law or agreed to in writing, software distributed under the
    License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
    express or implied. See the License for the specific language governing permissions and
    limitations under the License.
*/

package ai.picovoice.domainvoiceassistant;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ai.picovoice.android.voiceprocessor.VoiceProcessor;
import ai.picovoice.android.voiceprocessor.VoiceProcessorException;
import ai.picovoice.cheetah.Cheetah;
import ai.picovoice.cheetah.CheetahException;
import ai.picovoice.cheetah.CheetahTranscript;
import ai.picovoice.orca.Orca;
import ai.picovoice.orca.OrcaException;
import ai.picovoice.orca.OrcaSynthesizeParams;
import ai.picovoice.picollm.PicoLLM;
import ai.picovoice.picollm.PicoLLMCompletion;
import ai.picovoice.picollm.PicoLLMDialog;
import ai.picovoice.picollm.PicoLLMException;
import ai.picovoice.picollm.PicoLLMGenerateParams;
import ai.picovoice.porcupine.Porcupine;
import ai.picovoice.porcupine.PorcupineException;

public class MainActivity extends AppCompatActivity {

    private enum UIState {
        LOADING,
        WAITING,
        LISTENING,
        RESPONDING
    }

    private static final String ACCESS_KEY = "${YOUR_ACCESS_KEY_HERE}";

    private static final String KEYWORD_FILE = "domain_voice_assistant_android.ppn";

    private static final float WAKE_WORD_SENSITIVITY = 0.7f;

    private static final String STT_MODEL_FILE = "cheetah_params.pv";

    private static final String TTS_MODEL_FILE = "orca_params.pv";

    private static final String LLM_MODEL_FILE = "picollm_model.pllm";

    private static final String SYSTEM_PROMPT =
            "You are Picovoice's chatbot on picovoice.ai. You are kind, polite and diplomatic, and you reply in " +
            "English only. You help visitors understand Picovoice's on-device voice, language, and vision AI " +
            "products, choose between them, get started, and find the right contact. Answer directly and " +
            "completely from Picovoice's documentation. Never state prices, plan limits, or numbers that are not " +
            "documented; if something is not documented, say so and point to the docs, Picovoice Console, GitHub, " +
            "the contact form, or hello@picovoice.ai." +
            "\n\n" +
            "Before saying an engine has or lacks an SDK, language or platform, be sure; if you are not sure, say " +
            "so and point to that engine's product page. Never invent product names, recipes, links, prices or " +
            "features. Answer the question that was asked, and do not add limitations nobody asked about.";

    private static final int DIALOG_HISTORY = 2;

    private static final int COMPLETION_TOKEN_LIMIT = 256;

    private static final float TEMPERATURE = 0f;

    private static final String STOP_PHRASE = "<|eot_id|>";

    private static final String LINK_WORDS = "the link shown on screen";

    private static final Pattern LINK_START_PATTERN = Pattern.compile("^(?:https?:|www\\.)", Pattern.CASE_INSENSITIVE);

    private static final Pattern LINK_TAIL_PATTERN = Pattern.compile("[.,!?:;]*$");

    private static final Pattern SENTENCE_END_PATTERN = Pattern.compile("[.!?]\\s+");

    private static final float ENDPOINT_DURATION_SEC = 1.0f;

    private static final long WAKE_WORD_LISTEN_TIMEOUT_MS = 8000;

    private static final long FOLLOW_UP_WINDOW_MS = 8000;

    private static final float PLAYBACK_START_BUFFER_SEC = 0.5f;

    private static class Segment {
        String text = "";
        int synthesizedSamples = 0;
        boolean isComplete = false;
    }

    private static class Exchange {
        final String question;
        final String answer;

        Exchange(String question, String answer) {
            this.question = question;
            this.answer = answer;
        }
    }

    private static class Sentence {
        final String text;
        final boolean isEnd;

        Sentence(String text, boolean isEnd) {
            this.text = text;
            this.isEnd = isEnd;
        }
    }

    private class Turn {
        final String question;
        final List<Segment> segments = new ArrayList<>();
        final Player player;
        Orca.OrcaStream stream = null;
        String completion = "";
        volatile boolean isInterrupted = false;

        Turn(String question) {
            this.question = question;
            this.player = new Player(this);
        }

        String spokenText() {
            int heard = player.heardSamples();
            StringBuilder spoken = new StringBuilder();
            for (Segment segment : segments) {
                if (segment.isComplete && heard >= segment.synthesizedSamples) {
                    spoken.append(segment.text);
                    heard -= segment.synthesizedSamples;
                    continue;
                }
                if (segment.synthesizedSamples > 0 && heard > 0) {
                    String[] words = segment.text.trim().split("\\s+");
                    int count = (int) Math.floor(words.length * ((double) heard / segment.synthesizedSamples));
                    for (int i = 0; i < count; i++) {
                        spoken.append(i > 0 ? " " : "").append(words[i]);
                    }
                }
                break;
            }
            return spoken.toString().replaceAll("\\s+", " ").trim();
        }
    }

    private static class SpeechText {
        private final Set<Character> validCharacters;
        private String pending = "";
        private boolean isInLink = false;

        SpeechText(String[] validCharacters) {
            this.validCharacters = new HashSet<>();
            for (String character : validCharacters) {
                if (character.length() > 0) {
                    this.validCharacters.add(character.charAt(0));
                }
            }
        }

        String push(String text) {
            pending += text;
            if (isInLink) {
                Matcher space = Pattern.compile("\\s").matcher(pending);
                if (!space.find()) {
                    return "";
                }
                pending = linkTail(pending.substring(0, space.start())) + pending.substring(space.start());
                isInLink = false;
            }

            Matcher wordMatcher = Pattern.compile("\\S*$").matcher(pending);
            int start = wordMatcher.find() ? wordMatcher.start() : pending.length();
            String word = pending.substring(start);
            if (LINK_START_PATTERN.matcher(word).find()) {
                String before = pending.substring(0, start);
                pending = word;
                isInLink = true;
                return clean(before) + LINK_WORDS;
            }

            int end = isHeld(word) ? start : pending.length();
            String speech = pending.substring(0, end);
            pending = pending.substring(end);
            return clean(speech);
        }

        String flush() {
            String speech = isInLink ? linkTail(pending) : pending;
            pending = "";
            isInLink = false;
            return clean(speech);
        }

        private static String linkTail(String text) {
            Matcher matcher = LINK_TAIL_PATTERN.matcher(text);
            return matcher.find() ? matcher.group() : "";
        }

        private static boolean isHeld(String word) {
            String lower = word.toLowerCase();
            return Pattern.compile("[.!?:]").matcher(word).find() ||
                    "https".startsWith(lower) ||
                    "www".startsWith(lower);
        }

        private String clean(String text) {
            StringBuilder cleaned = new StringBuilder();
            for (int i = 0; i < text.length(); i++) {
                char character = text.charAt(i);
                cleaned.append(validCharacters.contains(character) ? character : ' ');
            }
            return cleaned.toString().replaceAll(" {2,}", " ");
        }
    }

    private class Player {
        private final Turn turn;
        private final AudioTrack track;
        private final ConcurrentLinkedQueue<short[]> queue = new ConcurrentLinkedQueue<>();
        private int bufferedSamples = 0;
        private int writtenSamples = 0;
        private volatile boolean isPlaying = false;
        private volatile boolean isTextDone = false;
        private volatile boolean isStopped = false;
        private int heardAtStop = 0;

        Player(Turn turn) {
            this.turn = turn;

            int sampleRate = orca.getSampleRate();
            int minBufferSize = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            track = new AudioTrack(
                    new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    new AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    Math.max(minBufferSize, sampleRate * 2),
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE);
        }

        synchronized void add(short[] pcm) {
            if (isStopped) {
                return;
            }
            queue.add(pcm);
            bufferedSamples += pcm.length;
            if (!isPlaying && bufferedSamples >= orca.getSampleRate() * PLAYBACK_START_BUFFER_SEC) {
                start();
            }
        }

        synchronized void finishText() {
            isTextDone = true;
            if (!isPlaying && !isStopped) {
                start();
            }
        }

        synchronized void stop() {
            if (isStopped) {
                return;
            }
            isStopped = true;
            if (isPlaying) {
                heardAtStop = track.getPlaybackHeadPosition();
                track.pause();
                track.flush();
            } else {
                track.release();
            }
        }

        synchronized int heardSamples() {
            if (isStopped) {
                return heardAtStop;
            }
            return isPlaying ? track.getPlaybackHeadPosition() : 0;
        }

        private void start() {
            track.play();
            isPlaying = true;
            playbackExecutor.submit(this::run);
            mainHandler.post(() -> onSpeaking(turn));
        }

        private void run() {
            try {
                while (!isStopped) {
                    short[] pcm = queue.poll();
                    if (pcm == null) {
                        if (isTextDone && queue.isEmpty() && track.getPlaybackHeadPosition() >= writtenSamples) {
                            break;
                        }
                        sleep(10);
                        continue;
                    }

                    int offset = 0;
                    while (offset < pcm.length && !isStopped) {
                        int written = track.write(pcm, offset, pcm.length - offset);
                        if (written < 0) {
                            break;
                        }
                        offset += written;
                    }
                    writtenSamples += offset;
                }
            } catch (RuntimeException e) {
                onEngineProcessError(e.toString());
            }

            boolean isFinished;
            synchronized (this) {
                isFinished = !isStopped;
                isStopped = true;
                track.stop();
                track.release();
            }
            if (isFinished) {
                mainHandler.post(() -> finishTurn(turn));
            }
        }
    }

    private interface TranscriptListener {
        void onTranscript(String transcript, boolean isEndpoint);
    }

    private final VoiceProcessor voiceProcessor = VoiceProcessor.getInstance();

    private Porcupine porcupine;
    private Cheetah cheetah;
    private PicoLLM picollm;
    private Orca orca;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final ExecutorService engineExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService llmExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService ttsExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService playbackExecutor = Executors.newSingleThreadExecutor();

    private final Object stateLock = new Object();

    private UIState currentState = UIState.LOADING;

    private String transcript = "";
    private Runnable listenTimeout = null;

    private Turn turn = null;
    private final List<Exchange> exchanges = new ArrayList<>();

    private short[] cheetahBuffer = new short[0];

    private ConstraintLayout loadModelLayout;
    private ConstraintLayout chatLayout;

    private TextView loadModelText;
    private ProgressBar loadModelProgress;

    private TextView chatText;
    private ScrollView chatTextScrollView;
    private TextView statusText;
    private ProgressBar statusProgress;
    private ImageButton clearTextButton;
    private EditText promptEditText;
    private ImageButton promptButton;

    private SpannableStringBuilder chatTextBuilder = new SpannableStringBuilder();

    private int spanColour;

    @SuppressLint("SetTextI18n")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main_layout);

        loadModelLayout = findViewById(R.id.loadModelLayout);
        chatLayout = findViewById(R.id.chatLayout);

        loadModelText = findViewById(R.id.loadModelText);
        loadModelProgress = findViewById(R.id.loadModelProgress);

        spanColour = ContextCompat.getColor(this, R.color.colorPrimary);

        chatText = findViewById(R.id.chatText);
        chatTextScrollView = findViewById(R.id.chatScrollView);
        statusText = findViewById(R.id.statusText);
        statusProgress = findViewById(R.id.statusProgress);

        clearTextButton = findViewById(R.id.clearButton);
        clearTextButton.setOnClickListener(view -> {
            synchronized (stateLock) {
                interruptTurn();
                exchanges.clear();
                if (currentState != UIState.LOADING) {
                    waitForWakeWord();
                }
            }
            mainHandler.post(() -> {
                chatTextBuilder = new SpannableStringBuilder();
                chatText.setText("");
            });
        });

        promptEditText = findViewById(R.id.promptEditText);
        promptButton = findViewById(R.id.promptButton);
        promptButton.setOnClickListener(view -> ask());
        promptEditText.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                ask();
                return true;
            }
            return false;
        });

        updateUIState(UIState.LOADING);
        engineExecutor.submit(() -> {
            try {
                initEngines();
            } catch (RuntimeException e) {
                onEngineInitError(e.toString());
            }
        });
    }

    private void ask() {
        String question = promptEditText.getText().toString().trim();
        if (question.isEmpty()) {
            return;
        }
        promptEditText.setText("");

        synchronized (stateLock) {
            if (currentState == UIState.LOADING) {
                return;
            }
            interruptTurn();
            if (currentState == UIState.LISTENING) {
                removeUserLabel();
            }
            appendUserLabel(question);
            startTurn(question);
        }
    }

    private void initEngines() {
        mainHandler.post(() -> loadModelText.setText("Loading Porcupine..."));
        try {
            porcupine = new Porcupine.Builder()
                    .setAccessKey(ACCESS_KEY)
                    .setKeywordPath(KEYWORD_FILE)
                    .setSensitivity(WAKE_WORD_SENSITIVITY)
                    .build(getApplicationContext());
        } catch (PorcupineException e) {
            onEngineInitError(e.getMessage());
            return;
        }

        mainHandler.post(() -> loadModelText.setText("Loading Cheetah..."));
        try {
            cheetah = new Cheetah.Builder()
                    .setAccessKey(ACCESS_KEY)
                    .setModelPath(STT_MODEL_FILE)
                    .setEndpointDuration(ENDPOINT_DURATION_SEC)
                    .setEnableAutomaticPunctuation(true)
                    .setEnableTextNormalization(true)
                    .build(getApplicationContext());
        } catch (CheetahException e) {
            onEngineInitError(e.getMessage());
            return;
        }

        mainHandler.post(() -> loadModelText.setText("Loading picoLLM..."));
        File llmModelFile = extractModelFile(LLM_MODEL_FILE);
        if (llmModelFile == null) {
            onEngineInitError("Unable to read the picoLLM model from the app assets");
            return;
        }
        try {
            picollm = new PicoLLM.Builder()
                    .setAccessKey(ACCESS_KEY)
                    .setModelPath(llmModelFile.getAbsolutePath())
                    .setEnableContextCaching(true)
                    .build();

            PicoLLMDialog dialog = picollm.getDialogBuilder()
                    .setHistory(0)
                    .setSystem(SYSTEM_PROMPT)
                    .build();
            dialog.addHumanRequest("");
            picollm.generate(
                    dialog.getPrompt(),
                    new PicoLLMGenerateParams.Builder().setCompletionTokenLimit(1).build());
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

        voiceProcessor.addFrameListener(this::onFrame);
        voiceProcessor.addErrorListener(error -> onEngineProcessError(error.getMessage()));

        synchronized (stateLock) {
            waitForWakeWord();
        }
        startListening();
    }

    private void releaseEngines() {
        try {
            voiceProcessor.stop();
        } catch (VoiceProcessorException e) {
            onEngineProcessError(e.getMessage());
        }
        voiceProcessor.clearFrameListeners();
        voiceProcessor.clearErrorListeners();

        if (porcupine != null) {
            porcupine.delete();
            porcupine = null;
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
    }

    private void onFrame(short[] frame) {
        synchronized (stateLock) {
            try {
                switch (currentState) {
                    case WAITING:
                        if (porcupine.process(frame) == 0) {
                            listen(true);
                        }
                        break;
                    case LISTENING:
                        if (porcupine.process(frame) == 0) {
                            removeUserLabel();
                            listen(true);
                        } else {
                            feedCheetah(frame, this::onListeningTranscript);
                        }
                        break;
                    case RESPONDING:
                        if (porcupine.process(frame) == 0) {
                            interruptTurn();
                            listen(true);
                        }
                        break;
                    default:
                        break;
                }
            } catch (PorcupineException | CheetahException e) {
                onEngineProcessError(e.getMessage());
            }
        }
    }

    private void feedCheetah(short[] frame, TranscriptListener listener) throws CheetahException {
        short[] buffer = Arrays.copyOf(cheetahBuffer, cheetahBuffer.length + frame.length);
        System.arraycopy(frame, 0, buffer, cheetahBuffer.length, frame.length);

        int start = 0;
        while (start + cheetah.getFrameLength() <= buffer.length) {
            CheetahTranscript result = cheetah.process(
                    Arrays.copyOfRange(buffer, start, start + cheetah.getFrameLength()));
            start += cheetah.getFrameLength();
            String text = result.getTranscript();
            if (result.getIsEndpoint()) {
                text += cheetah.flush().getTranscript();
                cheetahBuffer = new short[0];
                listener.onTranscript(text, true);
                return;
            }
            listener.onTranscript(text, false);
        }
        cheetahBuffer = Arrays.copyOfRange(buffer, start, buffer.length);
    }

    private void resetCheetah() {
        cheetahBuffer = new short[0];
        try {
            cheetah.flush();
        } catch (CheetahException e) {
            onEngineProcessError(e.getMessage());
        }
    }

    private void clearListenTimeout() {
        if (listenTimeout != null) {
            mainHandler.removeCallbacks(listenTimeout);
            listenTimeout = null;
        }
    }

    private void waitForWakeWord() {
        clearListenTimeout();
        currentState = UIState.WAITING;
        updateUIState(UIState.WAITING);
    }

    private void listen(boolean afterWakeWord) {
        clearListenTimeout();
        currentState = UIState.LISTENING;
        transcript = "";
        resetCheetah();
        updateUIState(UIState.LISTENING);
        appendUserLabel("");

        listenTimeout = () -> {
            synchronized (stateLock) {
                if (currentState == UIState.LISTENING && transcript.trim().isEmpty()) {
                    removeUserLabel();
                    waitForWakeWord();
                }
            }
        };
        mainHandler.postDelayed(listenTimeout, afterWakeWord ? WAKE_WORD_LISTEN_TIMEOUT_MS : FOLLOW_UP_WINDOW_MS);
    }

    private void onListeningTranscript(String text, boolean isEndpoint) {
        if (text.length() > 0) {
            clearListenTimeout();
            transcript += text;
            appendChatText(text);
        }
        if (isEndpoint && !transcript.trim().isEmpty()) {
            startTurn(transcript.trim());
        }
    }

    private void addExchange(String question, String answer) {
        exchanges.add(new Exchange(question, answer));
        while (exchanges.size() > DIALOG_HISTORY) {
            exchanges.remove(0);
        }
    }

    private void startTurn(String question) {
        clearListenTimeout();
        currentState = UIState.RESPONDING;
        transcript = "";

        Turn current = new Turn(question);
        turn = current;
        updateUIState(UIState.RESPONDING);
        appendAssistantLabel();

        List<Exchange> history = new ArrayList<>(exchanges);
        llmExecutor.submit(() -> respond(current, history));
    }

    private void respond(Turn current, List<Exchange> history) {
        SpeechText speechText = new SpeechText(orca.getValidCharacters());
        StringBuilder raw = new StringBuilder();
        boolean[] isTextDone = {false};

        Runnable finishText = () -> {
            if (isTextDone[0]) {
                return;
            }
            isTextDone[0] = true;
            for (Sentence sentence : splitSentences(speechText.flush())) {
                queueSpeech(current, sentence.text, sentence.isEnd, raw.toString());
                raw.setLength(0);
            }
            queueSpeech(current, "", true, raw.toString());
            ttsExecutor.submit(() -> endSpeech(current));
        };

        try {
            PicoLLMDialog dialog = picollm.getDialogBuilder()
                    .setHistory(Math.min(DIALOG_HISTORY, history.size()))
                    .setSystem(SYSTEM_PROMPT)
                    .build();
            for (Exchange exchange : history) {
                dialog.addHumanRequest(exchange.question);
                dialog.addLLMResponse(exchange.answer);
            }
            dialog.addHumanRequest(current.question);

            PicoLLMCompletion completion = picollm.generate(
                    dialog.getPrompt(),
                    new PicoLLMGenerateParams.Builder()
                            .setCompletionTokenLimit(COMPLETION_TOKEN_LIMIT)
                            .setTemperature(TEMPERATURE)
                            .setStopPhrases(new String[]{STOP_PHRASE})
                            .setStreamCallback(token -> {
                                String text = token.replace(STOP_PHRASE, "");
                                if (current.isInterrupted || isTextDone[0]) {
                                    return;
                                }
                                if (text.isEmpty()) {
                                    if (token.contains(STOP_PHRASE)) {
                                        finishText.run();
                                    }
                                    return;
                                }

                                appendChatText(text);
                                raw.append(text);
                                for (Sentence sentence : splitSentences(speechText.push(text))) {
                                    queueSpeech(current, sentence.text, sentence.isEnd, raw.toString());
                                    raw.setLength(0);
                                }
                            })
                            .build());

            finishText.run();
            current.completion = completion.getCompletion().replace(STOP_PHRASE, "").trim();
            ttsExecutor.submit(() -> {
                if (!current.isInterrupted) {
                    try {
                        current.player.finishText();
                    } catch (RuntimeException e) {
                        onSpeechError(current, e.toString());
                    }
                }
            });
        } catch (PicoLLMException | RuntimeException e) {
            onEngineProcessError(e.toString());
            synchronized (stateLock) {
                if (turn == current) {
                    interruptTurn();
                    listen(false);
                }
            }
        }
    }

    private void queueSpeech(Turn current, String text, boolean isSentenceEnd, String raw) {
        ttsExecutor.submit(() -> speak(current, text, isSentenceEnd, raw));
    }

    private void speak(Turn current, String text, boolean isSentenceEnd, String raw) {
        if (current.isInterrupted) {
            return;
        }

        Segment last = current.segments.isEmpty() ? null : current.segments.get(current.segments.size() - 1);
        if (last == null || (last.isComplete && !text.trim().isEmpty())) {
            last = new Segment();
            current.segments.add(last);
        }
        last.text += raw;

        try {
            if (!text.isEmpty()) {
                if (current.stream == null) {
                    current.stream = orca.streamOpen(new OrcaSynthesizeParams.Builder().build());
                }
                playPcm(current, last, current.stream.synthesize(text));
            }
            if ((isSentenceEnd || text.endsWith(LINK_WORDS)) && current.stream != null) {
                playPcm(current, last, current.stream.flush());
            }
        } catch (OrcaException e) {
            onEngineProcessError(e.getMessage());
            if (current.stream != null) {
                current.stream.close();
                current.stream = null;
            }
        } catch (RuntimeException e) {
            onSpeechError(current, e.toString());
        }

        if (isSentenceEnd) {
            last.isComplete = true;
        }
    }

    private void endSpeech(Turn current) {
        if (current.isInterrupted || current.stream == null) {
            return;
        }

        Segment last = current.segments.get(current.segments.size() - 1);
        try {
            playPcm(current, last, current.stream.flush());
        } catch (OrcaException e) {
            onEngineProcessError(e.getMessage());
        } catch (RuntimeException e) {
            onSpeechError(current, e.toString());
        }
        if (current.stream != null) {
            current.stream.close();
            current.stream = null;
        }
        last.isComplete = true;
    }

    private void onSpeechError(Turn current, String message) {
        onEngineProcessError(message);
        synchronized (stateLock) {
            if (turn == current) {
                interruptTurn();
                listen(false);
            }
        }
    }

    private void playPcm(Turn current, Segment segment, short[] pcm) {
        if (pcm == null || pcm.length == 0 || current.isInterrupted) {
            return;
        }
        segment.synthesizedSamples += pcm.length;
        current.player.add(pcm);
    }

    private void onSpeaking(Turn current) {
        synchronized (stateLock) {
            if (turn == current) {
                setStatus("Speaking… say the wake word to interrupt", true);
            }
        }
    }

    private void finishTurn(Turn current) {
        synchronized (stateLock) {
            if (turn != current) {
                return;
            }
            turn = null;
            addExchange(current.question, current.completion);
            appendChatText("\n\n");
            listen(false);
        }
    }

    private boolean interruptTurn() {
        if (turn == null) {
            return false;
        }

        Turn current = turn;
        turn = null;
        current.isInterrupted = true;

        try {
            picollm.interrupt();
        } catch (PicoLLMException e) {
            onEngineProcessError(e.getMessage());
        }
        current.player.stop();
        ttsExecutor.submit(() -> {
            if (current.stream != null) {
                try {
                    current.stream.flush();
                } catch (OrcaException e) {
                    onEngineProcessError(e.getMessage());
                }
                current.stream.close();
                current.stream = null;
            }
        });

        String spoken = current.spokenText();
        if (!spoken.isEmpty()) {
            addExchange(current.question, spoken);
        }
        appendChatText(" (interrupted)\n\n");
        return true;
    }

    private static List<Sentence> splitSentences(String text) {
        List<Sentence> sentences = new ArrayList<>();
        Matcher matcher = SENTENCE_END_PATTERN.matcher(text);
        int last = 0;
        while (matcher.find()) {
            sentences.add(new Sentence(text.substring(last, matcher.end()), true));
            last = matcher.end();
        }
        if (last < text.length()) {
            sentences.add(new Sentence(text.substring(last), false));
        }
        return sentences;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
        releaseEngines();
        mainHandler.post(() -> {
            loadModelProgress.setVisibility(View.INVISIBLE);
            loadModelText.setText(message);
        });
    }

    private void onEngineProcessError(String message) {
        mainHandler.post(() -> {
            chatTextBuilder.append(String.format("\n\nError: %s\n\n", message));
            chatText.setText(chatTextBuilder);
            chatTextScrollView.fullScroll(ScrollView.FOCUS_DOWN);
        });
    }

    private void startListening() {
        if (voiceProcessor.hasRecordAudioPermission(this)) {
            try {
                voiceProcessor.start(porcupine.getFrameLength(), porcupine.getSampleRate());
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
            startListening();
        }
    }

    private void appendLabel(String label) {
        mainHandler.post(() -> {
            if (chatTextBuilder.length() > 0) {
                chatTextBuilder.append("\n\n");
            }
            int start = chatTextBuilder.length();
            chatTextBuilder.append(label).append("\n\n");
            chatTextBuilder.setSpan(
                    new ForegroundColorSpan(spanColour),
                    start,
                    start + label.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            chatText.setText(chatTextBuilder);
            chatTextScrollView.fullScroll(ScrollView.FOCUS_DOWN);
        });
    }

    private void appendUserLabel(String heard) {
        appendLabel("You:");
        if (!heard.isEmpty()) {
            appendChatText(heard);
        }
    }

    private void removeUserLabel() {
        mainHandler.post(() -> {
            String text = chatTextBuilder.toString();
            int start = text.lastIndexOf("You:");
            if (start >= 0 && text.indexOf("Assistant:", start) < 0) {
                chatTextBuilder.delete(Math.max(0, start - 2), chatTextBuilder.length());
                chatText.setText(chatTextBuilder);
            }
        });
    }

    private void appendAssistantLabel() {
        appendLabel("Assistant:");
    }

    private void appendChatText(String text) {
        mainHandler.post(() -> {
            chatTextBuilder.append(text);
            chatText.setText(chatTextBuilder);
            chatTextScrollView.fullScroll(ScrollView.FOCUS_DOWN);
        });
    }

    private void setStatus(String text, boolean isBusy) {
        mainHandler.post(() -> {
            statusText.setText(text);
            statusProgress.setVisibility(isBusy ? View.VISIBLE : View.GONE);
        });
    }

    private void updateUIState(UIState state) {
        mainHandler.post(() -> {
            switch (state) {
                case LOADING:
                    loadModelLayout.setVisibility(View.VISIBLE);
                    chatLayout.setVisibility(View.INVISIBLE);
                    loadModelProgress.setVisibility(View.VISIBLE);
                    loadModelText.setText(getResources().getString(R.string.loading_text));
                    break;
                case WAITING:
                    loadModelLayout.setVisibility(View.INVISIBLE);
                    chatLayout.setVisibility(View.VISIBLE);
                    statusProgress.setVisibility(View.GONE);
                    statusText.setText("Say the wake word or type a question");
                    break;
                case LISTENING:
                    loadModelLayout.setVisibility(View.INVISIBLE);
                    chatLayout.setVisibility(View.VISIBLE);
                    statusProgress.setVisibility(View.GONE);
                    statusText.setText("Listening…");
                    break;
                case RESPONDING:
                    loadModelLayout.setVisibility(View.INVISIBLE);
                    chatLayout.setVisibility(View.VISIBLE);
                    statusProgress.setVisibility(View.VISIBLE);
                    statusText.setText("Thinking… say the wake word to cancel");
                    break;
                default:
                    break;
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        synchronized (stateLock) {
            interruptTurn();
            clearListenTimeout();
            currentState = UIState.LOADING;
        }
        releaseEngines();

        engineExecutor.shutdownNow();
        llmExecutor.shutdownNow();
        ttsExecutor.shutdownNow();
        playbackExecutor.shutdownNow();
    }
}
