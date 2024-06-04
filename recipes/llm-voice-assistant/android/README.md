## See It In Action!

[![LLM VA in Action](https://img.youtube.com/vi/ <TODO> /0.jpg)](https://www.youtube.com/ <TODO>)

## Compatibility

- Android 5.0 (SDK 21+)

## AccessKey

AccessKey is your authentication and authorization token for deploying Picovoice SDKs, including picoLLM. Anyone who is
using Picovoice needs to have a valid AccessKey. You must keep your AccessKey secret. You would need internet
connectivity to validate your AccessKey with Picovoice license servers even though the LLM inference is running 100%
offline and completely free for open-weight models. Everyone who signs up for
[Picovoice Console](https://console.picovoice.ai/) receives a unique AccessKey.

## picoLLM Model

picoLLM Inference Engine supports a variety of open-weight models. The models can be downloaded from the [Picovoice Console](https://console.picovoice.ai/).

Download your desired model file (`.pllm`) from the Picovoice Console. If you do not download the
file directly from your Android device, you will need to upload it to the device.
To upload the model to the device, use the Android Studio Device Explorer or `adb push`:
```console
adb push ~/model.pllm /sdcard/Downloads/
```

## Usage

1. Open the `LLMVoiceAssistant` project in Android Studio.
2. Copy your `AccessKey` from Picovoice Console into the `ACCESS_KEY` variable in [MainActivity.java](llm-voice-assistant/src/main/java/ai/picovoice/llmvoiceassistant/MainActivity.java).
3. Connect a device or launch an Android simulator.
4. Build and run the demo.
5. Press the `Load Model` button and select the model file (`.pllm`) from your device's storage.
6. Say "Picovoice", then you'll be able to prompt the voice assistant.

## Custom Wake Word

The demo's default wake phrase is `Picovoice`. You can generate your custom (branded) wake word using
Picovoice Console by following [Porcupine Wake Word documentation (https://picovoice.ai/docs/porcupine/).
Once you have the model trained, add it to your project by following these steps:

1. Download the custom wake word file (`.ppn`)
2. Add it to the `${ANDROID_APP}/src/main/assets` directory of your Android project
3. Create an instance of Porcupine using the .setKeywordPaths builder method and the keyword path (relative to the assets directory or absolute path to the file on device):

```java
porcupine = new Porcupine.Builder()
        .setAccessKey("${ACCESS_KEY}")
        .setKeywordPath("${KEYWORD_FILE_PATH}")
        .build(getApplicationContext());
```

## Profiling

Profiling data is automatically printed in the app's `logcat`.

### Real-time Factor (RTF)

RTF is a standard metric for measuring the speed of speech processing (e.g., wake word, speech-to-text, and
text-to-speech). RTF is the CPU time divided by the processed (recognized or synthesized) audio length.
Hence, a lower RTF means a more efficient engine.

### Token per Second (TPS)

Token per second is the standard metric for measuring the speed of LLM inference engines. TPS is the number of
generated tokens divided by the compute time used to create them. A higher TPS is better.


