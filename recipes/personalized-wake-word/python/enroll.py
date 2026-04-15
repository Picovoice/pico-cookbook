import time
from argparse import ArgumentParser
from threading import (
    Event,
    Thread
)

import pveagle
import pvporcupine
from pvrecorder import PvRecorder


class Animation(Thread):
    def __init__(self):
        super().__init__()

        self.stop_event = Event()
        self.progress = 0

    def run(self):
        frames = [" .  ", " .. ", " ...", "  ..", "   .", "    "]

        i = 0
        while not self.stop_event.is_set():
            print(
                f'\033[2K\033[1G\r[{self.progress:3d}%] Say the wake word {frames[i % len(frames)]}',
                end='',
                flush=True)
            i += 1
            time.sleep(0.1)

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
        '--eagle_min_enrollment_chunks',
        type=int,
        default=4,
        help="Minimum number of enrollment speech chunks")
    args = parser.parse_args()

    access_key = args.access_key
    porcupine_model_path = args.porcupine_model_path
    porcupine_keyword_path = args.porcupine_keyword_path
    porcupine_sensitivity = args.porcupine_sensitivity
    eagle_speaker_profile_path = args.eagle_speaker_profile_path
    eagle_min_enrollment_chunks = args.eagle_min_enrollment_chunks

    eagle = None
    porcupine = None
    recorder = None
    progress = 0.0
    animation = None

    try:
        porcupine = pvporcupine.create(
            access_key=access_key,
            model_path=porcupine_model_path,
            keyword_paths=[porcupine_keyword_path],
            sensitivities=[porcupine_sensitivity])
        print(f"[OK] Porcupine Wake Word[V{porcupine.version}]")

        eagle = pveagle.create_profiler(
            access_key=access_key,
            min_enrollment_chunks=eagle_min_enrollment_chunks,
            voice_threshold=0.1)
        print(f"[OK] Eagle Speaker Recognition[V{eagle.version}]")

        recorder = PvRecorder(frame_length=porcupine.frame_length)
        recorder.start()

        animation = Animation()
        animation.start()

        while progress < 100.0:
            pcm = list()
            is_detected = False
            while not is_detected:
                frame = recorder.read()
                pcm.extend(frame)
                is_detected = porcupine.process(frame) == 0

            for i in range(len(pcm) // eagle.frame_length):
                progress = eagle.enroll(pcm[i * eagle.frame_length:(i + 1) * eagle.frame_length])
            animation.progress = int(progress)
            pcm.clear()
    except KeyboardInterrupt:
        pass
    finally:
        if animation is not None:
            animation.stop_event.set()
            animation.join()

        if progress == 100.:
            with open(eagle_speaker_profile_path, 'wb') as f:
                f.write(eagle.export().to_bytes())
            print(f'Speaker profile is saved to `{eagle_speaker_profile_path}`')

        if recorder is not None:
            recorder.stop()
            recorder.delete()

        if porcupine is not None:
            porcupine.delete()

        if eagle is not None:
            eagle.delete()


if __name__ == '__main__':
    main()
