from argparse import ArgumentParser
from enum import Enum
from typing import (
    Any,
    Callable,
    Dict,
    Optional,
    Sequence,
    Tuple,
)

import pvporcupine
from pvrecorder import PvRecorder
from pvspeaker import PvSpeaker


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
            name: str,
            access_key: str,
            recorder: Optional[Recorder],
            speaker: Optional[PvSpeaker],
    ) -> None:
        self._name = name
        self._access_key = access_key
        self._recorder = recorder
        self._speaker = speaker

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
            recorder: Optional[Recorder],
            speaker: Optional[PvSpeaker],
            access_key: str,
            keyword_path: str,
            library_path: Optional[str] = None,
            model_path: Optional[str] = None,
            device: str = 'best',
            sensitivity: float = 0.5
    ) -> None:
        super().__init__(
            name=name,
            access_key=access_key,
            recorder=recorder,
            speaker=speaker)

        self._recorder = recorder

        self._porcupine = pvporcupine.create(
            access_key=access_key,
            library_path=library_path,
            model_path=model_path,
            device=device,
            keyword_paths=[keyword_path],
            sensitivities=[sensitivity])

    def run(self) -> None:
        self._recorder.start()

        try:
            is_detected = False
            while not is_detected:
                is_detected = self._porcupine.process(self._recorder.read(self._porcupine.frame_length)) == 0
        finally:
            self._recorder.stop()

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
            next_step_fns: Dict[str, Callable[[Dict[str, Any]], Optional[str]]],
            start_step: str,
            access_key: str,
            name: Optional[str] = None
    ) -> None:
        self._recorder = Recorder(frame_length=160, device_index=-1)
        self._speaker = PvSpeaker(sample_rate=22050, bits_per_sample=16)

        self._steps = dict()
        for name, (step, kwargs) in steps.items():
            self._steps[name] = Step.create(
                step=step,
                name=name,
                access_key=access_key,
                recorder=self._recorder,
                speaker=self._speaker,
                **kwargs)

        self._next_step_fns = next_step_fns
        self._start_step = start_step
        self._name = name

    def run(self) -> None:
        current = self._start_step

        while current is not None:
            current = self._next_step_fns[current](self._steps[current].run())

    def __str__(self):
        return f"{self.__class__.__name__}{f"[{self._name}]" if self._name is not None else ""}"


def main() -> None:
    parser = ArgumentParser()
    parser.add_argument(
        '--access_key',
        required=True,
        help='AccessKey obtained from Picovoice Console (https://console.picovoice.ai/)')
    parser.add_argument(
        '--keyword_path',
        required=True,
        help='')
    args = parser.parse_args()

    access_key = args.access_key
    keyword_path = args.keyword_path

    workflow = Workflow(
        steps={
            'wake': (
                Steps.PORCUPINE,
                {
                    'keyword_path': keyword_path,
                }
            ),
        },
        next_step_fns={
            'wake': lambda x: None,
        },
        start_step='wake',
        access_key=access_key)

    workflow.run()


if __name__ == '__main__':
    main()
