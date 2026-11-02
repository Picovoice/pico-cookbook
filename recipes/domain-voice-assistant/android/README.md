# Domain Voice Assistant on Android

Ask a voice assistant about one domain, here Picovoice's products, and hear the answer spoken back, powered by on-device
voice AI customized for that domain.

## Compatibility

- Android 5.0 (SDK 21+)

## AccessKey

AccessKey is your authentication and authorization token for deploying Picovoice SDKs. Anyone who is using Picovoice
needs to have a valid AccessKey. You must keep your AccessKey secret. You would need internet connectivity to validate
your AccessKey with Picovoice license servers, even though the inference is running 100% offline. Everyone who signs up
for [Picovoice Console](https://console.picovoice.ai/) receives a unique AccessKey.

## Usage

These instructions assume your current working directory is `recipes/domain-voice-assistant/android`.

### 1. Download the LLM

Download `llama-3.2-1b-picovoice-assistant-355.pllm` from
[Hugging Face](https://huggingface.co/Picovoice/Llama-3.2-Picovoice-Assistant-1B).

The customized [Cheetah Streaming Speech-to-Text](https://picovoice.ai/docs/cheetah/) and
[Orca Streaming Text-to-Speech](https://picovoice.ai/docs/orca/) models are included in [res/models](../res/models).

### 2. Train the Wake Word Model

1. Open [Picovoice Console](https://console.picovoice.ai/)
2. Go to Porcupine Wake Word.
3. Enter your desired wake phrase.
4. Click Train.
5. Select target platform **Android** and download the generated wake word model file (`.ppn`).

Save the downloaded file somewhere accessible on your machine. You will pass its path to the setup script with
`--porcupine_keyword_path`.

### 3. Run the Setup Script

The setup script copies your wake word file (`.ppn`), the Cheetah and Orca models (`.pv`), and the picoLLM model
(`.pllm`) to the assets folder.

It also places your `AccessKey` from Picovoice Console into the `ACCESS_KEY` variable in
[MainActivity.java](domain-voice-assistant/src/main/java/ai/picovoice/domainvoiceassistant/MainActivity.java).

```console
python setup.py \
    --access_key ${ACCESS_KEY} \
    --porcupine_keyword_path ${PATH_TO_PPN_FILE} \
    --picollm_model_path ${PATH_TO_PLLM}
```

### 4. Run the Android Demo

1. Open the `domain-voice-assistant` project in Android Studio.
2. Connect a device or launch an Android emulator.
3. Build and run the demo.

#### Instructions

- Say your wake word, then ask your question. You can also type it and press the arrow button.
- After an answer, the assistant keeps listening for about 8 seconds, so you can ask a follow-up question without the
  wake word.
- To interrupt an answer, say the wake word.
