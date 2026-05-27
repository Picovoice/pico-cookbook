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
    ("cheetah", "4.0.0"),
    ("koala", "3.0.0"),
    ("orca", "3.0.0"),
    ("porcupine", "4.0.0"),
    ("rhino", "4.0.0"),
]

COPIES = {
    "cheetah": [
        ("cheetah_params.pv", "cheetah_params.pv")
    ],
    "koala": [
        ("koala_params.pv", "koala_params.pv")
    ],
    "orca": [
        ("orca_params_en_female.pv", "orca_params_en_female.pv")
    ],
    "rhino": [
        ("rhino_params.pv", "rhino_params.pv")
    ],
    "porcupine": [
        ("porcupine_params.pv", "porcupine_params.pv")
    ]
}


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
        '--rhino_context_path',
        required=True,
        help='Absolute path to the Rhino context file (`.rhn`).')
    parser.add_argument(
        '--porcupine_keyword_path',
        required=True,
        help='Absolute path to the wake word model file (`.ppn`).')
    args = parser.parse_args()

    models_folder = os.path.join(os.path.dirname(__file__), "public", "models")

    context_target_path = os.path.join(models_folder, "voice_picking_web.rhn")
    shutil.copy(args.rhino_context_path, context_target_path)
    print(f"Copied {args.rhino_context_path} to {context_target_path}")

    keywords_folder = os.path.join(os.path.dirname(__file__), "public", "keywords")

    wakeword_target_path = os.path.join(keywords_folder, "voice_picking_web.ppn")
    shutil.copy(args.porcupine_keyword_path, wakeword_target_path)
    print(f"Copied {args.porcupine_keyword_path} to {wakeword_target_path}")

    for animal, version in ANIMALS:
        major, minor = version.split('.')[:2]
        folder = clone_repo(animal, major, minor)
        model_folder = os.path.join(folder, "lib", "common")
        for src_filename, dst_filename in COPIES[animal]:
            src_path = os.path.join(model_folder, src_filename)
            dst_path = os.path.join(models_folder, dst_filename)
            shutil.copy(src_path, dst_path)


if __name__ == '__main__':
    main()
