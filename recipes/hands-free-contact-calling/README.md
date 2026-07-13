# Hands-Free Contact Calling

Call contacts by name, resolve ambiguity, and handle follow-up clarification.

This demo shows how to combine wake word detection, speech-to-intent, and text-to-speech to create a hands-free contact
calling assistant. It can recognize commands like "call Sarah", resolve contacts from a contact list, ask clarification
questions when multiple contacts match, and return to always listening mode only after the call flow is complete. This
enables use cases like in-car calling, smart glasses, mobile assistants, accessibility tools, headset controls, and
embedded hands-free communication.

## Components

* [Orca Streaming Text-to-Speech](https://picovoice.ai/docs/orca/)
* [Porcupine Wake Word](https://picovoice.ai/docs/porcupine/)
* [Rhino Speech-to-Intent](https://picovoice.ai/docs/rhino/)

## Implementations

* [Python](python)
