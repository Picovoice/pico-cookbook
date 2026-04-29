import shutil
import string
import sys
from argparse import ArgumentParser
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

import picollm
import pvcheetah
import pvorca
from PIL import Image
from pvorca import Orca
from pvrecorder import PvRecorder
from pvspeaker import PvSpeaker


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
                stop_event.wait(refresh_sec)

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
        '--picollm_model_path',
        required=True,
        help='Absolute path to the picoLLM VLM model file (`.pllm`).')
    parser.add_argument(
        '--image_path',
        required=True,
        help='Absolute path to the image file.')
    parser.add_argument(
        '--picollm_device',
        default="best",
        help="String representation of the device to use for picoLLM inference. If set to `best`, picoLLM picks the "
             "most suitable device. If set to `gpu`, picoLLM uses the first available GPU. To select a specific GPU, "
             "set this argument to `gpu:${GPU_INDEX}`, where `${GPU_INDEX}` is the index of the target GPU. If set to "
             "`cpu`, picoLLM runs on the CPU with the default number of threads. To specify the number of threads, set "
             "this argument to `cpu:${NUM_THREADS}`, where `${NUM_THREADS}` is the desired number of threads.")
    parser.add_argument(
        "--endpoint_duration_sec",
        type=float,
        default=1.0,
        help="Duration of silence, in seconds, required to detect the end of the caller's utterance.")
    args = parser.parse_args()

    access_key = args.access_key
    picollm_model_path = args.picollm_model_path
    image_path = args.image_path
    endpoint_duration_sec = args.endpoint_duration_sec
    picollm_device = args.picollm_device

    cheetah = None
    vlm = None
    orca = None
    recorder = None
    speaker = None

    try:
        cheetah = pvcheetah.create(
            access_key=access_key,
            endpoint_duration_sec=endpoint_duration_sec,
            enable_automatic_punctuation=True,
            enable_text_normalization=True)
        print(f"[OK] Cheetah Streaming Speech-to-Text [V{cheetah.version}]")

        vlm = picollm.create(
            access_key=access_key,
            model_path=picollm_model_path,
            device=picollm_device)
        print(f"[OK] picoLLM Inference [V{vlm.version}]")

        orca = pvorca.create(access_key=access_key)
        print(f"[OK] Orca Streaming Text-to-Speech [V{orca.version}]")

        recorder = PvRecorder(frame_length=cheetah.frame_length)
        recorder.start()

        speaker = PvSpeaker(sample_rate=orca.sample_rate, bits_per_sample=16)
        speaker.start()

        print()

        image = Image.open(image_path).convert("RGB")

        while True:
            question = ""
            question_lock = Lock()

            def get_question() -> str:
                with question_lock:
                    display_question = "(listening)" if question == "" else question
                    return f"[Q] {display_question}"

            question_event, question_thread = print_async(get_question)

            while True:
                partial, is_endpoint = cheetah.process(recorder.read())
                with question_lock:
                    question += partial

                if is_endpoint:
                    remainder = cheetah.flush()
                    with question_lock:
                        question += remainder

                    if len(question) > 0:
                        break

            question_event.set()
            question_thread.join()
            recorder.stop()


            completion = vlm.generate_with_image(
                prompt=question,
                image_width=image.width,
                image_height=image.height,
                image=image.tobytes(),
                completion_token_limit=256,
                stop_phrases={'<|im_end|>'},
                frequency_penalty=2)
            answer = completion.completion.replace('<|im_end|>', '')
            print(answer)

            recorder.start()
    except KeyboardInterrupt:
        pass
    finally:
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

        if vlm is not None:
            vlm.release()

        if cheetah is not None:
            cheetah.delete()


if __name__ == "__main__":
    main()
