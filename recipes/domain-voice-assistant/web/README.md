# Domain Voice Assistant on the Web

Ask a voice assistant about one domain, here Picovoice's products, and hear the answer spoken back, powered by on-device
voice AI customized for that domain.

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

These instructions assume your current working directory is `recipes/domain-voice-assistant/web`.

### 1. Download the LLM

Download `llama-3.2-1b-picovoice-assistant-800.pllm` from
[Hugging Face](https://huggingface.co/Picovoice/Llama-3.2-Picovoice-Assistant-1B).

The customized [Cheetah Streaming Speech-to-Text](https://picovoice.ai/docs/cheetah/) and
[Orca Streaming Text-to-Speech](https://picovoice.ai/docs/orca/) models are included in [res/models](../res/models).

### 2. Train the Wake Word Model

1. Open [Picovoice Console](https://console.picovoice.ai/)
2. Go to Porcupine Wake Word.
3. Enter your desired wake phrase.
4. Click Train.
5. Select the **Web (WASM)** target platform and download the generated wake word model file (`.ppn`).

Save the downloaded file somewhere accessible on your machine. You will pass its path to the demo with
`--porcupine_keyword_path`.

### 3. Download the other Required Models

Run the setup script to download the model for [Porcupine Wake Word](https://picovoice.ai/docs/porcupine/).

It will also copy the Cheetah, Orca, and picoLLM models to the `public/models` folder, and your wake word file
(`.ppn`) to the `public/keywords` folder.

```bash
python setup.py \
    --picollm_model_path ${PATH_TO_PLLM} \
    --porcupine_keyword_path ${PATH_TO_PPN_FILE}
```

### 4. Build and Run the Demo

```bash
# Install Dependencies
yarn

# Build the Demo
yarn build

# Run the Demo
yarn start
```

### 5. Open the Demo Page

<!-- markdown-link-check-disable -->
- Go to [localhost:5000](http://localhost:5000) in your web browser.
<!-- markdown-link-check-enable -->
- Enter your AccessKey, then press the `Start Demo` button.

#### Instructions

- Say your wake word, then ask your question. You can also type it and press `Send`.
- After an answer, the assistant keeps listening for about 8 seconds, so you can ask a follow-up question without the
  wake word.
- To interrupt an answer, talk over it or press `Stop`.
