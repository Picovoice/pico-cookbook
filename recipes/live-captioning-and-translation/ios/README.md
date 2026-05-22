# Live Captioning and Translation on iOS

Transcribe and optionally translate live audio in real time, powered by on-device AI.

## Compatibility

- iOS 17.0+

## AccessKey

AccessKey is your authentication and authorization token for deploying Picovoice SDKs. Anyone who is using Picovoice
needs to have a valid AccessKey. You must keep your AccessKey secret. You would need internet connectivity to validate
your AccessKey with Picovoice license servers, even though the inference is running 100% offline. Everyone who signs up
for [Picovoice Console](https://console.picovoice.ai/) receives a unique AccessKey.

## Usage

These instructions assume your current working directory is `recipes/live-captioning-and-translation/ios`.

### 1. Download the Required Models

Run the setup script to download the models for [Cheetah Streaming Speech-to-Text](https://picovoice.ai/docs/cheetah/),
[Zebra Translation](https://picovoice.ai/docs/zebra/):

```console
python setup.py
```

### 2. Run the Demo

1. Open the `LiveCaptioningAndTranslation.xcodeproj` in XCode

2. Replace `let ACCESS_KEY = "${YOUR_ACCESS_KEY_HERE}"` in the file [VieModel.swift](./LiveCaptioningAndTranslation/ViewModel.swift) with your AccessKey obtained from [Picovoice Console](https://console.picovoice.ai/).

3. Build and run the project on your device.
