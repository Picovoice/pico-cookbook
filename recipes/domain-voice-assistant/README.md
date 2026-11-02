# Domain Voice Assistant

On-device voice assistant that knows one domain, here Picovoice's products, and speaks its answers back. Wake word detection, speech-to-text, LLM inference, text-to-speech, and voice activity detection all run on-device, with no cloud and no data leaving the device.

Cheetah Streaming Speech-to-Text, picoLLM Inference, and Orca Streaming Text-to-Speech each use a model customized for the domain.

## Components
- [Porcupine Wake Word](https://picovoice.ai/docs/porcupine/)
- [Cheetah Streaming Speech-to-Text](https://picovoice.ai/docs/cheetah/)
- [picoLLM Inference](https://picovoice.ai/docs/picollm/)
- [Orca Streaming Text-to-Speech](https://picovoice.ai/docs/orca/)
- [Cobra Voice Activity Detection](https://picovoice.ai/docs/cobra/)

## Implementations
- [Android](android)
- [Python](python)
- [Web](web)

## How it works
1. [Porcupine](https://picovoice.ai/docs/porcupine/) detects the wake word and starts listening.
2. [Cheetah](https://picovoice.ai/docs/cheetah/) transcribes the spoken question in real time.
3. [picoLLM](https://picovoice.ai/docs/picollm/) generates the answer with a local LLM.
4. [Orca](https://picovoice.ai/docs/orca/) speaks the answer aloud as it is generated.
5. On the web, [Cobra](https://picovoice.ai/docs/cobra/) detects when you talk over an answer, so you can interrupt it with a new question. On Android and Python, say the wake word to interrupt.
6. After an answer, the assistant keeps listening for a follow-up question for a few seconds, without the wake word.
