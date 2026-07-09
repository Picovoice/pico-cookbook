import csv
import os
import shutil
import string
import sys
from argparse import ArgumentParser
from pathlib import Path
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
from pvorca import Orca
from pvrecorder import PvRecorder
from pvspeaker import PvSpeaker


def yaml_list(values, indent="      "):
    return "\n".join(f'{indent}- "{value}"' for value in values)


def build_contact_phrases(row):
    phrases = set()

    first = row["first_name"].strip()
    last = row["last_name"].strip()
    nickname = row["nickname"].strip()

    if first:
        phrases.add(first)

    if first and last:
        phrases.add(f"{first} {last}")

    if nickname:
        phrases.add(nickname)

    return phrases


def build_context(
        template_path: str,
        csv_path: str,
        yml_path: str,
) -> None:
    contact_values = set()
    company_values = set()

    with open(csv_path, newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)

        for row in reader:
            contact_values.update(build_contact_phrases(row))

            company = row["company"].strip()
            if company:
                company_values.add(company)

    contact_values = sorted(contact_values, key=str.lower)
    company_values = sorted(company_values, key=str.lower)

    template = Path(template_path).read_text(encoding="utf-8")

    context = (
        template
        .replace("{{CONTACT_SLOT_VALUES}}", yaml_list(contact_values))
        .replace("{{COMPANY_SLOT_VALUES}}", yaml_list(company_values))
    )

    Path(yml_path).write_text(context, encoding="utf-8")


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
        help="AccessKey obtained from Picovoice Console (https://console.picovoice.ai/).")
    parser.add_argument(
        '--keyword_path',
        help='Path to Porcupine Wake Word model trained on Picovoice Console (https://console.picovoice.ai/).')
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
    keyword_path = args.keyword_path
    audio_device_index = args.audio_device_index

    if access_key is None or keyword_path is None:
        print('--access_key and --keyword_path are required arguments')
        return

    build_context(
        template_path=str(os.path.join(str(os.path.dirname(__file__)), "../res/template.yml")),
        csv_path=str(os.path.join(str(os.path.dirname(__file__)), "../res/contacts.csv")),
        yml_path=str(os.path.join(str(os.path.dirname(__file__)), "context.yml")))

    porcupine = None
    rhino = None
    orca = None
    recorder = None
    speaker = None

    try:
        porcupine = pvporcupine.create(
            access_key=access_key,
            keyword_paths=[keyword_path])
        print(f"[OK] Porcupine Wake Word[V{porcupine.version}]")

        pvrhino.train_context_from_yaml(
            access_key=access_key,
            output_path=str(os.path.join(str(os.path.dirname(__file__)), "context.rhn")),
            language='en',
            yaml_path=str(os.path.join(str(os.path.dirname(__file__)), "context.yml")))
        rhino = pvrhino.create(
            access_key=access_key,
            context_path=str(os.path.join(str(os.path.dirname(__file__)), "context.rhn")),
            require_endpoint=False)
        print(f"[OK] Rhino Speech-to-Intent[V{rhino.version}]")

        orca = pvorca.create(access_key=access_key)
        print(f"[OK] Orca Streaming Text-to-Speech[V{orca.version}]")

        recorder = PvRecorder(
            device_index=audio_device_index,
            frame_length=porcupine.frame_length)

        speaker = PvSpeaker(sample_rate=orca.sample_rate, bits_per_sample=16)
        speaker.start()

        print()

        while True:
            recorder.start()

            text = "Listening for wake word"
            lock = Lock()

            def get_text() -> str:
                with lock:
                    return text

            print_event, print_thread = print_async(get_text)

            is_detected = False
            while not is_detected:
                is_detected = porcupine.process(recorder.read()) == 0

            with lock:
                text = "Listening for voice command"

            while not rhino.process(recorder.read()):
                pass
            inference = rhino.get_inference()
            print(inference)

            recorder.stop()
            print_event.set()
            print_thread.join()

            pcm, word_alignments = orca.synthesize("calling")

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

        if rhino is not None:
            rhino.delete()

        if porcupine is not None:
            porcupine.delete()


if __name__ == "__main__":
    main()
