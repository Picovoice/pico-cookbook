# Retail Associate for Android

A fully on-device AI assistant for retail store managers.

## Compatibility

- Android 6.0 (SDK 23+)

## AccessKey

AccessKey is your authentication and authorization token for deploying Picovoice SDKs. Anyone who is using Picovoice
needs to have a valid AccessKey. You must keep your AccessKey secret. You would need internet connectivity to validate
your AccessKey with Picovoice license servers, even though the inference is running 100% offline. Everyone who signs up
for [Picovoice Console](https://console.picovoice.ai/) receives a unique AccessKey.

## Usage

These instructions assume your current working directory is `recipes/retail-associate/android`.

### 1. Train a Wake Word Model

1. Open [Picovoice Console](https://console.picovoice.ai/)
2. Go to Porcupine Wake Word.
3. Enter your desired wake phrase.
4. Click Train.
5. Select target platform **Android** and download the generated wake word model file (`.ppn`).

Save the downloaded file somewhere accessible on your machine. You will pass its path to the setup script with `--porcupine_keyword_path`.

### 2. Train the Speech-to-Intent Model

1. Open [Picovoice Console](https://console.picovoice.ai/)
2. Go to Rhino Speech-to-Intent.
3. Create an empty Rhino context.
4. Click Import YAML in the top-right corner.
5. Paste the [Rhino context YAML](../res/context.yml) for this demo.
6. Download the generated Rhino context file (`.rhn`) for target platform **Android**.

Save the downloaded file somewhere accessible on your machine. You will pass its path to the setup script with `--rhino_context_path`.

### 3. Run the Setup Script

Run the setup script to download the models for
- [Koala Noise Suppression](https://picovoice.ai/docs/koala/)
- [Orca Streaming Text-to-Speech](https://picovoice.ai/docs/orca/)

It will also copy the models provided for [Porcupine Wake Word](https://picovoice.ai/docs/porcupine/) and [Rhino Speech-to-Intent](https://picovoice.ai/docs/rhino/) to the assets folder.

Lastly, it will place your `AccessKey` from Picovoice Console into the `ACCESS_KEY` variable in [MainActivity.java](retail-associate/src/main/java/ai/picovoice/retailassociate/MainActivity.java).


This script will also add your wake word (`.ppn`) and rhino context (`.rhn`) files to the project:

```console
python setup.py \
    --access_key ${ACCESS_KEY} \
    --porcupine_keyword_path ${PATH_TO_PPN_FILE} \
    --rhino_context_path ${PATH_TO_RHN_FILE}
```

### 4. Run the Android Demo

1. Open the `retail-associate` project in Android Studio.
2. Connect a device or launch an Android simulator.
3. Build and run the demo.

#### Instructions

- Try the following commands:
  - start shift
  - where is John?
  - price check on Pink Butter mints
  - ask Mary to bring some Minced Garlic to aisle 12
  - code three at register 5
  - end shift

##### Full list of commands

- Where is (product)
- Are we out of (product)
- Price check on (product)
- Where is (coworker)
- Ask (coworker) to come to (location)
- Call for help at (location)
- Get next task
- start shift
- on break
- end shift
