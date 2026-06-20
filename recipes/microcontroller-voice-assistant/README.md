# Microcontroller Voice Assistant
On-device voice assistant for microcontrollers (MCU): hands-free voice control that combines wake word detection with task-oriented voice intent, both running entirely on the microcontroller. No cloud, no network, no data leaving the device.

Porcupine Wake Word listens for the trigger phrase, and Rhino Speech-to-Intent turns a spoken command directly into a structured intent on constrained embedded hardware.

## Components
- [Porcupine Wake Word](https://picovoice.ai/docs/porcupine/)
- [Rhino Speech-to-Intent](https://picovoice.ai/docs/rhino/)

## Implementations
- [Arduino](mcu/arduino)
- [STM32](mcu/stm32f411)

## How it works
1. [Porcupine](https://picovoice.ai/docs/porcupine/) detects the wake word and starts listening.
2. [Rhino](https://picovoice.ai/docs/rhino/) infers the intent and its details directly from the spoken command, with no transcription step.

## FAQ

**Is it fully on-device?**
Yes. [Porcupine](https://picovoice.ai/docs/porcupine/) and [Rhino](https://picovoice.ai/docs/rhino/) both run on the microcontroller, with no cloud, no network connection, and no data leaving the device.

**Which microcontrollers does it run on?**
It can run on any ARM Cortex-M microcontroller. Picovoice currently offers public support for Arduino and STM32. 

**How is it different from a large language model assistant?**
It uses [Rhino](https://picovoice.ai/docs/rhino/) to recognize a fixed set of voice commands within a defined context, so it is small and fast enough for microcontrollers. For open-ended conversation with a large language model, see the [LLM-Powered Voice Assistant](https://picovoice.ai/cookbook/llm-voice-ai-agent-assistant/), which needs more compute.

More FAQs can be found on [Picovoice website](https://picovoice.ai/cookbook/embedded-ai-voice-assistant/).
