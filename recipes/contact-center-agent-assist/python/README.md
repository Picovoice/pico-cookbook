# Contact Center Agent Assist in Python

On-device agent assist for contact centers: live call transcription that gets the company's product names right, with
the matching knowledge-base article surfaced as the caller speaks.

## Compatibility

- Runs on Linux (x86_64), macOS (arm64, x86_64), Windows (arm64, x86_64), and Raspberry Pi (5, 4, and 3).
- Python>=3.9

## AccessKey

AccessKey is your authentication and authorization token for deploying Picovoice SDKs. Anyone who is using Picovoice
needs to have a valid AccessKey. You must keep your AccessKey secret. You would need internet connectivity to validate
your AccessKey with Picovoice license servers, even though the inference is running 100% offline. Everyone who signs up
for [Picovoice Console](https://console.picovoice.ai/) receives a unique AccessKey.

## Usage

These instructions assume your current working directory is `recipes/contact-center-agent-assist/python`.

### 1. Create a Virtual Environment

```console
python -m venv .venv
```

### 2. Activate the Virtual Environment

On Linux, macOS, or Raspberry Pi:

```console
source .venv/bin/activate
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

On the first run, the demo reads the help desk export ([helpdesk.json](../res/helpdesk.json)), trains a custom model
over the [Cheetah Model API](https://picovoice.ai/docs/model-api/cheetah/), and saves it to `cheetah_model.pv`.
Training requires internet connectivity and sends the vocabulary, not audio; later runs reuse the saved model and
transcription runs fully offline.

The demo then listens on the microphone. Speak as the caller, for example:

> "Hi, my Aerlume has a red light on and the air feels stale."

> "I can't get the Nyxa to pair with the app anymore."

> "The Solivent shows offline ever since I changed my router."

The transcript streams live with the product names spelled correctly, and when an utterance matches the knowledge base,
the demo prints the suggested article and next step for the agent:

```text
[SUGGESTED] KB-102 — Nyxa smart lock pairing issues
            Confirm the lock's battery level, then walk the caller through removing and re-pairing the device.
```

Press Ctrl+C to stop.

### 5. Use Your Own Catalog

Edit [helpdesk.json](../res/helpdesk.json) (or point `--helpdesk_path` at your own export) to change the products,
boosted words, and knowledge-base articles, then retrain the model:

```console
python main.py \
  --access_key ${ACCESS_KEY} \
  --retrain
```

Each product may list pronunciations as space-separated IPA phonemes. Products without pronunciations get a generated
default.

### 6. View All Options

```console
python main.py --help
```
