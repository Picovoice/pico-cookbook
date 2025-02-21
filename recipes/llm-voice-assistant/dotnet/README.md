# Cross-Platform LLM Voice Assistant CLI Demo

A cross-platform voice assistant using Picovoice's Wake Word, STT, TTS and LLM technology with a text-based interface.

## Compatibility

- .NET 8.0
- Runs on Linux (x86_64), macOS (arm64, x86_64), Windows (x86_64), and Raspberry Pi (5 and 4).

## AccessKey

AccessKey is your authentication and authorization token for deploying Picovoice SDKs, including picoLLM. Anyone who is
using Picovoice needs to have a valid AccessKey. You must keep your AccessKey secret. You would need internet
connectivity to validate your AccessKey with Picovoice license servers even though the LLM inference is running 100%
offline and completely free for open-weight models. Everyone who signs up for
[Picovoice Console](https://console.picovoice.ai/) receives a unique AccessKey.

## picoLLM Model

picoLLM Inference Engine supports many open-weight models. The models are on
[Picovoice Console](https://console.picovoice.ai/).

## Usage

The demo uses .NET 8.0 to build:

```console
dotnet build
```

Run the demo:

```console
./bin/Debug/net8.0/LLMVoiceAssistant --access_key ${ACCESS_KEY} --picollm_model_path ${PICOLLM_MODEL_PATH} 
```

Replace `${ACCESS_KEY}` with yours obtained from Picovoice Console and `${PICOLLM_MODEL_PATH}` with the path to the 
model downloaded from Picovoice Console.

To see all available options, type the following:

```console
./bin/Debug/net8.0/LLMVoiceAssistant --help
```

## Custom Wake Word

The demo's default wake phrase is `Picovoice`. You can generate your custom (branded) wake word using Picovoice  Console by following [Porcupine Wake Word documentation (https://picovoice.ai/docs/porcupine/). Once you have the model trained, simply pass it to the demo
application using `--keyword_model_path` argument.

## Profiling

To see the runtime profiling metrics, run the demo with the `--profile` argument

The demo profiles two metrics: Real-time Factor (RTF) and Token per Second (TPS).

### Real-time Factor (RTF)

RTF is a standard metric for measuring the speed of speech processing (e.g., wake word, speech-to-text, and 
text-to-speech). RTF is the CPU time divided by the processed (recognized or synthesized) audio length. Hence, a lower RTF means a more efficient engine.

### Token per Second (TPS)

Token per second is the standard metric for measuring the speed of LLM inference engines. TPS is the number of 
generated tokens divided by the compute time used to create them. A higher TPS is better.
