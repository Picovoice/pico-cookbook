from argparse import ArgumentParser

from pveagle import (
    EagleActivationLimitError,
    EagleProfile,
    create_recognizer,
)
from pvporcupine import create as create_porcupine
from pvrecorder import PvRecorder


def main() -> None:
    parser = ArgumentParser()
    parser.add_argument(
        '--show_audio_devices',
        action='store_true',
        help='List available input audio devices and exit')

    parser.add_argument(
        '--access_key',
        required=True,
        help='AccessKey obtained from Picovoice Console (https://console.picovoice.ai/)')
    parser.add_argument('--wake_word_path', required=True, help='Absolute path the wake word file')
    parser.add_argument(
        '--porcupine_model_path',
        default=None,
        help='Absolute path to the Porcupine model file')
    parser.add_argument('--audio_device_index', type=int, default=-1, help='Index of input audio device')
    parser.add_argument('--porcupine_sensitivity', type=float, default=0.5, help='')

    parser.add_argument(
        '--speaker_profile_path',
        required=True,
        help='Absolute path to the speaker profile file')

    args = parser.parse_args()

    access_key = args.access_key
    wake_word_path = args.wake_word_path
    porcupine_model_path = args.porcupine_model_path
    audio_device_index = args.audio_device_index
    porcupine_sensitivity = args.porcupine_sensitivity

    if args.show_audio_devices:
        for index, name in enumerate(PvRecorder.get_available_devices()):
            print(f'Device #{index}: {name}')
        return

    with open(args.speaker_profile_path, 'rb') as f:
        profile = EagleProfile.from_bytes(f.read())

    porcupine = create_porcupine(
        access_key=access_key,
        model_path=porcupine_model_path,
        keyword_paths=[wake_word_path],
        sensitivities=[porcupine_sensitivity])
    eagle = None

    recorder = None
    try:
        eagle = create_recognizer(
            access_key=access_key,
            speaker_profiles=profile)

        recorder = PvRecorder(device_index=audio_device_index, frame_length=eagle.frame_length)
        recorder.start()

        print('Listening for audio... (press Ctrl+C to stop)')
        while True:
            pcm = recorder.read()
            is_detected = porcupine.process(pcm) == 0
            score = eagle.process(pcm)[0]
            if is_detected:
                print(score)


    except KeyboardInterrupt:
        print('\nStopping...')
    except EagleActivationLimitError:
        print('\nAccessKey has reached its processing limit')
    finally:
        if eagle is not None:
            eagle.delete()
        if recorder is not None:
            recorder.stop()
            recorder.delete()


if __name__ == '__main__':
    main()
