from argparse import ArgumentParser

import pveagle
import pvporcupine
from pveagle import EagleProfile
from pvrecorder import PvRecorder


def main() -> None:
    parser = ArgumentParser()

    parser.add_argument(
        '--access_key',
        required=True,
        help='AccessKey obtained from Picovoice Console (https://console.picovoice.ai/)')
    parser.add_argument(
        '--porcupine_model_path',
        default=None,
        help='Absolute path to the Porcupine model file')
    parser.add_argument('--wake_word_path', required=True, help='Absolute path the wake word file')
    parser.add_argument('--porcupine_sensitivity', type=float, default=0.5, help='')
    parser.add_argument(
        '--speaker_profile_path',
        required=True,
        help='Absolute path to the speaker profile file')

    args = parser.parse_args()

    access_key = args.access_key
    wake_word_path = args.wake_word_path
    porcupine_model_path = args.porcupine_model_path
    porcupine_sensitivity = args.porcupine_sensitivity

    with open(args.speaker_profile_path, 'rb') as f:
        profile = EagleProfile.from_bytes(f.read())

    porcupine = pvporcupine.create(
        access_key=access_key,
        model_path=porcupine_model_path,
        keyword_paths=[wake_word_path],
        sensitivities=[porcupine_sensitivity])
    eagle = None

    recorder = None
    try:
        eagle = pveagle.create_recognizer(access_key=access_key, speaker_profiles=profile)

        recorder = PvRecorder(frame_length=eagle.frame_length)
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
    finally:
        if eagle is not None:
            eagle.delete()
        if recorder is not None:
            recorder.stop()
            recorder.delete()


if __name__ == '__main__':
    main()
