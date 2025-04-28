import time
from argparse import ArgumentParser
from threading import Thread

from pveagle import (
    EagleActivationLimitError,
    EagleError,
    EagleProfilerEnrollFeedback,
    create_profiler,
)
from pvrecorder import PvRecorder

PV_RECORDER_FRAME_LENGTH = 512
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
        '--show_audio_devices',
        action='store_true',
        help='List available audio input devices and exit')

    common_parser = ArgumentParser(add_help=False)
    common_parser.add_argument(
        '--access_key',
        required=True,
        help='AccessKey obtained from Picovoice Console (https://console.picovoice.ai/)')
    common_parser.add_argument('--wake_word_path', required=True, help='Absolute path the wake word file')
    common_parser.add_argument('--porcupine_model_path', default=None)
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

    if args.command == 'enroll':
        try:
            eagle_profiler = create_profiler(access_key=args.access_key)
        except EagleError as e:
            print(f"Failed to initialize Eagle: {e}")
            return

        print(f'Eagle version: {eagle_profiler.version}')
        recorder = PvRecorder(frame_length=PV_RECORDER_FRAME_LENGTH, device_index=args.audio_device_index)
        print(f"Recording audio from `{recorder.selected_device}`")
        num_enroll_frames = eagle_profiler.min_enroll_samples // PV_RECORDER_FRAME_LENGTH
        sample_rate = eagle_profiler.sample_rate
        enrollment_animation = EnrollmentAnimation()
        print('Please keep speaking until the enrollment percentage reaches 100%')
        try:
            enroll_percentage = 0.0
            enrollment_animation.start()
            while enroll_percentage < 100.0:
                enroll_pcm = list()
                recorder.start()
                for _ in range(num_enroll_frames):
                    input_frame = recorder.read()
                    enroll_pcm.extend(input_frame)
                recorder.stop()

                enroll_percentage, feedback = eagle_profiler.enroll(enroll_pcm)
                enrollment_animation.percentage = enroll_percentage
                enrollment_animation.feedback = ' - %s' % FEEDBACK_TO_DESCRIPTIVE_MSG[feedback]

            speaker_profile = eagle_profiler.export()
            enrollment_animation.stop()
            with open(args.speaker_profile_path, 'wb') as f:
                f.write(speaker_profile.to_bytes())
            print('\nSpeaker profile is saved to %s' % args.speaker_profile_path)

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
