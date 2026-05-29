import os
import shutil
import subprocess
import sys
import time
from argparse import ArgumentParser
from threading import (
    Event,
    Thread
)
from typing import Tuple

DEMO = "SpeakerAwareWakeWord"


def main() -> None:
    parser = ArgumentParser()
    parser.add_argument(
        '--access_key',
        required=True,
        help='`AccessKey` obtained from `Picovoice Console` (https://console.picovoice.ai/).')
    parser.add_argument(
        '--keyword_path',
        required=True,
        help='Absolute path to the Porcupine model file (`.ppn`).')
    args = parser.parse_args()

    shutil.copy(args.keyword_path, os.path.join(os.path.dirname(__file__), "porcupine_model.ppn"))

    view_model_path = os.path.join(os.path.dirname(__file__), DEMO, "ViewModel.swift")
    with open(view_model_path, 'r') as file:
        contents = file.read()
    contents = contents.replace("${YOUR_ACCESS_KEY_HERE}", args.access_key)
    with open(view_model_path, 'w') as file:
        file.write(contents)


if __name__ == '__main__':
    main()
