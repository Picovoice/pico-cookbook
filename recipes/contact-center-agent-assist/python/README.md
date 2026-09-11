# Contact Center Agent Assist in Python

Transcribe the customer side of a support call in real time with a custom speech-to-text model trained from helpdesk
platform data, and surface the helpdesk's own knowledge-search results to the agent as the customer speaks.

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

On Windows:

```console
.venv\Scripts\activate
```

### 3. Install Dependencies

```console
pip install -r requirements.txt
```

### 4. Customize the Helpdesk Data (Optional)

The demo ships with sample helpdesk platform data ([helpdesk.json](../res/helpdesk.json)) shaped like what a
contact-center API would return: keyed ticket fields, including a `product` field whose options carry a representative
catalog of about thirty names (coined names include a `pronunciations` list for the custom vocabulary), support
`tags`, and help-center articles. The whole catalog drives the Cheetah vocabulary, while only a subset of products has
demo support articles, the way a real helpdesk knows more products than its articles discuss.

This demo simulates a contact-center platform's data and knowledge search locally, implemented in
[helpdesk.py](helpdesk.py). The `search_fixtures` section defines which help-center response is returned for a given
conversation.

If a custom model has already been trained, retrain it after changing the vocabulary:

```console
python main.py \
  --access_key ${ACCESS_KEY} \
  --retrain
```

### 5. Run the Demo

```console
python main.py \
  --access_key ${ACCESS_KEY}
```

On the first run, the demo reads the helpdesk configuration, trains a custom model over the
[Cheetah Model API](https://picovoice.ai/docs/model-api/cheetah/), and saves it to `cheetah_model_ikea.pv`. Later runs
reuse the saved model.

Speak as the customer and pause for about a second to end an utterance; tune the pause length with
`--endpoint_duration_sec`. Cheetah transcribes free-form speech, so any phrasing works. Some things to try with the
included IKEA helpdesk:

- "My Kallax keeps rocking even though I tightened everything."
- "Some parts were missing from my Billy."
- "My Strandmon chair arrived with a damaged leg."
- "Will the Kallax boxes fit in my car?"
- "I'm looking for a new chair, do you have any recommendations?"

Catalog products without a demo support article, like Hemnes or Trofast, still transcribe correctly; the helpdesk
simply returns nothing for them.

The demo runs as a full-screen split view: the live customer transcript streams on the left, and the agent-assist
panel on the right shows the returned support content.

```text
┌ IKEA Customer Support           Jane Doe | IKEA Family member ┐
├──────────────────────────────────┬────────────────────────────┤
│ Live Transcript                  │ Agent Assist               │
│                                  │                            │
│ [CUSTOMER] My Kallax keeps       │  STABILITY  KB-102 Kallax  │
│ rocking side to side.            │  assembly and stability    │
│                                  │  • A Kallax 2x2 unit...    │
│ [CUSTOMER] (listening) ...       │ ┌ Next step ─────────────┐ │
│                                  │ │ Confirm cam locks...   │ │
│                                  │ └────────────────────────┘ │
└──────────────────────────────────┴────────────────────────────┘
```

A clear result appears as a card with a topic badge, the article facts, and the next step; when several articles
apply, they are listed as related articles with their snippets. The card stays up until a different result replaces
it, and when the helpdesk returns nothing the demo simply keeps listening. Press Ctrl+C to stop.

### 6. View All Options

```console
python main.py --help
```
