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


ANIMALS = [
    ("porcupine", "4.0.0"),
    ("eagle", "3.0.0"),
]


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
        '--porcupine_keyword_path',
        required=True,
        help='Absolute path to the wake word model file (`.ppn`).')
    args = parser.parse_args()

    public_folder = os.path.join(os.path.dirname(__file__), "public")
    for animal, version in ANIMALS:
        major, minor = version.split('.')[:2]
        folder = clone_repo(animal, major, minor)
        model_folder = os.path.join(folder, "lib", "common")
        filename = f"{animal}_params.pv"
        src_path = os.path.join(model_folder, filename)
        dst_path = os.path.join(public_folder, "models", filename)
        shutil.copy(src_path, dst_path)

    shutil.copy(args.porcupine_keyword_path, os.path.join(public_folder, "keywords", "keyword.ppn"))


if __name__ == '__main__':
    main()
