import time
from argparse import ArgumentParser
from threading import Thread

import pveagle
import pvporcupine
from pveagle import EagleProfilerEnrollFeedback
from pvrecorder import PvRecorder

FEEDBACK_TO_DESCRIPTIVE_MSG = {
    EagleProfilerEnrollFeedback.AUDIO_OK: '✅',
    EagleProfilerEnrollFeedback.AUDIO_TOO_SHORT: 'Insufficient audio length',
    EagleProfilerEnrollFeedback.UNKNOWN_SPEAKER: 'Different speaker in audio',
    EagleProfilerEnrollFeedback.NO_VOICE_FOUND: 'No voice found in audio',
    EagleProfilerEnrollFeedback.QUALITY_ISSUE: 'Low audio quality due to bad microphone or environment'
}


class FeedbackAnimation(Thread):
    def __init__(self):
        super().__init__()

        self._stop = False
        self.percentage = 0
        self.feedback = ''

    def run(self):
        frames = [" .  ", " .. ", " ...", "  ..", "   .", "    "]
        i = 0

        while not self._stop:
            if self._stop:
                break

            print(
                f'\033[2K\033[1G\r[{self.percentage:3d}] {frames[i % len(frames)]} [{self.feedback}]',
                end='',
                flush=True)
            i += 1
            time.sleep(0.1)

        self._stop = False

    def stop(self):
        print('\033[2K\033[1G\r', end='', flush=True)
        self._stop = True


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
    args = parser.parse_args()

    access_key = args.access_key
    porcupine_model_path = args.porcupine_model_path
    porcupine_keyword_path = args.porcupine_keyword_path
    porcupine_sensitivity = args.porcupine_sensitivity
    eagle_speaker_profile_path = args.eagle_speaker_profile_path

    eagle = pveagle.create_profiler(access_key=access_key)
    porcupine = pvporcupine.create(
        access_key=access_key,
        model_path=porcupine_model_path,
        keyword_paths=[porcupine_keyword_path],
        sensitivities=[porcupine_sensitivity])

    recorder = PvRecorder(frame_length=porcupine.frame_length)

    enrollment_animation = FeedbackAnimation()

    enroll_percentage = 0.0
    enrollment_animation.start()
    while enroll_percentage < 100.0:
        enroll_pcm = list()
        recorder.start()
        wake_word_detected = False
        while not wake_word_detected:
            frame = recorder.read()
            enroll_pcm.extend(frame)
            wake_word_detected = porcupine.process(frame) == 0
        for _ in range(8):
            enroll_pcm.extend(recorder.read())
        recorder.stop()

        enroll_percentage, feedback = eagle.enroll(enroll_pcm)
        enrollment_animation.percentage = int(enroll_percentage)
        enrollment_animation.feedback = FEEDBACK_TO_DESCRIPTIVE_MSG[feedback]

    enrollment_animation.stop()

    with open(eagle_speaker_profile_path, 'wb') as f:
        f.write(eagle.export().to_bytes())
    print('Speaker profile is saved to %s' % eagle_speaker_profile_path)

    recorder.stop()
    recorder.delete()
    porcupine.delete()
    eagle.delete()


if __name__ == '__main__':
    main()
