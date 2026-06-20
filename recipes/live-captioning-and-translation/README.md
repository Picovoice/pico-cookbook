# Live Captioning and Translation

On-device live captioning and translation: transcribe live speech into real-time captions and subtitles, and show translated subtitles in another language alongside them. It runs fully on-device, with no cloud and no data leaving the device.

[![Live Captioning and Translation](https://img.youtube.com/vi/6_zObmdCsrk/hqdefault.jpg)](https://www.youtube.com/watch?v=6_zObmdCsrk)

## Components
- [Cheetah Streaming Speech-to-Text](https://picovoice.ai/docs/cheetah/)
- [Zebra Translation](https://picovoice.ai/docs/zebra/)

## Implementations
- [Android](android)
- [iOS](ios)
- [Python](python)
- [Web](web)

## How it works
1. [Cheetah](https://picovoice.ai/docs/cheetah/) transcribes the live audio into source-language captions in real time.
2. [Zebra](https://picovoice.ai/docs/zebra/) translates each caption into the target language and shows it alongside the source.

## FAQ
**Is it fully on-device?**
Yes. [Cheetah](https://picovoice.ai/docs/cheetah/) and [Zebra](https://picovoice.ai/docs/zebra/) both run on-device, with no cloud and no data leaving the device.

**Can I show captions only or translation only?**
Yes. Captions and translation are independent, so you can run both together, source captions only, or translated captions only.

**Does it speak the translation aloud?**
No. This recipe shows captions on screen only. For spoken translation, see the [Speech-to-Speech Translation](https://github.com/Picovoice/pico-cookbook/tree/main/recipes/speech-to-speech-translation) and [Live Conversation Translation](https://github.com/Picovoice/pico-cookbook/tree/main/recipes/live-conversation-translation) recipes.

More FAQs can be found on [Picovoice website](https://picovoice.ai/cookbook/live-captioning-and-translation/).
