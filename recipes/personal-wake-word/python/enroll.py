import time
from argparse import ArgumentParser
from threading import Thread

from pveagle import (
    EagleActivationLimitError,
    EagleError,
    EagleProfilerEnrollFeedback,
    create_profiler,
)
from pvporcupine import (
    PorcupineError,
)
from pvporcupine import create as create_porcupine
from pvrecorder import PvRecorder

FEEDBACK_TO_DESCRIPTIVE_MSG = {
    EagleProfilerEnrollFeedback.AUDIO_OK: 'Good audio',
    EagleProfilerEnrollFeedback.AUDIO_TOO_SHORT: 'Insufficient audio length',
    EagleProfilerEnrollFeedback.UNKNOWN_SPEAKER: 'Different speaker in audio',
    EagleProfilerEnrollFeedback.NO_VOICE_FOUND: 'No voice found in audio',
    EagleProfilerEnrollFeedback.QUALITY_ISSUE: 'Low audio quality due to bad microphone or environment'
}


class EnrollmentAnimation(Thread):
    def __init__(self, sleep_time_sec=0.1):
        self._sleep_time_sec = sleep_time_sec
        self._frames = [
            " .  ",
            " .. ",
            " ...",
            "  ..",
            "   .",
            "    "
        ]
        self._done = False
        self._percentage = 0
        self._feedback = ''
        super().__init__()

    def run(self):
        self._done = False
        while not self._done:
            for frame in self._frames:
                if self._done:
                    break
                print('\033[2K\033[1G\r[%3d%%]' % self._percentage + self._feedback + frame, end='', flush=True)
                time.sleep(self._sleep_time_sec)

    def stop(self):
        print('\033[2K\033[1G\r[%3d%%]' % self._percentage + self._feedback, end='', flush=True)
        self._done = True

    @property
    def percentage(self):
        return self._percentage

    @property
    def feedback(self):
        return self._feedback

    @percentage.setter
    def percentage(self, value):
        self._percentage = value

    @feedback.setter
    def feedback(self, value):
        self._feedback = value


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

    try:
        eagle_profiler = create_profiler(access_key=access_key)
    except EagleError as e:
        print(f"Failed to initialize Eagle: {e}")
        return

    print(f'Eagle version: {eagle_profiler.version}')

    try:
        porcupine = create_porcupine(
            access_key=access_key,
            model_path=porcupine_model_path,
            keyword_paths=[porcupine_keyword_path],
            sensitivities=[porcupine_sensitivity])
    except PorcupineError as e:
        print(f"Failed to initialize Porcupine wth {e}")
        return
    print(f"Porcupine version: {porcupine.version}")

    recorder = PvRecorder(frame_length=porcupine.frame_length)
    print(f"Recording audio from `{recorder.selected_device}`")
    enrollment_animation = EnrollmentAnimation()
    print('Please keep speaking until the enrollment percentage reaches 100%')
    try:
        enroll_percentage = 0.0
        enroll_index = 0
        enrollment_animation.start()
        while enroll_percentage < 100.0:
            enroll_pcm = list()
            recorder.start()

            is_detected = False
            while not is_detected:
                frame = recorder.read()
                enroll_pcm.extend(frame)
                is_detected = porcupine.process(frame) == 0

            for _ in range(8):
                enroll_pcm.extend(recorder.read())

            recorder.stop()

            enroll_index += 1

            enroll_percentage, feedback = eagle_profiler.enroll(enroll_pcm)
            enrollment_animation.percentage = enroll_percentage
            enrollment_animation.feedback = ' - %s' % FEEDBACK_TO_DESCRIPTIVE_MSG[feedback]

        speaker_profile = eagle_profiler.export()
        enrollment_animation.stop()
        with open(eagle_speaker_profile_path, 'wb') as f:
            f.write(speaker_profile.to_bytes())
        print('\nSpeaker profile is saved to %s' % eagle_speaker_profile_path)

    except KeyboardInterrupt:
        print('\nStopping enrollment. No speaker profile is saved.')
        enrollment_animation.stop()
    except EagleActivationLimitError:
        print('AccessKey has reached its processing limit')
    except EagleError as e:
        print('Failed to enroll speaker: %s' % e)
    finally:
        recorder.stop()
        recorder.delete()
        eagle_profiler.delete()


if __name__ == '__main__':
    main()
