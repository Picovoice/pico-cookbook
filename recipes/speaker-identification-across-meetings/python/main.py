from argparse import ArgumentParser

import pveagle
import pvfalcon
from pveagle import EagleProfile


def main() -> None:
    parser = ArgumentParser()
    parser.add_argument(
        '--access_key',
        required=True,
        help='AccessKey obtained from Picovoice Console (https://console.picovoice.ai/)')
    parser.add_argument(
        '--audio_path',
        required=True,
        help='')
    parser.add_argument(
        '--known_speakers',
        nargs='*',
        default=[],
        help='')
    parser.add_argument(
        '--run_profiler',
        action='store_true',
        help='')
    args = parser.parse_args()

    access_key = args.access_key
    audio_path = args.audio_path
    known_speakers = args.known_speakers
    run_profiler = args.run_profiler

    falcon = pvfalcon.create(access_key=access_key)
    print(f"[OK] Falcon Speaker Diarization[V{falcon.version}]")

    eagle_recognizer = None
    eagle_profiler = None
    if len(known_speakers) > 0:
        eagle_recognizer = pveagle.create_recognizer(access_key=access_key)
        print(f"[OK] Eagle Speaker Recognition[V{eagle_recognizer.version}]")
    if run_profiler:
        eagle_profiler = pveagle.create_profiler(access_key=access_key)
        if len(known_speakers) == 0:
            print(f"[OK] Eagle Speaker Recognition[V{eagle_profiler.version}]")

    segments = falcon.process_file(audio_path)
    speaker_map = dict()
    if eagle_recognizer is not None:
        speaker_segments = dict()
        for x in segments:
            speaker_segments.setdefault(x.speaker_tag, list()).append((x.start_sec, x.end_sec))

        known_profiles = list()
        for x in known_speakers:
            with open(x, 'rb') as f:
                known_profiles.append(EagleProfile.from_bytes(f.read()))

        for speaker, ss in speaker_segments.items():
            for s in ss:
                eagle_recognizer.process(pcm=None, speaker_profiles=known_profiles)

    for x in segments:
        print(f"[{speaker_map.get(x.speaker_tag, x.speaker_tag)}] {x.start_sec:.2f} → {x.end_sec:.2f}")


if __name__ == '__main__':
    main()
