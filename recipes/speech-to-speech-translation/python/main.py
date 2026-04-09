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

import pvbat
import pvcheetah
import pvorca
import pvzebra
from pvbat import BatLanguages
from pvorca import Orca
from pvrecorder import PvRecorder
from pvspeaker import PvSpeaker


class Languages(Enum):
    DE = "de"
    EN = "en"
    ES = "es"
    FR = "fr"
    IT = "it"


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
    IT_FR = "it-fr"

    @staticmethod
    def exists(x: Languages, y: Languages) -> bool:
        try:
            LanguagePairs(f"{x.value}-{y.value}")
            return True
        except ValueError:
            return False

    @classmethod
    def supported_source_languages(cls, to_language: Languages) -> Sequence[Languages]:
        res = list()

        for language_pair in cls:
            source, target = tuple(Languages(x) for x in language_pair.value.split('-'))
            if target is to_language:
                res.append(source)

        return res


def print_async(get_text: Callable[[], str], refresh_sec: float = 0.1, end: str = '\n') -> Tuple[Event, Thread]:
    stop_event = Event()

    def run() -> None:
        dots_list = [" .  ", " .. ", " ...", "  ..", "   .", "    "]
        i = 0

        # Hide the cursor.
        sys.stdout.write("\033[?25l")
        sys.stdout.flush()

        while not stop_event.is_set():
            text = get_text()
            dots = dots_list[i]
            sys.stdout.write(f"\r{text}{dots}")
            sys.stdout.flush()

            i = (i + 1) % len(dots_list)
            sleep(refresh_sec)

        text = get_text()
        sys.stdout.write(f"\r{text}    {end}")
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
        '--access_key',
        required=True,
        help='AccessKey obtained from Picovoice Console (https://console.picovoice.ai/)')
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument(
        '--language_pair',
        choices=[x.value for x in LanguagePairs],
        help='Translation language pair. If set, the source language is not detected automatically.')
    group.add_argument(
        '--target_language',
        choices=[x.value for x in Languages],
        help='Target language for translation. If set, the demo automatically identifies the spoken source language.')
    parser.add_argument(
        '--identification_min_confidence',
        type=float,
        default=0.75,
        help='Minimum confidence required for spoken language identification when using `--target_language`.')
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
        '--gender',
        choices=['female', 'male'],
        default='male',
        help='Voice gender to use for translated speech output.')
    args = parser.parse_args()

    access_key = args.access_key
    language_pair = LanguagePairs(args.language_pair) if args.language_pair is not None else None
    target_language = Languages(args.target_language) if args.target_language is not None else None
    identification_min_confidence = args.identification_min_confidence
    endpoint_duration_sec = args.endpoint_duration_sec
    disable_automatic_punctuation = args.disable_automatic_punctuation
    disable_text_normalization = args.disable_text_normalization
    gender = args.gender

    if language_pair is not None:
        source_language, target_language = [Languages(x) for x in language_pair.value.split('-')]
    else:
        source_language = None

    bat = None
    orca = None
    recorder = None
    speaker = None
    cheetah = None
    zebra = None

    try:
        orca_model_path = os.path.join(
            os.path.dirname(__file__),
            'orca/lib/common',
            f'orca_params_{target_language.value}_{gender}.pv')
        orca = pvorca.create(access_key=access_key, model_path=orca_model_path)
        print(f"[OK] Orca Streaming Text-to-Speech[V{orca.version}][{target_language.value.upper()}]")

        recorder = PvRecorder(frame_length=160)
        recorder.start()

        speaker = PvSpeaker(sample_rate=orca.sample_rate, bits_per_sample=16)
        speaker.start()

        pcm = list()

        if source_language is None:
            def show_supported_languages() -> Tuple[Event, Thread]:
                mic_event.set()
                mic_thread.join()
                print(
                    f"[WARN]️ Cannot translate from `{source_language.value}` to `{target_language.value}`. "
                    "Supported source languages are "
                    ', '.join(f"`{x.value}`" for x in LanguagePairs.supported_source_languages(target_language)))
                return print_async(lambda: text, end="\r\033[K")

            bat = pvbat.create(access_key=access_key)
            print(f"[OK] Bat Spoken Language Identification[V{bat.version}]")

            text = "[??]"
            mic_event, mic_thread = print_async(lambda: text, end="\r\033[K")

            offset = 0

            while True:
                pcm.extend(recorder.read())

                if (len(pcm) - offset) >= bat.frame_length:
                    language_probs = bat.process(pcm[offset:bat.frame_length + offset])
                    offset += bat.frame_length

                    if language_probs is not None:
                        identified, confidence = max(language_probs.items(), key=lambda kv: kv[1])
                        if identified is not BatLanguages.UNKNOWN and confidence >= identification_min_confidence:
                            try:
                                source_language = Languages(str(identified))
                            except ValueError:
                                mic_event, mic_thread = show_supported_languages()
                                continue

                            if not LanguagePairs.exists(source_language, target_language):
                                mic_event, mic_thread = show_supported_languages()
                            else:
                                mic_event.set()
                                mic_thread.join()
                                break

        print(f"Translating from `{source_language.value}` to `{target_language.value}`.")

        cheetah_model_path = os.path.join(
            os.path.dirname(__file__),
            'cheetah/lib/common',
            'cheetah_params.pv' if source_language is Languages.EN else f'cheetah_params_{source_language.value}.pv')
        cheetah = pvcheetah.create(
            access_key=access_key,
            model_path=cheetah_model_path,
            endpoint_duration_sec=endpoint_duration_sec,
            enable_automatic_punctuation=not disable_automatic_punctuation,
            enable_text_normalization=not disable_text_normalization)
        print(f"[OK] Cheetah Streaming Speech-to-Text[V{cheetah.version}][{source_language.value.upper()}]")

        zebra_model_path = os.path.join(
            os.path.dirname(__file__),
            'zebra/lib/common',
            f'zebra_params_{source_language.value}_{target_language.value}.pv')
        zebra = pvzebra.create(access_key=access_key, model_path=zebra_model_path)
        print(
            f"[OK] Zebra Translation[V{zebra.version}][{source_language.value.upper()} → "
            f"{target_language.value.upper()}]")
        print()

        text = ""
        text_lock = Lock()

        def get_text():
            with text_lock:
                return f"[{source_language.value.upper()}] {text}"

        text_event, text_thread = print_async(get_text)

        while True:
            while len(pcm) < cheetah.frame_length:
                pcm.extend(recorder.read())

            while len(pcm) >= cheetah.frame_length:
                partial, is_endpoint = cheetah.process(pcm[:cheetah.frame_length])
                with text_lock:
                    text += partial
                pcm = pcm[cheetah.frame_length:]
                if is_endpoint:
                    remainder = cheetah.flush()
                    with text_lock:
                        text += remainder
                    if len(text) > 0:
                        recorder.stop()
                        text_event.set()
                        text_thread.join()

                        translation = zebra.translate(text)
                        pcm_translation, alignments = orca.synthesize(translation)

                        utterance = ""
                        utterance_lock = Lock()

                        def get_utterance() -> str:
                            with utterance_lock:
                                return f"[{target_language.value.upper()}] {utterance}"

                        def update_utterance(chunk: str) -> None:
                            nonlocal utterance
                            with utterance_lock:
                                utterance += chunk

                        utterance_event, utterance_thread = print_async(get_utterance)

                        timer_thread = time_async(alignments=alignments, on_tick=update_utterance)

                        speaker.flush(pcm_translation)
                        timer_thread.join()
                        utterance_event.set()
                        utterance_thread.join()

                        text = ""
                        text_event, text_thread = print_async(get_text)

                        recorder.start()
    except KeyboardInterrupt:
        pass
    finally:
        # Make the cursor visible again.
        sys.stdout.write("\033[?25h")
        sys.stdout.flush()

        if zebra is not None:
            zebra.delete()

        if cheetah is not None:
            cheetah.delete()

        if speaker is not None:
            speaker.stop()
            speaker.delete()

        if recorder is not None:
            recorder.stop()
            recorder.delete()

        if orca is not None:
            orca.delete()

        if bat is not None:
            bat.delete()


if __name__ == '__main__':
    main()
