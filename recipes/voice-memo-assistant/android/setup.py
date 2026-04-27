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
    ("cheetah", "4.0.1"),
    ("orca", "3.0.0"),
]

COPIES = {
    "orca": [
        ("orca_params_en_female.pv", "orca_params_en_female.pv")
    ],
    "cheetah": [
        ("cheetah_params.pv", "cheetah_params.pv")
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
        '--keyword_path',
        required=True,
        help='Absolute path to the Porcupine model file (`.ppn`).')
    parser.add_argument(
        '--context_path',
        required=True,
        help='Absolute path to the Rhino model file (`.rhn`).')
    parser.add_argument(
        '--picollm_model_path',
        required=True,
        help='Absolute path to the picoLLM model file (`.pllm`).')
    args = parser.parse_args()

    public_folder = os.path.join(
        os.path.dirname(__file__),
        "voice-memo-assistant",
        "src",
        "main",
        "assets")
    for animal, version in ANIMALS:
        major, minor = version.split('.')[:2]
        folder = clone_repo(animal, major, minor)
        model_folder = os.path.join(folder, "lib", "common")
        for src_filename, dst_filename in COPIES[animal]:
            src_path = os.path.join(model_folder, src_filename)
            dst_path = os.path.join(public_folder, dst_filename)
            shutil.copy(src_path, dst_path)

    shutil.copy(args.keyword_path, os.path.join(public_folder, "porcupine_model.ppn"))
    shutil.copy(args.context_path, os.path.join(public_folder, "rhino_model.rhn"))
    shutil.copy(args.picollm_model_path, os.path.join(public_folder, "picollm_model.pllm"))


if __name__ == '__main__':
    main()
