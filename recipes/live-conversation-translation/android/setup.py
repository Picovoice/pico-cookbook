import os
import shutil
import subprocess
import sys
import time
from threading import (
    Event,
    Thread
)
from typing import Tuple


ANIMALS = [
    ("cheetah", "4.0.0"),
    ("orca", "3.0.0"),
    ("zebra", "1.0.0"),
]

RENAMES = [
    ("cheetah_params.pv", "cheetah_params_en.pv")
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
    public_folder = os.path.join(
        os.path.dirname(__file__),
        "live-conversation-translation",
        "src",
        "main",
        "assets")
    for animal, version in ANIMALS:
        major, minor = version.split('.')[:2]
        folder = clone_repo(animal, major, minor)
        model_folder = os.path.join(folder, "lib", "common")
        for filename in os.listdir(model_folder):
            src_path = os.path.join(model_folder, filename)
            dst_path = os.path.join(public_folder, filename)
            shutil.copy(src_path, dst_path)

    for old, new in RENAMES:
        src_path = os.path.join(public_folder, old)
        dst_path = os.path.join(public_folder, new)
        if os.path.exists(src_path):
            os.rename(src_path, dst_path)


if __name__ == '__main__':
    main()
