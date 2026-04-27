# Voice Memo Assistant in Android

## Compatibility

- Android 5.0 (SDK 21+)

## AccessKey

AccessKey is your authentication and authorization token for deploying Picovoice SDKs. Anyone who is using Picovoice
needs to have a valid AccessKey. You must keep your AccessKey secret. You would need internet connectivity to validate
your AccessKey with Picovoice license servers, even though the inference is running 100% offline. Everyone who signs up
for [Picovoice Console](https://console.picovoice.ai/) receives a unique AccessKey.

## Usage

These instructions assume your current working directory is `recipes/voice-memo-assistant/android`.

### 1. Download the LLM

Download `llama-3.2-1b-instruct-385.pllm` from [Picovoice Console](https://console.picovoice.ai/).

### 2. Train the Wake Word Model

1. Open [Picovoice Console](https://console.picovoice.ai/)
2. Go to Porcupine Wake Word.
3. Enter your desired wake phrase.
4. Click Train.
5. Select your target platform and download the generated wake word model file (`.ppn`).

### 3. Train the Speech-to-Intent Model

1. Open [Picovoice Console](https://console.picovoice.ai/)
2. Go to Rhino Speech-to-Intent.
3. Create an empty Rhino context.
4. Click Import YAML in the top-right corner.
5. Paste the [Rhino context YAML](../res/context.yml) for this demo.
6. Download the generated Rhino context file (`.rhn`) for your target platform.

### 4. Download the Required Models

Run the setup script to download and copy the models for [Cheetah Streaming Speech-to-Text](https://picovoice.ai/docs/cheetah/)
and [Orca Streaming Text-to-Speech](https://picovoice.ai/docs/orca/).

It will also copy the models for [Porcupine Wake Word](https://picovoice.ai/docs/porcupine/),
[Rhino Speech-to-Intent](https://picovoice.ai/docs/rhino/) and [picoLLM Inference](https://picovoice.ai/docs/picollm/)
to the assets folder:

```console
python setup.py --keyword_path ${PATH_TO_PPN} --context_path ${PATH_TO_RHN} --picollm_model_path ${PATH_TO_PLLM}
```

### 5. Run the demo

1. Open the `voice-memo-assistant` project in Android Studio.
2. Copy your `AccessKey` from Picovoice Console into the `ACCESS_KEY` variable in [MainActivity.java](voice-memo-assistant/src/main/java/ai/picovoice/voicememoassistant/MainActivity.java).
3. Connect a device or launch an Android simulator.
4. Build and run the demo.