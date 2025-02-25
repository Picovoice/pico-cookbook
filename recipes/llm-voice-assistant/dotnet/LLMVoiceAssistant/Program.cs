using System.Diagnostics;
using System.Text;
using System.Text.Json;
using System.Threading.Channels;

using Pv;

namespace LLMVoiceAssistant
{
    class LLMVoiceAssistant
    {
        static RTFProfiler? orcaProfiler;
        static RTFProfiler? porcupineProfiler;
        static RTFProfiler? cheetahProfiler;
        static TPSProfiler? pllmProfiler;
        static DelayProfiler? delayProfiler;
        static event EventHandler<Profiler?>? profilerEvent;
        static TaskCompletionSource<bool> llmFinish = new TaskCompletionSource<bool>();
        static Config config = new Config();

        class Config
        {
            public string? AccessKey { get; set; }
            public int CheetahEndpointDurationSec { get; set; } = 1;
            public int PicollmCompletionTokenLimit { get; set; } = 128;
            public int OrcaWarmupSec { get; set; } = 1;
            public string? PicollmModelPath { get; set; }
            public string? KeywordModelPath { get; set; }
            public string PicollmDevice { get; set; } = "best";
            public float PicollmPresencePenalty { get; set; } = 0.0f;
            public float PicollmFrequencyPenalty { get; set; } = 0.0f;
            public float PicollmTemperature { get; set; } = 1.0f;
            public float PicollmTopP { get; set; } = 1.0f;
            public float OrcaSpeechRate { get; set; } = 1.0f;
            public string? PicollmSystemPrompt { get; set; }
            public bool ShortAnswers { get; set; } = false;
            public bool Profile { get; set; } = false;
            public float PorcupineSensitivity { get; set; } = 0.5f;
            public string PorcupineWakeWord { get; set; } = "Picovoice";
            public readonly string[] StopPhrases =
            [
                "</s>",                                 // Llama-2, Mistral, and Mixtral
                "<end_of_turn>",                        // Gemma
                "<|endoftext|>",                        // Phi-2
                "<|eot_id|>",                           // Llama-3
                "<|end|>",
                "<|user|>",
                "<|assistant|>",                        // Phi-3
            ];
        }

        public class CompletionText
        {
            readonly string[] stopPhrases;
            int start;
            readonly StringBuilder text;
            string newTokens;

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
            readonly Cheetah cheetah;
            readonly Porcupine porcupine;
            readonly PvRecorder recorder;
            event EventHandler<short[]>? AudioReceived;
            string transcript = "";
            public event EventHandler<string>? UserInputReceived;
            public event EventHandler? WakeWordDetected;

            public Listener()
            {
                cheetah = Cheetah.Create(accessKey: config.AccessKey,
                                   endpointDurationSec: config.CheetahEndpointDurationSec,
                                   enableAutomaticPunctuation: true);
                porcupine =
                    config.KeywordModelPath == null
                        ? Porcupine.FromBuiltInKeywords(
                              accessKey: config.AccessKey,
                              keywords: [BuiltInKeyword.PICOVOICE],
                              sensitivities: [config.PorcupineSensitivity])
                        : Porcupine.FromKeywordPaths(
                              accessKey: config.AccessKey,
                              keywordPaths: [config.KeywordModelPath],
                              sensitivities: [config.PorcupineSensitivity]);
                recorder = PvRecorder.Create(frameLength: porcupine.FrameLength);
                cheetahProfiler?.Init(cheetah.SampleRate);
                porcupineProfiler?.Init(porcupine.SampleRate);
                AudioReceived += PorcupineOnAudioReceived;
                WakeWordDetected += OnWakeWordDetected;
                UserInputReceived += OnUserInputReceived;
            }

            public void Run()
            {
                recorder.Start();
                while (true)
                {
                    short[] pcm = recorder.Read();
                    AudioReceived?.Invoke(this, pcm);
                }
            }

            private void PorcupineOnAudioReceived(object? sender, short[] pcm)
            {
                porcupineProfiler?.Tick();
                int keywordIndex = porcupine.Process(pcm);
                porcupineProfiler?.Tock(pcm);
                if (keywordIndex >= 0)
                {
                    WakeWordDetected?.Invoke(this, EventArgs.Empty);
                    llmFinish.Task.Wait();
                    Console.WriteLine("\nWake word detected, utter your request or question ...");
                    Console.Write("User > ");
                }
            }

            private void CheetahOnAudioReceived(object? sender, short[] pcm)
            {
                cheetahProfiler?.Tick();
                var transcript = cheetah.Process(pcm);
                cheetahProfiler?.Tock(pcm);
                if (transcript.Transcript.Length > 0)
                {
                    this.transcript += transcript.Transcript;
                    Console.Write(transcript.Transcript);
                }
                if (transcript.IsEndpoint)
                {
                    cheetahProfiler?.Tick();
                    var remainingTranscript = cheetah.Flush();
                    cheetahProfiler?.Tock(null);
                    this.transcript += remainingTranscript.Transcript;
                    Console.WriteLine(remainingTranscript.Transcript);
                    UserInputReceived?.Invoke(this, this.transcript);
                }
            }

            private void OnWakeWordDetected(object? sender, EventArgs e)
            {
                profilerEvent?.Invoke(null, porcupineProfiler);
                porcupineProfiler?.Reset();
                cheetahProfiler?.Reset();
                AudioReceived -= PorcupineOnAudioReceived;
                AudioReceived += CheetahOnAudioReceived;
            }

            private void OnUserInputReceived(object? sender, string userInput)
            {
                profilerEvent?.Invoke(null, cheetahProfiler);
                delayProfiler?.Tick();
                transcript = "";
                AudioReceived -= CheetahOnAudioReceived;
                AudioReceived += PorcupineOnAudioReceived;
            }
        }

        private class Generator
        {
            readonly PicoLLM pllm;
            readonly Channel<string> userInputChannel = Channel.CreateUnbounded<string>();
            public event EventHandler<string>? PartialCompletionGenerated;
            public event EventHandler<PicoLLMEndpoint>? CompletionGenerationCompleted;

            public Generator(Listener listener)
            {
                pllm = PicoLLM.Create(accessKey: config.AccessKey,
                                        modelPath: config.PicollmModelPath,
                                        device: config.PicollmDevice);
                listener.UserInputReceived += OnUserInputReceived;
                listener.WakeWordDetected += OnWakeWordDetected;
                CompletionGenerationCompleted += OnCompletionGenerationCompleted;
                Task.Run(LlmWorker).ContinueWith((t) => Console.WriteLine(t.Exception));
            }

            private void OnUserInputReceived(object? sender, string userInput)
            {
                userInputChannel.Writer.TryWrite(userInput);
            }

            private void OnWakeWordDetected(object? sender, EventArgs e)
            {
                pllm.Interrupt();
            }

            private void OnCompletionGenerationCompleted(object? sender, PicoLLMEndpoint endpoint)
            {
                profilerEvent?.Invoke(null, pllmProfiler);
            }

            private async Task LlmWorker()
            {
                var dialog = config.PicollmSystemPrompt == null
                                 ? pllm.GetDialog()
                                 : pllm.GetDialog(system: config.PicollmSystemPrompt);
                var completion = new CompletionText(config.StopPhrases);
                var shortAnswerInstruction = "You are a voice assistant and your answers are very short but informative";

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
                    var userInput = await userInputChannel.Reader.ReadAsync();
                    llmFinish = new TaskCompletionSource<bool>();
                    dialog.AddHumanRequest(config.ShortAnswers
                                               ? $"{shortAnswerInstruction}. {userInput}"
                                               : userInput);
                    completion.Reset();
                    Console.Write($"LLM (say {config.PorcupineWakeWord} to interrupt) > ");
                    var result = pllm.Generate(
                        prompt: dialog.Prompt(),
                        completionTokenLimit: config.PicollmCompletionTokenLimit,
                        stopPhrases: config.StopPhrases,
                        presencePenalty: config.PicollmPresencePenalty,
                        frequencyPenalty: config.PicollmFrequencyPenalty,
                        temperature: config.PicollmTemperature,
                        topP: config.PicollmTopP,
                        streamCallback: callback);
                    dialog.AddLLMResponse(result.Completion);
                    Console.WriteLine();
                    CompletionGenerationCompleted?.Invoke(this, result.Endpoint);
                    llmFinish.SetResult(true);
                }
            }
        }

        private class Speaker
        {
            readonly Orca orca;
            readonly Generator generator;
            CancellationTokenSource cancellationTokenSource = new CancellationTokenSource();
            TaskCompletionSource<bool> speakerFinish = new TaskCompletionSource<bool>();

            public Speaker(Generator generator, Listener listener)
            {
                orca = Orca.Create(accessKey: config.AccessKey);
                orcaProfiler?.Init(orca.SampleRate);
                this.generator = generator;
                listener.WakeWordDetected += OnWakeWordDetected;
                speakerFinish.SetResult(true);
            }

            private void OnWakeWordDetected(object? sender, EventArgs _)
            {
                cancellationTokenSource.Cancel();
                cancellationTokenSource.Dispose();
                speakerFinish.Task.Wait();
                speakerFinish = new TaskCompletionSource<bool>();
                cancellationTokenSource = new CancellationTokenSource();
                var cancellationToken = cancellationTokenSource.Token;
                Orca.OrcaStream orcaStream = orca.StreamOpen(speechRate: config.OrcaSpeechRate);
                PvSpeaker speaker = new PvSpeaker(orca.SampleRate, 16, bufferSizeSecs: 1);
                speaker.Start();

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
                        {
                            Thread.Sleep(50);
                        }
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
                        var pcm = await audioChannel.Reader.ReadAsync(cancellationToken);
                        pcmBuffer = [.. pcmBuffer, .. pcm];
                        if (!isStarted &&
                            (pcmBuffer.Length > config.OrcaWarmupSec * orca.SampleRate ||
                             audioChannel.Reader.Completion.IsCompleted))
                        {
                            delayProfiler?.Tock();
                            isStarted = true;
                        }
                        if (isStarted)
                        {
                            while (pcmBuffer.Length > 0 && !cancellationToken.IsCancellationRequested)
                            {
                                var written = speaker.Write(
                                    pcmBuffer.SelectMany(s => BitConverter.GetBytes(s)).ToArray());
                                pcmBuffer = pcmBuffer[written..];
                            }
                        }
                    }
                }).ContinueWith((t) =>
                {
                    speaker.Flush();
                    Console.WriteLine($"Say {config.PorcupineWakeWord} to start.");
                });
                Task.Run(async () =>
                {
                    while (true)
                    {
                        var text = await completionChannel.Reader.ReadAsync(cancellationToken);
                        orcaProfiler?.Tick();
                        var pcm = orcaStream.Synthesize(text);
                        orcaProfiler?.Tock(pcm);
                        if (pcm != null)
                        {
                            audioChannel.Writer.TryWrite(pcm);
                        }
                    }
                }).ContinueWith(async (t) =>
                    {
                        await ttsTask;
                        speaker.Stop();
                        speaker.Dispose();
                        generator.PartialCompletionGenerated -= OnPartialCompletion;
                        generator.CompletionGenerationCompleted -= OnCompletionGenerationCompleted;
                        orcaStream.Dispose();
                        speakerFinish.SetResult(true);
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
                        if (++argIndex < args.Length)
                        {
                            var configPath = args[argIndex++];
                            var configString = File.ReadAllText(configPath);
                            var userConfig = JsonSerializer.Deserialize<Config>(configString,
                                options: new JsonSerializerOptions
                                {
                                    PropertyNameCaseInsensitive = true,
                                    PropertyNamingPolicy = JsonNamingPolicy.SnakeCaseLower
                                });
                            if (userConfig == null)
                            {
                                Console.WriteLine("Invalid config file.");
                                System.Environment.Exit(1);
                            }
                            config = userConfig;
                        }
                        break;
                    case "--access_key":
                        if (++argIndex < args.Length)
                        {
                            config.AccessKey = args[argIndex++];
                        }
                        break;
                    case "--picollm_model_path":
                        if (++argIndex < args.Length)
                        {
                            config.PicollmModelPath = args[argIndex++];
                        }
                        break;
                    case "--keyword_model_path":
                        if (++argIndex < args.Length)
                        {
                            config.KeywordModelPath = args[argIndex++];
                            config.PorcupineWakeWord = "your wake word";
                        }
                        break;
                    case "--cheetah_endpoint_duration_sec":
                        if (++argIndex < args.Length)
                        {
                            config.CheetahEndpointDurationSec = int.Parse(args[argIndex++]);
                        }
                        break;
                    case "--picollm_device":
                        if (++argIndex < args.Length)
                        {
                            config.PicollmDevice = args[argIndex++];
                        }
                        break;
                    case "--picollm_completion_token_limit":
                        if (++argIndex < args.Length)
                        {
                            config.PicollmCompletionTokenLimit = int.Parse(args[argIndex++]);
                        }
                        break;
                    case "--picollm_presence_penalty":
                        if (++argIndex < args.Length)
                        {
                            config.PicollmPresencePenalty = float.Parse(args[argIndex++]);
                        }
                        break;
                    case "--picollm_frequency_penalty":
                        if (++argIndex < args.Length)
                        {
                            config.PicollmFrequencyPenalty = float.Parse(args[argIndex++]);
                        }
                        break;
                    case "--picollm_temperature":
                        if (++argIndex < args.Length)
                        {
                            config.PicollmTemperature = float.Parse(args[argIndex++]);
                        }
                        break;
                    case "--picollm_top_p":
                        if (++argIndex < args.Length)
                        {
                            config.PicollmTopP = float.Parse(args[argIndex++]);
                        }
                        break;
                    case "--picollm_system_prompt":
                        if (++argIndex < args.Length)
                        {
                            config.PicollmSystemPrompt = args[argIndex++];
                        }
                        break;
                    case "--orca_warmup_sec":
                        if (++argIndex < args.Length)
                        {
                            config.OrcaWarmupSec = int.Parse(args[argIndex++]);
                        }
                        break;
                    case "--orca_speech_rate":
                        if (++argIndex < args.Length)
                        {
                            config.OrcaSpeechRate = float.Parse(args[argIndex++]);
                        }
                        break;
                    case "--porcupine_sensitivity":
                        if (++argIndex < args.Length)
                        {
                            config.PorcupineSensitivity = float.Parse(args[argIndex++]);
                        }
                        break;
                    case "--short_answers":
                        if (++argIndex < args.Length)
                        {
                            config.ShortAnswers = bool.Parse(args[argIndex++]);
                        }
                        break;
                    case "--profile":
                        if (++argIndex < args.Length)
                        {
                            config.Profile = bool.Parse(args[argIndex++]);
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

            if (config.AccessKey == null || config.PicollmModelPath == null)
            {
                Console.WriteLine("Access key and LLM model path are required.");
                System.Environment.Exit(1);
            }

            Console.WriteLine("Initializing... ");

            if (config.Profile)
            {
                profilerEvent += (_, profiler) => Console.WriteLine($"[{profiler?.Stats()}]");
                orcaProfiler = new RTFProfiler("Orca");
                porcupineProfiler = new RTFProfiler("Porcupine");
                cheetahProfiler = new RTFProfiler("Cheetah");
                pllmProfiler = new TPSProfiler("PicoLLM");
                delayProfiler = new DelayProfiler();
            }
            Listener listener = new Listener();
            Generator generator = new Generator(listener);
            Speaker speaker = new Speaker(generator, listener);
            llmFinish.SetResult(true);
            Console.WriteLine($"Initialized. Say {config.PorcupineWakeWord} to start.");
            listener.Run();
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

interface Profiler
{
    public string Stats();
}

class RTFProfiler : Profiler
{
    int sampleRate;
    readonly Stopwatch stopwatch = new Stopwatch();
    double computeTimeSeconds = 0;
    double audioTimeSeconds = 0;
    public string Name { get; private set; }

    public RTFProfiler(string name)
    {
        Name = name;
    }

    public void Init(int sampleRate)
    {
        this.sampleRate = sampleRate;
    }

    public void Tick()
    {
        stopwatch.Restart();
    }

    public void Tock(short[]? pcm)
    {
        stopwatch.Stop();
        computeTimeSeconds += stopwatch.Elapsed.TotalSeconds;
        audioTimeSeconds += pcm == null ? 0 : (double)pcm.Length / sampleRate;
    }

    public double RTF()
    {
        double rtf = 0;
        if (audioTimeSeconds > 0)
        {
            rtf = computeTimeSeconds / audioTimeSeconds;
        }
        computeTimeSeconds = 0;
        audioTimeSeconds = 0;
        return rtf;
    }

    public string Stats()
    {
        return $"{Name} RTF: {RTF():F2}";
    }

    public void Reset()
    {
        computeTimeSeconds = 0;
        audioTimeSeconds = 0;
    }
}

class TPSProfiler : Profiler
{
    int numTokens;
    double startSec;
    readonly Stopwatch stopwatch;
    public string Name { get; private set; }

    public TPSProfiler(string name)
    {
        numTokens = 0;
        startSec = 0.0;
        stopwatch = new Stopwatch();
        Name = name;
    }

    public void Tock()
    {
        if (!stopwatch.IsRunning)
        {
            stopwatch.Start();
            startSec = stopwatch.Elapsed.TotalSeconds;
        }
        else
        {
            numTokens += 1;
        }
    }

    public double TPS()
    {
        double elapsedSec = stopwatch.Elapsed.TotalSeconds - startSec;
        double tps = elapsedSec > 0 ? numTokens / elapsedSec : 0.0;

        numTokens = 0;
        startSec = 0.0;
        stopwatch.Reset();

        return tps;
    }

    public string Stats()
    {
        return $"{Name} TPS: {TPS():F2}";
    }

    public void Reset()
    {
        numTokens = 0;
        startSec = 0.0;
        stopwatch.Reset();
    }
}

class DelayProfiler : Profiler
{
    readonly Stopwatch stopwatch = new Stopwatch();
    public DelayProfiler() { }

    public void Tick()
    {
        stopwatch.Restart();
    }

    public void Tock()
    {
        stopwatch.Stop();
    }

    public string Stats()
    {
        return $"Delay: {stopwatch.ElapsedMilliseconds:F2}ms";
    }
}