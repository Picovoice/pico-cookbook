# Personalized Wake Word in Python

Personalize wake word detection, powered by on-device voice AI.

## Compatibility

- Runs on Linux (x86_64), macOS (arm64, x86_64), Windows (arm64, x86_64), and Raspberry Pi (5, 4, and 3).
- Python>=3.9

## AccessKey

AccessKey is your authentication and authorization token for deploying Picovoice SDKs. Anyone who is using Picovoice
needs to have a valid AccessKey. You must keep your AccessKey secret. You would need internet connectivity to validate
your AccessKey with Picovoice license servers, even though the inference is running 100% offline. Everyone who signs up
for [Picovoice Console](https://console.picovoice.ai/) receives a unique AccessKey.

## Usage

These instructions assume your current working directory is `recipes/personalized-wake-word/python`.

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

### 4. Train a Wake Word Model

Follow instruction on Porcupine Wake Word [documentation](https://picovoice.ai/docs/porcupine/#custom-wake-words) to
train a wake word model on [Picovoice Console](https://console.picovoice.ai/) in minutes.

### 5. Enroll the User

```console
python enroll.py \
--access_key ${ACCESS_KEY} \
--porcupine_keyword_path ${PORCUPINE_KEYWORD_PATH} \
--eagle_speaker_profile_path ${EAGLE_SPEAKER_PROFILE_PATH}
```

### 6. Run the Demo

```console
python main.py \
--access_key ${ACCESS_KEY} \
--porcupine_keyword_path ${PORCUPINE_KEYWORD_PATH} \
--eagle_speaker_profile_path ${EAGLE_SPEAKER_PROFILE_PATH}
```
