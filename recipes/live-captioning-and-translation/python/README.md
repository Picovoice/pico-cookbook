# Live Captioning and Translation in Python

Transcribe and optionally translate live audio in real time, powered by on-device AI.

## Compatibility

- Runs on Linux (x86_64), macOS (arm64, x86_64), Windows (arm64, x86_64), and Raspberry Pi (5, 4, and 3).
- Python>=3.9

## AccessKey

AccessKey is your authentication and authorization token for deploying Picovoice SDKs. Anyone who is using Picovoice
needs to have a valid AccessKey. You must keep your AccessKey secret. You would need internet connectivity to validate
your AccessKey with Picovoice license servers, even though the inference is running 100% offline. Everyone who signs up
for [Picovoice Console](https://console.picovoice.ai/) receives a unique AccessKey.

## Usage

These instructions assume your current working directory is `recipes/live-captioning-and-translation/python`.

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

Run the setup script to download the models for [Cheetah Streaming Speech-to-Text](https://picovoice.ai/docs/cheetah/)
and [Zebra Translation](https://picovoice.ai/docs/zebra/):

```console
python setup.py
```

### 5. Run the Demo

#### 5.1 From Microphone

```console
python main.py --access_key ${ACCESS_KEY} --language_pair ${LANGUAGE_PAIR}
```

For example:

```console
python main.py --access_key ${ACCESS_KEY} --language_pair en-fr
```

Use the same source and target language to transcribe only. For example, `en-en`.


#### 5.2 From WAV File

```console
python main.py --access_key ${ACCESS_KEY} --language_pair ${LANGUAGE_PAIR} --wav_path ${WAV_PATH}
```

`${WAV_PATH}` must point to a 16 kHz, 16-bit, single-channel, uncompressed WAV file.

### 6. View All Options

```console
python main.py --help
```
