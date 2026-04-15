# Live Captioning and Translation in Python

## Compatibility

- Runs on Linux (x86_64), macOS (arm64, x86_64), Windows (arm64, x86_64), and Raspberry Pi (5, 4, and 3).
- Python>=3.9

## AccessKey

AccessKey is your authentication and authorization token for deploying Picovoice SDKs. Anyone who is using Picovoice
needs to have a valid AccessKey. You must keep your AccessKey secret. You would need internet connectivity to validate
your AccessKey with Picovoice license servers, even though the inference is running 100% offline. Everyone who signs up
for [Picovoice Console](https://console.picovoice.ai/) receives a unique AccessKey.

## Usage

These instructions assume your current working directory is `recipes/live-conversation-translation/python`.

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
and [Zebra Translation](https://picovoice.ai/docs/zebra/):

```console
python setup.py
```

### 5. Run the Demo

```console
python main.py --access_key ${ACCESS_KEY}
```

### 6. View All Options

```console
python main.py --help
```

```console
yt-dlp -x --audio-format wav --postprocessor-args "-ac 1 -ar 16000 -sample_fmt s16" -o ${WAV_PATH} "${YOUTUBE_URL}"
```
