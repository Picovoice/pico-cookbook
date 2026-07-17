# Document Q&A on iOS

Ask questions about a local document using speech and receive spoken answers, powered by on-device AI.

This demo indexes a local text document, retrieves the most relevant chunks using picoLLM embeddings, answers questions
with a picoLLM chat model, and speaks the answer with Orca Streaming Text-to-Speech.

## Components

- [Cheetah Streaming Speech-to-Text](https://picovoice.ai/docs/cheetah/)
- [picoLLM Inference](https://picovoice.ai/docs/picollm/)
- [Orca Streaming Text-to-Speech](https://picovoice.ai/docs/orca/)

## Compatibility

- iOS 17.0+

## AccessKey

AccessKey is your authentication and authorization token for deploying Picovoice SDKs. Anyone who is using Picovoice
needs to have a valid AccessKey. You must keep your AccessKey secret. You would need internet connectivity to validate
your AccessKey with Picovoice license servers, even though the inference is running 100% offline. Everyone who signs up
for [Picovoice Console](https://console.picovoice.ai/) receives a unique AccessKey.

## Usage

### 1. Download the LLMs

Download `qwen2.5-500m-it-590.pllm` and `embeddinggemma-300m-375.pllm` from
[Picovoice Console](https://console.picovoice.ai/).

### 2. Download the Required Models

Run the setup script to download and copy the models for [Cheetah Streaming Speech-to-Text](https://picovoice.ai/docs/cheetah/)
and [Orca Streaming Text-to-Speech](https://picovoice.ai/docs/orca/).

Lastly, it will place your `AccessKey` from Picovoice Console into the `ACCESS_KEY` variable
in [ViewModel.swift](DocumentQA/ViewModel.swift).

```console
cd pico-cookbook/recipes/document-qa/ios

python setup.py \
    --access_key ${ACCESS_KEY} \
    --picollm_model_path ${PATH_TO_PLLM} \
    --picollm_embedding_model_path ${PATH_TO_EMBEDDING_PLLM}
```

### 3. Run the XCode project

1. Open the `DocumentQA` project in XCode
2. Build and run the demo.
3. Tap the `Load Document` button in the demo.
