# Personalized Wake Word

On-device personalized wake word detection that activates only for the enrolled speaker. It combines wake word detection with speaker recognition, so the device wakes for one person's voice and ignores everyone else, all on-device with no cloud and no data leaving the device.

Porcupine Wake Word detects the trigger phrase, and Eagle Speaker Recognition verifies that it was spoken by the enrolled user.

[![Personalized Wake Word](https://img.youtube.com/vi/bReWNXNHW-A/hqdefault.jpg)](https://www.youtube.com/watch?v=bReWNXNHW-A)

## Components
- [Porcupine Wake Word](https://picovoice.ai/docs/porcupine/)
- [Eagle Speaker Recognition](https://picovoice.ai/docs/eagle/)

## Implementations
- [Android](android)
- [iOS](ios)
- [Python](python)
- [Web](web)

## How it works
1. Enroll a speaker once with [Eagle](https://picovoice.ai/docs/eagle/) to create their voice profile.
2. [Porcupine](https://picovoice.ai/docs/porcupine/) listens for the wake word.
3. [Eagle](https://picovoice.ai/docs/eagle/) checks the speaker against the enrolled profile and activates only if it matches.

## FAQ

**Is it fully on-device?**
Yes. [Porcupine](https://picovoice.ai/docs/porcupine/) and [Eagle](https://picovoice.ai/docs/eagle/) both run on-device, with no cloud and no data leaving the device.

**Do I need to enroll my voice first?**
Yes. You enroll once to create a voice profile with [Eagle](https://picovoice.ai/docs/eagle/), and the wake word then responds only to that profile.

**Can it tell apart multiple users on a shared device?**
This recipe is for a single enrolled user on a personal device, like a "Personalized Hey Siri" per phone. To identify which of several enrolled people spoke the wake word on a shared device, see the [Speaker-Aware Wake Word](../speaker-aware-wake-word) recipe.

More FAQs can be found on [Picovoice website](https://picovoice.ai/cookbook/personalized-wake-word/).
