# Voice Memo Assistant on iOS

Record, rewrite, summarize, and replay voice memos hands-free, powered by on-device voice AI.

## Compatibility

- iOS 16.0+

## AccessKey

AccessKey is your authentication and authorization token for deploying Picovoice SDKs. Anyone who is using Picovoice
needs to have a valid AccessKey. You must keep your AccessKey secret. You would need internet connectivity to validate
your AccessKey with Picovoice license servers, even though the inference is running 100% offline. Everyone who signs up
for [Picovoice Console](https://console.picovoice.ai/) receives a unique AccessKey.

## Usage

### 1. Train the Wake Word Model

1. Open [Picovoice Console](https://console.picovoice.ai/)
2. Go to Porcupine Wake Word.
3. Enter your desired wake phrase.
4. Click Train.
5. Select the **iOS** platform and download the generated wake word model file (`.ppn`).

### 2. Train the Speech-to-Intent Model

1. Open [Picovoice Console](https://console.picovoice.ai/)
2. Go to Rhino Speech-to-Intent.
3. Create an empty Rhino context.
4. Click Import YAML in the top-right corner.
5. Paste the [Rhino context YAML](../res/context.yml) for this demo.
6. Select the **iOS** platform and download the generated Rhino context file (`.rhn`).

### 3. Download the LLM

Download `llama-3.2-1b-instruct-385.pllm` from [Picovoice Console](https://console.picovoice.ai/).

### 4. Download the Required Models

Run the setup script to download and copy the models for [Cheetah Streaming Speech-to-Text](https://picovoice.ai/docs/cheetah/)
and [Orca Streaming Text-to-Speech](https://picovoice.ai/docs/orca/).

It will also copy the models provided for [Porcupine Wake Word](https://picovoice.ai/docs/porcupine/),
[Rhino Speech-to-Intent](https://picovoice.ai/docs/rhino/) and [picoLLM Inference](https://picovoice.ai/docs/picollm/)
to the project folder.

Lastly, it will place your `AccessKey` from Picovoice Console into the `ACCESS_KEY` variable
in [ViewModel.swift](VoiceMemo/ViewModel.swift).

```console
cd pico-cookbook/recipes/voice-memo-assistant/ios

python setup.py \
    --access_key ${ACCESS_KEY} \
    --keyword_path ${PATH_TO_PPN} \
    --context_path ${PATH_TO_RHN} \
    --picollm_model_path ${PATH_TO_PLLM}
```

### 4. Run the XCode project

1. Open the `VoiceMemo` project in XCode
2. Build and run the demo.
