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
import pvrhino
import picollm
import pvcheetah
import pvorca
from pvcheetah import Cheetah
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
            self.DECLINE_CALL: "Sorry, {username} is unavailable right now.",
            self.ASK_FOR_DETAILS: "Can you briefly say who you are and what this is regarding?",
            self.ASK_TO_TEXT: "{username} can't talk right now. Please send a text message instead.",
            self.ASK_TO_EMAIL: "Please send the details by email. Thank you.",
            self.ASK_TO_CALL_BACK: "{username} can't take your call right now. Please call back later.",
            self.BLOCK_CALLER: "This number is not accepting calls.",
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


SYSTEM = """Extract call information.

Return exactly two lines:
caller: <one short value>
reason: <one short value>

Rules:
- Use exactly one value for caller.
- Use exactly one value for reason.
- Do not list alternatives.
- Do not use commas.
- Do not explain.
- If the caller says a company or organization, use that as caller.
- If the caller says only a generic role like customer service, use that as caller.
- If the caller does not say who they are, use unknown.
- If the caller does not say why they are calling, use unknown.
- Use lowercase unless the caller gives a proper name.

Examples:
Caller said: "I'm calling from the bank."
caller: bank
reason: unknown

Caller said: "This is UPS with a package delivery."
caller: UPS
reason: package delivery

Caller said: "This is customer service."
caller: customer service
reason: unknown

Caller said: "I'm calling about your credit card."
caller: unknown
reason: credit card

Caller said: "Hello, can you hear me?"
caller: unknown
reason: unknown
"""


def extract_caller_and_reason_from_llm_inference(inference: str) -> Tuple[str, str]:
    inference_lines = inference.split("\n")
    if len(inference_lines) != 2:
        return "unknown", "unknown"

    caller_line = inference_lines[0]
    reason_line = inference_lines[1]

    if not caller_line.startswith("caller: "):
        return "unknown", "unknown"
    caller = caller_line[len("caller: "):]
    if len(caller) == 0:
        return "unknown", "unknown"

    if not reason_line.startswith("reason: "):
        return "unknown", "unknown"
    reason = reason_line[len("reason: "):]
    if len(reason) == 0:
        return "unknown", "unknown"

    return caller, reason


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


def record_and_transcribe(cheetah: Cheetah, recorder: PvRecorder) -> str:
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

    return text


def main() -> None:
    parser = ArgumentParser()
    parser.add_argument(
        "--access_key",
        required=True,
        help="AccessKey obtained from Picovoice Console (https://console.picovoice.ai/).")
    parser.add_argument(
        '--picollm_model_path',
        required=True,
        help='Absolute path to the picoLLM model file (`.pllm`).')
    parser.add_argument(
        '--context_path',
        required=True,
        help="Path to the Rhino Speech-to-Intent context file trained on Picovoice Console "
             "(https://console.picovoice.ai/).")
    parser.add_argument(
        "--username",
        default="the recipient",
        help="Name of the person receiving the call. Used in spoken prompts.")
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
    parser.add_argument(
        '--picollm_device',
        help="String representation of the device to use for picoLLM inference. If set to `best`, picoLLM picks the "
             "most suitable device. If set to `gpu`, picoLLM uses the first available GPU. To select a specific GPU, "
             "set this argument to `gpu:${GPU_INDEX}`, where `${GPU_INDEX}` is the index of the target GPU. If set to "
             "`cpu`, picoLLM runs on the CPU with the default number of threads. To specify the number of threads, set "
             "this argument to `cpu:${NUM_THREADS}`, where `${NUM_THREADS}` is the desired number of threads.")
    parser.add_argument(
        '--ask_for_details_retry_limit',
        type=int,
        default=2,
        help="Maximum number of times to ask the caller for missing identity or reason details before declining the "
             "call.")
    args = parser.parse_args()

    access_key = args.access_key
    picollm_model_path = args.picollm_model_path
    context_path = args.context_path
    username = args.username
    username_pronunciation = args.username_pronunciation
    endpoint_duration_sec = args.endpoint_duration_sec
    picollm_device = args.picollm_device
    ask_for_details_retry_limit = args.ask_for_details_retry_limit

    username_orca = username
    if username_pronunciation is not None:
        username_orca = f"{{{username}|{' '.join(username_pronunciation)}}}"

    cheetah = None
    llm = None
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
        print(f"[OK] Cheetah Streaming Speech-to-Text [V{cheetah.version}]")

        llm = picollm.create(
            access_key=access_key,
            model_path=picollm_model_path,
            device=picollm_device)
        print(f"[OK] picoLLM Inference [V{llm.version}]")

        orca = pvorca.create(access_key=access_key)
        print(f"[OK] Orca Streaming Text-to-Speech [V{orca.version}]")

        rhino = pvrhino.create(
            access_key=access_key,
            context_path=context_path,
            require_endpoint=False)
        print(f"[OK] Rhino Speech-to-Intent [V{rhino.version}]")

        recorder = PvRecorder(frame_length=cheetah.frame_length)

        speaker = PvSpeaker(sample_rate=orca.sample_rate, bits_per_sample=16)
        speaker.start()

        print()

        ask_for_details_retry_count = 0
        while True:
            synthesize_and_playback(
                orca=orca,
                speaker=speaker,
                text=action.prompt(username_orca))

            if action.is_terminal():
                return

            text = record_and_transcribe(cheetah=cheetah, recorder=recorder)

            dialog = llm.get_dialog(system=SYSTEM)
            dialog.add_human_request(f"Caller said: \"{text}\"\n")
            completion = llm.generate(
                prompt=dialog.prompt(),
                stop_phrases={'<|eot_id|>'})
            inference = completion.completion.strip('\n ').replace('<|eot_id|>', '')
            caller, reason = extract_caller_and_reason_from_llm_inference(inference)
            if caller == 'unknown' or reason == 'unknown':
                if caller == 'unknown' and reason == 'unknown':
                    print("[AI] Unknown caller with no specific reason. I will ask for more information.")
                elif caller == 'unknown':
                    print(
                        f"[AI] Unknown caller is trying to speak with you about `{reason}`. "
                        f"I will ask for their identity.")
                else:
                    print(f"[AI] `{caller}` is trying to speak with you. I will ask for their reason.")

                if ask_for_details_retry_count < ask_for_details_retry_limit:
                    action = Actions.ASK_FOR_DETAILS
                else:
                    action = Actions.DECLINE_CALL
            else:
                print(f"[AI] `{caller}` is trying to speak with you about `{reason}`.")
                break

            ask_for_details_retry_count += 1

        synthesize_and_playback(
            orca=orca,
            speaker=speaker,
            text=f"{caller} is trying to speak to you about {reason}.")

        while True:
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

            synthesize_and_playback(
                orca=orca,
                speaker=speaker,
                text=action.prompt(username_orca))

            if action.is_terminal():
                break

            record_and_transcribe(cheetah=cheetah, recorder=recorder)
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

        if llm is not None:
            llm.release()

        if cheetah is not None:
            cheetah.delete()


if __name__ == "__main__":
    main()
