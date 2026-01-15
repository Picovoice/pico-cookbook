#!/usr/bin/python3

import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile
from typing import Dict, Any, Sequence

sys.path.append("..")
from generate_pv_params_file import generate_pv_params_single_lang

LANGUAGE_CODE_TO_NAME = {
    "de": "german",
    "en": "english",
    "es": "spanish",
    "fr": "french",
    "it": "italian",
    "ja": "japanese",
    "ko": "korean",
    "pt": "portuguese",
    "zh": "mandarin",
}


def generate_package(
        github_folder: str,
        porcupine_repo: str,
        rhino_repo: str,
        template_folder: str):
    for lang, lang_name in LANGUAGE_CODE_TO_NAME.items():
        for product in ["picovoice"]:
            project_name = f"{product}-arduino-{lang}"
            project_folder = os.path.join(github_folder, project_name)
            src = os.path.join(os.path.dirname(__file__), template_folder, product)
            if not os.path.isdir(project_folder):
                os.mkdir(project_folder)
            shutil.copytree(src, project_folder, dirs_exist_ok=True)
            command = f"find '{project_folder}' -type f -exec sed -i 's/{{LANGUAGE}}/{lang_name.capitalize()}/g' {{}} \\;"
            _, err = subprocess.Popen(
                command, shell=True, stderr=subprocess.PIPE, stdout=subprocess.PIPE
            ).communicate()
            if err.decode("utf-8") != "" and "error" in err.decode("utf-8"):
                raise Exception(f'Failed to generate package: {err.decode("utf-8")}')
            command = f"find '{project_folder}' -type f -exec sed -i 's/{{LANGUAGE_CODE}}/{lang.upper()}/g' {{}} \\;"
            _, err = subprocess.Popen(
                command, shell=True, stderr=subprocess.PIPE, stdout=subprocess.PIPE
            ).communicate()
            if err.decode("utf-8") != "" and "error" in err.decode("utf-8"):
                raise Exception(f'Failed to generate package: {err.decode("utf-8")}')
            command = f"mv {project_folder}/src/{product.capitalize()}_LANG.cpp {project_folder}/src/{product.capitalize()}_{lang.upper()}.cpp"
            _, err = subprocess.Popen(
                command, shell=True, stderr=subprocess.PIPE, stdout=subprocess.PIPE
            ).communicate()
            if err.decode("utf-8") != "" and "error" in err.decode("utf-8"):
                raise Exception(f'Failed to generate package: {err.decode("utf-8")}')
            command = f"mv {project_folder}/src/{product.capitalize()}_LANG.h {project_folder}/src/{product.capitalize()}_{lang.upper()}.h"
            _, err = subprocess.Popen(
                command, shell=True, stderr=subprocess.PIPE, stdout=subprocess.PIPE
            ).communicate()
            if err.decode("utf-8") != "" and "error" in err.decode("utf-8"):
                raise Exception(f'Failed to generate package: {err.decode("utf-8")}')

            keyword, model = generate_pv_params_single_lang(
                porcupine_repo,
                rhino_repo,
                lang,
                f"{project_folder}/examples/IntentBasedVoiceAssistantExample")

            command = f"find '{project_folder}' -type f -exec sed -i 's/{{LANGUAGE_KEYWORD}}/{keyword}/g' {{}} \\;"
            _, err = subprocess.Popen(
                command, shell=True, stderr=subprocess.PIPE, stdout=subprocess.PIPE
            ).communicate()
            if err.decode("utf-8") != "" and "error" in err.decode("utf-8"):
                raise Exception(f'Failed to generate package: {err.decode("utf-8")}')



def main():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--template-folder",
        help="Template folder",
        default=".templates"
    )
    parser.add_argument(
        "--github-folder",
        required=True,
        help="GitHub folder"
    )
    parser.add_argument(
        "--porcupine_repo",
        "-p",
        required=True,
        help="Path to the root of the Porcupine repository",
    )
    parser.add_argument(
        "--rhino_repo",
        "-r",
        required=True,
        help="Path to the root of the Rhino repository",
    )

    args = parser.parse_args()

    generate_package(
        github_folder=args.github_folder,
        porcupine_repo=args.porcupine_repo,
        rhino_repo=args.rhino_repo,
        template_folder=args.template_folder,
    )


if __name__ == "__main__":
    main()
