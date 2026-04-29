import queue
import re
import shutil
import sys
from argparse import ArgumentParser
from threading import (
    Event,
    Lock,
    Thread
)
from typing import (
    Callable,
    Optional,
    Set,
    Tuple
)

import picollm
import pvorca
from PIL import Image
from picollm import PicoLLM
from pvorca import Orca
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


def sanitize_for_orca(text: str, valid_characters: Set[str]) -> str:
    valid_character_set = set(valid_characters)

    replacements = {
        "\n": " ",
        "\r": " ",
        "\t": " ",
        "“": '"',
        "”": '"',
        "‘": "'",
        "’": "'",
        "—": "-",
        "–": "-",
        "…": "...",
    }

    text = "".join(replacements.get(x, x) for x in text)
    text = "".join(x if x in valid_character_set else " " for x in text)
    text = re.sub(r"\s+", " ", text)

    return text


def stream_ocr_result(
        ocr: PicoLLM,
        orca: Orca,
        speaker: PvSpeaker,
        image: Image.Image,
) -> str:
    text_queue: queue.Queue[Optional[str]] = queue.Queue()
    tts_error_queue: queue.Queue[BaseException] = queue.Queue()

    extracted_text = ""
    extracted_text_lock = Lock()

    pending_speech_text = ""
    pending_speech_lock = Lock()

    progress = ""
    progress_lock = Lock()

    sentence_end_pattern = re.compile(r"([.!?;:])\s+")

    def get_extracted_text() -> str:
        with extracted_text_lock:
            if len(extracted_text) == 0:
                with progress_lock:
                    if len(progress) > 0:
                        return progress
                return "[OCR] (reading image)"

            return f"[OCR] {extracted_text}"

    def update_progress(x: float) -> None:
        nonlocal progress
        with progress_lock:
            progress = f"{x:.2f}%"

    def pop_speakable_text(force: bool = False) -> str:
        nonlocal pending_speech_text

        with pending_speech_lock:
            text = pending_speech_text

            if len(text.strip()) == 0:
                return ""

            last_end = -1
            for match in sentence_end_pattern.finditer(text):
                last_end = match.end()

            if last_end > 0:
                speakable = text[:last_end]
                pending_speech_text = text[last_end:]
                return speakable

            if len(text) >= 180:
                split = text.rfind(" ", 0, 180)
                if split > 60:
                    speakable = text[:split + 1]
                    pending_speech_text = text[split + 1:]
                    return speakable

            if force:
                speakable = text
                pending_speech_text = ""
                return speakable

            return ""

    def tts_worker() -> None:
        stream = None

        try:
            stream = orca.stream_open()

            while True:
                text = text_queue.get()
                if text is None:
                    break

                speech_text = sanitize_for_orca(
                    text=text,
                    valid_characters=orca.valid_characters)

                if len(speech_text.strip()) == 0:
                    continue

                pcm = stream.synthesize(speech_text)
                if pcm is not None and len(pcm) > 0:
                    speaker.flush(pcm)

            pcm = stream.flush()
            if pcm is not None and len(pcm) > 0:
                speaker.flush(pcm)

        except BaseException as e:
            tts_error_queue.put(e)

        finally:
            if stream is not None:
                stream.close()

    def on_ocr_stream(text: str) -> None:
        nonlocal extracted_text
        nonlocal pending_speech_text

        text = (
            text
            .replace('<|im_end|>', '')
            .replace('<｜end▁of▁sentence｜>', '')
        )
        if len(text) == 0:
            return

        with extracted_text_lock:
            extracted_text += text

        with pending_speech_lock:
            pending_speech_text += text

        while True:
            speakable_text = pop_speakable_text(force=False)
            if len(speakable_text) == 0:
                break

            text_queue.put(speakable_text)

    ocr_event, ocr_thread = print_async(get_extracted_text)
    tts_thread = Thread(target=tts_worker, daemon=True)
    tts_thread.start()

    try:
        completion = ocr.generate_ocr(
            image_width=image.width,
            image_height=image.height,
            image=image.tobytes(),
            stream_callback=on_ocr_stream,
            prompt_progress_callback=update_progress)

        final_text = (
            completion.completion
            .strip()
            .replace('<|im_end|>', '')
            .replace('<｜end▁of▁sentence｜>', '')
        )

        with extracted_text_lock:
            if len(final_text) > 0:
                extracted_text = final_text
            elif len(extracted_text.strip()) == 0:
                extracted_text = "No text was detected."

        remaining_speech_text = pop_speakable_text(force=True)
        if len(remaining_speech_text) > 0:
            text_queue.put(remaining_speech_text)

        text_queue.put(None)
        tts_thread.join()

        if not tts_error_queue.empty():
            raise tts_error_queue.get()

        ocr_event.set()
        ocr_thread.join()

        with extracted_text_lock:
            return extracted_text.strip()

    except BaseException:
        text_queue.put(None)
        tts_thread.join(timeout=1.0)
        ocr_event.set()
        ocr_thread.join()
        raise


def main() -> None:
    parser = ArgumentParser()
    parser.add_argument(
        "--access_key",
        required=True,
        help="AccessKey obtained from Picovoice Console (https://console.picovoice.ai/).")
    parser.add_argument(
        '--picollm_model_path',
        required=True,
        help='Absolute path to the picoLLM OCR model file (`.pllm`).')
    parser.add_argument(
        '--image_path',
        required=True,
        help='Absolute path to the image file to read.')
    parser.add_argument(
        '--picollm_device',
        default="best",
        help="String representation of the device to use for picoLLM inference. If set to `best`, picoLLM picks the "
             "most suitable device. If set to `gpu`, picoLLM uses the first available GPU. To select a specific GPU, "
             "set this argument to `gpu:${GPU_INDEX}`, where `${GPU_INDEX}` is the index of the target GPU. If set to "
             "`cpu`, picoLLM runs on the CPU with the default number of threads. To specify the number of threads, set "
             "this argument to `cpu:${NUM_THREADS}`, where `${NUM_THREADS}` is the desired number of threads.")
    args = parser.parse_args()

    access_key = args.access_key
    picollm_model_path = args.picollm_model_path
    image_path = args.image_path
    picollm_device = args.picollm_device

    ocr = None
    orca = None
    speaker = None

    try:
        ocr = picollm.create(
            access_key=access_key,
            model_path=picollm_model_path,
            device=picollm_device)
        print(f"[OK] picoLLM OCR [V{ocr.version}]")

        orca = pvorca.create(access_key=access_key)
        print(f"[OK] Orca Streaming Text-to-Speech [V{orca.version}]")

        speaker = PvSpeaker(sample_rate=orca.sample_rate, bits_per_sample=16)
        speaker.start()

        print()

        image = Image.open(image_path).convert("RGB")

        stream_ocr_result(
            ocr=ocr,
            orca=orca,
            speaker=speaker,
            image=image)

    except KeyboardInterrupt:
        pass

    finally:
        sys.stdout.write("\033[?25h")
        sys.stdout.flush()

        if speaker is not None:
            speaker.stop()
            speaker.delete()

        if orca is not None:
            orca.delete()

        if ocr is not None:
            ocr.release()


if __name__ == "__main__":
    main()