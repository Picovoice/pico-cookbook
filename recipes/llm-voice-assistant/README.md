# LLM-Powered Voice Assistant

On-device LLM voice assistant: a hands-free voice assistant powered by a large language model (LLM), with wake word detection, speech-to-text, LLM inference, and text-to-speech all running on-device. No cloud, no data leaving the device.

Porcupine Wake Word listens for the trigger phrase, Cheetah Streaming Speech-to-Text transcribes the request, picoLLM Inference generates the response with a local LLM, and Orca Streaming Text-to-Speech speaks it back.

[![LLM-Powered Voice Assistant](https://img.youtube.com/vi/5JkDVbkedBU/hqdefault.jpg)](https://www.youtube.com/watch?v=5JkDVbkedBU)

## Components
- [Porcupine Wake Word](https://picovoice.ai/docs/porcupine/)
- [Cheetah Streaming Speech-to-Text](https://picovoice.ai/docs/cheetah/)
- [picoLLM Inference Engine](https://picovoice.ai/docs/picollm/)
- [Orca Streaming Text-to-Speech](https://picovoice.ai/docs/orca/)

## Implementations
- [Android](android)
- [.NET](dotnet)
- [iOS](ios)
- [Node.js](nodejs)
- [Python](python)
- [Web](web)

## How it works
1. [Porcupine](https://picovoice.ai/docs/porcupine/) detects the wake word and starts listening.
2. [Cheetah](https://picovoice.ai/docs/cheetah/) transcribes the spoken request in real time.
3. [picoLLM](https://picovoice.ai/docs/picollm/) runs a local LLM to generate the response.
4. [Orca](https://picovoice.ai/docs/orca/) speaks the response aloud as it is generated.

## FAQ

**Is it fully on-device?**
Yes. [Porcupine](https://picovoice.ai/docs/porcupine/), [Cheetah](https://picovoice.ai/docs/cheetah/), [picoLLM](https://picovoice.ai/docs/picollm/), and [Orca](https://picovoice.ai/docs/orca/) all run on-device, with no cloud and no data leaving the device.

**Which LLMs can it run?**
It can run any LLM compressed by [picoCompression](https://picovoice.ai/technologies/ai-model-compression/). The list of [pre-compressed models can be found here](https://github.com/Picovoice/picollm#models)

**Does it work hands-free?**
Yes. [Porcupine](https://picovoice.ai/docs/porcupine/) listens for a wake word, so you start the assistant with your voice and never touch the device.

**Does it respond by voice in real time?**
Yes. [Orca](https://picovoice.ai/docs/orca/) streams speech as [picoLLM](https://picovoice.ai/docs/picollm/) generates the answer, so the assistant starts speaking before the full response is complete.

More FAQs can be found on [Picovoice website](https://picovoice.ai/cookbook/llm-voice-ai-agent-assistant/).
