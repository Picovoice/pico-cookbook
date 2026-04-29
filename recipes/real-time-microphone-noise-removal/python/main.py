from argparse import ArgumentParser
from typing import Sequence

import pvkoala
from pvrecorder import PvRecorder


class AINoiseSuppressedRecorder(object):
    def __init__(self, access_key: str) -> None:
        self._koala = pvkoala.create(access_key=access_key)
        self._recorder = PvRecorder(frame_length=self._koala.frame_length)
        self._buffer = list()

    def start(self) -> None:
        self._recorder.start()

    def stop(self) -> None:
        self._buffer.clear()
        self._recorder.stop()
        self._koala.reset()

    def read(self, num_samples: int) -> Sequence[int]:
        pcm = list()

        if len(self._buffer) > 0:
            num_from_buffer = min(num_samples, len(self._buffer))
            pcm.extend(self._buffer[:num_from_buffer])
            self._buffer = self._buffer[num_from_buffer:]

        while len(pcm) < num_samples:
            frame = self._koala.process(self._recorder.read())

            remaining = num_samples - len(pcm)
            if len(frame) <= remaining:
                pcm.extend(frame)
            else:
                pcm.extend(frame[:remaining])
                self._buffer.extend(frame[remaining:])

        return pcm

    def delete(self) -> None:
        self._recorder.delete()
        self._koala.delete()


def main() -> None:
    parser = ArgumentParser()
    parser.add_argument(
        '--access_key',
        required=True,
        help='AccessKey obtained from Picovoice Console (https://console.picovoice.ai/)')
    args = parser.parse_args()

    access_key = args.access_key
