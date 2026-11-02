# Domain Voice Assistant in Python

Ask a voice assistant about one domain, here Picovoice's products, and hear the answer spoken back, powered by on-device
voice AI customized for that domain.

## Compatibility

- Runs on Linux (x86_64), macOS (arm64, x86_64), Windows (arm64, x86_64), and Raspberry Pi (5 and 4).
- Python>=3.9

## AccessKey

AccessKey is your authentication and authorization token for deploying Picovoice SDKs. Anyone who is using Picovoice
needs to have a valid AccessKey. You must keep your AccessKey secret. You would need internet connectivity to validate
your AccessKey with Picovoice license servers, even though the inference is running 100% offline. Everyone who signs up
for [Picovoice Console](https://console.picovoice.ai/) receives a unique AccessKey.

## Usage

These instructions assume your current working directory is `recipes/domain-voice-assistant/python`.

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

### 4. Download the LLM

Download `llama-3.2-1b-picovoice-assistant-800.pllm` from
[Hugging Face](https://huggingface.co/Picovoice/Llama-3.2-Picovoice-Assistant-1B).

The customized [Cheetah Streaming Speech-to-Text](https://picovoice.ai/docs/cheetah/) and
[Orca Streaming Text-to-Speech](https://picovoice.ai/docs/orca/) models are included in [res/models](../res/models).

### 5. Train the Wake Word Model

1. Open [Picovoice Console](https://console.picovoice.ai/)
2. Go to Porcupine Wake Word.
3. Enter your desired wake phrase.
4. Click Train.
5. Select your target platform and download the generated wake word model file (`.ppn`).

Save the downloaded file somewhere accessible on your machine. You will pass its path to the demo with `--keyword_path`.

### 6. Run the Demo

```console
python main.py \
  --access_key ${ACCESS_KEY} \
  --keyword_path ${KEYWORD_PATH} \
  --picollm_model_path ${PICOLLM_MODEL_PATH}
```

Where:

* `${ACCESS_KEY}` is your Picovoice AccessKey from Picovoice Console.
* `${KEYWORD_PATH}` is the path to the Porcupine wake word model file (`.ppn`).
* `${PICOLLM_MODEL_PATH}` is the path to the picoLLM model file (`.pllm`).

#### Instructions

- Say your wake word, then ask your question.
- After an answer, the assistant keeps listening for about 8 seconds, so you can ask a follow-up question without the
  wake word.
- To interrupt an answer, say the wake word.
- Press `Ctrl+C` to exit.

### 7. View All Options

```console
python main.py --help
```
