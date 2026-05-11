# Call Screen on the Web

Screen unknown calls with real-time transcription and natural voice responses, powered by on-device AI.

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

These instructions assume your current working directory is `recipes/call-screen/web`.

### 1. Train the Speech-to-Intent Model

1. Open [Picovoice Console](https://console.picovoice.ai/)
2. Go to Rhino Speech-to-Intent.
3. Create an empty Rhino context.
4. Click Import YAML in the top-right corner.
5. Paste the [Rhino context YAML](../res/context.yml) for this demo.
6. Select the **Web** platform and download the generated Rhino context file (`.rhn`).

### 2. Download the Required Models

Run the setup script to download and copy the models for [Cheetah Streaming Speech-to-Text](https://picovoice.ai/docs/cheetah/)
and [Orca Streaming Text-to-Speech](https://picovoice.ai/docs/orca/).

It will also copy the models provided for [Rhino Speech-to-Intent](https://picovoice.ai/docs/rhino/) to the `public/models` folder.

Lastly, it will place your `AccessKey` from Picovoice Console and name into the `ACCESS_KEY` and `USERNAME` variables
in [MainActivity.java](call-screen/src/main/java/ai/picovoice/callscreen/MainActivity.java).

```bash
python setup.py \
    --access_key ${ACCESS_KEY} \
    --name ${NAME} \
    --context_path ${PATH_TO_RHN}
```

### 3. Build and run the Demo 

```bash
# Install Dependencies
yarn

# Build the Demo
yarn build

# Run the Demo
yarn start
```

### 4. Open the Demo page

<!-- markdown-link-check-disable -->
- go to [localhost:5000](http://localhost:5000) in your web browser.
<!-- markdown-link-check-enable -->
