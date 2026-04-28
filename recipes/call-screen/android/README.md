## Compatibility

- Android 5.0 (SDK 21+)

## AccessKey

AccessKey is your authentication and authorization token for deploying Picovoice SDKs. Anyone who is
using Picovoice needs to have a valid AccessKey. You must keep your AccessKey secret. You would need internet
connectivity to validate your AccessKey with Picovoice license servers. Everyone who signs up for
[Picovoice Console](https://console.picovoice.ai/) receives a unique AccessKey.

## Usage

// 1. Open the `CallScreen` project in Android Studio.
// 2. Copy your `AccessKey` from Picovoice Console into the `ACCESS_KEY` variable in [MainActivity.java](llm-voice-assistant/src/main/java/ai/picovoice/callscreen/MainActivity.java).
// 3. Connect a device or launch an Android simulator.
// 4. Build and run the demo.
// 5. Say "Picovoice", then you'll be able to prompt the voice assistant.

/*
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
*/

## Profiling

Profiling data is automatically printed in the app's `logcat`.

### Real-time Factor (RTF)

RTF is a standard metric for measuring the speed of speech processing (e.g., wake word, speech-to-text, and
text-to-speech). RTF is the CPU time divided by the processed (recognized or synthesized) audio length.
Hence, a lower RTF means a more efficient engine.
