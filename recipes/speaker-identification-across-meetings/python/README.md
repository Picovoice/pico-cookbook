# Speaker Identification Across Meetings in Python

Recognize known speakers across meeting recordings, powered by on-device speaker diarization and speaker recognition.

## Compatibility

- Runs on Linux (x86_64), macOS (arm64, x86_64), Windows (arm64, x86_64), and Raspberry Pi (5, 4, and 3).
- Python>=3.9

## AccessKey

AccessKey is your authentication and authorization token for deploying Picovoice SDKs. Anyone who is using Picovoice
needs to have a valid AccessKey. You must keep your AccessKey secret. You would need internet connectivity to validate
your AccessKey with Picovoice license servers, even though the inference is running 100% offline. Everyone who signs up
for [Picovoice Console](https://console.picovoice.ai/) receives a unique AccessKey.

## Usage

These instructions assume your current working directory is `recipes/speaker-identification-across-meetings/python`.

### 1. Create a Virtual Environment

```console
python -m venv .venv
```

### 2. Activate the Virtual Environment

On Linux, macOS, or Raspberry Pi:

```console
source .venv/bin/activate
```

On Windows:

```console
.venv\Scripts\activate
```

### 3. Install Dependencies

```console
pip install -r requirements.txt
```

### 4. Create Speaker Profiles from a Meeting

Run the demo with `--profile_unknown_speakers` to create Eagle speaker profiles for each diarized speaker:

```console
python main.py \
  --access_key ${ACCESS_KEY} \
  --audio_path meeting_1.wav \
  --profile_unknown_speakers \
  --unknown_speaker_profiles_folder ./speaker_profiles
```

The demo first diarizes the meeting, then creates one Eagle profile per speaker:

```console
Saved unknown speaker `0` profile to `./speaker_profiles/0.bin`.
Saved unknown speaker `1` profile to `./speaker_profiles/1.bin`.

Diarization:
[0] 0.34 → 4.82
[1] 5.10 → 8.91
[0] 9.24 → 12.47
```

You can rename the generated profiles to meaningful names:

```console
mv ./speaker_profiles/0.bin ./speaker_profiles/alice.bin
mv ./speaker_profiles/1.bin ./speaker_profiles/bob.bin
```

The filename, without extension, is used as the speaker name in future runs.

### 5. Identify Known Speakers in Another Meeting

Use the saved Eagle profiles to recognize known speakers in a new meeting recording:

```console
python main.py \
  --access_key ${ACCESS_KEY} \
  --audio_path meeting_2.wav \
  --known_speaker_profile_paths ./speaker_profiles/alice.bin ./speaker_profiles/bob.bin
```

If a diarized speaker matches a known speaker above the similarity threshold, the output uses the known speaker name:

```console
Identified speaker `0` as `alice` with similarity 0.743.

Diarization:
[alice] 0.34 → 4.82
[1] 5.10 → 8.91
[alice] 9.24 → 12.47
```

### 6. Tune the Similarity Threshold

By default, the similarity threshold is `0.5`. Increase it to make identification stricter:

```console
python main.py \
  --access_key ${ACCESS_KEY} \
  --audio_path meeting_2.wav \
  --known_speaker_profile_paths ./speaker_profiles/alice.bin ./speaker_profiles/bob.bin \
  --similarity_threshold 0.6
```

## How It Works

The demo first uses Falcon Speaker Diarization to determine who spoke when in the meeting recording. It groups all audio
segments for each diarized speaker tag, then uses Eagle Speaker Recognition to compare each speaker against the provided
known speaker profiles.

If the best similarity score is greater than or equal to `--similarity_threshold`, the diarized speaker tag is replaced
with the known speaker name. If `--profile_unknown_speakers` is enabled, the demo creates Eagle profiles for speakers
that were not matched to a known speaker.
