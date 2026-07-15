# Voice Guided Field Reporting

On-device voice-guided field reporting: capture field reports hands-free with wake word activation, spoken prompts, structured intent capture, and free-form voice notes. Everything runs on-device, with no cloud processing and no data leaving the device.

Porcupine Wake Word starts the report, Orca Streaming Text-to-Speech speaks each prompt, Rhino Speech-to-Intent captures structured answers, and Cheetah Streaming Speech-to-Text records free-form notes.

[![Voice Guided Field Reporting](https://img.youtube.com/vi/aUT9kIZptvU/hqdefault.jpg)](https://www.youtube.com/watch?v=aUT9kIZptvU)

## Components
- [Porcupine Wake Word](https://picovoice.ai/docs/porcupine/)
- [Rhino Speech-to-Intent](https://picovoice.ai/docs/rhino/)
- [Cheetah Streaming Speech-to-Text](https://picovoice.ai/docs/cheetah/)
- [Orca Streaming Text-to-Speech](https://picovoice.ai/docs/orca/)

## Implementations
- [Android](android)
- [iOS](ios)
- [Python](python)
- [Web](web)

## How it works
1. [Porcupine](https://picovoice.ai/docs/porcupine/) detects the wake word and starts the field report.
2. [Orca](https://picovoice.ai/docs/orca/) speaks each prompt in the report, one field at a time.
3. [Rhino](https://picovoice.ai/docs/rhino/) captures each answer as a structured intent and re-prompts if it does not understand.
4. [Cheetah](https://picovoice.ai/docs/cheetah/) records the final free-form notes as text.

## FAQ

**Is it fully on-device?**
Yes. [Porcupine](https://picovoice.ai/docs/porcupine/), [Rhino](https://picovoice.ai/docs/rhino/), [Cheetah](https://picovoice.ai/docs/cheetah/), and [Orca](https://picovoice.ai/docs/orca/) all run on-device, with no cloud and no data leaving the device.

**Is it hands-free?**
Yes. [Porcupine](https://picovoice.ai/docs/porcupine/) starts the report with a wake word and [Orca](https://picovoice.ai/docs/orca/) speaks each prompt, so the worker keeps their hands and eyes on the task.

**How does it capture structured fields and free-form notes?**
[Rhino](https://picovoice.ai/docs/rhino/) captures the structured fields as voice intents, so each answer maps to a known field, and [Cheetah](https://picovoice.ai/docs/cheetah/) transcribes the free-form notes at the end as open text.

More FAQs can be found on [Picovoice website](https://picovoice.ai/cookbook/voice-field-reporting/).
