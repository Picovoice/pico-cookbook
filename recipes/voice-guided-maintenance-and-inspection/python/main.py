from typing import (
    Any,
    Callable,
    Optional,
    Sequence,
    Tuple,
)
from enum import Enum
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
            name: Optional[str] = None,
    ) -> None:
        self._name = name

    def run(self) -> None:
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
            recorder: Recorder,
            porcupine: Porcupine,
            name: Optional[str] = None,
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
        return f"{self.__class__.__name__}{f"[{self._name}]" if self._name is not None else ""}"


class RhinoStep(Step):
    pass


class CheetahStep(Step):
    pass


class Workflow(object):
    def __init__(
            self,
            graph: Sequence[Tuple[Steps, ]],
            name: Optional[str] = None
    ) -> None:
        self._name = name

    def run(self) -> None:
        pass

    def __str__(self):
        return f"{self.__class__.__name__}{f"[{self._name}]" if self._name is not None else ""}"


def main() -> None:
    pass


if __name__ == '__main__':
    main()
