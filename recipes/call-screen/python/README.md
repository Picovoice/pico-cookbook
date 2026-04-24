# Call Screen in Python

Screen unknown calls with real-time transcription and natural voice responses, powered by on-device AI.

## Compatibility

- Runs on Linux (x86_64), macOS (arm64, x86_64), Windows (arm64, x86_64), and Raspberry Pi (5, 4, and 3).
- Python>=3.9

## AccessKey

AccessKey is your authentication and authorization token for deploying Picovoice SDKs. Anyone who is using Picovoice
needs to have a valid AccessKey. You must keep your AccessKey secret. You would need internet connectivity to validate
your AccessKey with Picovoice license servers, even though the inference is running 100% offline. Everyone who signs up
for [Picovoice Console](https://console.picovoice.ai/) receives a unique AccessKey.

## Usage

These instructions assume your current working directory is `recipes/call-screen/python`.

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

### 4. Train the Speech-to-Intent Model

1. Open [Picovoice Console](https://console.picovoice.ai/)
2. Go to Rhino Speech-to-Intent.
3. Create an empty Rhino context.
4. Click Import YAML in the top-right corner.
5. Paste the [Rhino context YAML](../res/context.yml) for this demo.
6. Download the generated Rhino context file (`.rhn`) for your target platform.

### 5. Run the Demo

```console
python main.py \
  --access_key ${ACCESS_KEY} \
  --context_path ${CONTEXT_PATH}
```

Use `--username` to set the name of the person receiving the call.

For example:

```console
python main.py \
  --access_key ${ACCESS_KEY} \
   --context_path ${CONTEXT_PATH} \
   --username Alireza
```

You can also provide `--username_pronunciation` to control how the name is spoken by
[Orca Streaming Text-to-Speech](https://picovoice.ai/platform/orca/).

```console
python main.py \
  --access_key ${ACCESS_KEY} \
  --context_path ${CONTEXT_PATH} \
  --username Alireza \
  --username_pronunciation AE L IY R EH Z AA
```

You can find information about custom pronunciation and related phonemes on
[Orca's documentation](https://picovoice.ai/docs/orca/#custom-pronunciation).


### 5. View All Options

```console
python main.py --help
```
