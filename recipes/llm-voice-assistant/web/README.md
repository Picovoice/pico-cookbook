## Compatibility

- Node.js 18+
- Chrome / Edge
- Firefox
- Safari

## AccessKey

AccessKey is your authentication and authorization token for deploying Picovoice SDKs, including picoLLM. Anyone who is
using Picovoice needs to have a valid AccessKey. You must keep your AccessKey secret. You would need internet
connectivity to validate your AccessKey with Picovoice license servers even though the LLM inference is running 100%
offline and completely free for open-weight models. Everyone who signs up for
[Picovoice Console](https://console.picovoice.ai/) receives a unique AccessKey.

## picoLLM Model

picoLLM Inference Engine supports many open-weight models. The models are on
[Picovoice Console](https://console.picovoice.ai/).

## Installation

Install the required packages using `yarn`:

```console
yarn isntall
```

or using `npm`:

```console
npm install
```

## Usage

1. Build the core Picovoice SDK functionality and audio playback using `yarn`:

```console
yarn build
```

or using `npm`:

```console
npm run build
```

This builds the TypeScript files inside [src](./src), which uses `Porcupine`, `Cheetah`, `Orca` and `PicoLLM` to
build the core functionality.

2. Start the server using `yarn`:

```console
yarn start
```

or using `npm`:

```console
npm run start
```

3. Open [localhost:5000](http://localhost:5000) in your web browser.

4. Enter your AccessKey and upload your`picoLLM` model file which can be obtained from
[Picovoice Console](https://console.picovoice.ai/). Then press `Init Voice Assistant`.
