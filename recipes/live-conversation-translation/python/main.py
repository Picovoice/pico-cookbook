import os
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
import pvzebra
from pvorca import Orca
from pvrecorder import PvRecorder
from pvspeaker import PvSpeaker


class LanguagePairs(Enum):
    DE_EN = "de-en"
    DE_ES = "de-es"
    DE_FR = "de-fr"
    DE_IT = "de-it"
    EN_DE = "en-de"
    EN_ES = "en-es"
    EN_FR = "en-fr"
    EN_IT = "en-it"
    ES_DE = "es-de"
    ES_EN = "es-en"
    ES_FR = "es-fr"
    ES_IT = "es-it"
    FR_DE = "fr-de"
    FR_EN = "fr-en"
    FR_ES = "fr-es"
    IT_DE = "it-de"
    IT_EN = "it-en"
    IT_ES = "it-es"


def print_async(get_text: Callable[[], str], refresh_sec: float = 0.1) -> Tuple[Event, Thread]:
    stop_event = Event()

    def run() -> None:
        dots_list = [" .  ", " .. ", " ...", "  ..", "   .", "    "]
        i = 0

        # Hide the cursor.
        sys.stdout.write("\033[?25l")
        sys.stdout.flush()

        residue = 0

        while not stop_event.is_set():
            text = get_text()
            dots = dots_list[i]
            sys.stdout.write(f"\r{' ' * residue}")
            sys.stdout.write(f"\r{text}{dots}")
            sys.stdout.flush()
            residue = len(text) + len(dots)

            i = (i + 1) % len(dots_list)
            sleep(refresh_sec)

        text = get_text()
        sys.stdout.write(f"\r{text}    ")
        sys.stdout.flush()

    thread = Thread(target=run, daemon=True)
    thread.start()
    return stop_event, thread


def time_async(alignments: Sequence[Orca.WordAlignment], on_tick: Callable[[str], None]) -> Thread:
    def run() -> None:
        start_sec = monotonic()

        for i, x in enumerate(alignments):
            delay = float(x.start_sec) - (monotonic() - start_sec)
            if delay > 0.:
                sleep(delay)

            suffix = ' ' if i < (len(alignments) - 1) and (alignments[i + 1].word not in string.punctuation) else ''
            on_tick(x.word + suffix)

    thread = Thread(target=run, daemon=True)
    thread.start()
    return thread


def main() -> None:
    parser = ArgumentParser()
    parser.add_argument(
        '--access_key',
        required=True,
        help='AccessKey obtained from Picovoice Console (https://console.picovoice.ai/)')
    parser.add_argument(
        '--language_pair',
        choices=[x.value for x in LanguagePairs],
        required=True,
        help='Languages used in the conversion.')
    parser.add_argument(
        '--endpoint_duration_sec',
        type=float,
        default=1.0,
        help='Duration of silence in seconds required to detect the end of an utterance.')
    parser.add_argument(
        '--disable_automatic_punctuation',
        action='store_true',
        help='Disable automatic punctuation in Streaming Speech-to-Text.')
    parser.add_argument(
        '--disable_text_normalization',
        action='store_true',
        help='Disable text normalization in Streaming Speech-to-Text.')
    parser.add_argument(
        '--genders',
        nargs=2,
        choices=['female', 'male'],
        default=['male', 'male'],
        help="Gender of Speakers for Streaming Text-to-Speech, in `--language_pair` order.")
    args = parser.parse_args()

    access_key = args.access_key
    languages = LanguagePairs(args.language_pair).value.split('-')
    endpoint_duration_sec = args.endpoint_duration_sec
    disable_automatic_punctuation = args.disable_automatic_punctuation
    disable_text_normalization = args.disable_text_normalization
    genders = args.genders

    cheetahs = list()
    zebras = list()
    orcas = list()
    recorder = None
    speaker = None

    try:
        for x in languages:
            model_path = os.path.join(
                os.path.dirname(__file__),
                'cheetah/lib/common',
                'cheetah_params.pv' if x == 'en' else f'cheetah_params_{x}.pv')
            cheetah = pvcheetah.create(
                access_key=access_key,
                model_path=model_path,
                endpoint_duration_sec=endpoint_duration_sec,
                enable_automatic_punctuation=not disable_automatic_punctuation,
                enable_text_normalization=not disable_text_normalization)
            print(f"[OK] Cheetah Streaming Speech-to-Text[V{cheetah.version}][{x.upper()}]")
            cheetahs.append(cheetah)

        for x, y in [languages, reversed(languages)]:
            model_path = os.path.join(
                os.path.dirname(__file__),
                'zebra/lib/common',
                f'zebra_params_{x}_{y}.pv')
            zebra = pvzebra.create(access_key=access_key, model_path=model_path)
            print(f"[OK] Zebra Translation[V{zebra.version}][{x.upper()} → {y.upper()}]")
            zebras.append(zebra)

        for x, y in zip(reversed(languages), genders):
            model_path = os.path.join(
                os.path.dirname(__file__),
                'orca/lib/common',
                f'orca_params_{x}_{y}.pv')
            orca = pvorca.create(access_key=access_key, model_path=model_path)
            print(f"[OK] Orca Streaming Text-to-Speech[V{orca.version}][{x.upper()}]")
            orcas.append(orca)
        print()

        recorder = PvRecorder(frame_length=cheetahs[0].frame_length)

        speaker = PvSpeaker(sample_rate=orcas[0].sample_rate, bits_per_sample=16)
        speaker.start()

        index = 0
        while True:
            text = ""
            text_lock = Lock()

            def get_text() -> str:
                with text_lock:
                    display_text = "(listening)" if text == "" else text
                    return f"HUMAN[{languages[index].upper()}] {display_text}"

            text_event, text_thread = print_async(get_text)

            recorder.start()
            while len(text) == 0:
                is_endpoint = False
                while not is_endpoint:
                    partial, is_endpoint = cheetahs[index].process(recorder.read())
                    with text_lock:
                        text += partial

                remainder = cheetahs[index].flush()
                with text_lock:
                    text += remainder

            recorder.stop()
            text_event.set()
            text_thread.join()
            print()

            translation = zebras[index].translate(text)
            pcm, alignments = orcas[index].synthesize(translation)

            utterance = ""
            utterance_lock = Lock()

            def get_utterance() -> str:
                with utterance_lock:
                    return f"AI[{languages[(index + 1) % 2].upper()}] {utterance}"

            utterance_event, utterance_thread = print_async(get_utterance)

            def update_utterance(chunk: str) -> None:
                nonlocal utterance
                with utterance_lock:
                    utterance += chunk

            timer_thread = time_async(alignments=alignments, on_tick=update_utterance)

            speaker.flush(pcm)
            timer_thread.join()
            utterance_event.set()
            utterance_thread.join()
            print('\n')

            index = (index + 1) % 2
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

        for orca in orcas:
            orca.delete()

        for zebra in zebras:
            zebra.delete()

        for cheetah in cheetahs:
            cheetah.delete()


if __name__ == '__main__':
    main()
