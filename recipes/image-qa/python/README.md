# Image Question Answering in Python

Ask questions about an image using your voice and hear the answer spoken back in real time.

This demo combines on-device speech-to-text, vision-language inference, and streaming text-to-speech to create a
hands-free image question answering experience. Cheetah transcribes the user's spoken question, picoLLM analyzes the
image and generates an answer, and Orca reads the response aloud as it is being generated.

Use it to ask natural questions such as what objects are visible in an image, what is happening in the scene, where
something is located, or how the image should be described.

## Compatibility

- Runs on Linux (x86_64), macOS (arm64, x86_64), Windows (arm64, x86_64), and Raspberry Pi (5, 4, and 3).
- Python>=3.9

## AccessKey

AccessKey is your authentication and authorization token for deploying Picovoice SDKs. Anyone who is using Picovoice
needs to have a valid AccessKey. You must keep your AccessKey secret. You would need internet connectivity to validate
your AccessKey with Picovoice license servers, even though the inference is running 100% offline. Everyone who signs up
for [Picovoice Console](https://console.picovoice.ai/) receives a unique AccessKey.

## Usage

These instructions assume your current working directory is `recipes/image-qa/python`.

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

Download `qwen3-vl-2b-it-537.pllm` from [Picovoice Console](https://console.picovoice.ai/).

### 5. Run the Demo

```console
python main.py \
  --access_key ${ACCESS_KEY} \
  --picollm_model_path ${PICOLLM_MODEL_PATH} \
  --image_path ${IMAGE_PATH}
```

### 6. View All Options

```console
python main.py --help
```

