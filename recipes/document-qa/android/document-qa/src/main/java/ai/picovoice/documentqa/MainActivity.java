/*
    Copyright 2026 Picovoice Inc.

    You may not use this file except in compliance with the license. A copy of the license is
    located in the "LICENSE" file accompanying this source.

    Unless required by applicable law or agreed to in writing, software distributed under the
    License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
    express or implied. See the License for the specific language governing permissions and
    limitations under the License.
*/

package ai.picovoice.documentqa;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
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
import ai.picovoice.orca.OrcaException;
import ai.picovoice.orca.OrcaSynthesizeParams;
import ai.picovoice.picollm.PicoLLM;
import ai.picovoice.picollm.PicoLLMDialog;
import ai.picovoice.picollm.PicoLLMException;
import ai.picovoice.picollm.PicoLLMGenerateParams;

public class MainActivity extends AppCompatActivity {

    private enum UIState {
        INIT,
        LOADING_MODEL,
        STT,
        LLM_TTS
    }

    private static final String ACCESS_KEY = "${YOUR_ACCESS_KEY_HERE}";

    private static final String LLM_IT_MODEL_FILE = "llama-3.2-1b-instruct-385.pllm";

    private static final String LLM_EMBED_MODEL_FILE = "embeddinggemma-300m-375.pllm";

    private static final String STT_MODEL_FILE = "cheetah_params.pv";

    private static final String TTS_MODEL_FILE = "orca_params_female.pv";

    private static final int COMPLETION_TOKEN_LIMIT = 128;

    private static final int TTS_WARMUP_SECONDS = 1;

    private static final int CHUNK_SIZE = 300;
    private static final int CHUNK_OVERLAP = 200;
    private static final int TOPK = 4;

    private static final String[] STOP_PHRASES = new String[]{
            "</s>",             // Llama-2, Mistral, and Mixtral
            "<end_of_turn>",    // Gemma
            "<|endoftext|>",    // Phi-2
            "<|eot_id|>",       // Llama-3
            "<|end|>", "<|user|>", "<|assistant|>", // Phi-3
    };

    private final VoiceProcessor voiceProcessor = VoiceProcessor.getInstance();

    private Cheetah cheetah;
    private PicoLLM picollmEmbedding;

    private PicoLLM picollmChat;

    private Orca orca;

    ArrayList<String> chunks;
    ArrayList<float[]> embeddings;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final ExecutorService engineExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService ttsSynthesizeExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService ttsPlaybackExecutor = Executors.newSingleThreadExecutor();

    private AudioTrack ttsOutput;

    private UIState currentState = UIState.INIT;

    private StringBuilder llmPromptText = new StringBuilder();

    private ConstraintLayout loadModelLayout;
    private ConstraintLayout chatLayout;

    private Button loadDocumentButton;
    private TextView loadDocumentText;
    private ProgressBar loadDocumentProgress;

    private TextView chatText;

    private ScrollView chatTextScrollView;

    private TextView statusText;

    private ProgressBar statusProgress;

    private VolumeMeterView volumeMeterView;

    private ImageButton loadNewDocumentButton;

    private ImageButton skipButton;

    private SpannableStringBuilder chatTextBuilder;

    private int spanColour;

    @SuppressLint({"DefaultLocale", "SetTextI18n"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main_layout);

        loadModelLayout = findViewById(R.id.loadModelLayout);
        chatLayout = findViewById(R.id.chatLayout);

        loadDocumentText = findViewById(R.id.loadDocumentText);
        loadDocumentProgress = findViewById(R.id.loadDocumentProgress);
        loadDocumentButton = findViewById(R.id.loadDocumentButton);

        loadDocumentButton.setOnClickListener(view -> {
            modelSelection.launch(new String[]{"text/plain"});
        });

        spanColour = ContextCompat.getColor(this, R.color.colorPrimary);

        updateUIState(UIState.INIT);

        chatText = findViewById(R.id.chatText);
        chatTextScrollView = findViewById(R.id.chatScrollView);
        statusText = findViewById(R.id.statusText);
        statusProgress = findViewById(R.id.statusProgress);
        volumeMeterView = findViewById(R.id.volumeMeterView);

        loadNewDocumentButton = findViewById(R.id.loadNewDocumentButton);
        loadNewDocumentButton.setOnClickListener(view -> {
            try {
                voiceProcessor.stop();
            } catch (VoiceProcessorException e) {
                onEngineProcessError(e.getMessage());
            }
            updateUIState(UIState.INIT);

            engineExecutor.submit(this::cleanupEngines);

            chunks = new ArrayList<>();
            embeddings = new ArrayList<>();
            mainHandler.post(() -> chatText.setText(""));
        });

        skipButton = findViewById(R.id.skipButton);
        skipButton.setOnClickListener(view -> {
            mainHandler.post(this::interrupt);
        });
    }

    ActivityResultLauncher<String[]> modelSelection = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            new ActivityResultCallback<Uri>() {
                @SuppressLint("SetTextI18n")
                @Override
                public void onActivityResult(Uri selectedUri) {
                    updateUIState(UIState.LOADING_MODEL);

                    if (selectedUri == null) {
                        updateUIState(UIState.INIT);
                        mainHandler.post(() -> loadDocumentText.setText("No file selected"));
                        return;
                    }

                    engineExecutor.submit(() -> {
                        initEngines(selectedUri);
                    });
                }
            });

    private ArrayList<String> chunkDocument(Uri uri) {
        StringBuilder textBuilder = new StringBuilder();
        try {
            InputStream in = getContentResolver().openInputStream(uri);
            BufferedReader r = new BufferedReader(new InputStreamReader(in));
            for (String line; (line = r.readLine()) != null; ) {
                textBuilder.append(line).append('\n');
            }
        } catch (Exception e) {
            onEngineInitError(e.getMessage());
            return null;
        }

        String text = textBuilder.toString();
        ArrayList<String> chunks = new ArrayList<>();
        int start = 0;

        while (start < text.length()) {
            int end = Math.min(start + CHUNK_SIZE, text.length());

            if (end < text.length()) {
                int paragraphBreak = text.lastIndexOf("\n\n", start);
                if (paragraphBreak <= end && paragraphBreak > (start + (CHUNK_SIZE / 2))) {
                    end = paragraphBreak;
                }
            }

            String chunk = text.substring(start, end);
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }

            if (end >= text.length()) {
                break;
            }

            start = Math.max(0, end - CHUNK_OVERLAP);
        }

        return chunks;
    }

    private float[] normalizeVector(float[] vector) {
        double sum = 0;
        for (float x : vector) {
            sum += x * x;
        }
        float norm = (float) Math.pow(sum, 0.5);

        float[] normalizedVector = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            normalizedVector[i] = vector[i] / norm;
        }
        return normalizedVector;
    }

    private float dotProduct(float[] a, float[] b) {
        float sum = 0;
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }

    private float[] generateEmbedding(String prompt) throws PicoLLMException {
        float[] embeddingRaw = picollmEmbedding.generateEmbeddings(prompt);
        return normalizeVector(embeddingRaw);
    }

    private ArrayList<float[]> generateEmbeddings(ArrayList<String> chunks) throws PicoLLMException {
        ArrayList<float[]> embeddings = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {
            String statusText = "Generating embeddings " + (i + 1) + "/" + chunks.size();
            mainHandler.post(() -> loadDocumentText.setText(statusText));
            embeddings.add(generateEmbedding(chunks.get(i)));
        }

        return embeddings;
    }

    private ArrayList<String> retrieveChunks(String question) throws PicoLLMException {
        float[] questionEmbedding = generateEmbedding(question);

        float[] scores = new float[embeddings.size()];
        for (int i = 0; i < embeddings.size(); i++) {
            scores[i] = dotProduct(questionEmbedding, embeddings.get(i));
        }

        float[] topks = new float[TOPK];
        int[] indices = new int[TOPK];

        Arrays.fill(topks, -Float.MAX_VALUE);

        for (int i = 0; i < scores.length; i++) {
            float element = scores[i];
            int indice = i;

            if (element > topks[TOPK - 1]) {
                for (int j = 0; j < TOPK; j++) {
                    if (element > topks[j]) {
                        final float prev_topk = topks[j];
                        topks[j] = element;
                        element = prev_topk;

                        final int prev_topk_indice = indices[j];
                        indices[j] = indice;
                        indice = prev_topk_indice;
                    }
                }
            }
        }

        ArrayList<String> retrievedChunks = new ArrayList<>();
        for (int index : indices) {
            retrievedChunks.add(chunks.get(index));
        }

        return retrievedChunks;
    }

    private void initEngines(Uri documentUri) {
        mainHandler.post(() -> loadDocumentText.setText("Loading Cheetah..."));
        try {
            cheetah = new Cheetah.Builder()
                    .setAccessKey(ACCESS_KEY)
                    .setModelPath(STT_MODEL_FILE)
                    .setEnableAutomaticPunctuation(true)
                    .setEndpointDuration(1)
                    .build(getApplicationContext());
        } catch (CheetahException e) {
            onEngineInitError(e.getMessage());
            return;
        }

        mainHandler.post(() -> loadDocumentText.setText("Loading picoLLM (embedding)..."));
        File llmEmbeddingModelFile = extractModelFile(LLM_EMBED_MODEL_FILE);
        try {
            picollmEmbedding = new PicoLLM.Builder()
                    .setAccessKey(ACCESS_KEY)
                    .setModelPath(llmEmbeddingModelFile.getAbsolutePath())
                    .build();
        } catch (PicoLLMException e) {
            onEngineInitError(e.getMessage());
            return;
        }

        mainHandler.post(() -> loadDocumentText.setText("Loading picoLLM (chat)..."));
        File llmChatModelFile = extractModelFile(LLM_IT_MODEL_FILE);
        try {
            picollmChat = new PicoLLM.Builder()
                    .setAccessKey(ACCESS_KEY)
                    .setModelPath(llmChatModelFile.getAbsolutePath())
                    .build();
        } catch (PicoLLMException e) {
            onEngineInitError(e.getMessage());
            return;
        }

        mainHandler.post(() -> loadDocumentText.setText("Loading Orca..."));
        try {
            orca = new Orca.Builder()
                    .setAccessKey(ACCESS_KEY)
                    .setModelPath(TTS_MODEL_FILE)
                    .build(getApplicationContext());
        } catch (OrcaException e) {
            onEngineInitError(e.getMessage());
            return;
        }

        mainHandler.post(() -> loadDocumentText.setText("Reading document..."));
        boolean hasCache = loadCachedEmbeddings(documentUri);
        if (hasCache) {
            mainHandler.post(() -> {
                AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);

                builder.setMessage("Cached embeddings found for file, would you like to use them?")
                        .setTitle("Use Cache?");

                builder.setPositiveButton("Use Cache", (dialog, id) -> {
                    engineExecutor.submit(() -> {
                        initUI();
                        startSTTListening();
                    });
                });
                builder.setNegativeButton("Generate New", (dialog, id) -> {
                    engineExecutor.submit(() -> {
                        generateNewEmbeddings(documentUri);
                        initUI();
                        startSTTListening();
                    });
                });

                AlertDialog dialog = builder.create();
                dialog.show();
            });
        } else {
            generateNewEmbeddings(documentUri);
            initUI();
            startSTTListening();
        }
    }

    boolean loadCachedEmbeddings(Uri documentUri) {
        int filenameHash = documentUri.getLastPathSegment().hashCode();
        String cacheFilename = String.format("%d.json", filenameHash);
        File cacheFile = new File(getApplicationContext().getFilesDir(), cacheFilename);

        if (!cacheFile.exists()) {
            return false;
        }

        Log.i("PICOVOICE", String.format("Loading cached embeddings for `%s` from `%s`", documentUri, cacheFile));

        try {

            try (InputStream is = new FileInputStream(cacheFile);
                 InputStreamReader reader = new InputStreamReader(is)) {
                StringBuilder jsonStringBuilder = new StringBuilder();

                char[] buffer = new char[1];
                while (reader.read(buffer) != -1) {
                    jsonStringBuilder.append(buffer);
                    buffer = new char[1];
                }
                reader.close();
                is.close();

                JSONObject embeddingsJson = new JSONObject(jsonStringBuilder.toString());
                if (embeddingsJson.getInt("chunkSize") != CHUNK_SIZE) {
                    Log.i("PICOVOICE", String.format("Mismatched chunk size in cache %d vs %d", embeddingsJson.getInt("chunkSize"), CHUNK_SIZE));
                    return false;
                }
                if (embeddingsJson.getInt("chunkOverlap") != CHUNK_OVERLAP) {
                    Log.i("PICOVOICE", String.format("Mismatched chunk size in cache %d vs %d", embeddingsJson.getInt("chunkOverlap"), CHUNK_OVERLAP));
                    return false;
                }

                ArrayList<float[]> cachedEmbeddings = new ArrayList<>();
                JSONArray cachedEmbeddingsJson = embeddingsJson.getJSONArray("embeddings");
                for (int i = 0; i < cachedEmbeddingsJson.length(); i++) {
                    JSONArray cachedEmbeddingsJsonI = cachedEmbeddingsJson.getJSONArray(i);
                    float[] cachedEmbeddingsI = new float[cachedEmbeddingsJsonI.length()];
                    for (int j = 0; j < cachedEmbeddingsJsonI.length(); j++) {
                        cachedEmbeddingsI[j] = (float) cachedEmbeddingsJsonI.getDouble(i);
                    }
                    cachedEmbeddings.add(cachedEmbeddingsI);
                }

                ArrayList<String> cachedChunks = new ArrayList<>();
                JSONArray cachedChunksJson = embeddingsJson.getJSONArray("chunks");
                for (int i = 0; i < cachedChunksJson.length(); i++) {
                    cachedChunks.add(cachedChunksJson.getString(i));
                }

                embeddings = cachedEmbeddings;
                chunks = cachedChunks;
                return true;
            } catch (IOException e) {
                Log.i("PICOVOICE", String.format("Failed to load embeddings: %s", e));
                return false;
            }
        } catch (JSONException e) {
            Log.i("PICOVOICE", String.format("Failed to load embeddings: %s", e));
            return false;
        }
    }

    void saveCachedEmbeddings(Uri documentUri) {
        int filenameHash = documentUri.getLastPathSegment().hashCode();
        String cacheFilename = String.format("%d.json", filenameHash);
        File cacheFile = new File(getApplicationContext().getFilesDir(), cacheFilename);

        Log.i("PICOVOICE", String.format("Saving embeddings for `%s` to `%s`", documentUri, cacheFile));

        try {
            JSONObject embeddingsJson = new JSONObject();
            embeddingsJson.put("chunkSize", CHUNK_SIZE);
            embeddingsJson.put("chunkOverlap", CHUNK_OVERLAP);

            JSONArray embeddingsJsonArray = new JSONArray();
            for (int i = 0; i < embeddings.size(); i++) {
                JSONArray embeddingsJsonArrayI = new JSONArray(embeddings.get(i));
                embeddingsJsonArray.put(embeddingsJsonArrayI);
            }
            embeddingsJson.put("embeddings", embeddingsJsonArray);

            JSONArray chunksJsonArray = new JSONArray();
            for (int i = 0; i < chunks.size(); i++) {
                chunksJsonArray.put(chunks.get(i));
            }
            embeddingsJson.put("chunks", chunksJsonArray);

            try (OutputStream os = new FileOutputStream(cacheFile)) {
                os.write(embeddingsJson.toString().getBytes());
            } catch (IOException e) {
                Log.i("PICOVOICE", String.format("Failed to save embeddings: %s", e));
            }
        } catch (JSONException e) {
            Log.i("PICOVOICE", String.format("Failed to save embeddings: %s", e));
        }
    }

    private void generateNewEmbeddings(Uri documentUri) {
        try {
            chunks = chunkDocument(documentUri);
            embeddings = generateEmbeddings(chunks);
            saveCachedEmbeddings(documentUri);
        } catch (PicoLLMException e) {
            onEngineInitError(e.getMessage());
            return;
        }
    }

    private void initUI() {
        chatTextBuilder = new SpannableStringBuilder();
        updateUIState(UIState.STT);

        voiceProcessor.addFrameListener(this::runWakeWordSTT);

        voiceProcessor.addErrorListener(error -> {
            onEngineProcessError(error.getMessage());
        });
    }

    private void runWakeWordSTT(short[] frame) {
        volumeMeterView.processFrame(frame);

        if (currentState == UIState.STT) {
            try {
                CheetahTranscript result = cheetah.process(frame);
                llmPromptText.append(result.getTranscript());
                mainHandler.post(() -> {
                    chatTextBuilder.append(result.getTranscript());
                    chatText.setText(chatTextBuilder);
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
        }
    }

    private String buildPrompt(String question, ArrayList<String> retrievedChunks) throws PicoLLMException {
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < retrievedChunks.size(); i++) {
            context.append(String.format("[Excerpt %d]\n%s", i + 1, retrievedChunks.get(i)));
        }

        String systemPrompt =
                "You are a document question-answering assistant. " +
                "Answer only using the provided document excerpts. " +
                "If the answer is not in the excerpts, say that you do not know from the provided document. " +
                "Do not give legal advice. " +
                "Keep the answer concise. " +
                "Do not use Markdown formatting. " +
                "Do not use bullet points. " +
                "Use plain text only.";
        PicoLLMDialog dialog = picollmChat.getDialogBuilder().setSystem(systemPrompt).build();

        String prompt = String.format("Document excerpts:\n\n%s\n\nQuestion:\n%s", context.toString(), question);
        dialog.addHumanRequest(prompt);

        return dialog.getPrompt();
    }

    private void runLLM(String question) {
        if (question.isEmpty()) {
            return;
        }

        ArrayList<String> retrievedChunks;
        try {
            retrievedChunks = retrieveChunks(question);
        } catch (PicoLLMException e) {
            onEngineProcessError(e.getMessage());
            return;
        }

        AtomicBoolean isQueueingTokens = new AtomicBoolean(false);
        CountDownLatch tokensReadyLatch = new CountDownLatch(1);
        ConcurrentLinkedQueue<String> tokenQueue = new ConcurrentLinkedQueue<>();

        AtomicBoolean isQueueingPcm = new AtomicBoolean(false);
        CountDownLatch pcmReadyLatch = new CountDownLatch(1);
        ConcurrentLinkedQueue<short[]> pcmQueue = new ConcurrentLinkedQueue<>();

        updateUIState(UIState.LLM_TTS);

        mainHandler.post(() -> {
            int start = chatTextBuilder.length();
            chatTextBuilder.append("picoLLM:\n\n");
            chatTextBuilder.setSpan(
                    new ForegroundColorSpan(spanColour),
                    start,
                    start + 8,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            chatText.setText(chatTextBuilder);
        });

        engineExecutor.submit(() -> {
            try {
                isQueueingTokens.set(true);

                String prompt = buildPrompt(question, retrievedChunks);
                picollmChat.generate(
                        prompt,
                        new PicoLLMGenerateParams.Builder()
                                .setStreamCallback(token -> {
                                    if (token != null && !token.isEmpty()) {
                                        boolean containsStopPhrase = false;
                                        for (String k : STOP_PHRASES) {
                                            if (token.contains(k)) {
                                                containsStopPhrase = true;
                                                break;
                                            }
                                        }

                                        if (!containsStopPhrase && currentState == UIState.LLM_TTS) {
                                            tokenQueue.add(token);
                                            tokensReadyLatch.countDown();

                                            mainHandler.post(() -> {
                                                chatTextBuilder.append(token);
                                                chatText.setText(chatTextBuilder);
                                                chatTextScrollView.fullScroll(ScrollView.FOCUS_DOWN);
                                            });
                                        }
                                    }
                                })
                                .setCompletionTokenLimit(COMPLETION_TOKEN_LIMIT)
                                .setStopPhrases(STOP_PHRASES)
                                .build());

                isQueueingTokens.set(false);

                mainHandler.post(() -> {
                    skipButton.setEnabled(true);
                    skipButton.setImageDrawable(
                            ResourcesCompat.getDrawable(getResources(),
                                    R.drawable.clear_button,
                                    null));
                    chatTextBuilder.append("\n\n");
                });

                llmPromptText = new StringBuilder();
            } catch (PicoLLMException e) {
                onEngineProcessError(e.getMessage());
            }
        });

        ttsSynthesizeExecutor.submit(() -> {
            Orca.OrcaStream orcaStream;
            try {
                orcaStream = orca.streamOpen(new OrcaSynthesizeParams.Builder().build());
            } catch (OrcaException e) {
                onEngineProcessError(e.getMessage());
                return;
            }

            short[] warmupPcm;
            if (TTS_WARMUP_SECONDS > 0) {
                warmupPcm = new short[0];
            }

            try {
                tokensReadyLatch.await();
            } catch (InterruptedException e) {
                onEngineProcessError(e.getMessage());
                return;
            }

            isQueueingPcm.set(true);
            while (isQueueingTokens.get() || !tokenQueue.isEmpty()) {
                String token = tokenQueue.poll();
                if (token != null && !token.isEmpty()) {
                    try {
                        short[] pcm = orcaStream.synthesize(token);

                        if (pcm != null && pcm.length > 0) {
                            if (warmupPcm != null) {
                                int offset = warmupPcm.length;
                                warmupPcm = Arrays.copyOf(warmupPcm, offset + pcm.length);
                                System.arraycopy(pcm, 0, warmupPcm, offset, pcm.length);
                                if (warmupPcm.length > TTS_WARMUP_SECONDS * orca.getSampleRate()) {
                                    pcmQueue.add(warmupPcm);
                                    pcmReadyLatch.countDown();
                                    warmupPcm = null;
                                }
                            } else {
                                pcmQueue.add(pcm);
                                pcmReadyLatch.countDown();
                            }
                        }
                    } catch (OrcaException e) {
                        onEngineProcessError(e.getMessage());
                        return;
                    }
                }
            }

            try {
                short[] flushedPcm = orcaStream.flush();

                if (flushedPcm != null && flushedPcm.length > 0) {
                    if (warmupPcm != null) {
                        int offset = warmupPcm.length;
                        warmupPcm = Arrays.copyOf(warmupPcm, offset + flushedPcm.length);
                        System.arraycopy(flushedPcm, 0, warmupPcm, offset, flushedPcm.length);
                        pcmQueue.add(warmupPcm);
                        pcmReadyLatch.countDown();
                    }
                    else {
                        pcmQueue.add(flushedPcm);
                        pcmReadyLatch.countDown();
                    }
                }
            } catch (OrcaException e) {
                onEngineProcessError(e.getMessage());
            }

            isQueueingPcm.set(false);

            orcaStream.close();
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

            try {
                pcmReadyLatch.await();
            } catch (InterruptedException e) {
                onEngineProcessError(e.getMessage());
                return;
            }

            while (isQueueingPcm.get() || !pcmQueue.isEmpty()) {
                short[] pcm = pcmQueue.poll();
                if (pcm != null && pcm.length > 0 && ttsOutput.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                    ttsOutput.write(pcm, 0, pcm.length);
                }
            }

            if (ttsOutput.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                ttsOutput.flush();
                ttsOutput.stop();
            }
            ttsOutput.release();

            updateUIState(UIState.STT);
        });
    }

    private void interrupt() {
        try {
            picollmChat.interrupt();
            if (ttsOutput != null && ttsOutput.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                ttsOutput.stop();
            }
        } catch (PicoLLMException e) {
            onEngineProcessError(e.getMessage());
        }
    }

    private File extractModelFile(String filename) {
        File modelFile = new File(getApplicationContext().getFilesDir(), filename);

        try (InputStream is = getApplicationContext().getAssets().open(filename);
             OutputStream os = new FileOutputStream(modelFile)) {
            byte[] buffer = new byte[8192];
            int numBytesRead;
            while ((numBytesRead = is.read(buffer)) != -1) {
                os.write(buffer, 0, numBytesRead);
            }
        } catch (IOException e) {
            onEngineInitError("Unable to load file: " + e.getMessage());
            return null;
        }

        return modelFile;
    }

    private void onEngineInitError(String message) {
        updateUIState(UIState.INIT);
        mainHandler.post(() -> loadDocumentText.setText(message));
    }

    private void onEngineProcessError(String message) {
        updateUIState(UIState.STT);
        mainHandler.post(() -> chatText.setText(message));
    }

    private void startSTTListening() {
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
            startSTTListening();
        }
    }

    private void updateUIState(UIState state) {
        mainHandler.post(() -> {
            switch (state) {
                case INIT:
                    loadModelLayout.setVisibility(View.VISIBLE);
                    chatLayout.setVisibility(View.INVISIBLE);
                    loadDocumentButton.setEnabled(true);
                    loadDocumentButton.setBackground(
                            ResourcesCompat.getDrawable(
                                    getResources(),
                                    R.drawable.button_background,
                                    null));
                    loadDocumentProgress.setVisibility(View.INVISIBLE);
                    loadDocumentText.setText(getResources().getString(R.string.intro_text));
                    break;
                case LOADING_MODEL:
                    loadModelLayout.setVisibility(View.VISIBLE);
                    chatLayout.setVisibility(View.INVISIBLE);
                    loadDocumentButton.setEnabled(false);
                    loadDocumentButton.setBackground(
                            ResourcesCompat.getDrawable(
                                    getResources(),
                                    R.drawable.button_disabled,
                                    null));
                    loadDocumentProgress.setVisibility(View.VISIBLE);
                    loadDocumentText.setText("Loading model...");
                    break;
                case STT:
                    loadModelLayout.setVisibility(View.INVISIBLE);
                    chatLayout.setVisibility(View.VISIBLE);

                    loadNewDocumentButton.setImageDrawable(
                            ResourcesCompat.getDrawable(
                                    getResources(),
                                    R.drawable.arrow_back_button,
                                    null));
                    loadNewDocumentButton.setEnabled(true);
                    volumeMeterView.setVisibility(View.VISIBLE);
                    statusProgress.setVisibility(View.GONE);
                    statusText.setVisibility(View.VISIBLE);
                    statusText.setText("Listening...");

                    int start = chatTextBuilder.length();
                    chatTextBuilder.append("You:\n\n");
                    chatTextBuilder.setSpan(
                            new ForegroundColorSpan(spanColour),
                            start,
                            start + 4,
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    chatText.setText(chatTextBuilder);

                    skipButton.setEnabled(false);
                    skipButton.setImageDrawable(
                            ResourcesCompat.getDrawable(
                                    getResources(),
                                    R.drawable.clear_button_disabled,
                                    null));
                    break;
                case LLM_TTS:
                    loadModelLayout.setVisibility(View.INVISIBLE);
                    chatLayout.setVisibility(View.VISIBLE);

                    loadNewDocumentButton.setImageDrawable(
                            ResourcesCompat.getDrawable(
                                    getResources(),
                                    R.drawable.arrow_back_button_disabled,
                                    null));
                    loadNewDocumentButton.setEnabled(false);
                    chatText.setText("");
                    volumeMeterView.setVisibility(View.GONE);
                    statusProgress.setVisibility(View.VISIBLE);
                    statusText.setVisibility(View.VISIBLE);
                    statusText.setText("Generating...");
                    skipButton.setEnabled(false);
                    skipButton.setImageDrawable(
                            ResourcesCompat.getDrawable(
                                    getResources(),
                                    R.drawable.clear_button_disabled,
                                    null));
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
        ttsPlaybackExecutor.shutdownNow();

        cleanupEngines();
    }

    void cleanupEngines() {
        if (cheetah != null) {
            cheetah.delete();
            cheetah = null;
        }

        if (picollmEmbedding != null) {
            picollmEmbedding.delete();
            picollmEmbedding = null;
        }

        if (picollmChat != null) {
            picollmChat.delete();
            picollmChat = null;
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
