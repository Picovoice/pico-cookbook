# Personalized Wake Word in Android

Personalized wake word detection that activates only for the enrolled speaker, powered by on-device voice AI.

## Compatibility

- Android 6.0 (SDK 23+)

## AccessKey

AccessKey is your authentication and authorization token for deploying Picovoice SDKs. Anyone who is using Picovoice
needs to have a valid AccessKey. You must keep your AccessKey secret. You would need internet connectivity to validate
your AccessKey with Picovoice license servers, even though the inference is running 100% offline. Everyone who signs up
for [Picovoice Console](https://console.picovoice.ai/) receives a unique AccessKey.

## Usage

These instructions assume your current working directory is `recipes/personalized-wake-word/python`.

### 1. Train a Wake Word Model

Follow instruction on Porcupine Wake Word [documentation](https://picovoice.ai/docs/porcupine/#custom-wake-words) to
train a wake word model on [Picovoice Console](https://console.picovoice.ai/) in minutes. Ensure you train the wake word for the Android platform.

### 2. Add Wake Word to the Project

Copy your downloaded wake word file into the assets folder, located at `${ANDROID_PROJECT}/src/main/assets`.

### 3. Add Wake Word and AccessKey to MainActivity.java

Open the project in Android Studio and open `MainActivity.java`. Change the value of the `ACCESS_KEY` variable to your Picovoice AccessKey and the value of `WAKE_WORD_FILE` to the name of the file you copied into the assets folder.

### 4. Run the Demo with Android Studio

With an Android device connected or an Android simulator running, build and run the demo using Android Studio.

