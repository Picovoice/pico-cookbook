# Live Conversation Translation in Python

Two-way real-time speech translation powered by on-device AI. Have live conversations across languages, with each person
speaking naturally in their own language and hearing the other translated in real time.

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
[Zebra Translation](https://picovoice.ai/docs/zebra/), and
[Orca Streaming Text-to-Speech](https://picovoice.ai/docs/orca/):

```console
python setup.py
```

### 5. Run the Demo

```console
python main.py --access_key ${ACCESS_KEY} --language_pair ${LANGUAGE_PAIR}
```

`${LANGUAGE_PAIR}` is the tuple of languages used in the conversation. e.g., `es-en` means the first person is speaking
Spanish and the second person is speaking `English`.

### 6. View All Options

```console
python main.py --help
```
