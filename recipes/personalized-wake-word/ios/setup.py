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

DEMO = "PersonalizedWakeWord"


def animate_status(text: str) -> Tuple[Event, Thread]:
    stop_event = Event()

    def worker() -> None:
        frames = [" .  ", " .. ", " ...", "  ..", "   .", "    "]
        i = 0
        while not stop_event.is_set():
            sys.stdout.write(f'\r{text}{frames[i % len(frames)]}')
            sys.stdout.flush()
            i += 1
            time.sleep(0.1)

    thread = Thread(target=worker, daemon=True)
    thread.start()
    return stop_event, thread


def clone_repo(animal: str, major: str, minor: str) -> str:
    folder = os.path.join(os.path.dirname(__file__), animal)
    if os.path.exists(folder):
        shutil.rmtree(folder)

    stop_event, thread = animate_status(f"Cloning {animal.capitalize()}'s GitHub repository")

    try:
        subprocess.run(
            [
                "git",
                "clone",
                "--branch",
                f"v{major}.{minor}",
                "--depth",
                "1",
                "--filter=blob:none",
                "--sparse",
                f"https://github.com/Picovoice/{animal}.git",
                folder,
            ],
            check=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )

        subprocess.run(
            [
                "git",
                "-C",
                folder,
                "sparse-checkout",
                "set",
                "lib/common",
            ],
            check=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
    finally:
        stop_event.set()
        thread.join()

    sys.stdout.write(f"\rCloned {animal.capitalize()}'s GitHub repository.{' ' * 20}\n")
    sys.stdout.flush()

    return folder


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
