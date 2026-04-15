import string
from argparse import ArgumentParser
from collections import deque
from enum import Enum
from threading import (
    Condition,
    Thread
)
from time import (
    monotonic,
    sleep
)
from typing import Sequence

import pvcheetah
import pvorca
from pvorca import Orca
from pvrecorder import PvRecorder
from pvspeaker import PvSpeaker

GREETING = "Hi, {username} can't answer right now. Please say your name and why you're calling."


class Actions(Enum):
    GREET = 0
    CONNECT_CALL = 1
    DECLINE_CALL = 2
    ASK_FOR_DETAILS = 3
    ASK_TO_TEXT = 4
    ASK_TO_EMAIL = 5
    ASK_TO_CALL_BACK = 6
    BLOCK_CALLER = 7

    def __str__(self) -> str:
        return {
            self.GREET: "Greet",
            self.CONNECT_CALL: "Connect Call",
            self.DECLINE_CALL: "Decline Call",
            self.ASK_FOR_DETAILS: "Ask for Details",
            self.ASK_TO_TEXT: "Ask to Text",
            self.ASK_TO_EMAIL: "Ask to Email",
            self.ASK_TO_CALL_BACK: "Ask to Call Back",
            self.BLOCK_CALLER: "Block Caller",
        }[self]

    def prompt(self, username: str) -> str:
        return {
            self.GREET: "Hi, {username} can't answer right now. Please say your name and why you're calling.",
            self.CONNECT_CALL: "Okay, one moment while I connect you.",
            self.DECLINE_CALL: "Sorry, {username} is unavailable right now.",
            self.ASK_FOR_DETAILS: "Can you briefly say what this is regarding?",
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


class PicoOut(Thread):
    def __init__(
            self,
            refresh_sec: float = 0.1
    ) -> None:
        super().__init__(daemon=True)

        self._refresh_sec = refresh_sec
        self._cv = Condition()
        self._queue = deque()
        self._stop_flag = False

    def stop(self) -> None:
        with self._cv:
            self._stop_flag = True
            self._cv.notify_all()

    def run(self) -> None:
        while True:
            with self._cv:
                while not self._queue and not self._stop_flag:
                    self._cv.wait()

                if self._stop_flag:
                    return

                word_alignments = self._queue.popleft()

            self._play_orca(word_alignments)

    def follow_orca(self, word_alignments: Sequence[Orca.WordAlignment]) -> None:
        with self._cv:
            if self._stop_flag:
                return
            self._queue.append(word_alignments)
            self._cv.notify()

    def _play_orca(self, word_alignments: Sequence[Orca.WordAlignment]) -> None:
        punctuations = string.punctuation
        start = monotonic()

        for i, alignment in enumerate(word_alignments):
            with self._cv:
                if self._stop_flag:
                    return

            delay_sec = alignment.start_sec - (monotonic() - start)
            if delay_sec > 0:
                slept = 0.0
                while slept < delay_sec:
                    with self._cv:
                        if self._stop_flag:
                            return

                    chunk = min(self._refresh_sec, delay_sec - slept)
                    sleep(chunk)
                    slept += chunk

            with self._cv:
                if self._stop_flag:
                    return

            print(
                alignment.word,
                end="" if (i == (len(word_alignments) - 1) or word_alignments[i + 1].word in punctuations) else " ",
                flush=True)

        print()

    @staticmethod
    def follow_cheetah(text: str, flushed: bool = False) -> None:
        print(text, flush=True, end='\n' if flushed else '')

    @staticmethod
    def display_options() -> None:
        for x in Actions:
            print(f"{x.value}. {str(x)}")


def main() -> None:
    parser = ArgumentParser()
    parser.add_argument(
        "--access-key",
        required=True)
    parser.add_argument(
        "--username",
        default="the recipient")
    parser.add_argument(
        "--username-pronunciation",
        nargs='+')
    parser.add_argument(
        "--endpoint-duration-sec",
        type=float,
        default=1.0)
    args = parser.parse_args()

    access_key = args.access_key
    username = args.username
    username_pronunciation = args.username_pronunciation
    endpoint_duration_sec = args.endpoint_duration_sec

    username_orca = username
    if username_pronunciation is not None:
        username_orca = f"{{{username}|{' '.join(username_pronunciation)}}}"

    po = PicoOut()
    po.start()

    cheetah = pvcheetah.create(
        access_key=access_key,
        endpoint_duration_sec=endpoint_duration_sec,
        enable_automatic_punctuation=True)

    orca = pvorca.create(access_key=access_key)

    recorder = PvRecorder(frame_length=cheetah.frame_length)

    speaker = PvSpeaker(sample_rate=orca.sample_rate, bits_per_sample=16)
    speaker.start()

    action = Actions.GREET

    try:
        while True:
            pcm, word_alignments = orca.synthesize(action.prompt(username_orca))
            po.follow_orca(word_alignments)

            speaker.flush(pcm)

            if action.is_terminal():
                break

            recorder.start()
            is_endpoint = False
            while not is_endpoint:
                partial, is_endpoint = cheetah.process(recorder.read())
                po.follow_cheetah(partial)
            recorder.stop()
            tail = cheetah.flush()
            po.follow_cheetah(tail, flushed=True)

            po.display_options()

            while True:
                try:
                    action = Actions(int(input(">")))
                    break
                except ValueError:
                    pass
    except KeyboardInterrupt:
        pass
    finally:
        speaker.stop()
        speaker.delete()
        recorder.stop()
        recorder.delete()
        orca.delete()
        cheetah.delete()
        po.stop()
        po.join()


if __name__ == "__main__":
    main()
