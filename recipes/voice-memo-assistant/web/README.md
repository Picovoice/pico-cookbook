# Voice Memo Assistant on the Web

Record, rewrite, summarize, and replay voice memos hands-free, powered by on-device voice AI.

## Compatibility

- Node.js 18+
- Chrome / Edge
- Firefox
- Safari

## AccessKey

AccessKey is your authentication and authorization token for deploying Picovoice SDKs, including picoLLM. Anyone who is
using Picovoice needs to have a valid AccessKey. You must keep your AccessKey secret. You would need internet
connectivity to validate your AccessKey with Picovoice license servers even though the LLM inference is running 100%
offline and completely free for open-weight models. Everyone who signs up for
[Picovoice Console](https://console.picovoice.ai/) receives a unique AccessKey.

## Usage

These instructions assume your current working directory is `recipes/voice-memo-assistant/web`.

### 1. Download the LLM

Download `llama-3.2-1b-instruct-385.pllm` from [Picovoice Console](https://console.picovoice.ai/).

### 2. Train the Wake Word Model

1. Open [Picovoice Console](https://console.picovoice.ai/)
2. Go to Porcupine Wake Word.
3. Enter your desired wake phrase.
4. Click Train.
5. Select target platform `WASM` and download the generated wake word model file (`.ppn`).

### 3. Train the Speech-to-Intent Model

1. Open [Picovoice Console](https://console.picovoice.ai/)
2. Go to Rhino Speech-to-Intent.
3. Create an empty Rhino context.
4. Click Import YAML in the top-right corner.
5. Paste the [Rhino context YAML](../res/context.yml) for this demo.
6. Download the generated Rhino context file (`.rhn`) for target platform `WASM`.

### 4. Download the Required Models

Run the setup script to download and copy the models for [Cheetah Streaming Speech-to-Text](https://picovoice.ai/docs/cheetah/)
and [Orca Streaming Text-to-Speech](https://picovoice.ai/docs/orca/).

It will also copy the models provided for [Porcupine Wake Word](https://picovoice.ai/docs/porcupine/),
[Rhino Speech-to-Intent](https://picovoice.ai/docs/rhino/) and [picoLLM Inference](https://picovoice.ai/docs/picollm/)
to the public models folder.

```console
python setup.py \
    --keyword_path ${PATH_TO_PPN} \
    --context_path ${PATH_TO_RHN} \
    --picollm_model_path ${PATH_TO_PLLM}
```

### 5. Install Dependencies

```console
yarn
```

### 6. Build the Demo

```console
yarn build
```

### 7. Run the Demo

```console
yarn start
```

### 8. Open the Demo page

<!-- markdown-link-check-disable -->
- go to [localhost:5000](http://localhost:5000) in your web browser.
<!-- markdown-link-check-enable -->
