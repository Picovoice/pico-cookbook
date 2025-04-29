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
        help="Absolute path to the Porcupine's model file (.pv)")
    parser.add_argument(
        '--porcupine_keyword_path',
        required=True,
        help="Absolute path to the Porcupine's keyword file (.ppn)")
    parser.add_argument(
        '--porcupine_sensitivity',
        type=float,
        default=0.5,
        help="Porcupine's detection sensitivity [0.0-1.0]")
    parser.add_argument(
        '--eagle_speaker_profile_path',
        required=True,
        help="Absolute path to the Eagle's speaker profile file")
    parser.add_argument(
        '--eagle_threshold',
        type=float,
        default=0.9,
        help="Eagle's recognition threshold [0.0-1.0]")
    args = parser.parse_args()

    access_key = args.access_key
    porcupine_model_path = args.porcupine_model_path
    porcupine_keyword_path = args.porcupine_keyword_path
    porcupine_sensitivity = args.porcupine_sensitivity
    eagle_speaker_profile_path = args.eagle_speaker_profile_path
    eagle_threshold = args.eagle_threshold

    with open(eagle_speaker_profile_path, 'rb') as f:
        speaker_profile = EagleProfile.from_bytes(f.read())

    porcupine = pvporcupine.create(
        access_key=access_key,
        model_path=porcupine_model_path,
        keyword_paths=[porcupine_keyword_path],
        sensitivities=[porcupine_sensitivity])
    eagle = pveagle.create_recognizer(
        access_key=access_key,
        speaker_profiles=speaker_profile)

    recorder = PvRecorder(frame_length=eagle.frame_length)
    recorder.start()
    print('Listening ... (press Ctrl+C to stop)')

    try:
        while True:
            pcm = recorder.read()
            wake_word_detected = porcupine.process(pcm) == 0
            score = eagle.process(pcm)[0]
            if wake_word_detected:
                print(score)
                print(eagle_threshold)
    except KeyboardInterrupt:
        print('\nStopping...')
    finally:
        recorder.stop()
        recorder.delete()
        eagle.delete()
        porcupine.delete()


if __name__ == '__main__':
    main()
