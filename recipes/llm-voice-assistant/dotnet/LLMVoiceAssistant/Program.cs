using System.Collections.Concurrent;
using System.Text;
using System.Text.Json;
using System.Threading.Channels;

using Pv;

namespace LLMVoiceAssistant
{
    class LLMVoiceAssistant
    {
        static string? ACCESS_KEY;
        static int CHEETAH_ENDPOINT_DURATION_SEC = 1;
        static int COMPLETION_TOKEN_LIMIT = 128;
        static int TTS_WARMUP_SECONDS = 1;
        static string? LLM_MODEL_PATH;
        static string? KEYWORD_MODEL_PATH;
        static string LLM_DEVICE = "best";
        static float LLM_PRESENCE_PENALTY = 0.0f;
        static float LLM_FREQUENCY_PENALTY = 0.0f;
        static float LLM_TEMPERATURE = 1.0f;
        static float LLM_TOP_P = 1.0f;
        static float ORCA_SPEECH_RATE = 1.0f;
        static string? PICOLLM_SYSTEM_PROMPT;
        static bool SHORT_ANSWERS = false;
        static bool PROFILE = false;
        static float PORCUPINE_SENSITIVITY = 0.5f;
        static string PORCUPINE_WAKE_WORD = "Picovoice";
        static readonly string[] STOP_PHRASES = [
            "</s>",                                 // Llama-2, Mistral, and Mixtral
            "<end_of_turn>",                        // Gemma
            "<|endoftext|>",                        // Phi-2
            "<|eot_id|>",                           // Llama-3
            "<|end|>", "<|user|>", "<|assistant|>", // Phi-3
        ];

        static RTFProfiler? orcaProfiler;
        static RTFProfiler? porcupineProfiler;
        static RTFProfiler? cheetahProfiler;
        static TPSProfiler? pllmProfiler;
        static DelayProfiler? delayProfiler;
        static event EventHandler<Profiler?>? profilerEvent;

        public class CompletionText
        {
            private readonly string[] stopPhrases;
            private int start;
            private readonly StringBuilder text;
            private string newTokens;

            public CompletionText(string[] stopPhrases)
            {
                this.stopPhrases = stopPhrases;
                this.start = 0;
                this.text = new StringBuilder();
                this.newTokens = string.Empty;
            }

            public void Reset()
            {
                start = 0;
                text.Clear();
                newTokens = string.Empty;
            }

            public void Append(string input)
            {
                text.Append(input);
                int currentLength = text.Length;
                string currentText = text.ToString();
                int end = currentLength;

                foreach (var stopPhrase in stopPhrases)
                {
                    int index = currentText.IndexOf(stopPhrase, StringComparison.Ordinal);
                    if (index != -1 && end > index)
                    {
                        end = index;
                    }

                    for (int i = stopPhrase.Length - 1; i > 0; i--)
                    {
                        string prefix = stopPhrase.Substring(0, i);
                        if (currentText.EndsWith(prefix, StringComparison.Ordinal))
                        {
                            int possibleEnd = currentLength - i;
                            if (end > possibleEnd)
                            {
                                end = possibleEnd;
                            }
                        }
                    }
                }

                int tokenLength = end - start;
                if (tokenLength < 0)
                {
                    tokenLength = 0;
                }
                newTokens = currentText.Substring(start, tokenLength);

                start = end;
            }

            public string GetNewTokens() { return newTokens; }
        }

        private class Listener
        {
            private readonly Cheetah _cheetah;
            private readonly Porcupine _porcupine;
            private readonly PvRecorder _recorder;
            public event EventHandler<string>? UserInputReceived;
            public event EventHandler? WakeWordDetected;
            private event EventHandler<short[]>? AudioReceived;

            private string _transcript = "";

            public Listener()
            {
                _cheetah =
                    Cheetah.Create(accessKey: ACCESS_KEY,
                                   endpointDurationSec: CHEETAH_ENDPOINT_DURATION_SEC,
                                   enableAutomaticPunctuation: true);
                _porcupine =
                    KEYWORD_MODEL_PATH == null
                        ? Porcupine.FromBuiltInKeywords(
                              accessKey: ACCESS_KEY, keywords: [BuiltInKeyword.PICOVOICE],
                              sensitivities: [PORCUPINE_SENSITIVITY])
                        : Porcupine.FromKeywordPaths(
                              accessKey: ACCESS_KEY, keywordPaths: [KEYWORD_MODEL_PATH],
                              sensitivities: [PORCUPINE_SENSITIVITY]);
                _recorder = PvRecorder.Create(frameLength: _porcupine.FrameLength);
                cheetahProfiler?.Init(_cheetah.SampleRate);
                porcupineProfiler?.Init(_porcupine.SampleRate);
                AudioReceived += PorcupineOnAudioReceived;
                WakeWordDetected += OnWakeWordDetected;
                UserInputReceived += OnUserInputReceived;
            }

            public void run()
            {
                _recorder.Start();
                while (true)
                {
                    short[] pcm = _recorder.Read();
                    AudioReceived?.Invoke(this, pcm);
                }
            }

            private void PorcupineOnAudioReceived(object? sender, short[] pcm)
            {
                porcupineProfiler?.Tick();
                int keywordIndex = _porcupine.Process(pcm);
                porcupineProfiler?.Tock(pcm);
                if (keywordIndex >= 0)
                {
                    WakeWordDetected?.Invoke(this, EventArgs.Empty);
                }
            }

            private void CheetahOnAudioReceived(object? sender, short[] pcm)
            {
                cheetahProfiler?.Tick();
                var transcript = _cheetah.Process(pcm);
                cheetahProfiler?.Tock(pcm);
                if (transcript.Transcript.Length > 0)
                {
                    _transcript += transcript.Transcript;
                    Console.Write(transcript.Transcript);
                }
                if (transcript.IsEndpoint)
                {
                    cheetahProfiler?.Tick();
                    var remainingTranscript = _cheetah.Flush();
                    cheetahProfiler?.Tock(null);
                    _transcript += remainingTranscript.Transcript;
                    Console.WriteLine(remainingTranscript.Transcript);
                    UserInputReceived?.Invoke(this, _transcript);
                }
            }

            private void OnWakeWordDetected(object? sender, EventArgs e)
            {
                profilerEvent?.Invoke(null, porcupineProfiler);
                porcupineProfiler?.Reset();
                cheetahProfiler?.Reset();
                Console.WriteLine(
                    "Wake word detected, utter your request or question ...");
                Console.Write("User > ");
                AudioReceived -= PorcupineOnAudioReceived;
                AudioReceived += CheetahOnAudioReceived;
            }

            private void OnUserInputReceived(object? sender, string userInput)
            {
                profilerEvent?.Invoke(null, cheetahProfiler);
                delayProfiler?.Tick();
                _transcript = "";
                AudioReceived -= CheetahOnAudioReceived;
                AudioReceived += PorcupineOnAudioReceived;
            }
        }

        private class Generator
        {
            readonly PicoLLM _pllm;
            readonly Channel<string> _userInputChannel =
                Channel.CreateUnbounded<string>();
            public event EventHandler<string>? PartialCompletionGenerated;
            public event EventHandler<PicoLLMEndpoint>? CompletionGenerationCompleted;

            public Generator(Listener listener)
            {
                _pllm = PicoLLM.Create(accessKey: ACCESS_KEY, modelPath: LLM_MODEL_PATH,
                                       device: LLM_DEVICE);
                listener.UserInputReceived += OnUserInputReceived;
                listener.WakeWordDetected += OnWakeWordDetected;
                CompletionGenerationCompleted += (_, endpoint) =>
                    profilerEvent?.Invoke(null, pllmProfiler);
                Task.Run(llmWorker).ContinueWith((t) => Console.WriteLine(t.Exception));
            }

            private void OnUserInputReceived(object? sender, string userInput)
            {
                _userInputChannel.Writer.TryWrite(userInput);
            }

            private void OnWakeWordDetected(object? sender, EventArgs e)
            {
                _pllm.Interrupt();
            }

            private async Task llmWorker()
            {
                var dialog = PICOLLM_SYSTEM_PROMPT == null
                                 ? _pllm.GetDialog()
                                 : _pllm.GetDialog(system: PICOLLM_SYSTEM_PROMPT);
                var completion = new CompletionText(STOP_PHRASES);
                var short_answer_instruction = "You are a voice assistant and your " +
                                               "answers are very short but informative";

                void callback(string token)
                {
                    pllmProfiler?.Tock();
                    completion.Append(token);
                    if (completion.GetNewTokens().Length > 0)
                    {
                        PartialCompletionGenerated?.Invoke(this, completion.GetNewTokens());
                        Console.Write(token);
                    }
                }

                while (true)
                {
                    var userInput = await _userInputChannel.Reader.ReadAsync();
                    dialog.AddHumanRequest(SHORT_ANSWERS
                                               ? $"{short_answer_instruction}. {userInput}"
                                               : userInput);
                    completion.Reset();
                    Console.Write($"LLM (say {PORCUPINE_WAKE_WORD} to interrupt) > ");
                    var result = _pllm.Generate(
                        prompt: dialog.Prompt(),
                        completionTokenLimit: COMPLETION_TOKEN_LIMIT,
                        stopPhrases: STOP_PHRASES, presencePenalty: LLM_PRESENCE_PENALTY,
                        frequencyPenalty: LLM_FREQUENCY_PENALTY,
                        temperature: LLM_TEMPERATURE, topP: LLM_TOP_P,
                        streamCallback: callback);
                    dialog.AddLLMResponse(result.Completion);
                    Console.WriteLine();
                    CompletionGenerationCompleted?.Invoke(this, result.Endpoint);
                    Console.WriteLine($"Say {PORCUPINE_WAKE_WORD} to start.");
                }
            }
        }

        private class Speaker
        {
            Orca _orca;
            Generator generator;
            CancellationTokenSource _cancellationTokenSource =
                new CancellationTokenSource();
            readonly Channel<short[]> _audioChannel =
                Channel.CreateUnbounded<short[]>();

            public Speaker(Generator generator, Listener listener)
            {
                _orca = Orca.Create(accessKey: ACCESS_KEY);
                orcaProfiler?.Init(_orca.SampleRate);
                this.generator = generator;
                listener.WakeWordDetected += OnWakeWordDetected;
            }

            private void OnWakeWordDetected(object? sender, EventArgs _)
            {
                _cancellationTokenSource.Cancel();
                _cancellationTokenSource.Dispose();
                _cancellationTokenSource = new CancellationTokenSource();
                var cancellationToken = _cancellationTokenSource.Token;
                Orca.OrcaStream orcaStream;
                PvSpeaker speaker =
                    new PvSpeaker(_orca.SampleRate, 16, bufferSizeSecs: 1);
                speaker.Start();
                while (true)
                {
                    try
                    {
                        orcaStream = _orca.StreamOpen(speechRate: ORCA_SPEECH_RATE);
                        break;
                    }
                    catch (System.Exception)
                    {
                        continue;
                    }
                }

                var audioChannel = Channel.CreateUnbounded<short[]>();
                var completionChannel = Channel.CreateUnbounded<string>();
                void OnPartialCompletion(object? _, string completion)
                {
                    completionChannel.Writer.TryWrite(completion);
                }
                void OnCompletionGenerationCompleted(object? _, PicoLLMEndpoint endpoint)
                {
                    if (endpoint != PicoLLMEndpoint.INTERRUPTED)
                    {
                        while (completionChannel.Reader.Count > 0)
                            ;
                        orcaProfiler?.Tick();
                        var pcm = orcaStream.Flush();
                        orcaProfiler?.Tock(pcm);
                        profilerEvent?.Invoke(null, orcaProfiler);
                        if (pcm != null)
                        {
                            audioChannel.Writer.TryWrite(pcm);
                        }
                    }
                    audioChannel.Writer.Complete();
                    generator.PartialCompletionGenerated -= OnPartialCompletion;
                    generator.CompletionGenerationCompleted -=
                        OnCompletionGenerationCompleted;
                    profilerEvent?.Invoke(null, delayProfiler);
                }
                generator.PartialCompletionGenerated += OnPartialCompletion;
                generator.CompletionGenerationCompleted +=
                    OnCompletionGenerationCompleted;
                var ttsTask = Task.Run(async () =>
                {
                    short[] pcmBuffer = [];
                    bool isStarted = false;
                    while (!audioChannel.Reader.Completion.IsCompleted &&
                           !cancellationToken.IsCancellationRequested)
                    {
                        var pcm =
                            await audioChannel.Reader.ReadAsync(cancellationToken);
                        pcmBuffer = [.. pcmBuffer, .. pcm];
                        if (!isStarted &&
                            (pcmBuffer.Length >
                                 TTS_WARMUP_SECONDS * _orca.SampleRate ||
                             audioChannel.Reader.Completion.IsCompleted))
                        {
                            delayProfiler?.Tock();
                            isStarted = true;
                        }
                        if (isStarted)
                        {
                            while (pcmBuffer.Length > 0 &&
                                   !cancellationToken.IsCancellationRequested)
                            {
                                var written = speaker.Write(
                                    pcmBuffer.SelectMany(s => BitConverter.GetBytes(s))
                                        .ToArray());
                                pcmBuffer = pcmBuffer[written..];
                            }
                        }
                    }
                });
                Task.Run(async () =>
                {
                    while (true)
                    {
                        var text = await completionChannel.Reader.ReadAsync(
                            cancellationToken);
                        orcaProfiler?.Tick();
                        var pcm = orcaStream.Synthesize(text);
                        orcaProfiler?.Tock(pcm);
                        if (pcm != null)
                        {
                            audioChannel.Writer.TryWrite(pcm);
                        }
                    }
                })
                    .ContinueWith(async (t) =>
                    {
                        Console.WriteLine(t.Exception);
                        await ttsTask;
                        speaker.Flush();
                        speaker.Stop();
                        speaker.Dispose();
                        orcaStream.Dispose();
                        generator.PartialCompletionGenerated -=
                            OnPartialCompletion;
                        generator.CompletionGenerationCompleted -=
                            OnCompletionGenerationCompleted;
                    });
            }
        }

        static void Main(string[] args)
        {

            if (args.Length == 0)
            {
                Console.WriteLine(HELP_TEXT);
                return;
            }

            int argIndex = 0;
            while (argIndex < args.Length)
            {
                switch (args[argIndex])
                {
                    case "--config":
                        // TODO: Config parsing
                        if (++argIndex < args.Length)
                        {
                            var configPath = args[argIndex++];
                            var configString = File.ReadAllText(configPath);
                            var config = JsonSerializer.Deserialize<Dictionary<string, object>>(
                                configString);
                            if (config == null)
                            {
                                Console.WriteLine("Invalid config file.");
                                System.Environment.Exit(1);
                            }
                            config.TryGetValue("access_key", out object? accessKeyObj);
                            ACCESS_KEY = (accessKeyObj as JsonElement?)?.GetString();
                            config.TryGetValue("picollm_model_path", out object? llmModelPathObj);
                            LLM_MODEL_PATH = (llmModelPathObj as JsonElement?)?.GetString();
                            config.TryGetValue("keyword_model_path", out object? keywordModelPathObj);
                            KEYWORD_MODEL_PATH = (keywordModelPathObj as JsonElement?)?.GetString();
                            config.TryGetValue("cheetah_endpoint_duration_sec", out object? cheetahEndpointDurationSecObj);
                            CHEETAH_ENDPOINT_DURATION_SEC = (cheetahEndpointDurationSecObj as JsonElement?)?.GetInt32() ?? CHEETAH_ENDPOINT_DURATION_SEC;
                            config.TryGetValue("picollm_device", out object? llmDeviceObj);
                            LLM_DEVICE = (llmDeviceObj as JsonElement?)?.GetString() ?? LLM_DEVICE;
                            config.TryGetValue("picollm_completion_token_limit", out object? completionTokenLimitObj);
                            COMPLETION_TOKEN_LIMIT = (completionTokenLimitObj as JsonElement?)?.GetInt32() ?? COMPLETION_TOKEN_LIMIT;
                            config.TryGetValue("picollm_presence_penalty", out object? llmPresencePenaltyObj);
                            LLM_PRESENCE_PENALTY = (llmPresencePenaltyObj as JsonElement?)?.GetSingle() ?? LLM_PRESENCE_PENALTY;
                            config.TryGetValue("picollm_frequency_penalty", out object? llmFrequencyPenaltyObj);
                            LLM_FREQUENCY_PENALTY = (llmFrequencyPenaltyObj as JsonElement?)?.GetSingle() ?? LLM_FREQUENCY_PENALTY;
                            config.TryGetValue("picollm_temperature", out object? llmTemperatureObj);
                            LLM_TEMPERATURE = (llmTemperatureObj as JsonElement?)?.GetSingle() ?? LLM_TEMPERATURE;
                            config.TryGetValue("picollm_top_p", out object? llmTopPObj);
                            LLM_TOP_P = (llmTopPObj as JsonElement?)?.GetSingle() ?? LLM_TOP_P;
                            config.TryGetValue("picollm_system_prompt", out object? llmSystemPromptObj);
                            PICOLLM_SYSTEM_PROMPT = (llmSystemPromptObj as JsonElement?)?.GetString();
                            config.TryGetValue("orca_warmup_sec", out object? orcaWarmupSecObj);
                            TTS_WARMUP_SECONDS = (orcaWarmupSecObj as JsonElement?)?.GetInt32() ?? TTS_WARMUP_SECONDS;
                            config.TryGetValue("orca_speech_rate", out object? orcaSpeechRateObj);
                            ORCA_SPEECH_RATE = (orcaSpeechRateObj as JsonElement?)?.GetSingle() ?? ORCA_SPEECH_RATE;
                            config.TryGetValue("porcupine_sensitivity", out object? porcupineSensitivityObj);
                            PORCUPINE_SENSITIVITY = (porcupineSensitivityObj as JsonElement?)?.GetSingle() ?? PORCUPINE_SENSITIVITY;
                            config.TryGetValue("short_answers", out object? shortAnswersObj);
                            SHORT_ANSWERS = (shortAnswersObj as JsonElement?)?.GetBoolean() ?? SHORT_ANSWERS;
                            config.TryGetValue("profile", out object? profileObj);
                            PROFILE = (profileObj as JsonElement?)?.GetBoolean() ?? PROFILE;
                        }
                        break;
                    case "--access_key":
                        if (++argIndex < args.Length)
                        {
                            ACCESS_KEY = args[argIndex++];
                        }
                        break;
                    case "--picollm_model_path":
                        if (++argIndex < args.Length)
                        {
                            LLM_MODEL_PATH = args[argIndex++];
                        }
                        break;
                    case "--keyword_model_path":
                        if (++argIndex < args.Length)
                        {
                            KEYWORD_MODEL_PATH = args[argIndex++];
                            PORCUPINE_WAKE_WORD = "your wake word";
                        }
                        break;
                    case "--cheetah_endpoint_duration_sec":
                        if (++argIndex < args.Length)
                        {
                            CHEETAH_ENDPOINT_DURATION_SEC = int.Parse(args[argIndex++]);
                        }
                        break;
                    case "--picollm_device":
                        if (++argIndex < args.Length)
                        {
                            LLM_DEVICE = args[argIndex++];
                        }
                        break;
                    case "--picollm_completion_token_limit":
                        if (++argIndex < args.Length)
                        {
                            COMPLETION_TOKEN_LIMIT = int.Parse(args[argIndex++]);
                        }
                        break;
                    case "--picollm_presence_penalty":
                        if (++argIndex < args.Length)
                        {
                            LLM_PRESENCE_PENALTY = float.Parse(args[argIndex++]);
                        }
                        break;
                    case "--picollm_frequency_penalty":
                        if (++argIndex < args.Length)
                        {
                            LLM_FREQUENCY_PENALTY = float.Parse(args[argIndex++]);
                        }
                        break;
                    case "--picollm_temperature":
                        if (++argIndex < args.Length)
                        {
                            LLM_TEMPERATURE = float.Parse(args[argIndex++]);
                        }
                        break;
                    case "--picollm_top_p":
                        if (++argIndex < args.Length)
                        {
                            LLM_TOP_P = float.Parse(args[argIndex++]);
                        }
                        break;
                    case "--picollm_system_prompt":
                        if (++argIndex < args.Length)
                        {
                            PICOLLM_SYSTEM_PROMPT = args[argIndex++];
                        }
                        break;
                    case "--orca_warmup_sec":
                        if (++argIndex < args.Length)
                        {
                            TTS_WARMUP_SECONDS = int.Parse(args[argIndex++]);
                        }
                        break;
                    case "--orca_speech_rate":
                        if (++argIndex < args.Length)
                        {
                            ORCA_SPEECH_RATE = float.Parse(args[argIndex++]);
                        }
                        break;
                    case "--porcupine_sensitivity":
                        if (++argIndex < args.Length)
                        {
                            PORCUPINE_SENSITIVITY = float.Parse(args[argIndex++]);
                        }
                        break;
                    case "--short_answers":
                        if (++argIndex < args.Length)
                        {
                            SHORT_ANSWERS = bool.Parse(args[argIndex++]);
                        }
                        break;
                    case "--profile":
                        if (++argIndex < args.Length)
                        {
                            PROFILE = bool.Parse(args[argIndex++]);
                        }
                        break;
                    case "--help":
                    case "-h":
                        Console.WriteLine(HELP_TEXT);
                        System.Environment.Exit(0);
                        break;
                    default:
                        argIndex++;
                        break;
                }
            }

            if (ACCESS_KEY == null || LLM_MODEL_PATH == null)
            {
                Console.WriteLine("Access key and LLM model path are required.");
                System.Environment.Exit(1);
            }

            Console.WriteLine("Initializing... ");

            if (PROFILE)
            {
                profilerEvent += (_, profiler) =>
                    Console.WriteLine($"[{profiler?.Stats()}]");
                orcaProfiler = new RTFProfiler("Orca");
                porcupineProfiler = new RTFProfiler("Porcupine");
                cheetahProfiler = new RTFProfiler("Cheetah");
                pllmProfiler = new TPSProfiler("PicoLLM");
                delayProfiler = new DelayProfiler();
            }
            Listener listener = new Listener();
            Generator generator = new Generator(listener);
            Speaker speaker = new Speaker(generator, listener);
            Console.WriteLine($"Initialized. Say {PORCUPINE_WAKE_WORD} to start.");
            listener.run();
        }

        static readonly string HELP_TEXT = @"
Arguments:
  --access_key: `AccessKey` obtained from `Picovoice Console` (https://console.picovoice.ai/).

  --picollm_model_path: Absolute path to the file containing LLM parameters (`.pllm`).

  --keyword_model_path: Absolute path to the keyword model file (`.ppn`). If not set, `Picovoice` will be the wake phrase.

  --cheetah_endpoint_duration_sec: Duration of silence (pause) after the user's utterance to consider it the end of the utterance.

  --picollm_device: String representation of the device (e.g., CPU or GPU) to use for inference. Options:
    - `best`: Picks the most suitable device.
    - `gpu`: Uses the first available GPU device.
    - `gpu:${GPU_INDEX}`: Selects a specific GPU by index.
    - `cpu`: Runs on CPU with default threads.
    - `cpu:${NUM_THREADS}`: Runs on CPU with specified threads.

  --picollm_completion_token_limit: Maximum number of tokens in the completion. Set to `None` for no limit.

  --picollm_presence_penalty: Positive value penalizes logits already appearing in the partial completion. `0.0` means no effect.

  --picollm_frequency_penalty: Positive value penalizes logits based on frequency in partial completion. `0.0` means no effect.

  --picollm_temperature: Sampling temperature controlling randomness:
    - Higher value increases randomness.
    - Lower value reduces variability.
    - `0` selects the maximum logit.

  --picollm_top_p: Limits sampling to high-probability logits forming the `top_p` portion of the probability mass (0 < `top_p` ≤ 1). `1.` turns off the feature.

  --picollm_system_prompt: Text prompt to instruct the LLM before input.

  --orca_warmup_sec: Duration of synthesized audio to buffer before streaming. Higher values help slower devices but increase initial delay.

  --orca_speech_rate: Rate of speech for generated audio.

  --porcupine_sensitivity: Sensitivity for detecting keywords.

  --short_answers: Flag to enable short answers.

  --profile: Flag to show runtime profiling information.
";
    }
}