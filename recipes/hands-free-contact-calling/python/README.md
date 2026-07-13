# Hands-Free Contact Calling

Call contacts by name, resolve ambiguity, and handle follow-up clarification.

This demo shows how to combine wake word detection, speech-to-intent, and text-to-speech to create a hands-free contact
calling assistant. It can recognize commands like "call Sarah", resolve contacts from a contact list, ask clarification
questions when multiple contacts match, and return to always listening mode only after the call flow is complete. This
enables use cases like in-car calling, smart glasses, mobile assistants, accessibility tools, headset controls, and
embedded hands-free communication.

## Compatibility

- Runs on Linux (x86_64), macOS (arm64, x86_64), Windows (arm64, x86_64), and Raspberry Pi (5, 4, and 3).
- Python>=3.9

## AccessKey

AccessKey is your authentication and authorization token for deploying Picovoice SDKs. Anyone who is using Picovoice
needs to have a valid AccessKey. You must keep your AccessKey secret. You would need internet connectivity to validate
your AccessKey with Picovoice license servers, even though the inference is running 100% offline. Everyone who signs up
for [Picovoice Console](https://console.picovoice.ai/) receives a unique AccessKey.

## Usage

These instructions assume your current working directory is `recipes/hands-free-contact-calling/python`.

### 1. Create a Virtual Environment

```console
python -m venv .venv
```

### 2. Activate the Virtual Environment

On Linux, macOS, or Raspberry Pi:

```console
source .venv/bin/activate
```

On Windows:

```console
.venv\Scripts\activate
```

### 3. Install Dependencies

```console
pip install -r requirements.txt
```

### 4. Train a Wake Word Model

1. Open [Picovoice Console](https://console.picovoice.ai/)
2. Go to Porcupine Wake Word.
3. Enter your desired wake phrase.
4. Click Train.
5. Select your target platform and download the generated wake word model file (`.ppn`).

### 5. Update the Contact List (Optional)

The demo reads contacts from [`../res/contacts.csv`](../res/contacts.csv).

The contact list uses the following columns:

```csv
contact_id,first_name,last_name,nickname,company,phone_mobile,phone_work,phone_home,default_phone
```

For example:

```csv
C001,Sarah,Chen,,Dyson,6045551001,,6045551002,mobile
C002,John,Smith,Johnny,Evergreen Bank,6045551010,,6045551011,mobile
C003,Maya,Ng,May,Harbor Dental,6045551032,,,mobile
```

The demo uses the contact list to generate dynamic Rhino slot values for contact names, nicknames, and company names.

### 6. Update the Speech-to-Intent Template (Optional)

The demo uses the Rhino YAML template at [`../res/context.template`](../res/context.template).

The template contains placeholders for dynamic slot values:

```text
contact:
{{CONTACTS}}

company:
{{COMPANIES}}
```

At runtime, the demo reads `contacts.csv`, injects the generated contact and company values into `template.yml`, writes a
generated `context.yml`, and trains a Rhino context file (`context.rhn`) from the generated YAML.

You do not need to manually download a Rhino context file for this demo.

### 7. Run the Demo

```console
python main.py \
  --access_key ${ACCESS_KEY} \
  --keyword_path ${KEYWORD_PATH}
```

Where:

- `${ACCESS_KEY}` is your Picovoice AccessKey from Picovoice Console.
- `${KEYWORD_PATH}` is the path to the Porcupine wake word model file (`.ppn`).

To use a specific microphone, pass `--audio_device_index`:

```console
python main.py \
  --access_key ${ACCESS_KEY} \
  --keyword_path ${KEYWORD_PATH} \
  --audio_device_index ${AUDIO_DEVICE_INDEX}
```

### 8. Try Voice Commands

Say your wake word first, then try commands such as:

```console
call Sarah
call Sarah Chen
call Sarah on mobile
call John at work
call Maya from Harbor Dental
call Sarah from Dyson on mobile
```

If multiple contacts match, the assistant asks a follow-up question:

```console
AI: I found Sarah Chen, Sarah Khan, Sarah Farrell. Which one?
```

For follow-ups, you do not need to say the wake word again. You can respond with:

```console
Sarah Chen
```

After the call flow completes, the assistant returns to listening for the wake word.

### 9. View All Options

```console
python main.py --help
```

To list available audio input devices:

```console
python main.py --show_audio_devices
```
