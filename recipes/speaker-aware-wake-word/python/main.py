import os
import sys
import time
from argparse import ArgumentParser
from threading import (
    Event,
    Lock,
    Thread
)
from typing import (
    Dict,
    Optional,
    Sequence
)

import pvporcupine
from pveagle import (
    EagleProfile,
    create_recognizer
)
from pvrecorder import PvRecorder


class Animation(Thread):
    def __init__(self, speakers: Sequence[str]):
        super().__init__(daemon=True)

        self.stop_event = Event()
        self._lock = Lock()

        self._speakers = speakers
        self._speaker_states: Dict[str, Optional[float]] = {
            speaker: None for speaker in self._speakers
        }
        self._last_detected_speaker: Optional[str] = None
        self._last_detection_time: float = 0.0

        self._num_lines = 1 + len(self._speakers)

    def run(self):
        frames = [" .  ", " .. ", " ...", "  ..", "   .", "    "]
        i = 0
        first_frame = True

        while not self.stop_event.is_set():
            now = time.time()

            with self._lock:
                if self._last_detected_speaker is not None and now - self._last_detection_time >= 1.0:
                    self._speaker_states[self._last_detected_speaker] = None
                    self._last_detected_speaker = None
                    self._last_detection_time = 0.0

                speaker_states = dict(self._speaker_states)

            if first_frame:
                sys.stdout.write("\n" * self._num_lines)
                first_frame = False

            sys.stdout.write(f"\033[{self._num_lines}F")

            sys.stdout.write(f"\033[2K\rSay the wake word {frames[i % len(frames)]}\n")
            for speaker in self._speakers:
                similarity = speaker_states[speaker]
                if similarity is None:
                    sys.stdout.write(f"\033[2K\r -  {speaker}\n")
                else:
                    sys.stdout.write(f"\033[2K\r ✓  {speaker} ({similarity:.2f})\n")

            sys.stdout.flush()

            i += 1
            time.sleep(0.1)

        sys.stdout.write(f"\033[{self._num_lines}F")
        for _ in range(self._num_lines):
            sys.stdout.write("\033[2K\r\n")
        sys.stdout.write(f"\033[{self._num_lines}F")
        sys.stdout.flush()

    def update(self, speaker: str, similarity: float) -> None:
        with self._lock:
            if self._last_detected_speaker is not None:
                self._speaker_states[self._last_detected_speaker] = None

            self._speaker_states[speaker] = similarity
            self._last_detected_speaker = speaker
            self._last_detection_time = time.time()


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
        '--eagle_speaker_profile_paths',
        nargs='+',
        required=True,
        help="Absolute path to the Eagle's speaker profile files")
    parser.add_argument(
        '--eagle_threshold',
        type=float,
        default=0.5,
        help="Eagle's recognition threshold [0.0-1.0]")
    args = parser.parse_args()

    access_key = args.access_key
    porcupine_model_path = args.porcupine_model_path
    porcupine_keyword_path = args.porcupine_keyword_path
    porcupine_sensitivity = args.porcupine_sensitivity
    eagle_speaker_profile_paths = args.eagle_speaker_profile_paths
    eagle_threshold = args.eagle_threshold

    speaker_profiles = list()
    for path in eagle_speaker_profile_paths:
        with open(path, 'rb') as f:
            speaker_profiles.append(EagleProfile.from_bytes(f.read()))

    speakers = [os.path.basename(x).rsplit('.', maxsplit=1)[0] for x in eagle_speaker_profile_paths] + ["unknown"]

    porcupine = None
    eagle = None
    recorder = None
    animation = None

    try:
        porcupine = pvporcupine.create(
            access_key=access_key,
            model_path=porcupine_model_path,
            keyword_paths=[porcupine_keyword_path],
            sensitivities=[porcupine_sensitivity])
        print(f"[OK] Porcupine Wake Word[V{porcupine.version}]")

        eagle = create_recognizer(access_key=access_key, voice_threshold=0.)
        print(f"[OK] Eagle Speaker Recognition[V{eagle.version}]")

        recorder = PvRecorder(frame_length=porcupine.frame_length)
        recorder.start()

        pcm = list()

        animation = Animation(speakers)
        animation.start()

        while True:
            frame = recorder.read()

            pcm.extend(frame)
            pcm = pcm[-eagle.min_process_samples:]

            is_detected = porcupine.process(frame) == 0
            if is_detected:
                similarities = eagle.process(pcm, speaker_profiles=speaker_profiles)
                if similarities is not None:
                    speaker, similarity = max(zip(speakers, similarities), key=lambda x: x[1])
                    if similarity < eagle_threshold:
                        speaker = 'unknown'
                        similarity = 1. - similarity
                else:
                    speaker = 'unknown'
                    similarity = 1.

                animation.update(speaker=speaker, similarity=similarity)
    except KeyboardInterrupt:
        pass
    finally:
        if animation is not None:
            animation.stop_event.set()
            animation.join()

        if recorder is not None:
            recorder.stop()
            recorder.delete()

        if eagle is not None:
            eagle.delete()

        if porcupine is not None:
            porcupine.delete()


if __name__ == '__main__':
    main()
