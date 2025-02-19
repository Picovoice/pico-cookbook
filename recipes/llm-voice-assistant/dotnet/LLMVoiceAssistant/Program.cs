using System.Collections.Concurrent;
using System.Text;
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
        static event EventHandler<Profiler?>? ProfilerEvent;

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
            public event EventHandler? WakeWordDetectedWhileGenerating;
            private event EventHandler<short[]>? AudioReceived;

            private string _transcript = "";
            private bool generating = false;

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

            public void RegisterGenerationCallback(Generator generator)
            {
                generator.CompletionGenerationCompleted += (_, _) => generating = false;
                generator.CompletionGenerationStarted += (_, _) => generating = true;
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
                    if (generating)
                    {
                        WakeWordDetectedWhileGenerating?.Invoke(this, EventArgs.Empty);
                    }
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
                ProfilerEvent?.Invoke(null, porcupineProfiler);
                porcupineProfiler?.Reset();
                cheetahProfiler?.Reset();
                Console.WriteLine(
                    "Wake word detected, utter your request or question ...");
                Console.Write("User: ");
                AudioReceived -= PorcupineOnAudioReceived;
                AudioReceived += CheetahOnAudioReceived;
            }

            private void OnUserInputReceived(object? sender, string userInput)
            {
                ProfilerEvent?.Invoke(null, cheetahProfiler);
                _transcript = "";
                AudioReceived -= CheetahOnAudioReceived;
                AudioReceived += PorcupineOnAudioReceived;
            }
        }

        private class Generator
        {
            readonly PicoLLM _pllm;
            readonly Channel<string> _userInputChannel = Channel.CreateUnbounded<string>();
            public event EventHandler<string>? PartialCompletionGenerated;
            public event EventHandler? CompletionGenerationStarted;
            public event EventHandler<PicoLLMEndpoint>? CompletionGenerationCompleted;

            public Generator(Listener listener)
            {
                _pllm = PicoLLM.Create(accessKey: ACCESS_KEY, modelPath: LLM_MODEL_PATH,
                                       device: LLM_DEVICE);
                listener.UserInputReceived += OnUserInputReceived;
                listener.WakeWordDetectedWhileGenerating += OnWakeWordDetected;
                CompletionGenerationCompleted += (_, endpoint) =>
                    ProfilerEvent?.Invoke(null, pllmProfiler);
                Task.Run(llmWorker);
            }

            private void OnUserInputReceived(object? sender, string userInput)
            {
                if (userInput.Length != 0)
                    _userInputChannel.Writer.WriteAsync(userInput);
            }

            private void OnWakeWordDetected(object? sender, EventArgs e)
            {
                Console.WriteLine("Interrupted by wake up word.");
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
                    CompletionGenerationStarted?.Invoke(this, EventArgs.Empty);
                    Console.Write($"LLM (say {PORCUPINE_WAKE_WORD} to interrupt): ");
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
            PvSpeaker _speaker;
            readonly Channel<short[]> _audioChannel = Channel.CreateUnbounded<short[]>();
            event EventHandler? OrcaSynthesisCompleted;

            public Speaker(Generator generator)
            {
                _orca = Orca.Create(accessKey: ACCESS_KEY);
                _speaker = new PvSpeaker(_orca.SampleRate, 16, bufferSizeSecs: 1);
                orcaProfiler?.Init(_orca.SampleRate);
                Task.Run(() => orcaWorker(generator));
                Task.Run(() => ttsWorker(generator));
            }

            private async Task orcaWorker(Generator generator)
            {
                Channel<string> completionChannel = Channel.CreateUnbounded<string>();
                var orcaStream = _orca.StreamOpen(speechRate: ORCA_SPEECH_RATE);
                generator.CompletionGenerationCompleted += (sender, endpoint) =>
                {
                    if (endpoint == PicoLLMEndpoint.INTERRUPTED)
                    {
                        orcaStream.Flush();
                        orcaProfiler?.Reset();
                        while (completionChannel.Reader.TryRead(out _))
                            ;
                    }
                    else
                    {
                        while (completionChannel.Reader.Count > 0)
                            ;
                        orcaProfiler?.Tick();
                        var pcm = orcaStream.Flush();
                        orcaProfiler?.Tock(pcm);
                        ProfilerEvent?.Invoke(null, orcaProfiler);
                        if (pcm != null)
                        {
                            _audioChannel.Writer.TryWrite(pcm);
                        }
                        OrcaSynthesisCompleted?.Invoke(this, EventArgs.Empty);
                    }
                };
                generator.PartialCompletionGenerated += (_, completion) =>
                    completionChannel.Writer.TryWrite(completion);

                while (true)
                {
                    var text = await completionChannel.Reader.ReadAsync();
                    orcaProfiler?.Tick();
                    var pcm = orcaStream.Synthesize(text);
                    orcaProfiler?.Tock(pcm);
                    if (pcm != null)
                    {
                        await _audioChannel.Writer.WriteAsync(pcm);
                    }
                }
            }

            private async Task ttsWorker(Generator generator)
            {
                ConcurrentQueue<short> pcmBuffer = [];
                generator.CompletionGenerationCompleted += (_, endpoint) =>
                {
                    if (endpoint == PicoLLMEndpoint.INTERRUPTED)
                    {
                        pcmBuffer = [];
                        _speaker.Flush();
                        _speaker.Stop();
                    }
                };

                OrcaSynthesisCompleted += (_, _) =>
                {
                    if (!_speaker.IsStarted)
                    {
                        _speaker.Start();
                        while (pcmBuffer.Count > 0)
                        {
                            var written = _speaker.Write(
                                pcmBuffer.SelectMany(s => BitConverter.GetBytes(s)).ToArray());
                            while (written-- != 0)
                                pcmBuffer.TryDequeue(out short _);
                        }
                    }
                    while (!pcmBuffer.IsEmpty)
                        ;
                    pcmBuffer = [];
                    _speaker.Flush();
                    _speaker.Stop();
                };

                while (true)
                {
                    var pcm = await _audioChannel.Reader.ReadAsync();
                    for (int i = 0; i < pcm.Length; i++)
                        pcmBuffer.Enqueue(pcm[i]);
                    if (!_speaker.IsStarted)
                    {
                        if (pcmBuffer.Count > TTS_WARMUP_SECONDS * _orca.SampleRate)
                        {
                            _speaker.Start();
                        }
                    }
                    if (_speaker.IsStarted)
                    {
                        while (!pcmBuffer.IsEmpty)
                        {
                            var written = _speaker.Write(
                                pcmBuffer.SelectMany(s => BitConverter.GetBytes(s)).ToArray());
                            while (written-- != 0)
                                pcmBuffer.TryDequeue(out short _);
                        }
                    }
                }
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
                ProfilerEvent += (_, profiler) =>
                    Console.WriteLine($"[{profiler?.Stats()}]");
                orcaProfiler = new RTFProfiler("Orca");
                porcupineProfiler = new RTFProfiler("Porcupine");
                cheetahProfiler = new RTFProfiler("Cheetah");
                pllmProfiler = new TPSProfiler("PicoLLM");
            }
            Listener listener = new Listener();
            Generator generator = new Generator(listener);
            Speaker speaker = new Speaker(generator);
            listener.RegisterGenerationCallback(generator);
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