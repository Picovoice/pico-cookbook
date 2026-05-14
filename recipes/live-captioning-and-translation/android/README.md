# Live Captioning and Translation on Android

Transcribe and optionally translate live audio in real time, powered by on-device AI.

## Compatibility

- Android 11.0 (SDK 30+)

## AccessKey

AccessKey is your authentication and authorization token for deploying Picovoice SDKs. Anyone who is using Picovoice
needs to have a valid AccessKey. You must keep your AccessKey secret. You would need internet connectivity to validate
your AccessKey with Picovoice license servers, even though the inference is running 100% offline. Everyone who signs up
for [Picovoice Console](https://console.picovoice.ai/) receives a unique AccessKey.

## Usage

These instructions assume your current working directory is `recipes/live-captioning-and-translation/android`.

### 1. Download the Required Models

Run the setup script to download the models for [Cheetah Streaming Speech-to-Text](https://picovoice.ai/docs/cheetah/),
[Zebra Translation](https://picovoice.ai/docs/zebra/):

```console
python setup.py
```

### 2. Build and Run the Demo

1. Open the `live-captioning-and-translation` project in Android Studio.

2. Copy your `AccessKey` from Picovoice Console into the `ACCESS_KEY` variable in [MainActivity.java](live-captioning-and-translation/src/main/java/ai/picovoice/livecaptioningandtranslation/MainActivity.java).

3. Connect a device or launch an Android simulator.
