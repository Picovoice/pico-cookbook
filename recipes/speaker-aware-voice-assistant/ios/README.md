# Speaker-Aware Voice Assistant on iOS

Build voice assistants that can personalize behavior and enforce access control based on who is speaking.

This demo shows how to combine wake word detection, speech-to-intent, speaker recognition, and text-to-speech to create
a voice assistant that reacts differently for different users. It can allow everyone to use basic commands, personalize
responses for recognized speakers, and restrict sensitive actions to authorized admins. This enables use cases like
personal dashboards, user-specific settings, family profiles, workplace permissions, protected device controls,
and admin-only operations.

## Compatibility

- iOS 17.0+

## AccessKey

AccessKey is your authentication and authorization token for deploying Picovoice SDKs. Anyone who is using Picovoice
needs to have a valid AccessKey. You must keep your AccessKey secret. You would need internet connectivity to validate
your AccessKey with Picovoice license servers, even though the inference is running 100% offline. Everyone who signs up
for [Picovoice Console](https://console.picovoice.ai/) receives a unique AccessKey.

## Usage

These instructions assume your current working directory is `recipes/speaker-aware-voice-assistant/ios`.

### 1. Train a Wake Word Model

1. Open [Picovoice Console](https://console.picovoice.ai/)
2. Go to Porcupine Wake Word.
3. Enter your desired wake phrase.
4. Click Train.
5. Select your target platform and download the generated wake word model file (`.ppn`).

Save the downloaded file somewhere accessible on your machine. You will pass its path to the setup script with `--keyword_path`.

### 2. Train the Speech-to-Intent Model

1. Open [Picovoice Console](https://console.picovoice.ai/)
2. Go to Rhino Speech-to-Intent.
3. Create an empty Rhino context.
4. Click Import YAML in the top-right corner.
5. Paste the [Rhino context YAML](../res/context.yml) for this demo.
6. Download the generated Rhino context file (`.rhn`) for your target platform.

Save the downloaded file somewhere accessible on your machine. You will pass its path to the setup script with `--context_path`.

### 3. Run the Setup Script

Run the setup script to download and copy the models for [Orca Streaming Text-to-Speech](https://picovoice.ai/docs/orca/).

It will also copy the models provided for [Porcupine Wake Word](https://picovoice.ai/docs/porcupine/) and
[Rhino Speech-to-Intent](https://picovoice.ai/docs/rhino/) to the assets folder.

Lastly, it will place your `AccessKey` from Picovoice Console into the `ACCESS_KEY` variable
in [ViewModel.swift](SpeakerAwareVoiceAssistant/ViewModel.swift).

```console
python setup.py \
    --access_key ${ACCESS_KEY} \
    --keyword_path ${PATH_TO_PPN} \
    --context_path ${PATH_TO_RHN}
```

### 4. Run the XCode project

1. Open the `SpeakerAwareVoiceAssistant` project in XCode
2. Build and run the demo.
3. Tap the `Start Demo` button in the demo.
