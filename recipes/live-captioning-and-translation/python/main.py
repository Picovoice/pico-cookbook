import array
import os
import re
import shutil
import sys
import wave
from argparse import ArgumentParser
from enum import Enum
from threading import (
    Event,
    Lock,
    Thread
)
from time import (
    sleep,
    time
)
from typing import (
    Callable,
    Tuple
)

import pvcheetah
import pvzebra
from pvrecorder import PvRecorder
from pvspeaker import PvSpeaker


class LanguagePairs(Enum):
    DE_DE = "de-de"
    DE_EN = "de-en"
    DE_ES = "de-es"
    DE_FR = "de-fr"
    DE_IT = "de-it"
    EN_DE = "en-de"
    EN_EN = "en-en"
    EN_ES = "en-es"
    EN_FR = "en-fr"
    EN_IT = "en-it"
    ES_DE = "es-de"
    ES_EN = "es-en"
    ES_ES = "es-es"
    ES_FR = "es-fr"
    ES_IT = "es-it"
    FR_DE = "fr-de"
    FR_EN = "fr-en"
    FR_ES = "fr-es"
    FR_FR = "fr-fr"
    IT_DE = "it-de"
    IT_EN = "it-en"
    IT_ES = "it-es"
    IT_FR = "it-fr"
    IT_IT = "it-it"


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


def main_microphone(
        access_key: str,
        source_language: str,
        target_language: str,
        endpoint_duration_sec: float,
        disable_text_normalization: bool,
) -> None:
    recorder = None
    cheetah = None
    zebra = None

    try:
        cheetah_model_path = os.path.join(
            os.path.dirname(__file__),
            'cheetah/lib/common',
            'cheetah_params.pv' if source_language == 'en' else f'cheetah_params_{source_language}.pv')
        cheetah = pvcheetah.create(
            access_key=access_key,
            model_path=cheetah_model_path,
            endpoint_duration_sec=endpoint_duration_sec,
            enable_automatic_punctuation=True,
            enable_text_normalization=not disable_text_normalization)
        print(f"[OK] Cheetah Streaming Speech-to-Text[V{cheetah.version}][{source_language.upper()}]")

        if source_language != target_language:
            zebra_model_path = os.path.join(
                os.path.dirname(__file__),
                'zebra/lib/common',
                f'zebra_params_{source_language}_{target_language}.pv')
            zebra = pvzebra.create(access_key=access_key, model_path=zebra_model_path)
            print(f"[OK] Zebra Translation[V{zebra.version}][{source_language.upper()} → {target_language.upper()}]")

        recorder = PvRecorder(frame_length=cheetah.frame_length)
        recorder.start()

        print()

        text = ""
        text_lock = Lock()

        def get_text():
            with text_lock:
                display_text = "(listening)" if text == "" else text
                return f"[{source_language.upper()}] {display_text}"

        text_event, text_thread = print_async(get_text)

        while True:
            partial, is_endpoint = cheetah.process(recorder.read())
            with text_lock:
                text += partial
            if is_endpoint:
                remainder = cheetah.flush()
                with text_lock:
                    text += remainder
                if len(text) > 0:
                    text_event.set()
                    text_thread.join()

                    if target_language != source_language:
                        print(f"[{target_language.upper()}] {zebra.translate(text)}")

                    print()
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

        if recorder is not None:
            recorder.stop()
            recorder.delete()


def main_file(
        access_key: str,
        source_language: str,
        target_language: str,
        wave_path: str,
        endpoint_duration_sec: float,
        disable_text_normalization: bool
) -> None:
    with wave.open(wave_path, "rb") as f:
        assert f.getnchannels() == 1
        assert f.getsampwidth() == 2
        assert f.getframerate() == 16000
        assert f.getcomptype() == 'NONE'
        num_frames = f.getnframes()
        audio_bytes = f.readframes(num_frames)
        audio = array.array("h")
        audio.frombytes(audio_bytes)

    speaker = None
    cheetah = None
    zebra = None

    try:
        cheetah_model_path = os.path.join(
            os.path.dirname(__file__),
            'cheetah/lib/common',
            'cheetah_params.pv' if source_language == 'en' else f'cheetah_params_{source_language}.pv')
        cheetah = pvcheetah.create(
            access_key=access_key,
            model_path=cheetah_model_path,
            endpoint_duration_sec=endpoint_duration_sec,
            enable_automatic_punctuation=True,
            enable_text_normalization=not disable_text_normalization)
        print(f"[OK] Cheetah Streaming Speech-to-Text[V{cheetah.version}][{source_language.upper()}]")

        if source_language != target_language:
            zebra_model_path = os.path.join(
                os.path.dirname(__file__),
                'zebra/lib/common',
                f'zebra_params_{source_language}_{target_language}.pv')
            zebra = pvzebra.create(access_key=access_key, model_path=zebra_model_path)
            print(f"[OK] Zebra Translation[V{zebra.version}][{source_language.upper()} → {target_language.upper()}]")

        speaker = PvSpeaker(sample_rate=cheetah.sample_rate, bits_per_sample=16)
        speaker.start()

        print()

        text = ""
        text_lock = Lock()

        def get_text():
            with text_lock:
                return f"[{source_language.upper()}] {text}"

        text_event, text_thread = print_async(get_text)

        playback_start_sec = time()
        played_samples = 0

        for i in range(len(audio) // cheetah.frame_length):
            frame = audio[i * cheetah.frame_length:(i + 1) * cheetah.frame_length]

            partial, is_endpoint = cheetah.process(frame)
            speaker.write(frame)

            played_samples += len(frame)
            playback_time_sec = played_samples / cheetah.sample_rate
            delay_sec = playback_start_sec + playback_time_sec - time()
            if delay_sec > 0:
                sleep(delay_sec)

            with text_lock:
                text += partial

            if is_endpoint:
                remainder = cheetah.flush()
                with text_lock:
                    text += remainder
                if len(text) > 0:
                    text_event.set()
                    text_thread.join()

                    if target_language != source_language:
                        print(f"[{target_language.upper()}] {zebra.translate(text)}")

                    print()
                    text = ""
                    text_event, text_thread = print_async(get_text)
            elif bool(re.search(r'[.!?](?:\s|$)', text)):
                match = re.match(r'^((?:.*?[.!?](?:\s+|$))+)?(.*)$', text, re.DOTALL)
                full_sentences = (match.group(1) or '').strip()
                tail = match.group(2).strip()

                text = full_sentences + " " * len(tail)
                sleep(.1)
                text_event.set()
                text_thread.join()

                if target_language != source_language:
                    print(f"[{target_language.upper()}] {zebra.translate(full_sentences)}")

                print()
                text = tail
                text_event, text_thread = print_async(get_text)
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


def main() -> None:
    parser = ArgumentParser()
    parser.add_argument(
        '--access_key',
        required=True,
        help='AccessKey obtained from Picovoice Console (https://console.picovoice.ai/)')
    parser.add_argument(
        '--language_pair',
        required=True,
        choices=[x.value for x in LanguagePairs],
        help='Translation language pair.')
    parser.add_argument(
        '--wave_path',
        help='')
    parser.add_argument(
        '--endpoint_duration_sec',
        type=float,
        default=.25,
        help='Duration of silence in seconds required to detect the end of an utterance.')
    parser.add_argument(
        '--disable_text_normalization',
        action='store_true',
        help='Disable text normalization in Streaming Speech-to-Text.')
    args = parser.parse_args()

    access_key = args.access_key
    language_pair = LanguagePairs(args.language_pair)
    wave_path = args.wave_path
    endpoint_duration_sec = args.endpoint_duration_sec
    disable_text_normalization = args.disable_text_normalization

    source_language, target_language = [x for x in language_pair.value.split('-')]

    if source_language == target_language:
        print(f"Transcribing `{source_language}`.")
    else:
        print(f"Translating from `{source_language}` to `{target_language}`.")

    if wave_path is None:
        main_microphone(
            access_key=access_key,
            source_language=source_language,
            target_language=target_language,
            endpoint_duration_sec=endpoint_duration_sec,
            disable_text_normalization=disable_text_normalization)
    else:
        main_file(
            access_key=access_key,
            source_language=source_language,
            target_language=target_language,
            wave_path=wave_path,
            endpoint_duration_sec=endpoint_duration_sec,
            disable_text_normalization=disable_text_normalization)


if __name__ == '__main__':
    main()
