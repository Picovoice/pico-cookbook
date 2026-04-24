# Call Assist in Python

Screen unknown calls with real-time transcription, natural voice responses, and on-device LLM reasoning. The assistant
answers an incoming call on behalf of the user, asks the caller to identify themselves and explain why they are calling,
transcribes the caller’s response, and uses a local LLM to extract the caller’s identity and reason for calling.

If the caller does not provide enough information, the assistant follows up automatically and asks for the missing
details. Once it has enough context, it summarizes the call for the user and lets them choose what to do next, such as
'connecting the call, declining it, asking for more details, requesting a text or email, asking the caller to call back
later, or blocking the caller.

The entire interaction is powered by on-device AI, combining real-time speech-to-text, text-to-speech, speech-to-intent,
and local language model inference to create a private, low-latency call-screening experience without sending audio or
call content to the cloud.

## Compatibility

- Runs on Linux (x86_64), macOS (arm64, x86_64), Windows (arm64, x86_64), and Raspberry Pi (5, 4, and 3).
- Python>=3.9

## AccessKey

AccessKey is your authentication and authorization token for deploying Picovoice SDKs. Anyone who is using Picovoice
needs to have a valid AccessKey. You must keep your AccessKey secret. You would need internet connectivity to validate
your AccessKey with Picovoice license servers, even though the inference is running 100% offline. Everyone who signs up
for [Picovoice Console](https://console.picovoice.ai/) receives a unique AccessKey.

## Usage

These instructions assume your current working directory is `recipes/call-assist/python`.

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

Download `llama-3.2-1b-instruct-385.pllm` from [Picovoice Console](https://console.picovoice.ai/).

### 5. Train the Speech-to-Intent Model

1. Open [Picovoice Console](https://console.picovoice.ai/)
2. Go to Rhino Speech-to-Intent.
3. Create an empty Rhino context.
4. Click Import YAML in the top-right corner.
5. Paste the [Rhino context YAML](../res/context.yml) for this demo.
6. Download the generated Rhino context file (`.rhn`) for your target platform.

### 6. Run the Demo

```console
python main.py \
  --access_key ${ACCESS_KEY} \
  --picollm_model_path ${PICOLLM_MODEL_PATH} \
  --context_path ${CONTEXT_PATH}   
```

Use `--username` to set the name of the person receiving the call.

For example:

```console
python main.py \
  --access_key ${ACCESS_KEY} \
  --picollm_model_path ${PICOLLM_MODEL_PATH} \
  --context_path ${CONTEXT_PATH} \
  --username Alireza
```

You can also provide `--username_pronunciation` to control how the name is spoken by
[Orca Streaming Text-to-Speech](https://picovoice.ai/platform/orca/).

```console
python main.py \
  --access_key ${ACCESS_KEY} \
  --picollm_model_path ${PICOLLM_MODEL_PATH} \
  --context_path ${CONTEXT_PATH} \
  --username Alireza \
  --username_pronunciation AE L IY R EH Z AA
```

You can find information about custom pronunciation and related phonemes on
[Orca's documentation](https://picovoice.ai/docs/orca/#custom-pronunciation).


### 7. View All Options

```console
python main.py --help
```
