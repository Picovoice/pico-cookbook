# Voice Guided Maintenance & Inspection on the Web

Hands-free inspections with guided voice prompts, structured inspection workflows, complex slot extraction, and
free-form voice notes. Powered by on-device voice AI.

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

These instructions assume your current working directory is `recipes/voice-guided-maintenance-and-inspection/web`.

### 1. Train a Wake Word Model

1. Open [Picovoice Console](https://console.picovoice.ai/)
2. Go to Porcupine Wake Word.
3. Enter your desired wake phrase.
4. Click Train.
5. Select your target platform and download the generated wake word model file (`.ppn`).

Save the downloaded file somewhere accessible on your machine. You will pass its path to the demo with `--keyword_path`.

### 2. Train the Speech-to-Intent Model

1. Open [Picovoice Console](https://console.picovoice.ai/)
2. Go to Rhino Speech-to-Intent.
3. Create an empty Rhino context.
4. Click Import YAML in the top-right corner.
5. Paste the [Rhino context YAML](../res/context.yml) for this demo.
6. Download the generated Rhino context file (`.rhn`) for your target platform.

### 3. Run the Setup Script

Run the setup script to download and copy the models for [Cheetah Streaming Speech-to-Text](https://picovoice.ai/docs/cheetah/)
and [Orca Streaming Text-to-Speech](https://picovoice.ai/docs/orca/).

It will also copy the models provided for [Porcupine Wake Word](https://picovoice.ai/docs/porcupine/) and
[Rhino Speech-to-Intent](https://picovoice.ai/docs/rhino/) to the assets folder.

```console
python setup.py \
    --keyword_path ${PATH_TO_PPN} \
    --context_path ${PATH_TO_RHN}
```

### 4. Build and Run the Demo 

```bash
# Install Dependencies
yarn

# Build the Demo
yarn build

# Run the Demo
yarn start
```

### 5. Open the Demo Page

<!-- markdown-link-check-disable -->
- Go to [localhost:5000](http://localhost:5000) in your web browser.
<!-- markdown-link-check-enable -->
- Input your picovoice `AccessKey`
- Press the Start Demo button
