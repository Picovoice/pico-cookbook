# Speaker Aware Wake Word on iOS

Detect wake words and recognize who said them, powered by on-device voice AI.

## Compatibility

- iOS 17.0+

## AccessKey

AccessKey is your authentication and authorization token for deploying Picovoice SDKs. Anyone who is using Picovoice
needs to have a valid AccessKey. You must keep your AccessKey secret. You would need internet connectivity to validate
your AccessKey with Picovoice license servers, even though the inference is running 100% offline. Everyone who signs up
for [Picovoice Console](https://console.picovoice.ai/) receives a unique AccessKey.

## Usage

### 1. Train a Wake Word Model

Follow instruction on Porcupine Wake Word [documentation](https://picovoice.ai/docs/porcupine/#custom-wake-words) to
train a wake word model on [Picovoice Console](https://console.picovoice.ai/) in minutes. Ensure you train the wake word for the **iOS** platform.

### 2. Add Wake Word to the Project

Run the setup script to copy the wake word model provided for [Porcupine Wake Word](https://picovoice.ai/docs/porcupine/) to the project folder.

It will also place your `AccessKey` from Picovoice Console and name into the `ACCESS_KEY` variable
in [ViewModel.swift](SpeakerAwareWakeWord/ViewModel.swift).

```console
cd pico-cookbook/recipes/speaker-aware-wake-word/ios

python setup.py \
    --access_key ${ACCESS_KEY} \
    --keyword_path ${PATH_TO_PPN}
```

### 3. Run the XCode project

1. Open the `SpeakerAwareWakeWord` project in XCode
2. Build and run the demo.
3. Tap the `Start Demo` button in the demo.
