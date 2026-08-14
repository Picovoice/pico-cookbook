# Contact Center Agent Assist

On-device agent assist for contact centers: live call transcription that gets the company's product names right, with
the matching knowledge-base article surfaced as the caller speaks.

General-purpose speech-to-text misses the words that matter most on a support call: product names, plan tiers, and
domain jargon. This demo reads a help desk export with the product catalog and knowledge base, trains a custom Cheetah
Streaming Speech-to-Text model over the Cheetah Model API, and then transcribes the call in real time, fully on-device.
Each finalized utterance is matched against the knowledge base, and the most relevant article and suggested next step
are shown to the agent.

## Components

- [Cheetah Streaming Speech-to-Text](https://picovoice.ai/docs/cheetah/)
- [Cheetah Model API](https://picovoice.ai/docs/model-api/cheetah/)

## Implementations

- [Python](python)

## How it works

1. The help desk export ([helpdesk.json](res/helpdesk.json)) provides the product catalog, words to boost, and the
   knowledge-base articles.
2. The [Cheetah Model API](https://picovoice.ai/docs/model-api/cheetah/) trains a custom speech-to-text model from that
   vocabulary: product names are added as new words, with optional custom pronunciations, and domain terms are boosted.
3. The trained model file (`.pv`) is saved locally and reused on later runs, so training happens once.
4. [Cheetah](https://picovoice.ai/docs/cheetah/) loads the custom model and transcribes the live call in real time,
   fully on-device.
5. Each finalized utterance is scored against the knowledge base, and the best-matching article is surfaced with its
   suggested next step for the agent.

## FAQ

**Is it fully on-device?**
Transcription is. [Cheetah](https://picovoice.ai/docs/cheetah/) runs on-device, with no cloud and no audio leaving the
device, which suits calls that carry payment or health information. Only the one-time model training call goes to the
[Cheetah Model API](https://picovoice.ai/docs/model-api/cheetah/), and it sends the vocabulary definition, not audio.

**How does it get product names right?**
The product catalog is added to the model's vocabulary before the model is trained, each product with optional custom
pronunciations, and domain terms are boosted so the model is more likely to select them. The model learns the catalog
instead of guessing at unfamiliar words.

**Does it need access to a specific help desk platform?**
No. It reads a plain export file with products and articles, so any help desk, CRM, or product catalog that can produce
one works. Swap in your own data and retrain.

**Does the custom model need to be retrained for every call?**
No. The trained model file is saved locally and reused across calls and runs. Retraining is only needed when the
catalog or vocabulary changes.
