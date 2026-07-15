# Voice Picking for Android

Perform fully on-device, hands-free voice picking with spoken prompts, structured intent capture for picking workflows,
and support for complex slots and exception handling.

## Compatibility

- Android 6.0 (SDK 23+)

## AccessKey

AccessKey is your authentication and authorization token for deploying Picovoice SDKs. Anyone who is using Picovoice
needs to have a valid AccessKey. You must keep your AccessKey secret. You would need internet connectivity to validate
your AccessKey with Picovoice license servers, even though the inference is running 100% offline. Everyone who signs up
for [Picovoice Console](https://console.picovoice.ai/) receives a unique AccessKey.

## Usage

These instructions assume your current working directory is `recipes/voice-picking/android`.

### 1. Train a Wake Word Model

1. Open [Picovoice Console](https://console.picovoice.ai/)
2. Go to Porcupine Wake Word.
3. Enter your desired wake phrase.
4. Click Train.
5. Select target platform **Android** and download the generated wake word model file (`.ppn`).

Save the downloaded file somewhere accessible on your machine. You will pass its path to the setup script with `--porcupine_keyword_path`.

### 2. Train the Speech-to-Intent Model

1. Open [Picovoice Console](https://console.picovoice.ai/)
2. Go to Rhino Speech-to-Intent.
3. Create an empty Rhino context.
4. Click Import YAML in the top-right corner.
5. Paste the [Rhino context YAML](../res/context.yml) for this demo.
6. Download the generated Rhino context file (`.rhn`) for target platform **Android**.

Save the downloaded file somewhere accessible on your machine. You will pass its path to the setup script with `--rhino_context_path`.

### 3. Run the Setup Script

Run the setup script to download the models for
- [Orca Streaming Text-to-Speech](https://picovoice.ai/docs/orca/)

It will also copy the models provided for [Porcupine Wake Word](https://picovoice.ai/docs/porcupine/) and [Rhino Speech-to-Intent](https://picovoice.ai/docs/rhino/) to the assets folder.

Lastly, it will place your `AccessKey` from Picovoice Console into the `ACCESS_KEY` variable in [MainActivity.java](voice-picking/src/main/java/ai/picovoice/voicepicking/MainActivity.java).


This script will also add your wake word (`.ppn`) and rhino context (`.rhn`) files to the project:

```console
python setup.py \
    --access_key ${ACCESS_KEY} \
    --porcupine_keyword_path ${PATH_TO_PPN_FILE} \
    --rhino_context_path ${PATH_TO_RHN_FILE}
```

### 4. Run the Android Demo

1. Open the `voice-picking` project in Android Studio.
2. Connect a device or launch an Android simulator.
3. Build and run the demo.

#### Instructions

- When asked to check digits, respond with the digits of the location you are at (ex: one nine, four two)
- When asked to pick a number of items, respond with:
  - `picked ${N}`, where `N` is the number of items you picked
  - `short pick ${N}` if there are not enough items to complete the full pick
  - `damaged item`
  - `location empty`
  - `I am done` to exit the workflow early
- See `../res/context.yml` for more details
