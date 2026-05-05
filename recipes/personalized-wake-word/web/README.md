# Personalized Wake Word on the Web

Personalized wake word detection that activates only for the enrolled speaker, powered by on-device voice AI.

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

These instructions assume your current working directory is `recipes/personalized-wake-word/web`.

### 1. Train a Wake Word Model

Follow instruction on Porcupine Wake Word [documentation](https://picovoice.ai/docs/porcupine/#custom-wake-words) to
train a wake word model on [Picovoice Console](https://console.picovoice.ai/) in minutes.

### 2. Download the Required Models

Run the setup script to download the models for [Porcupine Wake Word](https://picovoice.ai/docs/porcupine/) and
[Eagle Speaker Recognition](https://picovoice.ai/docs/eagle/), as well as add your wake word file (`.ppn`) to the project:

```console
python setup.py --porcupine_keyword_path ${PATH_TO_PPN_FILE}
```

### 3. Install Dependencies

```console
yarn
```

### 4. Build the Demo

```console
yarn build
```

### 5. Run the Demo

```console
yarn start
```

### 6. Open the Demo page

<!-- markdown-link-check-disable -->
- go to [localhost:5000](http://localhost:5000) in your web browser.
<!-- markdown-link-check-enable -->