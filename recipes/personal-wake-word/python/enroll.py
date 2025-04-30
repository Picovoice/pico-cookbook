import os
import shutil
import struct
import time
import wave
from argparse import ArgumentParser
from threading import Thread
from typing import Optional

import pveagle
import pvporcupine
from pveagle import EagleProfilerEnrollFeedback
from pvrecorder import PvRecorder


class FeedbackAnimation(Thread):
    def __init__(self):
        super().__init__()

        self._stop: bool = False
        self.progress: int = 0
        self.feedback: Optional[EagleProfilerEnrollFeedback] = None

    def run(self):
        frames = [" .  ", " .. ", " ...", "  ..", "   .", "    "]
        i = 0

        while not self._stop:
            if self._stop:
                break

            if self.feedback is None:
                print(
                    f'\033[2K\033[1G\r[{self.progress:3d}%] 🎤 Say the wake word {frames[i % len(frames)]}',
                    end='',
                    flush=True)
                i += 1
                time.sleep(0.1)
            else:
                feedback = {
                    EagleProfilerEnrollFeedback.AUDIO_OK: '✅',
                    EagleProfilerEnrollFeedback.AUDIO_TOO_SHORT: '⚠️ Insufficient audio length',
                    EagleProfilerEnrollFeedback.UNKNOWN_SPEAKER: '⚠️ Different speaker in audio',
                    EagleProfilerEnrollFeedback.NO_VOICE_FOUND: '⚠️ No voice found in audio',
                    EagleProfilerEnrollFeedback.QUALITY_ISSUE: '⚠️ Low audio quality'
                }[self.feedback]
                print(
                    f'\033[2K\033[1G\r[{self.progress:3d}%] {feedback}',
                    end='',
                    flush=True)
                self.feedback = None
                i = 0
                time.sleep(.5 if self.feedback == EagleProfilerEnrollFeedback.AUDIO_OK else 1.)

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
        '--xray_folder',
        help='')
    args = parser.parse_args()

    access_key = args.access_key
    porcupine_model_path = args.porcupine_model_path
    porcupine_keyword_path = args.porcupine_keyword_path
    porcupine_sensitivity = args.porcupine_sensitivity
    eagle_speaker_profile_path = args.eagle_speaker_profile_path
    xray_folder = args.xray_folder

    if xray_folder is not None:
        if os.path.exists(xray_folder):
            shutil.rmtree(xray_folder)
        os.makedirs(xray_folder)

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

    try:
        while enroll_percentage < 100.0:
            enroll_pcm = list()
            recorder.start()
            wake_word_detected = False
            while not wake_word_detected:
                frame = recorder.read()
                enroll_pcm.extend(frame)
                wake_word_detected = porcupine.process(frame) == 0
            # for _ in range(8):
            #     enroll_pcm.extend(recorder.read())
            recorder.stop()

            if xray_folder is not None:
                index = len(os.listdir(xray_folder))

                with wave.open(os.path.join(xray_folder, f'{index}-{enroll_percentage}.wav'), 'w') as f:
                    f.setnchannels(1)
                    f.setsampwidth(2)
                    f.setframerate(recorder.sample_rate)
                    f.writeframes(struct.pack('%dh' % len(enroll_pcm), *enroll_pcm))

            enroll_percentage, feedback = eagle.enroll(enroll_pcm)
            enrollment_animation.progress = int(enroll_percentage)
            enrollment_animation.feedback = feedback
    except KeyboardInterrupt:
        pass
    finally:
        enrollment_animation.stop()
        recorder.stop()
        recorder.delete()
        porcupine.delete()
        if enroll_percentage == 100.:
            with open(eagle_speaker_profile_path, 'wb') as f:
                f.write(eagle.export().to_bytes())
            print('Speaker profile is saved to %s' % eagle_speaker_profile_path)
        eagle.delete()


if __name__ == '__main__':
    main()
