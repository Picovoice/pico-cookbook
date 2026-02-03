# Porcupine Wake Word Engine + Rhino Speech-To-Intent Engine Demo for Arduino ({LANGUAGE} language)

This package contains a demo project with examples for Arduino using Porcupine Wake Word Engine in conjunction with Rhino Speech-to-Intent Engine.

## Compatibility

- [Arduino Nano 33 BLE Sense](https://docs.arduino.cc/hardware/nano-33-ble)

## AccessKey

The Porcupine and Rhino SDKs require a valid `AccessKey` at initialization. `AccessKey`s act as your credentials when using Porcupine and Rhino SDKs.
You can get your `AccessKey` for free. Make sure to keep your `AccessKey` secret.
Signup or Login to [Picovoice Console](https://console.picovoice.ai/) to get your `AccessKey`.

## Installation

1. Install the [Arduino IDE](https://www.arduino.cc/en/software/) for your platform.
2. With the IDE open, go to `Tools`->`Manage Libraries...`
3. Search for `Picovoice_{LANGUAGE_CODE}`, then click `INSTALL`.

This demo package is intended to be used via the Arduino Library Manager.

## Demo

The package contains only the Intent-Based Voice Assistant demo. To access the demo:

1. Open `File`->`Examples`->`Picovoice_{LANGUAGE_CODE}`->`IntentBasedVoiceAssistantExample`.
2. Replace `ACCESS_KEY` in the source with the `AccessKey` obtained from Picovoice Console.
3. Press `Upload` and check the `Serial Monitor` for outputs.

Additional information can be found in the Picovoice docs for [Porcupine](https://picovoice.ai/docs/quick-start/porcupine-arduino/) and [Rhino](https://picovoice.ai/docs/quick-start/rhino-arduino/).

## Create Custom Wake Word

1. Compile and upload the `Porcupine_{LANGUAGE_CODE}/GetUUID` sketch from the `File -> Examples` menu. Copy the UUID of the board printed at the beginning of the session to the serial monitor.
2. Go to [Picovoice Console](https://console.picovoice.ai/) to create models for [Porcupine wake word engine](https://picovoice.ai/docs/quick-start/console-porcupine/).
3. Select `Arm Cortex M` as the platform when training the model.
4. Select your board type (`Arduino Nano 33 BLE Sense`) and provide the UUID of the chipset on the board.

The model is now being trained. You will be able to download it within a few hours.

## Import the Custom Wake Word

1. Download your custom voice model(s) from [Picovoice Console](https://console.picovoice.ai/).
2. Decompress the zip file. The model for Porcupine wake word is located in two files: A binary `.ppn` file, and as a `.h` header file containing a `C` array version of the binary model.
3. Copy the contents of the array inside the `.h` header file and update update the `DEFAULT_KEYWORD_ARRAY` values in `pv_params.h`.

## Create Custom Context

1. Compile and upload the `Rhino_{LANGUAGE_CODE}/GetUUID` sketch from the `File -> Examples` menu. Copy the UUID of the board printed at the beginning of the session to the serial monitor.
2. Go to [Picovoice Console](https://console.picovoice.ai/) to create a context for [Rhino speech to intent engine](https://picovoice.ai/docs/quick-start/console-rhino/).
3. Select `Arm Cortex M` as the platform when training the model.
4. Select your board type (`Arduino Nano 33 BLE Sense`) and provide the UUID of the chipset on the board.

## Import the Custom Context

1. Download your custom voice model(s) from [Picovoice Console](https://console.picovoice.ai/).
2. Decompress the zip file. The model for Rhino speech to intent is located in two files: A binary `.rhn` file, and as a `.h` header file containing a `C` array version of the binary model.
3. Copy the contents of the array inside the `.h` header file and update the `CONTEXT_ARRAY` values in `pv_params.h`.
