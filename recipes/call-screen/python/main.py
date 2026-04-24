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

import pvcheetah
import pvorca
import pvrhino
from pvorca import Orca
from pvrecorder import PvRecorder
from pvspeaker import PvSpeaker


class Actions(Enum):
    GREET = "Greet"
    CONNECT_CALL = "Connect Call"
    DECLINE_CALL = "Decline Call"
    ASK_FOR_DETAILS = "Ask for Details"
    ASK_TO_TEXT = "Ask to Text"
    ASK_TO_EMAIL = "Ask to Email"
    ASK_TO_CALL_BACK = "Ask to Call Back"
    BLOCK_CALLER = "Block Caller"

    def prompt(self, username: str) -> str:
        return {
            self.GREET: "Hi, {username} can't answer right now. Please say your name and why you're calling.",
            self.CONNECT_CALL: "Okay, one moment while I connect you.",
            self.DECLINE_CALL: "Sorry, {username} is unavailable right now. Goodbye!",
            self.ASK_FOR_DETAILS: "Can you briefly say what this is regarding?",
            self.ASK_TO_TEXT: "{username} can't talk right now. Please send a text message instead. Goodbye!",
            self.ASK_TO_EMAIL: "Please send the details by email. Thank you. Goodbye!",
            self.ASK_TO_CALL_BACK: "{username} can't take your call right now. Please call back later. Goodbye!",
            self.BLOCK_CALLER: "This number is not accepting calls. Goodbye!",
        }[self].format(username=username)

    def is_terminal(self) -> bool:
        return {
            self.GREET: False,
            self.CONNECT_CALL: True,
            self.DECLINE_CALL: True,
            self.ASK_FOR_DETAILS: False,
            self.ASK_TO_TEXT: True,
            self.ASK_TO_EMAIL: True,
            self.ASK_TO_CALL_BACK: True,
            self.BLOCK_CALLER: True,
        }[self]


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


def main() -> None:
    parser = ArgumentParser()
    parser.add_argument(
        "--access_key",
        required=True,
        help="AccessKey obtained from Picovoice Console (https://console.picovoice.ai/).")
    parser.add_argument(
        '--context_path',
        required=True,
        help="Path to Rhino Speech-to-Intent context trained on Picovoice Console (https://console.picovoice.ai/).")
    parser.add_argument(
        "--username",
        default="the recipient",
        help="Name of the person receiving the call. Used in the spoken prompts.")
    parser.add_argument(
        "--username_pronunciation",
        nargs='+',
        help="Optional pronunciation of the username as a sequence of phonemes for Orca. "
             "If provided, it is used instead of the plain username when synthesizing prompts.")
    parser.add_argument(
        "--endpoint_duration_sec",
        type=float,
        default=1.0,
        help="Duration of silence, in seconds, required to detect the end of the caller's utterance.")
    args = parser.parse_args()

    access_key = args.access_key
    context_path = args.context_path
    username = args.username
    username_pronunciation = args.username_pronunciation
    endpoint_duration_sec = args.endpoint_duration_sec

    username_orca = username
    if username_pronunciation is not None:
        username_orca = f"{{{username}|{' '.join(username_pronunciation)}}}"

    cheetah = None
    orca = None
    rhino = None
    recorder = None
    speaker = None

    action = Actions.GREET

    try:
        cheetah = pvcheetah.create(
            access_key=access_key,
            endpoint_duration_sec=endpoint_duration_sec,
            enable_automatic_punctuation=True,
            enable_text_normalization=True)
        print(f"[OK] Cheetah Streaming Speech-to-Text[V{cheetah.version}]")

        orca = pvorca.create(access_key=access_key)
        print(f"[OK] Orca Streaming Text-to-Speech[V{orca.version}]")

        rhino = pvrhino.create(
            access_key=access_key,
            context_path=context_path,
            require_endpoint=False)
        print(f"[OK] Rhino Speech-to-Intent[V{rhino.version}]")

        recorder = PvRecorder(frame_length=cheetah.frame_length)

        speaker = PvSpeaker(sample_rate=orca.sample_rate, bits_per_sample=16)
        speaker.start()

        print()

        while True:
            pcm, word_alignments = orca.synthesize(action.prompt(username_orca))

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

            if action.is_terminal():
                break

            text = ""
            text_lock = Lock()

            def get_text():
                with text_lock:
                    display_text = "(listening)" if text == "" else text
                    return f"[CALLER] {display_text}"

            text_event, text_thread = print_async(get_text)

            recorder.start()
            is_endpoint = False
            while not is_endpoint:
                partial, is_endpoint = cheetah.process(recorder.read())
                with text_lock:
                    text += partial
            recorder.stop()
            remainder = cheetah.flush()
            with text_lock:
                text += remainder

            recorder.stop()
            text_event.set()
            text_thread.join()

            print()

            for x in Actions:
                print(f"- {x.value}")

            def get_text():
                return "[AI] Select one of the call-assist actions above"

            text_event, text_thread = print_async(get_text)

            recorder.start()
            action = None
            while action is None:
                while not rhino.process(recorder.read()):
                    pass
                inference = rhino.get_inference()
                if inference.is_understood and inference.intent == 'chooseAction':
                    action = Actions(inference.slots['action'])
            recorder.stop()
            text_event.set()
            text_thread.join()
            print(f"[{username.upper()}] {action.value}.\n")
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

        if rhino is not None:
            rhino.delete()

        if orca is not None:
            orca.delete()

        if cheetah is not None:
            cheetah.delete()


if __name__ == "__main__":
    main()
