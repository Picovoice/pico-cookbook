# Speech-to-Speech Translation in Python

Real-time speech-to-speech translation powered by on-device AI. Speak in one language and hear it translated in real
time into another.

## Compatibility

- Runs on Linux (x86_64), macOS (arm64, x86_64), Windows (arm64, x86_64), and Raspberry Pi (5, 4, and 3).
- Python>=3.9

## AccessKey

AccessKey is your authentication and authorization token for deploying Picovoice SDKs. Anyone who is using Picovoice
needs to have a valid AccessKey. You must keep your AccessKey secret. You would need internet connectivity to validate
your AccessKey with Picovoice license servers, even though the inference is running 100% offline. Everyone who signs up
for [Picovoice Console](https://console.picovoice.ai/) receives a unique AccessKey.

## Usage

These instructions assume your current working directory is `recipes/speech-to-speech-translation/python`.

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

### 4. Download the Required Models

Run the setup script to download the models for [Cheetah Streaming Speech-to-Text](https://picovoice.ai/docs/cheetah/),
[Zebra Translation](https://picovoice.ai/docs/zebra/), and
[Orca Streaming Text-to-Speech](https://picovoice.ai/docs/orca/):

```console
python setup.py
```

### 5. Run the Demo

To automatically detect the spoken source language and translate it into the target language:

```console
python main.py --access_key ${ACCESS_KEY} --target_language ${TARGET_LANGUAGE}
```

To explicitly set the source and target language pair:

```console
python main.py --access_key ${ACCESS_KEY} --language_pair ${LANGUAGE_PAIR}
```

### 6. View All Options

```console
python main.py --help
```
