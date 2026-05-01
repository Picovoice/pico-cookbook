# Document Q&A in Android

Ask questions about a local document using speech and receive spoken answers, powered by on-device AI.

This demo indexes a local text document, retrieves the most relevant chunks using picoLLM embeddings, answers questions
with a picoLLM chat model, and speaks the answer with Orca Streaming Text-to-Speech.

## Components

- [Cheetah Streaming Speech-to-Text](https://picovoice.ai/docs/cheetah/)
- [picoLLM Inference](https://picovoice.ai/docs/picollm/)
- [Orca Streaming Text-to-Speech](https://picovoice.ai/docs/orca/)

## Compatibility

- Android 5.0 (SDK 21+)

## AccessKey

AccessKey is your authentication and authorization token for deploying Picovoice SDKs. Anyone who is using Picovoice
needs to have a valid AccessKey. You must keep your AccessKey secret. You would need internet connectivity to validate
your AccessKey with Picovoice license servers, even though the inference is running 100% offline. Everyone who signs up
for [Picovoice Console](https://console.picovoice.ai/) receives a unique AccessKey.

## picoLLM Model

picoLLM Inference Engine supports a variety of open-weight models. The models can be downloaded from the [Picovoice Console](https://console.picovoice.ai/).

Download your desired model file (`.pllm`) from the Picovoice Console. If you do not download the
file directly from your Android device, you will need to upload it to the device.
To upload the model to the device, use the Android Studio Device Explorer or `adb push`:
```console
adb push ~/model.pllm /sdcard/Downloads/
```

## Usage

These instructions assume your current working directory is `recipes/document-qa/android`.

### 1. Download the LLMs

Download `llama-3.2-1b-instruct-385.pllm` and `embeddinggemma-300m-375.pllm` from
[Picovoice Console](https://console.picovoice.ai/).

### 2. Add LLMs to the Project

Copy your downloaded picoLLM model files into the assets folder, located at `/src/main/assets`.

### 3. Add AccessKey to MainActivity.java

Open the project in Android Studio and open `MainActivity.java`. Change the value of the `ACCESS_KEY` variable to your Picovoice AccessKey.

### 4. Run the Demo with Android Studio

With an Android device connected or an Android simulator running, build and run the demo using Android Studio.

