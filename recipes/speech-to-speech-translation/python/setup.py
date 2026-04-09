import os
import shutil
import subprocess


def clone_repo(animal: str, major: str, minor: str) -> None:
    folder = os.path.join(os.path.dirname(__file__), animal)
    if os.path.exists(folder):
        shutil.rmtree(folder)

    subprocess.run(
        [
            "git",
            "clone",
            "--branch",
            f"v{major}.{minor}",
            "--depth",
            "1",
            f"https://github.com/Picovoice/{animal}.git",
            os.path.join(os.path.dirname(__file__), animal),
        ],
        check=True,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )


def main() -> None:
    with open(os.path.join(os.path.dirname(__file__), 'requirements.txt')) as f:
        for entry in f.read().strip(' \n').split('\n'):
            if any(x in entry for x in {'cheetah', 'orca', 'zebra'}):
                package, version = entry.split('~=')
                animal = package[2:]
                major, minor = version.split('.')[:2]
                clone_repo(animal, major, minor)


if __name__ == '__main__':
    main()
