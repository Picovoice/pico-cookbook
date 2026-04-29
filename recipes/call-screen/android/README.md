## Compatibility

- Android 5.0 (SDK 21+)

// TODO: update this readme with correct information

## AccessKey

AccessKey is your authentication and authorization token for deploying Picovoice SDKs. Anyone who is
using Picovoice needs to have a valid AccessKey. You must keep your AccessKey secret. You would need internet
connectivity to validate your AccessKey with Picovoice license servers. Everyone who signs up for
[Picovoice Console](https://console.picovoice.ai/) receives a unique AccessKey.

## Usage

### 1. Open the `CallScreen` project in Android Studio.

1. Copy your `AccessKey` from Picovoice Console into the `ACCESS_KEY` variable in [MainActivity.java](llm-voice-assistant/src/main/java/ai/picovoice/callscreen/MainActivity.java).
2. Set your name into the `USERNAME` variable in ...

### 2. Train the Speech-to-Intent Model

1. Open [Picovoice Console](https://console.picovoice.ai/)
2. Go to Rhino Speech-to-Intent.
3. Create an empty Rhino context.
4. Click Import YAML in the top-right corner.
5. Paste the [Rhino context YAML](../res/context.yml) for this demo.
6. Select the **Android** platform and download the generated Rhino context file (`.rhn`).

### 3. Run the Android Studio project

1. Connect a device or launch an Android simulator.
2. Build and run the demo.
3. Press the `Start Demo` button.

/*
## Custom Wake Word

The demo's default wake phrase is `Picovoice`. You can generate your custom (branded) wake word using
Picovoice Console by following [Porcupine Wake Word documentation](https://picovoice.ai/docs/porcupine/).
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
