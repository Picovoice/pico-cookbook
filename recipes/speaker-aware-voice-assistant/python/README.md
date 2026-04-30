# Speaker-Aware Voice Assistant

Build voice assistants that can personalize behavior and enforce access control based on who is speaking.

This demo shows how to combine wake word detection, speech-to-intent, speaker recognition, and text-to-speech to create
a voice assistant that reacts differently for different users. It can allow everyone to use basic commands, personalize
responses for recognized speakers, and restrict sensitive actions to authorized admins. This enables use cases like
personal dashboards, user-specific settings, family profiles, workplace permissions, protected device controls,
and admin-only operations.

## Compatibility

- Runs on Linux (x86_64), macOS (arm64, x86_64), Windows (arm64, x86_64), and Raspberry Pi (5, 4, and 3).
- Python>=3.9

## AccessKey

AccessKey is your authentication and authorization token for deploying Picovoice SDKs. Anyone who is using Picovoice
needs to have a valid AccessKey. You must keep your AccessKey secret. You would need internet connectivity to validate
your AccessKey with Picovoice license servers, even though the inference is running 100% offline. Everyone who signs up
for [Picovoice Console](https://console.picovoice.ai/) receives a unique AccessKey.

## Usage

These instructions assume your current working directory is `recipes/speaker-aware-voice-assistant/python`.

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

### 4. Train a Wake Word Model

1. Open [Picovoice Console](https://console.picovoice.ai/)
2. Go to Porcupine Wake Word.
3. Enter your desired wake phrase.
4. Click Train.
5. Select your target platform and download the generated wake word model file (`.ppn`).

### 5. Train the Speech-to-Intent Model

1. Open [Picovoice Console](https://console.picovoice.ai/)
2. Go to Rhino Speech-to-Intent.
3. Create an empty Rhino context.
4. Click Import YAML in the top-right corner.
5. Paste the [Rhino context YAML](../res/context.yml) for this demo.
6. Download the generated Rhino context file (`.rhn`) for your target platform.

### 6. Enroll Users

Create one Eagle speaker profile for each user you want the assistant to recognize.

```console
python enroll.py \
  --access_key ${ACCESS_KEY} \
  --eagle_speaker_profile_path ${EAGLE_SPEAKER_PROFILE_PATH}
```

### 7. Run the Demo

```console
python main.py \
  --access_key ${ACCESS_KEY} \
  --keyword_path ${KEYWORD_PATH} \
  --context_path ${CONTEXT_PATH} \
  --user_profile_paths ${USER_PROFILE_PATH_1} ${USER_PROFILE_PATH_2} ... \
  --user_roles ${USER_ROLE_1} ${USER_ROLE_2} ...
```

### 7. View All Options

```console
python main.py --help
```
