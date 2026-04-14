# Live Conversation Translation on Android

Two-way real-time speech translation powered by on-device AI. Have live conversations across languages, with each person
speaking naturally in their own language and hearing the other translated in real time.

## Compatibility

- Android 11.0 (SDK 30+)

## AccessKey

AccessKey is your authentication and authorization token for deploying Picovoice SDKs. Anyone who is using Picovoice
needs to have a valid AccessKey. You must keep your AccessKey secret. You would need internet connectivity to validate
your AccessKey with Picovoice license servers, even though the inference is running 100% offline. Everyone who signs up
for [Picovoice Console](https://console.picovoice.ai/) receives a unique AccessKey.

## Usage

These instructions assume your current working directory is `recipes/live-conversation-translation/android`.

### 1. Download the Required Models

Run the setup script to download the models for [Cheetah Streaming Speech-to-Text](https://picovoice.ai/docs/cheetah/),
[Zebra Translation](https://picovoice.ai/docs/zebra/), and
[Orca Streaming Text-to-Speech](https://picovoice.ai/docs/orca/):

```console
python setup.py
```

### 2. Build and Run the Demo

1. Open the `live-conversation-translation` project in Android Studio.

2. Copy your `AccessKey` from Picovoice Console into the `ACCESS_KEY` variable in [MainActivity.java](live-conversation-translation/src/main/java/ai/picovoice/liveconversationtranslation/MainActivity.java).

3. Connect a device or launch an Android simulator.
