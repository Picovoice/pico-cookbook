# Voice Picking

On-device voice picking: hands-free, voice-directed picking for warehouses with spoken pick instructions, check-digit location confirmation, and exception handling. It runs fully on-device, with no cloud and no data leaving the device.

Porcupine Wake Word starts the workflow, Orca Streaming Text-to-Speech reads each pick instruction, and Rhino Speech-to-Intent captures the worker's spoken confirmations and exceptions.

[![Voice Picking](https://img.youtube.com/vi/wHg-O3QR1OE/hqdefault.jpg)](https://www.youtube.com/watch?v=wHg-O3QR1OE)

## Components
- [Porcupine Wake Word](https://picovoice.ai/docs/porcupine/)
- [Rhino Speech-to-Intent](https://picovoice.ai/docs/rhino/)
- [Orca Streaming Text-to-Speech](https://picovoice.ai/docs/orca/)

## Implementations
- [Android](android)
- [iOS](ios)
- [Python](python)
- [Web](web)

## How it works
1. [Porcupine](https://picovoice.ai/docs/porcupine/) detects the wake word and starts the picking workflow.
2. [Orca](https://picovoice.ai/docs/orca/) directs the worker to a location and reads the pick instruction and check digits.
3. [Rhino](https://picovoice.ai/docs/rhino/) captures the spoken check-digit confirmation and proceeds only when it matches.
4. [Rhino](https://picovoice.ai/docs/rhino/) records the pick result or an exception: short pick, damaged item, or empty location.
5. The workflow advances through each task until done.

## FAQ

**Is it fully on-device?**
Yes. [Porcupine](https://picovoice.ai/docs/porcupine/), [Rhino](https://picovoice.ai/docs/rhino/), and [Orca](https://picovoice.ai/docs/orca/) all run on-device, with no cloud and no data leaving the device.

**What is check-digit confirmation?**
[Orca](https://picovoice.ai/docs/orca/) reads the check digits for a location, the worker says them back, and [Rhino](https://picovoice.ai/docs/rhino/) advances only when the spoken digits match the location, confirming the right bin before picking.

**Does it handle pick exceptions?**
Yes. Beyond confirming the picked quantity, the worker can report a short pick, a damaged item, or an empty location by voice, and can exit the workflow at any time.

**Can it integrate with a warehouse management system?**
The recipe is a self-contained voice-picking workflow driven by a task list. It is structured so the tasks and pick results can be wired into a WMS or order management system.

More FAQs can be found on [Picovoice website](https://picovoice.ai/cookbook/voice-picking/).
