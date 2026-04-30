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

DEMO = "call-screen"

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
        '--access_key',
        required=True,
        help='`AccessKey` obtained from `Picovoice Console` (https://console.picovoice.ai/).')
    parser.add_argument(
        '--name',
        required=True,
        help='Name of the user having their calls screened.')
    parser.add_argument(
        '--context_path',
        required=True,
        help='Absolute path to the Rhino model file (`.rhn`).')
    args = parser.parse_args()

    public_folder = os.path.join(
        os.path.dirname(__file__),
        DEMO,
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

    shutil.copy(args.context_path, os.path.join(public_folder, "call_screen_demo_android.rhn"))

    main_activity_path = os.path.join(
        os.path.dirname(__file__),
        DEMO,
        "src",
        "main",
        f"java/ai/picovoice/{DEMO.replace("-", "")}",
        "MainActivity.java")
    with open(main_activity_path, 'r') as main_activity_file:
        main_activity_contents = main_activity_file.read()
    main_activity_contents = main_activity_contents
        .replace("${YOUR_ACCESS_KEY_HERE}", args.access_key)
        .replace("${YOUR_NAME_HERE}", args.name)
    with open(main_activity_path, 'w') as main_activity_file:
        main_activity_file.write(main_activity_contents)


if __name__ == '__main__':
    main()