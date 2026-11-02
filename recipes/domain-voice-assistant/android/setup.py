import os
import shutil
from argparse import ArgumentParser


def main() -> None:
    parser = ArgumentParser()
    parser.add_argument(
        '--access_key',
        required=True,
        help='`AccessKey` obtained from `Picovoice Console` (https://console.picovoice.ai/).')
    parser.add_argument(
        '--porcupine_keyword_path',
        required=True,
        help='Absolute path to the wake word model file (`.ppn`).')
    parser.add_argument(
        '--picollm_model_path',
        required=True,
        help='Absolute path to the picoLLM model file (`.pllm`).')
    args = parser.parse_args()

    assets_folder = os.path.join(
        os.path.dirname(__file__),
        "domain-voice-assistant",
        "src",
        "main",
        "assets")

    shutil.copy(args.porcupine_keyword_path, os.path.join(assets_folder, "domain_voice_assistant_android.ppn"))
    models_folder = os.path.join(os.path.dirname(__file__), "..", "res", "models")
    for filename in ("cheetah_params.pv", "orca_params.pv"):
        shutil.copy(os.path.join(models_folder, filename), os.path.join(assets_folder, filename))
    shutil.copy(args.picollm_model_path, os.path.join(assets_folder, "picollm_model.pllm"))

    main_activity_path = os.path.join(
        os.path.dirname(__file__),
        "domain-voice-assistant",
        "src",
        "main",
        "java/ai/picovoice/domainvoiceassistant",
        "MainActivity.java")
    with open(main_activity_path, 'r') as main_activity_file:
        main_activity_contents = main_activity_file.read()
    main_activity_contents = main_activity_contents.replace("${YOUR_ACCESS_KEY_HERE}", args.access_key)
    with open(main_activity_path, 'w') as main_activity_file:
        main_activity_file.write(main_activity_contents)


if __name__ == '__main__':
    main()
