import os
import shutil
import subprocess

ANIMALS = [
    ("cheetah", "4.0.0"),
    ("orca", "3.0.0"),
    ("zebra", "1.0.0"),
]

RENAMES = [
    ("cheetah_params.pv", "cheetah_params_en.pv")
]

def clone_repo(animal: str, major: str, minor: str) -> str:
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
            folder,
        ],
        check=True,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )

    return folder


def main() -> None:
    public_folder = os.path.join(os.path.dirname(__file__), "public", "models")
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
