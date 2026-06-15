# Microcontroller Voice Assistant MCU Arduino

The folder contains the templated demo for Arduino.
Please refer to the packaged arduino examples:

- [picovoice-arduino-de](https://github.com/Picovoice/picovoice-arduino-de)
- [picovoice-arduino-en](https://github.com/Picovoice/picovoice-arduino-en)
- [picovoice-arduino-es](https://github.com/Picovoice/picovoice-arduino-es)
- [picovoice-arduino-fr](https://github.com/Picovoice/picovoice-arduino-fr)
- [picovoice-arduino-it](https://github.com/Picovoice/picovoice-arduino-it)
- [picovoice-arduino-ja](https://github.com/Picovoice/picovoice-arduino-ja)
- [picovoice-arduino-ko](https://github.com/Picovoice/picovoice-arduino-ko)
- [picovoice-arduino-pt](https://github.com/Picovoice/picovoice-arduino-pt)
- [picovoice-arduino-zh](https://github.com/Picovoice/picovoice-arduino-zh)

## Building the packages

### 1. Clone porcupine and Rhino

```
git clone https://github.com/Picovoice/porcupine.git
git clone https://github.com/Picovoice/rhino.git
```

### 2. Expand templates

After updating templates in the `.templates` folder, execute the following command to expand the template and build Arduino packages:

```bash
python3 setup.py --github-folder ${GITHUB_FOLDER} --porcupine_repo ${PORCUPINE_REPO_FOLDER} --rhino_repo ${RHINO_REPO_FOLDER}
```

- `{GITHUB_FOLDER}` point this to the `recipes/microcontroller-voice-assistant/mcu/arduino` dir.
- `{PORCUPINE_REPO_FOLDER}` point this to the root of the porcupine repo you cloned.
- `{RHINO_REPO_FOLDER}` point this to the root of the rhino repo you cloned.
