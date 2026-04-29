package ai.picovoice.callassist;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ai.picovoice.android.voiceprocessor.VoiceProcessor;
import ai.picovoice.android.voiceprocessor.VoiceProcessorException;
import ai.picovoice.cheetah.Cheetah;
import ai.picovoice.cheetah.CheetahException;
import ai.picovoice.orca.Orca;
import ai.picovoice.orca.OrcaException;
import ai.picovoice.picollm.PicoLLM;
import ai.picovoice.picollm.PicoLLMException;
import ai.picovoice.rhino.Rhino;
import ai.picovoice.rhino.RhinoException;

public class MainActivity extends AppCompatActivity {

    private static final String ACCESS_KEY = "${YOUR_ACCESS_KEY_HERE}";

    private static final String USERNAME = "${YOUR_USERNAME_HERE}";

    private static final String RHN_MODEL_FILE = "rhino_model.rhn";

    private static final String LLM_MODEL_FILE = "picollm_model.pllm";

    private static final String STT_MODEL_FILE = "cheetah_params.pv";

    private static final String TTS_MODEL_FILE = "orca_params_en_female.pv";

    

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final ExecutorService engineExecutor = Executors.newSingleThreadExecutor();

    private final VoiceProcessor voiceProcessor = VoiceProcessor.getInstance();

    private Rhino rhino;

    private Cheetah cheetah;

    private PicoLLM picollm;

    private Orca orca;

    private LinearLayout statusLayout;

    private TextView statusText;

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

        initStatusLayout();

        voiceProcessor.addFrameListener(this::frameListener);
        engineExecutor.submit(this::initEngines);
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
            statusLayout.invalidate();
        });
    }

    private void setError(String text) {
        mainHandler.post(() -> {
            int colorDanger = getResources().getColor(R.color.colorDanger);
            statusText.setText(text);
            statusText.setTextColor(colorDanger);
            statusLayout.setVisibility(View.VISIBLE);
            statusLayout.invalidate();
        });
    }

    private void initEngines() {
        setStatus("Loading Rhino...");
        try {
            rhino = new Rhino.Builder()
                    .setAccessKey(ACCESS_KEY)
                    .setContextPath(RHN_MODEL_FILE)
                    .build(getApplicationContext());
        } catch (RhinoException e) {
            setError(e.getMessage());
            return;
        }

        setStatus("Loading Cheetah...");
        try {
            cheetah = new Cheetah.Builder()
                    .setAccessKey(ACCESS_KEY)
                    .setModelPath(STT_MODEL_FILE)
                    .setEnableAutomaticPunctuation(true)
                    .build(getApplicationContext());
        } catch (CheetahException e) {
            setError(e.getMessage());
            return;
        }

        setStatus("Loading picoLLM...");
        File llmModelFile = extractModelFile(LLM_MODEL_FILE);
        try {
            picollm = new PicoLLM.Builder()
                    .setAccessKey(ACCESS_KEY)
                    .setModelPath(llmModelFile.getAbsolutePath())
                    .build();
        } catch (PicoLLMException e) {
            setError(e.getMessage());
            return;
        }

        setStatus("Loading Orca...");
        try {
            orca = new Orca.Builder()
                    .setAccessKey(ACCESS_KEY)
                    .setModelPath(TTS_MODEL_FILE)
                    .build(getApplicationContext());
        } catch (OrcaException e) {
            setError(e.getMessage());
            return;
        }

        startRecording();

        mainHandler.post(() -> statusLayout.setVisibility(View.GONE));
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

    private void startRecording() {
        if (voiceProcessor.hasRecordAudioPermission(this)) {
            try {
                voiceProcessor.start(cheetah.getFrameLength(), cheetah.getSampleRate());
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

    }
}