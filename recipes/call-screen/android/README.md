# Call Screen on Android

Screen unknown calls with real-time transcription and natural voice responses, powered by on-device AI.

## Compatibility

- Android 5.0 (SDK 21+)

## AccessKey

AccessKey is your authentication and authorization token for deploying Picovoice SDKs. Anyone who is using Picovoice
needs to have a valid AccessKey. You must keep your AccessKey secret. You would need internet connectivity to validate
your AccessKey with Picovoice license servers, even though the inference is running 100% offline. Everyone who signs up
for [Picovoice Console](https://console.picovoice.ai/) receives a unique AccessKey.

## Usage

### 1. Configure the demo

1. Open the `CallScreen` project in Android Studio. 
2. Copy your `AccessKey` from Picovoice Console into the `ACCESS_KEY` variable in [MainActivity.java](call-screen/src/main/java/ai/picovoice/callscreen/MainActivity.java).
3. Type your name in the `USERNAME` variable in [MainActivity.java](call-screen/src/main/java/ai/picovoice/callscreen/MainActivity.java).

### 2. Train the Speech-to-Intent Model

1. Open [Picovoice Console](https://console.picovoice.ai/)
2. Go to Rhino Speech-to-Intent.
3. Create an empty Rhino context.
4. Click Import YAML in the top-right corner.
5. Paste the [Rhino context YAML](../res/context.yml) for this demo.
6. Select the **Android** platform and download the generated Rhino context file (`.rhn`).
7. Copy the downloaded Rhino context file into [call-screen/src/main/assets](call-screen/src/main/assets) and rename it to `call_screen_demo_android.rhn`.

### 3. Run the Android Studio project

1. Connect a device or launch an Android simulator.
2. Build and run the demo.
3. Tap the `Start Demo` button in the demo.
