# Call Screen on iOS

Screen unknown calls with real-time transcription and natural voice responses, powered by on-device AI.

## Compatibility

- iOS 17.0+

## AccessKey

AccessKey is your authentication and authorization token for deploying Picovoice SDKs. Anyone who is using Picovoice
needs to have a valid AccessKey. You must keep your AccessKey secret. You would need internet connectivity to validate
your AccessKey with Picovoice license servers, even though the inference is running 100% offline. Everyone who signs up
for [Picovoice Console](https://console.picovoice.ai/) receives a unique AccessKey.

## Usage

### 1. Train the Speech-to-Intent Model

1. Open [Picovoice Console](https://console.picovoice.ai/)
2. Go to Rhino Speech-to-Intent.
3. Create an empty Rhino context.
4. Click Import YAML in the top-right corner.
5. Paste the [Rhino context YAML](../res/context.yml) for this demo.
6. Select the **iOS** platform and download the generated Rhino context file (`.rhn`).

### 2. Download the Required Models

Run the setup script to download and copy the models for [Cheetah Streaming Speech-to-Text](https://picovoice.ai/docs/cheetah/)
and [Orca Streaming Text-to-Speech](https://picovoice.ai/docs/orca/).

Lastly, it will place your `AccessKey` from Picovoice Console and name into the `ACCESS_KEY` and `USERNAME` variables
in [ViewModel.swift](CallScreen/ViewModel.swift).

```console
cd pico-cookbook/recipes/call-screen/ios

python setup.py \
    --access_key ${ACCESS_KEY} \
    --name ${NAME} \
    --context_path ${PATH_TO_RHN}
```

### 3. Run the XCode project

1. Open the `CallScreen` project in XCode
2. Build and run the demo.
3. Tap the `Start Demo` button in the demo.
