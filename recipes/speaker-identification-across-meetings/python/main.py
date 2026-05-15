import os
from argparse import ArgumentParser

import pveagle
import pvfalcon
import soundfile
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
        help='Path to the input meeting audio file. The audio must be 16 kHz.')
    parser.add_argument(
        '--known_speaker_profile_paths',
        nargs='*',
        default=[],
        help=(
            'Paths to Eagle speaker profile files for known speakers. '
            'The filename, without extension, is used as the speaker name.'))
    parser.add_argument(
        '--profile_unknown_speakers',
        action='store_true',
        help='Create Eagle speaker profiles for speakers that are not matched to known speakers.')
    parser.add_argument(
        '--unknown_speaker_profiles_folder',
        help=(
            'Folder where newly-created unknown speaker profiles are saved. '
            'Defaults to the folder containing the input audio file.'))
    parser.add_argument(
        '--similarity_threshold',
        type=float,
        default=0.5,
        help='Minimum Eagle similarity score required to identify a speaker. Defaults to 0.5.')
    args = parser.parse_args()

    access_key = args.access_key
    audio_path = args.audio_path
    known_speaker_profile_paths = args.known_speaker_profile_paths
    profile_unknown_speakers = args.profile_unknown_speakers
    unknown_speaker_profiles_folder = args.unknown_speaker_profiles_folder
    similarity_threshold = args.similarity_threshold

    if unknown_speaker_profiles_folder is None:
        unknown_speaker_profiles_folder = os.path.dirname(audio_path)

    falcon = None
    eagle_recognizer = None
    eagle_profiler = None

    try:
        falcon = pvfalcon.create(access_key=access_key)
        print(f"[OK] Falcon Speaker Diarization [V{falcon.version}]")

        if len(known_speaker_profile_paths) > 0:
            eagle_recognizer = pveagle.create_recognizer(access_key=access_key)
            print(f"[OK] Eagle Speaker Recognition [V{eagle_recognizer.version}]")

        if profile_unknown_speakers:
            eagle_profiler = pveagle.create_profiler(access_key=access_key)
            if eagle_recognizer is None:
                print(f"[OK] Eagle Speaker Recognition [V{eagle_profiler.version}]")

        print()

        pcm, sample_rate = soundfile.read(audio_path, dtype='int16', always_2d=True)
        if sample_rate != falcon.sample_rate:
            raise RuntimeError(f"Invalid sample rate: expected {falcon.sample_rate} Hz, got {sample_rate} Hz.")

        pcm = pcm[:, 0]

        segments = falcon.process(pcm)

        speaker_tag_map = dict()
        if eagle_recognizer is not None:
            speaker_pcms = dict()
            for x in segments:
                if x.speaker_tag not in speaker_pcms:
                    speaker_pcms[x.speaker_tag] = list()
                speaker_pcms[x.speaker_tag].extend(pcm[int(x.start_sec * sample_rate): int(x.end_sec * sample_rate)])

            known_speaker_profiles = list()
            known_speaker_names = list()
            for x in known_speaker_profile_paths:
                with open(x, 'rb') as f:
                    known_speaker_profiles.append(EagleProfile.from_bytes(f.read()))
                known_speaker_names.append(os.path.splitext(os.path.basename(x))[0])

            for speaker, speaker_pcm in speaker_pcms.items():
                similarities = eagle_recognizer.process(
                    pcm=speaker_pcm,
                    speaker_profiles=known_speaker_profiles)

                best_index = max(range(len(similarities)), key=lambda ii: similarities[ii])
                best_similarity = similarities[best_index]

                if best_similarity >= similarity_threshold:
                    speaker_tag_map[speaker] = known_speaker_names[best_index]
                    print(f"Identified speaker `{speaker}` as `{known_speaker_names[best_index]}`")

        if len(speaker_tag_map) > 0:
            print()

        if eagle_profiler is not None:
            os.makedirs(unknown_speaker_profiles_folder, exist_ok=True)

            speaker_intervals = dict()
            for x in segments:
                if x.speaker_tag not in speaker_tag_map:
                    if x.speaker_tag not in speaker_intervals:
                        speaker_intervals[x.speaker_tag] = list()
                    speaker_intervals[x.speaker_tag].append(
                        (int(x.start_sec * sample_rate), int(x.end_sec * sample_rate)))

            for speaker, intervals in speaker_intervals.items():
                for start_sample, end_sample in intervals:
                    for i in range(start_sample, end_sample, eagle_profiler.frame_length):
                        frame = pcm[i:i + eagle_profiler.frame_length]
                        if len(frame) == eagle_profiler.frame_length:
                            eagle_profiler.enroll(pcm=frame)

                percentage = eagle_profiler.flush()
                if percentage == 100.:
                    profile_path = os.path.join(unknown_speaker_profiles_folder, f"{speaker}.bin")
                    with open(profile_path, 'wb') as f:
                        f.write(eagle_profiler.export().to_bytes())
                    print(f"Saved unknown speaker `{speaker}` profile to `{profile_path}`.")
                else:
                    print(
                        f"Could not create a profile for unknown speaker `{speaker}`. "
                        f"Enrollment is {percentage:.1f}% complete.")

            if len(speaker_intervals) > 0:
                print()

        print("Diarization:")
        for x in segments:
            print(f"[{speaker_tag_map.get(x.speaker_tag, x.speaker_tag)}] {x.start_sec:.2f} → {x.end_sec:.2f}")
    finally:
        if eagle_profiler is not None:
            eagle_profiler.delete()

        if eagle_recognizer is not None:
            eagle_recognizer.delete()

        if falcon is not None:
            falcon.delete()


if __name__ == '__main__':
    main()
