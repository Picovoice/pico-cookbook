# Speaker Aware Voice Assistant on the Web

Build voice assistants that can personalize behavior and enforce access control based on who is speaking.

This demo shows how to combine wake word detection, speech-to-intent, speaker recognition, and text-to-speech to create
a voice assistant that reacts differently for different users. It can allow everyone to use basic commands, personalize
responses for recognized speakers, and restrict sensitive actions to authorized admins. This enables use cases like
personal dashboards, user-specific settings, family profiles, workplace permissions, protected device controls,
and admin-only operations.

## Compatibility

- Node.js 18+
- Chrome / Edge
- Firefox
- Safari

## AccessKey

AccessKey is your authentication and authorization token for deploying Picovoice SDKs. Anyone who is using Picovoice
needs to have a valid AccessKey. You must keep your AccessKey secret. You would need internet connectivity to validate
your AccessKey with Picovoice license servers, even though the inference is running 100% offline. Everyone who signs up
for [Picovoice Console](https://console.picovoice.ai/) receives a unique AccessKey.

## Usage

These instructions assume your current working directory is `recipes/speaker-aware-voice-assistant/web`.

### 1. Train a Wake Word Model

1. Open [Picovoice Console](https://console.picovoice.ai/)
2. Go to Porcupine Wake Word.
3. Enter your desired wake phrase.
4. Click Train.
5. Select your target platform and download the generated wake word model file (`.ppn`).

### 2. Train the Speech-to-Intent Model

1. Open [Picovoice Console](https://console.picovoice.ai/)
2. Go to Rhino Speech-to-Intent.
3. Create an empty Rhino context.
4. Click Import YAML in the top-right corner.
5. Paste the [Rhino context YAML](../res/context.yml) for this demo.
6. Download the generated Rhino context file (`.rhn`) for your target platform.

### 3. Download the Required Models

Run the setup script to download the models for
- [Eagle Speaker Recognition](https://picovoice.ai/docs/eagle/)
- [Orca Streaming Text-to-Speech](https://picovoice.ai/docs/orca/)
- [Porcupine Wake Word](https://picovoice.ai/docs/porcupine/)
- [Rhino Speech-to-Intent](https://picovoice.ai/docs/rhino/)

This script will also add your wake word (`.ppn`) and rhino context (`.rhn`) files to the project:

```console
python setup.py \
    --porcupine_keyword_path ${PATH_TO_PPN_FILE} \
    --rhino_context_path ${PATH_TO_RHN_FILE}
```

### 4. Build and Run the Demo

```bash
# install dependencies
yarn

# build the demo
yarn build

# run the demo
yarn start
```

### 5. Open the Demo page

<!-- markdown-link-check-disable -->
- Go to [localhost:5000](http://localhost:5000) in your web browser.
<!-- markdown-link-check-enable -->
- Enter your AccessKey, then press the `Start Enrollment` button.

- Enter the name and role of each user, then create a speaker profile by capturing each person's voice.

- Say the wakeword, then have different users speak the following commands
  - `do something that requires admin permission`
  - `do something just for me`
  - `do something anyone can do`
