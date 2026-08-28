# Contact Center Agent Assist

On-device contact-center transcription with domain-specific vocabulary through the [Cheetah Model API](https://picovoice.ai/docs/model-api/cheetah/), for agent-assist workflows.

General-purpose speech-to-text misses the words that matter most on a support call: product names, part numbers, and domain jargon. This demo takes the terminology already present in a contact-center platform's configuration, trains a custom Cheetah Streaming Speech-to-Text model over the Cheetah Model API, and transcribes the customer side of a call in real time, fully on-device. The live transcript is sent to the helpdesk's knowledge search, and the returned support articles are surfaced in a split-screen agent view as the customer describes their issue.

The sample helpdesk is configured for IKEA customer support, with a representative mock catalog of about thirty product names like Kallax, Strandmon, and Poang. The whole catalog customizes the speech model, while the demo's help-center content covers a smaller set of support scenarios. IKEA product names are used only as a realistic example catalog; this recipe is not affiliated with or endorsed by IKEA.

## Components

- [Cheetah Streaming Speech-to-Text](https://picovoice.ai/docs/cheetah/)

## Implementations

- [Python](python)
