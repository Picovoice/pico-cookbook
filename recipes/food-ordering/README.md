# Food Ordering with Voice AI

On-device voice ordering for QSR drive-thru and self-order kiosks. Customers add, change, and confirm a full order hands-free by voice, with wake word detection and speech-to-intent running fully on-device and no cloud.

## Components

- [Orca Streaming Text-to-Speech](https://picovoice.ai/docs/orca/)
- [Porcupine Wake Word](https://picovoice.ai/docs/porcupine/)
- [Rhino Speech-to-Intent](https://picovoice.ai/docs/rhino/)

## Implementations

- [Android](android)
- [Python](python)

## How it works
1. [Porcupine](https://picovoice.ai/docs/porcupine/) detects the wake word and starts a new order.
2. [Orca](https://picovoice.ai/docs/orca/) greets the customer and asks what they would like.
3. [Rhino](https://picovoice.ai/docs/rhino/) recognizes each spoken request as a structured intent and fills its slots: add, remove, or change items, with size, quantity, combo, and modifiers.
4. [Orca](https://picovoice.ai/docs/orca/)  confirms every change out loud and reads the full order back whenever the customer asks.
5. When the customer says that is all, [Orca](https://picovoice.ai/docs/orca/) reads the final order back and closes it. A help request notifies a staff member while the customer keeps ordering.

## FAQ

**Is it fully on-device?**
Yes. Porcupine, Rhino, and Orca all run on-device, with nothing sent to the cloud.

**Does it use an LLM?**
No. Orders are understood with [Rhino](https://picovoice.ai/docs/rhino/), which maps speech to a fixed set of intents and slots for a known menu, so there is no language model to download and responses are fast and predictable.

**What can a customer change by voice?**
Add, remove, or change items, adjust size or quantity, switch an item to a combo, start the order over, or have the full order read back, all by voice.
