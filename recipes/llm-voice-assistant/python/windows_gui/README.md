# Windows LLM Voice Assistant GUI Demo

A voice assistant for Windows using Picovoice's Wake Word, STT, TTS and LLM technology with a console-based graphical interface.

## Compatibility

- Python 3.8+
- Runs on Windows (x86_64).

## AccessKey

AccessKey is your authentication and authorization token for deploying Picovoice SDKs, including picoLLM. Anyone who is
using Picovoice needs to have a valid AccessKey. You must keep your AccessKey secret. You would need internet
connectivity to validate your AccessKey with Picovoice license servers even though the LLM inference is running 100%
offline and completely free for open-weight models. Everyone who signs up for
[Picovoice Console](https://console.picovoice.ai/) receives a unique AccessKey.

## picoLLM Model

picoLLM Inference Engine supports many open-weight models. The models are on
[Picovoice Console](https://console.picovoice.ai/).

## Usage

Install the required packages:

```console
pip install -r requirements.txt
```

Run the demo:

```console
python3 main.py --access_key ${ACCESS_KEY} --picollm_model_path ${PICOLLM_MODEL_PATH} 
```

Replace `${ACCESS_KEY}` with yours obtained from Picovoice Console and `${PICOLLM_MODEL_PATH}` with the path to the 
model downloaded from Picovoice Console.

To see all available options, type the following:

```console
python main.py --help
```

## Config File

In addition to command line arguments a config file can be used to pass arguments to the demo. By default the demo looks for `config.json` in the same directory as `main.py` but an alternative path can be passed using the `--config` option. Below is an example config file.

```json
{
    "access_key": "${ACCESS_KEY}",
    "picollm_model_path": "${PICOLLM_MODEL_PATH}"
}
```

## Custom Wake Word

The demo's default wake phrase is `Jarvis`. You can generate your custom (branded) wake word using Picovoice  Console by following [Porcupine Wake Word documentation (https://picovoice.ai/docs/porcupine/). Once you have the model trained, simply pass it to the demo
application using `--keyword_model_path` argument.