# Voice Memo Assistant

On-device voice memo assistant: record, rewrite, summarize, and replay voice memos hands-free by voice. Everything runs on-device, with no cloud processing and no data leaving the device.

Porcupine Wake Word starts the assistant, Rhino Speech-to-Intent handles voice commands, Cheetah Streaming Speech-to-Text transcribes the memo, picoLLM Inference rewrites or summarizes it with a local LLM, and Orca Streaming Text-to-Speech reads memos back aloud.

[![Voice Memo Assistant](https://img.youtube.com/vi/su_RxNBpuHg/hqdefault.jpg)](https://www.youtube.com/watch?v=su_RxNBpuHg)

## Components
- [Porcupine Wake Word](https://picovoice.ai/docs/porcupine/)
- [Rhino Speech-to-Intent](https://picovoice.ai/docs/rhino/)
- [Cheetah Streaming Speech-to-Text](https://picovoice.ai/docs/cheetah/)
- [picoLLM Inference](https://picovoice.ai/docs/picollm/)
- [Orca Streaming Text-to-Speech](https://picovoice.ai/docs/orca/)

## Implementations
- [Android](android)
- [iOS](ios)
- [Python](python)
- [Web](web)

## How it works
1. [Porcupine](https://picovoice.ai/docs/porcupine/) detects the wake word and starts the assistant.
2. [Rhino](https://picovoice.ai/docs/rhino/) recognizes a voice command: start a memo, read it, summarize it, or rewrite it.
3. [Cheetah](https://picovoice.ai/docs/cheetah/) transcribes the dictated memo until you say "stop recording".
4. [picoLLM](https://picovoice.ai/docs/picollm/) summarizes the memo into one short sentence or rewrites it to fix grammar, filler, and false starts, all with a local LLM.
5. [Orca](https://picovoice.ai/docs/orca/) reads the memo back aloud on command.

## FAQ

**Is it fully on-device?**
Yes. [Porcupine](https://picovoice.ai/docs/porcupine/), [Rhino](https://picovoice.ai/docs/rhino/), [Cheetah](https://picovoice.ai/docs/cheetah/), [picoLLM](https://picovoice.ai/docs/picollm/), and [Orca](https://picovoice.ai/docs/orca/) all run on-device, with no cloud and no data leaving the device.

**Is it hands-free?**
Yes. [Porcupine](https://picovoice.ai/docs/porcupine/) starts it with a wake word and [Rhino](https://picovoice.ai/docs/rhino/) takes voice commands, so you record and manage memos without touching the device.

**What can it do with a memo?**
Record it, read it back aloud, summarize it into one short sentence, or rewrite it to fix grammar, punctuation, filler words, and false starts while keeping the original meaning.

**Does the rewrite or summary change the meaning?**
No. [picoLLM](https://picovoice.ai/docs/picollm/) is instructed to preserve the original meaning and add no new information, cleaning up the wording or condensing it without altering the facts.

More FAQs can be found on [Picovoice website](https://picovoice.ai/cookbook/on-device-voice-memo/).
