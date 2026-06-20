# Speaker Identification Across Meetings

On-device speaker identification across meeting recordings: label who spoke when and recognize the same speakers from one meeting to the next. It combines speaker diarization with speaker recognition, running fully on-device with no cloud and no data leaving the device.

Falcon Speaker Diarization segments the recording by speaker, and Eagle Speaker Recognition matches each speaker to an enrolled voice profile, so recurring speakers are identified by name across meetings.

## Components
- [Falcon Speaker Diarization](https://picovoice.ai/docs/falcon/)
- [Eagle Speaker Recognition](https://picovoice.ai/docs/eagle/)

## Implementations
- [Python](python)

## How it works
1. [Falcon](https://picovoice.ai/docs/falcon/) diarizes the recording, splitting it into segments labeled by speaker.
2. [Eagle](https://picovoice.ai/docs/eagle/) matches each speaker against enrolled voice profiles to identify known speakers by name.
3. Unrecognized speakers can be enrolled as new profiles, so they are identified automatically in later meetings.

## FAQ

**Is it fully on-device?**
Yes. [Falcon](https://picovoice.ai/docs/falcon/) and [Eagle](https://picovoice.ai/docs/eagle/) both run on-device, with no cloud and no data leaving the device.

**What is the difference between diarization and recognition here?**
[Falcon](https://picovoice.ai/docs/falcon/) diarization works out who spoke when without knowing identities. [Eagle](https://picovoice.ai/docs/eagle/) recognition puts names to those speakers by matching them to enrolled voice profiles.

**How does it identify the same speaker across different meetings?**
[Eagle](https://picovoice.ai/docs/eagle/) stores a voice profile for each enrolled speaker, so the same person is recognized in any later recording, even when the meetings are separate.

**Does it need a bot to join the meeting?**
No. It processes a meeting recording on-device after the fact, so nothing joins the live call, and no audio is sent to the cloud.

More FAQs can be found on [Picovoice website](https://picovoice.ai/cookbook/speaker-identification-across-meetings/).
