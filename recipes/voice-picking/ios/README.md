# Voice Picking on iOS

Perform fully on-device, hands-free voice picking with spoken prompts, structured intent capture for picking workflows,
and support for complex slots and exception handling.

## Compatibility

- iOS 17.0+

## AccessKey

AccessKey is your authentication and authorization token for deploying Picovoice SDKs. Anyone who is using Picovoice
needs to have a valid AccessKey. You must keep your AccessKey secret. You would need internet connectivity to validate
your AccessKey with Picovoice license servers, even though the inference is running 100% offline. Everyone who signs up
for [Picovoice Console](https://console.picovoice.ai/) receives a unique AccessKey.

## Usage

These instructions assume your current working directory is `recipes/voice-picking/ios`.

### 1. Train a Wake Word Model

1. Open [Picovoice Console](https://console.picovoice.ai/)
2. Go to Porcupine Wake Word.
3. Enter your desired wake phrase.
4. Click Train.
5. Select target platform **iOS** and download the generated wake word model file (`.ppn`).

Save the downloaded file somewhere accessible on your machine. You will pass its path to the setup script with `--porcupine_keyword_path`.

### 2. Train the Speech-to-Intent Model

1. Open [Picovoice Console](https://console.picovoice.ai/)
2. Go to Rhino Speech-to-Intent.
3. Create an empty Rhino context.
4. Click Import YAML in the top-right corner.
5. Paste the [Rhino context YAML](../res/context.yml) for this demo.
6. Download the generated Rhino context file (`.rhn`) for target platform **iOS**.

Save the downloaded file somewhere accessible on your machine. You will pass its path to the setup script with `--rhino_context_path`.

### 3. Run the Setup Script

Run the setup script to download the models for
- [Koala Noise Suppression](https://picovoice.ai/docs/koala/)
- [Orca Streaming Text-to-Speech](https://picovoice.ai/docs/orca/)

It will also copy the models provided for [Porcupine Wake Word](https://picovoice.ai/docs/porcupine/) and [Rhino Speech-to-Intent](https://picovoice.ai/docs/rhino/) to the assets folder.

Lastly, it will place your `AccessKey` from Picovoice Console into the `ACCESS_KEY` variable
in [ViewModel.swift](VoicePicking/ViewModel.swift).

```console
python setup.py \
    --access_key ${ACCESS_KEY} \
    --porcupine_keyword_path ${PATH_TO_PPN} \
    --rhino_context_path ${PATH_TO_RHN}
```

### 4. Run the XCode project

1. Open the `VoicePicking` project in XCode
2. Build and run the demo.
3. Tap the `Start Demo` button in the demo.

#### Instructions

- When asked to check digits, respond with the digits of the location you are at (ex: one nine, four two)
- When asked to pick a number of items, respond with:
  - `picked ${N}`, where `N` is the number of items you picked
  - `short pick ${N}` if there are not enough items to complete the full pick
  - `damaged item`
  - `location empty`
  - `I am done` to exit the workflow early
- See the [Rhino context YAML](../res/context.yml) for more details
