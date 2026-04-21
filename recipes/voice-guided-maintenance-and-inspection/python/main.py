from argparse import ArgumentParser
from enum import Enum
from typing import (
    Any,
    Dict,
    Optional,
    Sequence,
    Tuple,
    Type
)

import pvcheetah
import pvorca
import pvporcupine
import pvrhino
from pvrecorder import PvRecorder
from pvspeaker import PvSpeaker


class Recorder(object):
    def __init__(
            self,
            frame_length: int = 512,
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
    CHEETAH = "Cheetah"
    ORCA = "Orca"
    PORCUPINE = "Porcupine"
    RHINO = "Rhino"


class Step(object):
    def __init__(
            self,
            name: str,
            access_key: str,
            recorder: Recorder,
            speaker: PvSpeaker,
    ) -> None:
        self._name = name
        self._access_key = access_key
        self._recorder = recorder
        self._speaker = speaker

    def run(self, **kwargs: Any) -> Optional[Dict[str, Any]]:
        raise NotImplementedError()

    def delete(self) -> None:
        raise NotImplementedError()

    def __str__(self) -> str:
        return f"{self.__class__.__name__} << {self._name} >>"

    @classmethod
    def create(cls, step: Steps, **kwargs: Any) -> "Step":
        children = {
            Steps.CHEETAH: CheetahStep,
            Steps.ORCA: OrcaStep,
            Steps.PORCUPINE: PorcupineStep,
            Steps.RHINO: RhinoStep,
        }

        if step not in children:
            raise ValueError(f"Cannot create a {cls.__name__} of type `{step.value}`")

        return children[step](**kwargs)


class CheetahStep(Step):
    def __init__(
            self,
            name: str,
            recorder: Recorder,
            speaker: PvSpeaker,
            access_key: str,
            model_path: Optional[str] = None,
            device: str = 'best',
            library_path: Optional[str] = None,
            endpoint_duration_sec: Optional[float] = 2.,
            enable_automatic_punctuation: bool = True,
            enable_text_normalization: bool = True
    ) -> None:
        super().__init__(
            name=name,
            access_key=access_key,
            recorder=recorder,
            speaker=speaker)

        self._cheetah = pvcheetah.create(
            access_key=access_key,
            model_path=model_path,
            device=device,
            library_path=library_path,
            endpoint_duration_sec=endpoint_duration_sec,
            enable_automatic_punctuation=enable_automatic_punctuation,
            enable_text_normalization=enable_text_normalization)

    def run(self) -> Optional[Dict[str, Any]]:
        partials = list()

        try:
            self._recorder.start()

            while True:
                partial, is_endpoint = self._cheetah.process(self._recorder.read(self._cheetah.frame_length))
                partials.append(partial)
                print(partial, end="", flush=True)

                if is_endpoint:
                    remainder = self._cheetah.flush()
                    partials.append(remainder)
                    print(remainder, end="\n", flush=True)
                    break
        except KeyboardInterrupt:
            remainder = self._cheetah.flush()
            partials.append(remainder)
            print(remainder, end="\n", flush=True)
        finally:
            self._recorder.stop()

        return {
            "text": ''.join(partials)
        }

    def delete(self) -> None:
        self._cheetah.delete()


class OrcaStep(Step):
    def __init__(
            self,
            name: str,
            recorder: Recorder,
            speaker: PvSpeaker,
            access_key: str,
            model_path: Optional[str] = None,
            device: str = 'best',
            library_path: Optional[str] = None
    ) -> None:
        super().__init__(
            name=name,
            access_key=access_key,
            recorder=recorder,
            speaker=speaker)

        self._orca = pvorca.create(
            access_key=access_key,
            model_path=model_path,
            device=device,
            library_path=library_path)

    def run(self, prompt: str) -> Optional[Dict[str, Any]]:
        try:
            self._speaker.start()

            pcm, alignment = self._orca.synthesize(text=prompt)
            self._speaker.flush(pcm)
        finally:
            self._speaker.stop()

    def delete(self) -> None:
        self._orca.delete()


class PorcupineStep(Step):
    def __init__(
            self,
            name: str,
            recorder: Recorder,
            speaker: PvSpeaker,
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

    def run(self) -> Optional[Dict[str, Any]]:
        try:
            self._recorder.start()

            is_detected = False
            while not is_detected:
                is_detected = self._porcupine.process(self._recorder.read(self._porcupine.frame_length)) == 0
        finally:
            self._recorder.stop()

    def delete(self) -> None:
        self._porcupine.delete()


class RhinoStep(Step):
    def __init__(
            self,
            name: str,
            recorder: Recorder,
            speaker: PvSpeaker,
            access_key: str,
            context_path: str,
            device: str = 'best',
            library_path: Optional[str] = None,
            model_path: Optional[str] = None,
            sensitivity: float = 0.5,
            endpoint_duration_sec: float = 1.,
            require_endpoint: bool = True
    ) -> None:
        super().__init__(
            name=name,
            access_key=access_key,
            recorder=recorder,
            speaker=speaker)

        self._rhino = pvrhino.create(
            access_key=access_key,
            context_path=context_path,
            library_path=library_path,
            model_path=model_path,
            device=device,
            sensitivity=sensitivity,
            endpoint_duration_sec=endpoint_duration_sec,
            require_endpoint=require_endpoint)

    def run(self) -> Optional[Dict[str, Any]]:
        try:
            self._recorder.start()

            while not self._rhino.process(self._recorder.read(self._rhino.frame_length)):
                pass
            inference = self._rhino.get_inference()
            return {
                'is_understood': inference.is_understood,
                'intent': inference.intent,
                'slots': inference.slots,
            }
        finally:
            self._rhino.reset()
            self._recorder.stop()

    def delete(self) -> None:
        self._rhino.delete()


class State(object):
    def __init__(
            self,
            step: Optional[Step] = None
    ) -> None:
        self._step = step

    def run(
            self,
            **kwargs: Any
    ) -> Tuple[Optional[Dict[str, Any]], Optional[Tuple[str, Optional[Dict[str, Any]]]]]:
        raise NotImplementedError()

    def __str__(self) -> str:
        return self.__class__.__name__


class StandbyState(State):
    def __init__(
            self,
            step: PorcupineStep
    ) -> None:
        super().__init__(step=step)

    def run(
            self
    ) -> Tuple[Optional[Dict[str, Any]], Optional[Tuple[str, Optional[Dict[str, Any]]]]]:
        self._step.run()

        return None, (IdentifyUnitPromptState.__name__, None)


class IdentifyUnitPromptState(State):
    def __init__(
            self,
            step: OrcaStep,
    ) -> None:
        super().__init__(step=step)

    def run(
            self,
            prompt: Optional[str] = None,
    ) -> Tuple[Optional[Dict[str, Any]], Optional[Tuple[str, Optional[Dict[str, Any]]]]]:
        if prompt is None:
            prompt = "What's the unit ID?"
        self._step.run(prompt=prompt)

        return None, (IdentifyUnitRecordState.__name__, None)


class IdentifyUnitRecordState(State):
    def __init__(
            self,
            step: RhinoStep
    ) -> None:
        super().__init__(step=step)

    def run(
            self
    ) -> Tuple[Optional[Dict[str, Any]], Optional[Tuple[str, Optional[Dict[str, Any]]]]]:
        inference = self._step.run()

        return inference, None

class Workflow(object):
    def __init__(
            self,
            steps: Dict[str, Tuple[Steps, Optional[Dict[str, Any]]]],
            states: Dict[str, Tuple[Type[State], str]],
            start_state: str,
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
                recorder=self._recorder,
                speaker=self._speaker,
                access_key=access_key,
                **kwargs if kwargs is not None else dict())

        self._states = dict()
        for name, (state_class, step_name) in states.items():
            self._states[name] = state_class(self._steps[step_name])

        self._start_state = self._states[start_state]
        self._name = name

        self._history = list()

    def run(self) -> None:
        current = self._start_state
        kwargs = dict()

        while True:
            print(current)
            sideeffect, future = current.run(**kwargs)
            self._history.append(sideeffect)
            if future is not None:
                current = self._states[future[0]]
                kwargs = future[1] if future[1] is not None else dict()
            else:
                break

    def reset(self) -> None:
        self._history = list()

    def delete(self) -> None:
        self._recorder.stop()
        self._recorder.delete()

        self._speaker.stop()
        self._speaker.delete()

        for step in self._steps.values():
            step.delete()

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
    parser.add_argument(
        '--context_path',
        required=True,
        help='')
    args = parser.parse_args()

    access_key = args.access_key
    keyword_path = args.keyword_path
    context_path = args.context_path

    workflow = Workflow(
        steps={
            'Standby': (Steps.PORCUPINE, {'keyword_path': keyword_path}),
            'PromptUser': (Steps.ORCA, None),
            'RecordUser': (Steps.RHINO, {'context_path': context_path}),
            'AppendNote': (Steps.CHEETAH, None)
        },
        states={
            StandbyState.__name__: (StandbyState, 'Standby'),
            IdentifyUnitPromptState.__name__: (IdentifyUnitPromptState, 'PromptUser'),
            IdentifyUnitRecordState.__name__: (IdentifyUnitRecordState, 'RecordUser'),
        },
        start_state=StandbyState.__name__,
        access_key=access_key)

    workflow.run()
    workflow.delete()


if __name__ == '__main__':
    main()
