# Call Assist on iOS

Screen unknown calls with real-time transcription, natural voice responses, and on-device LLM reasoning. The assistant
answers an incoming call on behalf of the user, asks the caller to identify themselves and explain why they are calling,
transcribes the caller's response, and extracts the caller's identity and reason for calling.

If the caller does not provide enough information, the assistant follows up automatically and asks for the missing
details. Once it has enough context, it summarizes the call for the user and lets them choose what to do next, such as
connecting the call, declining it, asking for more details, or blocking the caller.

The entire interaction is powered by on-device AI, combining real-time speech-to-text, text-to-speech, speech-to-intent,
and local language model inference to create a private, low-latency call-screening experience without sending audio or
call content to the cloud.

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

### 2. Download the LLM

Download `llama-3.2-1b-instruct-385.pllm` from [Picovoice Console](https://console.picovoice.ai/).

### 3. Download the Required Models

Run the setup script to download and copy the models for [Cheetah Streaming Speech-to-Text](https://picovoice.ai/docs/cheetah/)
and [Orca Streaming Text-to-Speech](https://picovoice.ai/docs/orca/).

It will also copy the models provided for [Rhino Speech-to-Intent](https://picovoice.ai/docs/rhino/)
and [picoLLM Inference](https://picovoice.ai/docs/picollm/) to the `assets` folder.

Lastly, it will place your `AccessKey` from Picovoice Console and name into the `ACCESS_KEY` and `USERNAME` variables
in [ViewModel.swift](CallAssist/ViewModel.swift).

```console
cd pico-cookbook/recipes/call-assist/ios

python setup.py \
    --access_key ${ACCESS_KEY} \
    --name ${NAME} \
    --context_path ${PATH_TO_RHN} \
    --picollm_model_path ${PATH_TO_PLLM}
```

### 4. Run the XCode project

1. Open the `CallAssist` project in XCode
2. Build and run the demo.
3. Tap the `Start Demo` button in the demo.
