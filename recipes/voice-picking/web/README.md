# Voice Picking on the Web

Perform fully on-device, hands-free voice picking with spoken prompts, structured intent capture for picking workflows,
and support for complex slots and exception handling.

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

These instructions assume your current working directory is `recipes/voice-picking/web`.

### 1. Train a Wake Word Model

1. Open [Picovoice Console](https://console.picovoice.ai/)
2. Go to Porcupine Wake Word.
3. Enter your desired wake phrase.
4. Click Train.
5. Select your target platform and download the generated wake word model file (`.ppn`).

Save the downloaded file somewhere accessible on your machine. You will pass its path to the demo with `--porcupine_keyword_path`.

### 2. Train the Speech-to-Intent Model

1. Open [Picovoice Console](https://console.picovoice.ai/)
2. Go to Rhino Speech-to-Intent.
3. Create an empty Rhino context.
4. Click Import YAML in the top-right corner.
5. Paste the [Rhino context YAML](../res/context.yml) for this demo.
6. Download the generated Rhino context file (`.rhn`) for your target platform.

Save the downloaded file somewhere accessible on your machine. You will pass its path to the demo with `--rhino_context_path`.

### 3. Download the Required Models

Run the setup script to download the models for
- [Cheetah Streaming Speech-to-Text](https://picovoice.ai/docs/cheetah/)
- [Koala Noise Suppression](https://picovoice.ai/docs/koala/)
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
- Enter your AccessKey, then press the `Start Demo` button.
