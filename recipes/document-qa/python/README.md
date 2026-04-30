# Document Q&A in Python

Ask questions about a local document using speech and receive spoken answers, powered by on-device AI.

This demo indexes a local text document, retrieves the most relevant chunks using picoLLM embeddings, answers questions
with a picoLLM chat model, and speaks the answer with Orca Streaming Text-to-Speech.

## Components

- [Cheetah Streaming Speech-to-Text](https://picovoice.ai/docs/cheetah/)
- [picoLLM Inference](https://picovoice.ai/docs/picollm/)
- [Orca Streaming Text-to-Speech](https://picovoice.ai/docs/orca/)

## Compatibility

- Runs on Linux (x86_64), macOS (arm64, x86_64), Windows (arm64, x86_64), and Raspberry Pi (5, 4, and 3).
- Python>=3.9

## AccessKey

AccessKey is your authentication and authorization token for deploying Picovoice SDKs. Anyone who is using Picovoice
needs to have a valid AccessKey. You must keep your AccessKey secret. You would need internet connectivity to validate
your AccessKey with Picovoice license servers, even though the inference is running 100% offline. Everyone who signs up
for [Picovoice Console](https://console.picovoice.ai/) receives a unique AccessKey.

## Usage

These instructions assume your current working directory is `recipes/document-qa/python`.

### 1. Create a Virtual Environment

```console
python -m venv .venv
```

### 2. Activate the Virtual Environment

On Linux, macOS, or Raspberry Pi:

```console
source .venv/bin/activate
```

On Windows:

```console
.venv\Scripts\activate
```

### 3. Install Dependencies

```console
pip install -r requirements.txt
```

### 4. Download the LLMs


Download `llama-3.2-1b-instruct-385.pllm` and `embeddinggemma-300m-375.pllm` from
[Picovoice Console](https://console.picovoice.ai/).

### 5. Run the Demo

```console
python main.py \
  --access_key ${ACCESS_KEY} \
  --picollm_embedding_model_path ${PICOLLM_EMBEDDING_MODEL_PATH} \
  --picollm_chat_model_path ${PICOLLM_CHAT_MODEL_PATH}
```

By default, the demo indexes the document at `../res/CPAL-1.0.txt`. You can provide a different text document with:

```console
python main.py \
  --access_key ${ACCESS_KEY} \
  --picollm_embedding_model_path ${PICOLLM_EMBEDDING_MODEL_PATH} \
  --picollm_chat_model_path ${PICOLLM_CHAT_MODEL_PATH} \
  --document_path ${DOCUMENT_PATH}
```

The first run may take a while on CPU because the demo generates embeddings for every document chunk. To save the
generated embeddings:

```console
python main.py \
  --access_key ${ACCESS_KEY} \
  --picollm_embedding_model_path ${PICOLLM_EMBEDDING_MODEL_PATH} \
  --picollm_chat_model_path ${PICOLLM_CHAT_MODEL_PATH} \
  --save_embeddings_path ${EMBEDDINGS_PATH}
```

For later runs, load the saved embeddings instead of regenerating them:

```console
python main.py \
  --access_key ${ACCESS_KEY} \
  --picollm_embedding_model_path ${PICOLLM_EMBEDDING_MODEL_PATH} \
  --picollm_chat_model_path ${PICOLLM_CHAT_MODEL_PATH} \
  --load_embeddings_path ${EMBEDDINGS_PATH}
```

### 6. View All Options

```console
python main.py --help
```
