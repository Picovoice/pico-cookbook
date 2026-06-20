# Speech-to-Speech Translation

On-device speech-to-speech translation with automatic language detection: speak in one language and hear it translated into another in real time. It detects the spoken language for you, so others can speak any supported language and you understand them in yours, all on-device with no cloud and no data leaving the device.

Bat Spoken Language Identification detects the language, Cheetah Streaming Speech-to-Text transcribes it, Zebra Translation translates it, and Orca Streaming Text-to-Speech speaks the translation aloud.

[![Speech-to-Speech Translation](https://img.youtube.com/vi/D9id2Dgv_OM/hqdefault.jpg)](https://www.youtube.com/watch?v=D9id2Dgv_OM)

## Components
- [Bat Spoken Language Identification](https://picovoice.ai/docs/bat/)
- [Cheetah Streaming Speech-to-Text](https://picovoice.ai/docs/cheetah/)
- [Zebra Translation](https://picovoice.ai/docs/zebra/)
- [Orca Streaming Text-to-Speech](https://picovoice.ai/docs/orca/)

## Implementations
- [Android](android)
- [iOS](ios)
- [Python](python)
- [Web](web)

## How it works
1. [Bat](https://picovoice.ai/docs/bat/) detects the language being spoken.
2. [Cheetah](https://picovoice.ai/docs/cheetah/) transcribes the speech in that language.
3. [Zebra](https://picovoice.ai/docs/zebra/) translates the text into the target language.
4. [Orca](https://picovoice.ai/docs/orca/) speaks the translation aloud.

## FAQ

**Is it fully on-device?**
Yes. [Bat](https://picovoice.ai/docs/bat/), [Cheetah](https://picovoice.ai/docs/cheetah/), [Zebra](https://picovoice.ai/docs/zebra/), and [Orca](https://picovoice.ai/docs/orca/) all run on-device, with no cloud and no data leaving the device.

**Do I need to set the input language?**
No. [Bat](https://picovoice.ai/docs/bat/) detects the spoken language automatically, so the speaker can talk in any supported language without choosing it first.

**Does it translate both directions in a conversation?**
This recipe is one-way: it translates one speaker into a target language, which suits situations where you need to understand many speakers. For a two-way interpreter where both people hear each other, see the [Live Conversation Translation](https://picovoice.ai/cookbook/live-conversation-translation/) recipe.

More FAQs can be found on [Picovoice website](https://picovoice.ai/cookbook/speech-to-speech-translation/).
