import os
import shutil
import struct
import time
import wave
from argparse import ArgumentParser
from threading import Thread

import pveagle
import pvporcupine
from pveagle import EagleProfile
from pvrecorder import PvRecorder


class FeedbackAnimation(Thread):
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
                    f'\033[2K\033[1G\r🎤 Say the wake word {frames[i % len(frames)]}',
                    end='',
                    flush=True)
                i += 1
                time.sleep(0.1)
            else:
                print(
                    f'\033[2K\033[1G\r {"✅" if self.speaker_verified else "❌"} ({self.speaker_similarity:.2f})',
                    end='',
                    flush=True)
                self.wake_word_detected = False
                self.speaker_verified = False
                self.speaker_similarity = 0.
                i = 0
                time.sleep(.5)

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
    parser.add_argument(
        '--xray_folder',
        help='')
    args = parser.parse_args()

    access_key = args.access_key
    porcupine_model_path = args.porcupine_model_path
    porcupine_keyword_path = args.porcupine_keyword_path
    porcupine_sensitivity = args.porcupine_sensitivity
    eagle_speaker_profile_path = args.eagle_speaker_profile_path
    eagle_threshold = args.eagle_threshold
    eagle_window_sec = args.eagle_window_sec
    xray_folder = args.xray_folder

    if xray_folder is not None:
        if os.path.exists(xray_folder):
            shutil.rmtree(xray_folder)
        os.makedirs(xray_folder)

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

    eagle_window_sample = int(eagle_window_sec * recorder.sample_rate)
    pcm_window = list()

    animation = FeedbackAnimation()
    animation.start()

    try:
        while True:
            pcm = recorder.read()

            pcm_window.extend(pcm)
            pcm_window = pcm_window[-eagle_window_sample:]

            wake_word_detected = porcupine.process(pcm) == 0
            if wake_word_detected:
                speaker_similarity = 0.
                for i in range(len(pcm_window) // eagle.frame_length):
                    speaker_similarity = \
                        eagle.process(pcm_window[i * eagle.frame_length:(i + 1) * eagle.frame_length])[0]
                eagle.reset()
                if xray_folder is not None:
                    index = len(os.listdir(xray_folder))

                    with wave.open(os.path.join(xray_folder, f'{index}-{speaker_similarity:.2f}.wav'), 'w') as f:
                        f.setnchannels(1)
                        f.setsampwidth(2)
                        f.setframerate(recorder.sample_rate)
                        f.writeframes(struct.pack('%dh' % len(pcm_window), *pcm_window))

                animation.wake_word_detected = True
                animation.speaker_verified = speaker_similarity > eagle_threshold
                animation.speaker_similarity = speaker_similarity
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
