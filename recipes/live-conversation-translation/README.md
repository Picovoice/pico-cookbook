# Live Conversation Translation
On-device live conversation translation: a real-time, two-way voice interpreter for face-to-face conversations between people who speak different languages. Each person speaks their own language and hears the other in theirs, fully on-device with no cloud and no data leaving the device.

Cheetah Streaming Speech-to-Text transcribes the speaker, Zebra Translation translates into the other language, and Orca Streaming Text-to-Speech speaks the translation aloud.

[![Live Conversation Translation](https://img.youtube.com/vi/SwN9kIwRP6o/hqdefault.jpg)](https://www.youtube.com/watch?v=SwN9kIwRP6o)

## Components
- [Cheetah Streaming Speech-to-Text](https://picovoice.ai/docs/cheetah/)
- [Zebra Translation](https://picovoice.ai/docs/zebra/)
- [Orca Streaming Text-to-Speech](https://picovoice.ai/docs/orca/)

## Implementations
- [Android](android)
- [iOS](ios)
- [Python](python)
- [Web](web)

## How it works
1. [Cheetah](https://picovoice.ai/docs/cheetah/) transcribes the current speaker in their language.
2. [Zebra](https://picovoice.ai/docs/zebra/) translates what they said into the other speaker's language.
3. [Orca](https://picovoice.ai/docs/orca/) speaks the translation aloud, then the recipe switches speakers and repeats.

## FAQ

**Is it fully on-device?**
Yes. [Cheetah](https://picovoice.ai/docs/cheetah/), [Zebra](https://picovoice.ai/docs/zebra/), and [Orca](https://picovoice.ai/docs/orca/) all run on-device, with no cloud and no data leaving the device.

**Does it translate both directions in a conversation?**
Yes. It is a two-way interpreter that alternates between two speakers, so each hears the other in their own language. For one-way translation of a single speaker, with automatic language detection, see [Speech-to-Speech Translation](https://picovoice.ai/cookbook/speech-to-speech-translation/).

**Does it show the text or only speak it?**
Both. It prints each speaker's transcription and its translation on screen, and speaks the translation aloud with [Orca](https://picovoice.ai/docs/orca/).

More FAQs can be found on [Picovoice website](https://picovoice.ai/cookbook/live-conversation-translation/).
