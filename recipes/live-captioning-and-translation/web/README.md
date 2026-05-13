# Live Captioning and Translation on the Web

Transcribe and optionally translate live audio in real time, powered by on-device AI.

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

These instructions assume your current working directory is `recipes/live-captioning-and-translation/web`.

### 1. Download the Required Models

Run the setup script to download the models for [Cheetah Streaming Speech-to-Text](https://picovoice.ai/docs/cheetah/) and
[Zebra Translation](https://picovoice.ai/docs/zebra/):

```console
python setup.py
```

### 2. Install Dependencies

```console
yarn
```

### 3. Build the Demo

```console
yarn build
```

### 4. Run the Demo

```console
yarn start
```

### 5. Open the Demo page

<!-- markdown-link-check-disable -->
- go to [localhost:5000](http://localhost:5000) in your web browser.
<!-- markdown-link-check-enable -->
