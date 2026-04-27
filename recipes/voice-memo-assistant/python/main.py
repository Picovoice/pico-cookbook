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
import pvporcupine
import pvrhino
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


def synthesize_and_playback(orca: Orca, speaker: PvSpeaker, text: str) -> None:
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


def main() -> None:
    parser = ArgumentParser()
    parser.add_argument(
        "--access_key",
        required=True,
        help="AccessKey obtained from Picovoice Console (https://console.picovoice.ai/).")
    parser.add_argument(
        '--keyword_path',
        required=True,
        help="Path to the Porcupine wake word file trained on Picovoice Console "
             "(https://console.picovoice.ai/).")
    parser.add_argument(
        '--context_path',
        required=True,
        help="Path to the Rhino Speech-to-Intent context file trained on Picovoice Console "
             "(https://console.picovoice.ai/).")
    parser.add_argument(
        '--picollm_model_path',
        required=True,
        help='Absolute path to the picoLLM model file (`.pllm`).')
    parser.add_argument(
        "--endpoint_duration_sec",
        type=float,
        default=1.0,
        help="Duration of silence, in seconds, required to detect the end of the memo recording.")
    parser.add_argument(
        '--picollm_device',
        help="String representation of the device to use for picoLLM inference. If set to `best`, picoLLM picks the "
             "most suitable device. If set to `gpu`, picoLLM uses the first available GPU. To select a specific GPU, "
             "set this argument to `gpu:${GPU_INDEX}`, where `${GPU_INDEX}` is the index of the target GPU. If set to "
             "`cpu`, picoLLM runs on the CPU with the default number of threads. To specify the number of threads, set "
             "this argument to `cpu:${NUM_THREADS}`, where `${NUM_THREADS}` is the desired number of threads.")
    args = parser.parse_args()

    access_key = args.access_key
    keyword_path = args.keyword_path
    context_path = args.context_path
    picollm_model_path = args.picollm_model_path
    endpoint_duration_sec = args.endpoint_duration_sec
    picollm_device = args.picollm_device

    porcupine = None
    rhino = None
    cheetah = None
    llm = None
    orca = None
    recorder = None
    speaker = None
    recording = None

    try:
        porcupine = pvporcupine.create(
            access_key=access_key,
            keyword_paths=[keyword_path])
        print(f"[OK] Porcupine Wake Word [V{porcupine.version}]")

        rhino = pvrhino.create(
            access_key=access_key,
            context_path=context_path,
            endpoint_duration_sec=0.5,
            require_endpoint=False)
        print(f"[OK] Rhino Speech-to-Intent [V{rhino.version}]")

        cheetah = pvcheetah.create(
            access_key=access_key,
            endpoint_duration_sec=endpoint_duration_sec,
            enable_automatic_punctuation=True,
            enable_text_normalization=True)
        print(f"[OK] Cheetah Streaming Speech-to-Text [V{cheetah.version}]")

        llm = picollm.create(
            access_key=access_key,
            model_path=picollm_model_path,
            device=picollm_device)
        print(f"[OK] picoLLM Inference [V{llm.version}]")

        orca = pvorca.create(access_key=access_key)
        print(f"[OK] Orca Streaming Text-to-Speech [V{orca.version}]")

        recorder = PvRecorder(frame_length=cheetah.frame_length)
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

            is_understood = False
            print_event, print_thread = print_async(get_text=lambda: "Say a voice command")

            while not is_understood:
                while not rhino.process(recorder.read()):
                    pass
                recorder.stop()
                print_event.set()
                print_thread.join()

                inference = rhino.get_inference()
                is_understood = inference.is_understood
                if is_understood:
                    if inference.intent == 'startMemo':
                        recorder.start()

                        recording = ""
                        recording_lock = Lock()

                        def get_recording() -> str:
                            with recording_lock:
                                display_text = "(listening)" if recording_lock == "" else recording
                                return f"[MEMO] {display_text}"

                        text_event, text_thread = print_async(get_recording)

                        while True:
                            partial, is_endpoint = cheetah.process(recorder.read())
                            with recording_lock:
                                recording += partial
                            if is_endpoint:
                                remainder = cheetah.flush()
                                with recording_lock:
                                    recording += remainder
                                    recording += ' '

                            if recording.lower().strip(' .').endswith('stop recording'):
                                with recording_lock:
                                    recording = recording.rstrip(' .')[:-len('stop recording')]
                                text_event.set()
                                text_thread.join()
                                break

                        recorder.stop()
                    elif inference.intent == 'readMemo':
                        if recording is not None:
                            synthesize_and_playback(orca=orca, speaker=speaker, text=recording)
                        else:
                            synthesize_and_playback(orca=orca, speaker=speaker, text="Please record a memo first.")
                    elif inference.intent == 'summarizeMemo':
                        if recording is not None:
                            dialog = llm.get_dialog()
                            dialog.add_human_request(f"""Summarize the memo below.

Return only the summary.

Rules:
- Do not include an intro.
- Do not include a label.
- Do not use quotes.
- Do not explain your changes.
- Keep the important details.
- Fix obvious transcription errors only when the meaning is clear.
- Do not add new information.
- Use one short sentence.

Memo:
{recording}

Summary:""")
                            print_event, print_thread = print_async(get_text=lambda: "Summarizing memo")
                            completion = llm.generate(
                                prompt=dialog.prompt(),
                                stop_phrases={'<|eot_id|>'})
                            print_event.set()
                            print_thread.join()
                            recording = completion.completion.strip('<|eot_id|>')
                        else:
                            synthesize_and_playback(orca=orca, speaker=speaker, text="Please record a memo first.")
                    elif inference.intent == 'rewriteMemo':
                        if recording is not None:
                            dialog = llm.get_dialog()
                            dialog.add_human_request(f"""Rewrite the memo below.

Return only the rewritten memo.

Rules:
- Do not include an intro.
- Do not include a label.
- Do not use quotes.
- Do not explain your changes.
- Fix grammar, punctuation, casing, repeated words, filler words, and false starts.
- Preserve the original meaning.
- Do not summarize.
- Do not add new information.

Memo:
{recording}

Rewritten memo:""")
                            print_event, print_thread = print_async(get_text=lambda: "Rewriting memo")
                            completion = llm.generate(
                                prompt=dialog.prompt(),
                                stop_phrases={'<|eot_id|>'})
                            print_event.set()
                            print_thread.join()
                            recording = completion.completion.strip('<|eot_id|>')
                        else:
                            synthesize_and_playback(orca=orca, speaker=speaker, text="Please record a memo first.")
                else:
                    synthesize_and_playback(
                        orca=orca,
                        speaker=speaker,
                        text="Sorry, I didn't understand that. Please try again.")

                recorder.start()
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

        if llm is not None:
            llm.release()

        if cheetah is not None:
            cheetah.delete()

        if rhino is not None:
            rhino.delete()

        if porcupine is not None:
            porcupine.delete()


if __name__ == "__main__":
    main()
