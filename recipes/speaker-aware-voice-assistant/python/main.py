import os
import shutil
import string
import sys
from argparse import ArgumentParser
from enum import Enum
from threading import (
    Event,
    Lock,
    Thread
)
from time import (
    monotonic,
    sleep
)
from typing import (
    Callable,
    Sequence,
    Tuple
)

import pvorca
import pvporcupine
import pvrhino
from pveagle import (
    EagleProfile,
    create_recognizer
)
from pvorca import Orca
from pvrecorder import PvRecorder
from pvspeaker import PvSpeaker


class UserRoles(Enum):
    ADMIN = 'admin'
    USER = 'user'


def print_async(get_text: Callable[[], str], refresh_sec: float = 0.1, end: str = '\n') -> Tuple[Event, Thread]:
    stop_event = Event()

    def wrap_text(text: str, width: int) -> list[str]:
        text = text.replace('\n', ' ')
        if width <= 0:
            return ['']
        return [text[i:i + width] for i in range(0, len(text), width)] or ['']

    def clear_block(num_lines: int) -> None:
        if num_lines <= 0:
            return

        sys.stdout.write('\r')
        if num_lines > 1:
            sys.stdout.write(f'\033[{num_lines - 1}F')

        for i in range(num_lines):
            sys.stdout.write('\033[2K')
            if i < num_lines - 1:
                sys.stdout.write('\n')

        if num_lines > 1:
            sys.stdout.write(f'\033[{num_lines - 1}F')
        sys.stdout.write('\r')

    def run() -> None:
        dots_list = [" .  ", " .. ", " ...", "  ..", "   .", "    "]
        i = 0
        prev_num_lines = 0

        sys.stdout.write("\033[?25l")
        sys.stdout.flush()

        try:
            while not stop_event.is_set():
                text = get_text()
                dots = dots_list[i]

                width = max(1, shutil.get_terminal_size(fallback=(80, 24)).columns - 1)
                lines = wrap_text(f"{text}{dots}", width)
                output = '\n'.join(lines)

                clear_block(prev_num_lines)
                sys.stdout.write(output)
                sys.stdout.flush()

                prev_num_lines = len(lines)
                i = (i + 1) % len(dots_list)
                sleep(refresh_sec)

            text = get_text()
            width = max(1, shutil.get_terminal_size(fallback=(80, 24)).columns - 1)
            lines = wrap_text(f"{text}    ", width)
            output = '\n'.join(lines)

            clear_block(prev_num_lines)
            sys.stdout.write(output)
            sys.stdout.write(end)
            sys.stdout.flush()

        finally:
            sys.stdout.write("\033[?25h")
            sys.stdout.flush()

    thread = Thread(target=run, daemon=True)
    thread.start()
    return stop_event, thread


def time_async(alignments: Sequence[Orca.WordAlignment], on_tick: Callable[[str], None]) -> Thread:
    def run() -> None:
        start_sec = monotonic()

        for i, x in enumerate(alignments):
            delay = float(x.start_sec) - (monotonic() - start_sec)
            if delay > 0:
                sleep(delay)

            suffix = ' ' if i < (len(alignments) - 1) and (alignments[i + 1].word not in string.punctuation) else ''
            on_tick(x.word + suffix)

    thread = Thread(target=run, daemon=True)
    thread.start()
    return thread


def synthesize_and_playback(orca: Orca, speaker: PvSpeaker, recorder: PvRecorder, text: str) -> None:
    recorder.stop()

    pcm, word_alignments = orca.synthesize(text)

    utterance = ""
    utterance_lock = Lock()

    def get_utterance() -> str:
        with utterance_lock:
            return f"[AI] {utterance}"

    def update_utterance(chunk: str) -> None:
        nonlocal utterance
        with utterance_lock:
            utterance += chunk

    utterance_event, utterance_thread = print_async(get_utterance)

    timer_thread = time_async(alignments=word_alignments, on_tick=update_utterance)

    speaker.flush(pcm)
    timer_thread.join()
    utterance_event.set()
    utterance_thread.join()
    recorder.start()


def main() -> None:
    parser = ArgumentParser()
    parser.add_argument(
        "--access_key",
        required=True,
        help="AccessKey obtained from Picovoice Console (https://console.picovoice.ai/).")
    parser.add_argument(
        '--keyword_path',
        required=True,
        help="Absolute path to the Porcupine's keyword file (.ppn)")
    parser.add_argument(
        '--context_path',
        required=True,
        help="Path to the Rhino Speech-to-Intent context file trained on Picovoice Console "
             "(https://console.picovoice.ai/).")
    parser.add_argument(
        '--user_profile_paths',
        required=True,
        nargs='+',
        help="")
    parser.add_argument(
        '--user_roles',
        required=True,
        nargs='+',
        choices=[x.value for x in UserRoles],
        help="")
    parser.add_argument(
        '--admin_similarity_threshold',
        type=float,
        default=0.75)
    args = parser.parse_args()

    access_key = args.access_key
    keyword_path = args.keyword_path
    context_path = args.context_path
    user_profile_paths = args.user_profile_paths
    user_roles = [UserRoles(x) for x in args.user_roles]
    admin_similarity_threshold = args.admin_similarity_threshold

    if len(user_profile_paths) != len(user_roles):
        raise ValueError()

    user_profiles = list()
    for path in user_profile_paths:
        with open(path, 'rb') as f:
            user_profiles.append(EagleProfile.from_bytes(f.read()))

    usernames = [os.path.basename(x).rsplit('.', maxsplit=1)[0] for x in user_profile_paths]

    porcupine = None
    rhino = None
    eagle = None
    orca = None
    recorder = None
    speaker = None

    try:
        porcupine = pvporcupine.create(
            access_key=access_key,
            keyword_paths=[keyword_path])
        print(f"[OK] Porcupine Wake Word[V{porcupine.version}]")

        rhino = pvrhino.create(
            access_key=access_key,
            context_path=context_path,
            require_endpoint=False)
        print(f"[OK] Rhino Speech-to-Intent [V{rhino.version}]")

        eagle = create_recognizer(access_key=access_key)
        print(f"[OK] Eagle Speaker Recognition[V{eagle.version}]")

        orca = pvorca.create(access_key=access_key)
        print(f"[OK] Orca Streaming Text-to-Speech [V{orca.version}]")

        recorder = PvRecorder(frame_length=porcupine.frame_length)
        recorder.start()

        speaker = PvSpeaker(sample_rate=orca.sample_rate, bits_per_sample=16)
        speaker.start()

        print()

        print_event, print_thread = print_async(get_text=lambda: "Say the wake word")

        while True:
            if porcupine.process(recorder.read()) != 0:
                continue
            print_event.set()
            print_thread.join()

            print_event, print_thread = print_async(get_text=lambda: "Say a voice command")

            pcm_voice_command = list()
            is_finalized = False
            while not is_finalized:
                frame = recorder.read()
                is_finalized = rhino.process(frame)
                pcm_voice_command.extend(frame)
            inference = rhino.get_inference()
            print_event.set()
            print_thread.join()

            if inference.is_understood:
                if len(pcm_voice_command) < eagle.min_process_samples:
                    pcm_voice_command.extend([0] * (eagle.min_process_samples - len(pcm_voice_command)))
                similarities = eagle.process(pcm_voice_command, speaker_profiles=user_profiles)
                if similarities is None:
                    synthesize_and_playback(
                        orca=orca,
                        speaker=speaker,
                        recorder=recorder,
                        text="I didn't who is talking")

                user_index = max(range(len(similarities)), key=similarities.__getitem__)
                user_similarity = similarities[user_index]
                username = usernames[user_index]
                user_role = user_roles[user_index]

                if inference.intent == 'adminOnly':
                    if user_similarity >= admin_similarity_threshold:
                        if user_role is UserRoles.ADMIN:
                            synthesize_and_playback(
                                orca=orca,
                                speaker=speaker,
                                recorder=recorder,
                                text='adminOnly')
                        else:
                            synthesize_and_playback(
                                orca=orca,
                                speaker=speaker,
                                recorder=recorder,
                                text='You are not an admin')
                    else:
                        synthesize_and_playback(
                            orca=orca,
                            speaker=speaker,
                            recorder=recorder,
                            text="Sorry, couldn't verify you")
                elif inference.intent == 'speakerPersonalized':
                    synthesize_and_playback(
                        orca=orca,
                        speaker=speaker,
                        recorder=recorder,
                        text=f'speakerPersonalized for {username}')
                elif inference.intent == 'generic':
                    synthesize_and_playback(
                        orca=orca,
                        speaker=speaker,
                        recorder=recorder,
                        text='generic')
                else:
                    raise NotImplementedError()
            else:
                synthesize_and_playback(
                    orca=orca,
                    speaker=speaker,
                    recorder=recorder,
                    text="I didn't understand")

            print_event, print_thread = print_async(get_text=lambda: "Say the wake word")
    except KeyboardInterrupt:
        pass
    finally:
        # Make the cursor visible again.
        sys.stdout.write("\033[?25h")
        sys.stdout.flush()

        if speaker is not None:
            speaker.stop()
            speaker.delete()

        if recorder is not None:
            recorder.stop()
            recorder.delete()

        if orca is not None:
            orca.delete()

        if eagle is not None:
            eagle.delete()

        if rhino is not None:
            rhino.delete()

        if porcupine is not None:
            porcupine.delete()


if __name__ == '__main__':
    main()
