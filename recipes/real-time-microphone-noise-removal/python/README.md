# Real Time Microphone Noise Removal in Python

Create an AI-powered microphone that removes background noise in real time.

This demo records audio from your microphone, saves the original raw audio, processes the same audio through Koala Noise
Suppression, and saves a second noise-suppressed version. You can then compare the two files to hear how Koala removes
background noise while preserving speech.

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

### 4. Run the Demo

```console
python main.py \
--access_key ${ACCESS_KEY}
```
The demo starts recording from your microphone immediately. Speak normally, then press Ctrl+C to stop.

By default, the demo saves two files:

```text
raw.wav
noise_suppressed.wav
```

`raw.wav` contains the original microphone audio. `noise_suppressed.wav` contains the same audio after real-time Koala
noise suppression. You can also choose the output paths:

```console
python main.py \
  --access_key ${ACCESS_KEY} \
  --raw_output_path ${RAW_OUTPUT_PATH} \
  --enhanced_output_path ${ENHANCED_OUTPUT_PATH}
```

### 5. Compare the Output

Listen to both files and compare the difference:

```console
python -m wave ./raw.wav
```

```console
python -m wave ./noise_suppressed.wav
```

Or open both WAV files in any audio player or editor.
