import os
import shutil
import struct
import time
import wave
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
    parser.add_argument('--xray-folder', default=None)

    parser.add_argument(
        '--speaker_profile_path',
        required=True,
        help='Absolute path to speaker profile file')

    args = parser.parse_args()

    access_key = args.access_key
    wake_word_path = args.wake_word_path
    porcupine_model_path = args.porcupine_model_path
    audio_device_index = args.audio_device_index
    porcupine_sensitivity = args.porcupine_sensitivity
    xray_folder = args.xray_folder

    if xray_folder is not None:
        if os.path.exists(xray_folder):
            shutil.rmtree(xray_folder)
        os.makedirs(xray_folder)

    if args.show_audio_devices:
        for index, name in enumerate(PvRecorder.get_available_devices()):
            print(f'Device #{index}: {name}')
        return

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
            keyword_paths=[wake_word_path],
            sensitivities=[porcupine_sensitivity])
    except PorcupineError as e:
        print(f"Failed to initialize Porcupine wth {e}")
        return
    print(f"Porcupine version: {porcupine.version}")

    recorder = PvRecorder(frame_length=porcupine.frame_length, device_index=audio_device_index)
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

            if xray_folder is not None:
                with wave.open(os.path.join(xray_folder, f"enroll-{enroll_index}.wav"), 'w') as wav_file:
                    wav_file.setnchannels(1)  # mono
                    wav_file.setsampwidth(2)  # 2 bytes per sample (16-bit)
                    wav_file.setframerate(porcupine.sample_rate)
                    wav_file.writeframes(struct.pack('%dh' % len(enroll_pcm), *enroll_pcm))

            enroll_index += 1

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
