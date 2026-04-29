# Image to Speech in Python

Read text from an image and hear it spoken aloud in real time.

This demo uses picoLLM OCR to extract text from an image, then streams the recognized text to Orca for text-to-speech
playback as the OCR result is generated. It also prints the extracted text in the terminal, making it useful for quickly
turning screenshots, documents, signs, labels, forms, or other text-heavy images into readable and spoken output.

Use it to read text from images such as receipts, product labels, notes, screenshots, forms, posters, or scanned
documents.

## Compatibility

- Runs on Linux (x86_64), macOS (arm64, x86_64), Windows (arm64, x86_64), and Raspberry Pi (5, 4, and 3).
- Python>=3.9

## AccessKey

AccessKey is your authentication and authorization token for deploying Picovoice SDKs. Anyone who is using Picovoice
needs to have a valid AccessKey. You must keep your AccessKey secret. You would need internet connectivity to validate
your AccessKey with Picovoice license servers, even though the inference is running 100% offline. Everyone who signs up
for [Picovoice Console](https://console.picovoice.ai/) receives a unique AccessKey.

## Usage

These instructions assume your current working directory is `recipes/image-to-speech/python`.

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

Download `deepseek-ocr-2-561.pllm` from [Picovoice Console](https://console.picovoice.ai/).

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
