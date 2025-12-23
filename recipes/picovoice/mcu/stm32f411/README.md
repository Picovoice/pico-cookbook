# Porcupine Wake Word Engine + Rhino Speech-To-Intent Engine Demo for STM32F411 (Multiple languages)

This package contains a demo project for the STM32F411 Discovery kit using Porcupine wake word engine in conjuction with Rhino speech to intent Engine.

## Supported Languages

1. English
2. Chinese (Mandarin)
3. French
4. German
5. Italian
6. Japanese
7. Korean
8. Portuguese
9. Spanish

- Support for additional languages is available for commercial customers on a case-by-case basis.

## Installation

For this demo, you need to:
<!-- markdown-link-check-disable -->
1. Download and install [STM32CubeIDE](https://www.st.com/en/development-tools/stm32cubeide.html), which is an
   all-in-one multi-OS development tool for STM32 microcontrollers.
2. Download [STM32Cube MCU Package for STM32F4 series](https://www.st.com/en/embedded-software/stm32cubef4.html) and
   extract it somewhere on your computer.
<!-- markdown-link-check-enable -->
3. Clone the [Porcupine](https://github.com/Picovoice/porcupine) and [Rhino](https://github.com/Picovoice/rhino) repositories.
4. Run the [`copy_resources.py`](../copy_resources.py) Python script passing in the paths to the cloned Porcupine / Rhino repositories:
```
python copy_resources.py --porcupine_repo {PATH_TO_PORCUPINE_REPO} --rhino_repo {PATH_TO_RHINO_REPO}
```

## AccessKey

Porcupine / Rhino require a valid Picovoice `AccessKey` at initialization. `AccessKey` acts as your credentials when using
Porcupine / Rhino SDKs.
You can get your `AccessKey` for free. Make sure to keep your `AccessKey` secret.
Signup or Login to [Picovoice Console](https://console.picovoice.ai/) to get your `AccessKey`.

## Usage

In the demo project, there is a separate build configuration for each supported languages. In order to activate a
specific configuration:

1. Click `Project` > `Build Configuration` > `Set Active`
2. Select the target configuration

Then, to compile and run the demo project on a STM32f411 discovery board, perform the following steps:

1. Open STM32CubeIDE
2. Click `File` > `Open Projects from file system...` to display the `Import Projects` dialog box. Select
   the [stm32f469i-disco](./stm32f411e-disco) folder from this repository, and then press the `Finish` button.
3. Go to the folder where you extracted `STM32Cube MCU Package for STM32F4 series`, and then copy the contents of
   the `/Middlewares/ST/STM32_Audio/Addons/PDM` folder
   to [/Middlewares/ST/STM32_Audio/Addons/PDM](./stm32f411e-disco/Middlewares/ST/STM32_Audio/Addons/PDM).
4. Select the `stm32f411e-disco-demo` project inside the `Project Explorer` window
5. Replace `ACCESS_KEY` in `main.c` with your AccessKey obtained
   from [Picovoice Console](https://console.picovoice.ai/)
6. Click `Project` > `Build Project`
7. Connect the board to the computer and press `Run` > `Run`
<!-- markdown-link-check-disable -->
> :warning: `printf()` uses the SWO connector and the trace port 0. For more information, refer
>
to [STM32 microcontroller debug toolbox](https://www.st.com/resource/en/application_note/dm00354244-stm32-microcontroller-debug-toolbox-stmicroelectronics.pdf)
> , Chapter 7.
<!-- markdown-link-check-enable -->
You can identify the default keyword for each language by referring to the [pv_params.h](./stm32f411e-disco/Inc/pv_params.h) file. Within this file, locate the language section enclosed by:

```c
#if defined(__PV_LANGUAGE_{LANGUAGE_NAME}__)
...
#endif
```

The default keyword for each language will be listed next to the `// wake-word` comment.

The default context for each language will be listed next to the `// context` comment.

## Create Custom Wake Word

1. Copy the UUID of the board printed at the beginning of the session to the serial port monitor.
2. Go to [Picovoice Console](https://console.picovoice.ai/) to create a model
   for [Porcupine wake word engine](https://picovoice.ai/docs/quick-start/console-porcupine/).
3. Select `Arm Cortex-M` as the platform when training the model.
4. Select `STM32` as the board type and provide the UUID of the chipset on the board.

The model is now being trained. You will be able to download it within a few hours.

## Import the Custom Wake Word

1. Download your custom voice model(s) from [Picovoice Console](https://console.picovoice.ai/).
2. Decompress the zip file. The model for Porcupine wake word is located in two files: A binary `.ppn` file, and as
   a `.h` header file containing a `C` array version of the binary model.
3. Copy the contents of the array inside the `.h` header file and update the `DEFAULT_KEYWORD_ARRAY` value
   in [/stm32f411e-disco/Inc/pv_params.h](./stm32f411e-disco/Inc/pv_params.h) in the language section for which the
   model is trained.

## Create Custom Context

1. Copy the UUID of the board printed at the beginning of the session to the serial port monitor.
2. Go to [Picovoice Console](https://console.picovoice.ai/) to create a context
   for [Rhino speech to intent engine](https://picovoice.ai/docs/quick-start/console-rhino/).
3. Select `Arm Cortex-M` as the platform when training the model.
4. Select `STM32` as the board type and provide the UUID of the chipset on the board.

The model is now being trained. You will be able to download it within a few hours.

## Import the Custom Context

1. Download your custom voice model(s) from [Picovoice Console](https://console.picovoice.ai/).
2. Decompress the zip file. The model for Rhino speech to intent is located in two files: A binary `.rhn` file, and as
   a `.h` header file containing a `C` array version of the binary model.
3. Copy the contents of the array inside the `.h` header file and update the `CONTEXT_ARRAY` value
   in [/stm32f411e-disco/Inc/pv_params.h](./stm32f411e-disco/Inc/pv_params.h) in the language section for which the
   model is trained.