from argparse import ArgumentParser
from enum import Enum
from typing import (
    Any,
    Callable,
    Dict,
    Optional,
    Sequence,
    Set,
    Tuple,
)

from pvporcupine import Porcupine
from pvrecorder import PvRecorder


class Recorder(object):
    def __init__(
            self,
            frame_length: int = 160,
            device_index: int = -1
    ) -> None:
        self._recorder = PvRecorder(frame_length=frame_length, device_index=device_index)
        self._buffer = list()

    def start(self) -> None:
        self._recorder.start()

    def stop(self) -> None:
        self._recorder.stop()

    def read(self, n: int) -> Sequence[int]:
        res = list()

        if self._buffer:
            num_from_buffer = min(n, len(self._buffer))
            res.extend(self._buffer[:num_from_buffer])
            self._buffer = self._buffer[num_from_buffer:]

        while len(res) < n:
            frame = self._recorder.read()

            remaining = n - len(res)
            if len(frame) <= remaining:
                res.extend(frame)
            else:
                res.extend(frame[:remaining])
                self._buffer.extend(frame[remaining:])

        return res

    def delete(self) -> None:
        self._recorder.delete()


class Steps(Enum):
    PORCUPINE = "Porcupine"


class Step(object):
    def __init__(
            self,
            name: str = None,
    ) -> None:
        self._name = name

    def run(self) -> Optional[Dict[str, Any]]:
        raise NotImplementedError()

    def __str__(self) -> str:
        raise NotImplementedError()

    @classmethod
    def create(cls, step: Steps, **kwargs: Any) -> "Step":
        children = {
            Steps.PORCUPINE: PorcupineStep,
        }

        if step not in children:
            raise ValueError(f"Cannot create a {cls.__name__} of type {step.value}")

        return children[step](**kwargs)


class PorcupineStep(Step):
    def __init__(
            self,
            name: str,
            recorder: Recorder,
            porcupine: Porcupine,
            on_detection: Callable[[], None] = None,
    ) -> None:
        super().__init__(name=name)

        recorder.start()
        self._recorder = recorder
        self._porcupine = porcupine
        self._on_detection = on_detection

    def run(self) -> None:
        is_detected = False
        while not is_detected:
            is_detected = self._porcupine.process(self._recorder.read(self._porcupine.frame_length)) == 0

        if self._on_detection is not None:
            self._on_detection()

    def __str__(self) -> str:
        return f"{self.__class__.__name__}[{self._name}]"


class RhinoStep(Step):
    pass


class CheetahStep(Step):
    pass


class Workflow(object):
    def __init__(
            self,
            steps: Dict[str, Tuple[Steps, Optional[Dict[str, Any]]]],
            next_step_fns: Dict[str, Callable[[Dict[str, Any]], str]],
            start_step: str,
            terminal_steps: Set[str],
            access_key: str,
            name: Optional[str] = None
    ) -> None:
        self._steps = dict((k, Step.create(step=step, **kwargs)) for k, (step, kwargs) in steps.items())
        self._next_step_fns = next_step_fns
        self._start_step = start_step
        self._terminal_steps = terminal_steps
        self._name = name

    def run(self) -> None:
        current = self._start_step

        while current not in self._terminal_steps:
            current = self._next_step_fns[current](self._steps[current].run())

    def __str__(self):
        return f"{self.__class__.__name__}{f"[{self._name}]" if self._name is not None else ""}"


def main() -> None:
    parser = ArgumentParser()
    parser.add_argument(
        '--access_key',
        required=True,
        help='AccessKey obtained from Picovoice Console (https://console.picovoice.ai/)')
    args = parser.parse_args()

    access_key = args.access_key

    workflow = Workflow(
        steps={
            'wake': (Steps.PORCUPINE, None),
            'sleep': (Steps.PORCUPINE, None)
        },
        next_step_fns={
            'wake': lambda x: 'sleep',
            'sleep': lambda x: 'sleep'
        },
        start_step='wake',
        terminal_steps=set(),
        access_key=access_key)

    workflow.run()


if __name__ == '__main__':
    main()
