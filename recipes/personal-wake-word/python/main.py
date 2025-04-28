from argparse import ArgumentParser

from pvrecorder import PvRecorder


def main() -> None:
    parser = ArgumentParser()
    parser.add_argument(
        '--show_audio_devices',
        action='store_true',
        help='List available audio input devices and exit')

    common_parser = ArgumentParser(add_help=False)
    common_parser.add_argument(
        '--access_key',
        required=True,
        help='AccessKey obtained from Picovoice Console (https://console.picovoice.ai/)')
    common_parser.add_argument('--wake_word_path', required=True, help='Absolute path the wake word file')
    common_parser.add_argument('--audio_device_index', type=int, default=-1, help='Index of input audio device')

    subparsers = parser.add_subparsers(dest='command')

    enroll_parser = subparsers.add_parser('enroll', help='Create a new speaker profile', parents=[common_parser])
    enroll_parser.add_argument(
        '--speaker_profile_path',
        required=True,
        help='Absolute path to newly enrolled speaker profile file')

    detection_parser = subparsers.add_parser(
        'listen',
        help='Detect utterances of the wake word spoken by a given speaker profile.',
        parents=[common_parser])
    detection_parser.add_argument(
        '--speaker_profile_path',
        required=True,
        help='Absolute path to the speaker profile file')

    args = parser.parse_args()

    if args.show_audio_devices:
        for index, name in enumerate(PvRecorder.get_available_devices()):
            print(f'Device #{index}: {name}')
        return


if __name__ == '__main__':
    main()
