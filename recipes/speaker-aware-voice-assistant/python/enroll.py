import time
from argparse import ArgumentParser
from threading import (
    Event,
    Thread
)

import pveagle
from pvrecorder import PvRecorder


ENROLLMENT_SENTENCES = [
    "The quick brown fox jumps over the lazy dog.",
    "I am recording my voice for speaker enrollment.",
    "This is my normal speaking voice in a quiet room.",
    "The assistant should recognize me when I speak.",
    "Voice recognition works best with clean and natural speech.",
]


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
                f'\033[2K\033[1G\r[{self.progress:3d}%] Enrolling your voice {frames[i % len(frames)]}',
                end='',
                flush=True)
            i += 1
            time.sleep(0.1)

        print('\033[2K\033[1G\r', end='', flush=True)


def print_enrollment_instructions() -> None:
    print()
    print("Read the following sentences aloud in your normal voice.")
    print("Keep speaking until enrollment reaches 100%.")
    print()

    for i, sentence in enumerate(ENROLLMENT_SENTENCES, start=1):
        print(f"{i}. {sentence}")

    print()


def main() -> None:
    parser = ArgumentParser()
    parser.add_argument(
        '--access_key',
        help='AccessKey obtained from Picovoice Console (https://console.picovoice.ai/)')
    parser.add_argument(
        '--eagle_speaker_profile_path',
        help="Absolute path to the Eagle's speaker profile file")
    parser.add_argument(
        '--eagle_min_enrollment_chunks',
        type=int,
        default=4,
        help="Minimum number of enrollment speech chunks")
    parser.add_argument(
        '--audio_device_index',
        type=int,
        default=-1,
        help='Index of input audio device')
    parser.add_argument(
        '--show_audio_devices',
        action='store_true',
        help='Only list available input audio devices and exit')
    args = parser.parse_args()

    if args.show_audio_devices:
        for index, name in enumerate(PvRecorder.get_available_devices()):
            print('Device #%d: %s' % (index, name))
        return

    access_key = args.access_key
    eagle_speaker_profile_path = args.eagle_speaker_profile_path
    if access_key is None or eagle_speaker_profile_path is None:
        print('--access_key and --eagle_speaker_profile_path are required arguments')
        return

    eagle_min_enrollment_chunks = args.eagle_min_enrollment_chunks

    eagle = None
    recorder = None
    progress = 0.0
    animation = None

    try:
        eagle = pveagle.create_profiler(
            access_key=access_key,
            min_enrollment_chunks=eagle_min_enrollment_chunks)
        print(f"[OK] Eagle Speaker Recognition[V{eagle.version}]")

        print_enrollment_instructions()

        recorder = PvRecorder(
            device_index=args.audio_device_index,
            frame_length=eagle.frame_length)
        recorder.start()

        animation = Animation()
        animation.start()

        while progress < 100.0:
            progress = eagle.enroll(recorder.read())
            animation.progress = int(progress)

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

        if eagle is not None:
            eagle.delete()


if __name__ == '__main__':
    main()
