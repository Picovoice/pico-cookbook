import time
from argparse import ArgumentParser
from threading import Thread

import pvporcupine
from pveagle import (
    EagleProfile,
    create_recognizer
)
from pvrecorder import PvRecorder


class Animation(Thread):
    def __init__(self):
        super().__init__()

        self._stop: bool = False
        self.wake_word_detected: bool = False
        self.speaker_verified: bool = False
        self.speaker_similarity: float = 0.

    def run(self):
        frames = [" .  ", " .. ", " ...", "  ..", "   .", "    "]
        i = 0

        while not self._stop:
            if self._stop:
                break

            if not self.wake_word_detected:
                print(
                    f'\033[2K\033[1G\rSay the wake word {frames[i % len(frames)]}',
                    end='',
                    flush=True)
                i += 1
                time.sleep(0.1)
            else:
                print(
                    f'\033[2K\033[1G\r {"Y" if self.speaker_verified else "N"} ({self.speaker_similarity:.2f})',
                    end='',
                    flush=True)
                self.wake_word_detected = False
                self.speaker_verified = False
                self.speaker_similarity = 0.
                i = 0
                time.sleep(1.)

        self._stop = False

    def stop(self):
        self._stop = True
        while self._stop:
            pass
        print('\033[2K\033[1G\r', end='', flush=True)


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
        default=0.75,
        help="Eagle's recognition threshold [0.0-1.0]")
    parser.add_argument(
        '--eagle_window_sec',
        type=float,
        default=2.,
        help="The length of audio window Eagle looks back after wake word detection.")
    args = parser.parse_args()

    access_key = args.access_key
    porcupine_model_path = args.porcupine_model_path
    porcupine_keyword_path = args.porcupine_keyword_path
    porcupine_sensitivity = args.porcupine_sensitivity
    eagle_speaker_profile_path = args.eagle_speaker_profile_path
    eagle_threshold = args.eagle_threshold
    eagle_window_sec = args.eagle_window_sec

    with open(eagle_speaker_profile_path, "rb") as f:
        speaker_profile = EagleProfile.from_bytes(f.read())

    porcupine = pvporcupine.create(
        access_key=access_key,
        model_path=porcupine_model_path,
        keyword_paths=[porcupine_keyword_path],
        sensitivities=[porcupine_sensitivity])
    print(f"[OK] Porcupine Wake Word[V{porcupine.version}]")

    eagle = create_recognizer(access_key=access_key)
    print(f"[OK] Eagle Speaker Recognition[V{eagle.version}]")

    recorder = PvRecorder(frame_length=porcupine.frame_length)
    recorder.start()

    eagle_window_sample = max(int(eagle_window_sec * recorder.sample_rate), eagle.min_process_samples)
    pcm_window = list()

    animation = Animation()
    animation.start()

    try:
        while True:
            pcm = recorder.read()

            pcm_window.extend(pcm)
            pcm_window = pcm_window[-eagle_window_sample:]

            wake_word_detected = porcupine.process(pcm) == 0
            if wake_word_detected:
                speaker_similarity = eagle.process(pcm_window, speaker_profiles=[speaker_profile])

                animation.wake_word_detected = True
                animation.speaker_verified = speaker_similarity is not None and speaker_similarity[0] > eagle_threshold
                animation.speaker_similarity = speaker_similarity[0] if speaker_similarity is not None else 0
    except KeyboardInterrupt:
        pass
    finally:
        animation.stop()
        recorder.stop()
        recorder.delete()
        eagle.delete()
        porcupine.delete()


if __name__ == '__main__':
    main()
