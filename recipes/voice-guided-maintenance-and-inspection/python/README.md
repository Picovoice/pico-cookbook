# Voice Guided Maintenance & Inspection in Python

Run a hands-free vehicle inspection workflow with Wake Word activation, Speech-to-Intent for structured inspection
steps, Streaming Speech-to-Text for final notes, and Text-to-Speech for user prompting.

## Compatibility

- Runs on Linux (x86_64), macOS (arm64, x86_64), Windows (arm64, x86_64), and Raspberry Pi (5, 4, and 3).
- Python>=3.9

## AccessKey

AccessKey is your authentication and authorization token for deploying Picovoice SDKs. Anyone who is using Picovoice
needs to have a valid AccessKey. You must keep your AccessKey secret. You would need internet connectivity to validate
your AccessKey with Picovoice license servers, even though the inference is running 100% offline. Everyone who signs up
for [Picovoice Console](https://console.picovoice.ai/) receives a unique AccessKey.

## Usage

These instructions assume your current working directory is `recipes/voice-guided-maintenance-and-inspection/python`.

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

1. Open [Picovoice Console](https://console.picovoice.ai/)
2. Go to Porcupine Wake Word.
3. Enter your desired wake phrase.
4. Click Train.
5. Select your target platform and download the generated wake word model file (`.ppn`).

Save the downloaded file somewhere accessible on your machine. You will pass its path to the demo with `--keyword_path`.

### 5. Train the Speech-to-Intent Model

1. Open [Picovoice Console](https://console.picovoice.ai/)
2. Go to Rhino Speech-to-Intent.
3. Create an empty Rhino context.
4. Click Import YAML in the top-right corner.
5. Paste the [Rhino context YAML](res/context.yml) for this demo.
6. Download the generated Rhino context file (`.rhn`) for your target platform.

### 6. Run the Demo

```console
python main.py \
  --access_key ${ACCESS_KEY} \
  --keyword_path ${KEYWORD_PATH} \
  --context_path ${CONTEXT_PATH}
```

Where:

* `${ACCESS_KEY}` is your Picovoice AccessKey from Picovoice Console.
* `${KEYWORD_PATH}` is the path to the Porcupine wake word model file (`.ppn`).
* `${CONTEXT_PATH}` is the path to the Rhino Speech-to-Intent context file (`.rhn`).

### 7. View All Options

```console
python main.py --help
```
