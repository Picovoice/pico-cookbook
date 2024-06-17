## See It In Action!

[![LLM VA in Action](https://img.youtube.com/vi/VNTzzePFhPk/0.jpg)](https://www.youtube.com/watch?v=VNTzzePFhPk)

## Compatibility

- iOS 16.0+

## AccessKey

AccessKey is your authentication and authorization token for deploying Picovoice SDKs, including picoLLM. Anyone who is
using Picovoice needs to have a valid AccessKey. You must keep your AccessKey secret. You would need internet
connectivity to validate your AccessKey with Picovoice license servers even though the LLM inference is running 100%
offline and completely free for open-weight models. Everyone who signs up for
[Picovoice Console](https://console.picovoice.ai/) receives a unique AccessKey.

## picoLLM Model

picoLLM Inference Engine supports many open-weight models. The models are on
[Picovoice Console](https://console.picovoice.ai/).

Download your desired model file from the [Picovoice Console](https://console.picovoice.ai/).
If you do not download the file directly from your iOS device,
you will need to upload it to the device to use it with the demos.
To upload the model, use AirDrop or connect your iOS device to your computer via USB or launch a simulator.
Copy your model file to the device.

## Usage

1. Install the dependencies using `CocoaPods`:

```console
pod install
```

2. Open the `LLMVoiceAssistantDemo.xcworkspace` in XCode

3. Replace `let ACCESS_KEY = "${YOUR_ACCESS_KEY_HERE}"` in the file [VieModel.swift](./LLMVoiceAssistantDemo/ViewModel.swift) with your AccessKey obtained from [Picovoice Console](https://console.picovoice.ai/).

4. Build and run the project on your device.

5. Press the `Load Model` button and load the model file from your device's storage.

6. Say "Picovoice", then speak to the voice assistant!

## Custom Wake Word

The demo's default wake phrase is `Picovoice`.
You can generate your custom (branded) wake word using Picovoice Console by following [Porcupine Wake Word documentation (https://picovoice.ai/docs/porcupine/).
Once you have the model trained, add it to the [resources](./LLMVoiceAssistantDemo/resources) directory
and include it as an argument to the `Porcupine` initialization in [VieModel.swift](./LLMVoiceAssistantDemo/ViewModel.swift)
