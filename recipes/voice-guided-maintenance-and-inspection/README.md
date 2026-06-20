# Voice Guided Maintenance & Inspection

On-device voice-guided maintenance and inspection: run hands-free inspections with guided voice prompts, structured slot capture, free-form voice notes, and an auto-compiled report. It suits vehicle inspections and DVIR-style checks, running fully on-device with no cloud and no data leaving the device.

Porcupine Wake Word starts the inspection, Orca Streaming Text-to-Speech speaks each prompt, Rhino Speech-to-Intent captures structured answers, Cheetah Streaming Speech-to-Text records free-form notes, and Koala Noise Suppression cleans the microphone for noisy shop and field conditions.

[![Voice Guided Maintenance & Inspection](https://img.youtube.com/vi/U3EtfjOzH6Y/hqdefault.jpg)](https://www.youtube.com/watch?v=U3EtfjOzH6Y)

## Components
- [Porcupine Wake Word](https://picovoice.ai/docs/porcupine/)
- [Rhino Speech-to-Intent](https://picovoice.ai/docs/rhino/)
- [Cheetah Streaming Speech-to-Text](https://picovoice.ai/docs/cheetah/)
- [Orca Streaming Text-to-Speech](https://picovoice.ai/docs/orca/)
- [Koala Noise Suppression](https://picovoice.ai/docs/koala/)

## Implementations
- [Android](android)
- [iOS](ios)
- [Python](python)
- [Web](web)

## How it works
1. [Porcupine](https://picovoice.ai/docs/porcupine/) detects the wake word and starts the inspection.
2. [Orca](https://picovoice.ai/docs/orca/) speaks each inspection prompt, one check at a time.
3. [Rhino](https://picovoice.ai/docs/rhino/) captures each answer as a structured intent and re-prompts if it does not understand.
4. [Cheetah](https://picovoice.ai/docs/cheetah/) records the final free-form notes as text.
5. The recipe compiles the captured checks and notes into an inspection report, with [Koala](https://picovoice.ai/docs/koala/) suppressing background noise throughout.

## FAQ

**Is it fully on-device?**
Yes. [Porcupine](https://picovoice.ai/docs/porcupine/), [Rhino](https://picovoice.ai/docs/rhino/), [Cheetah](https://picovoice.ai/docs/cheetah/), [Orca](https://picovoice.ai/docs/orca/), and [Koala](https://picovoice.ai/docs/koala/) all run on-device, with no cloud processing and no data leaving the device.

**Is it hands-free?**
Yes. [Porcupine](https://picovoice.ai/docs/porcupine/) starts the inspection with a wake word and [Orca](https://picovoice.ai/docs/orca/) speaks each prompt, so the technician keeps their hands and eyes on the equipment.

**What does the inspection capture?**
It walks through guided checks such as unit ID, oil level, tire condition, and vehicle status, captured as structured fields with [Rhino](https://picovoice.ai/docs/rhino/), then a free-form note transcribed with [Cheetah](https://picovoice.ai/docs/cheetah/), and compiles them into a report.

**Does it work in a noisy shop or field?**
Yes. [Koala](https://picovoice.ai/docs/koala/) suppresses background noise on the microphone, so it keeps working around running engines and equipment.

More FAQs can be found on [Picovoice website](https://picovoice.ai/cookbook/voice-dvir-and-inspection/).
