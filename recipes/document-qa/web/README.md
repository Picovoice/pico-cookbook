# Document Q&A on the Web

Ask questions about a local document using speech and receive spoken answers, powered by on-device AI.

This demo indexes a local text document, retrieves the most relevant chunks using picoLLM embeddings, answers questions
with a picoLLM chat model, and speaks the answer with Orca Streaming Text-to-Speech.

## Compatibility

- Node.js 18+
- Chrome / Edge
- Firefox
- Safari

## AccessKey

AccessKey is your authentication and authorization token for deploying Picovoice SDKs. Anyone who is using Picovoice
needs to have a valid AccessKey. You must keep your AccessKey secret. You would need internet connectivity to validate
your AccessKey with Picovoice license servers, even though the inference is running 100% offline. Everyone who signs up
for [Picovoice Console](https://console.picovoice.ai/) receives a unique AccessKey.

## Usage

These instructions assume your current working directory is `recipes/document-qa/web`.

### 1. Download the LLMs

Download `qwen2.5-500m-it-590.pllm` and `embeddinggemma-300m-375.pllm` from
[Picovoice Console](https://console.picovoice.ai/).

### 2. Download the other Required Models

Run the setup script to download and copy the models for [Cheetah Streaming Speech-to-Text](https://picovoice.ai/docs/cheetah/) and [Orca Streaming Text-to-Speech](https://picovoice.ai/docs/orca/).

It will also copy the `qwen2.5-500m-it-590.pllm` and `embeddinggemma-300m-375.pllm` models to the `public/models` folder.

```bash
python setup.py \
    --picollm_model_path ${PATH_TO_PLLM} \
    --picollm_embedding_model_path ${PATH_TO_EMBEDDING_PLLM}
```

### 3. Build and Run the Demo

```bash
# Install Dependencies
yarn

# Build the Demo
yarn build

# Run the Demo
yarn start
```

### 4. Open the Demo Page

<!-- markdown-link-check-disable -->
- Go to [localhost:5000](http://localhost:5000) in your web browser.
<!-- markdown-link-check-enable -->
- Input your picovoice `AccessKey` and a `name`
- Press the Start Demo button
