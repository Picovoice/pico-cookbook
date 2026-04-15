package ai.picovoice.liveconversationtranslation;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.AlignmentSpan;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ai.picovoice.android.voiceprocessor.VoiceProcessor;
import ai.picovoice.android.voiceprocessor.VoiceProcessorException;
import ai.picovoice.cheetah.Cheetah;
import ai.picovoice.cheetah.CheetahException;
import ai.picovoice.cheetah.CheetahTranscript;

public class MainActivity extends AppCompatActivity {

    private static final String ACCESS_KEY = "${YOUR_ACCESS_KEY_HERE}";

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

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final ExecutorService engineExecutor = Executors.newSingleThreadExecutor();

    private ConstraintLayout selectLanguageLayout;

    private ConstraintLayout chatLayout;

    private ListView languagePairView;

    private TextView statusText;

    private ProgressBar statusProgress;

    private TextView chatText;

    private ScrollView chatTextScrollView;

    private SpannableStringBuilder chatTextBuilder;

    private int chatLastNewline = 0;

    private Cheetah cheetah0;

    private void sendText(String text) {
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
            chatText.setText(chatTextBuilder);
            chatTextScrollView.fullScroll(ScrollView.FOCUS_DOWN);
        });
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

            chatText.setText(chatTextBuilder);
        });
    }

    private void setStatus(String text) {
        mainHandler.post(() -> {
            statusText.setVisibility(View.VISIBLE);
            statusText.setText(text);
        });
    }

    private void setError(String text) {
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
        languagePairView = findViewById(R.id.languagePair);
        statusText = findViewById(R.id.statusText);
        statusProgress = findViewById(R.id.statusProgress);
        chatText = findViewById(R.id.chatText);
        chatTextScrollView = findViewById(R.id.chatScrollView);
        chatTextBuilder = new SpannableStringBuilder();

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                this,
                android.R.layout.simple_list_item_1,
                languagePairs
        );
        languagePairView.setAdapter(adapter);

        languagePairView.setOnItemClickListener((parent, view, position, id) -> {
            String[] pair = languagePairs[position].split("-");

            selectLanguageLayout.setVisibility(View.GONE);
            chatLayout.setVisibility(View.VISIBLE);
            mainHandler.post(() -> chatText.setText(""));

            engineExecutor.submit(() -> {
                setStatus(String.format("Loading Cheetah %s", pair[0]));

                try {
                    String model_path = String.format("cheetah_params_%s.pv", pair[0]);
                    cheetah0 = new Cheetah.Builder()
                            .setAccessKey(ACCESS_KEY)
                            .setModelPath(model_path)
                            .setEnableAutomaticPunctuation(true)
                            .build(getApplicationContext());
                } catch (CheetahException e) {
                    setError(e.getMessage());
                }

                setStatus("Listening for ...");
            });
        });
    }
}