from argparse import ArgumentParser
from dataclasses import dataclass
from enum import Enum
from typing import (
    Any,
    Dict,
    List,
    Optional,
    Sequence,
    Tuple,
    Type
)

import pvcheetah
import pvkoala
import pvorca
import pvporcupine
import pvrhino
from pvrecorder import PvRecorder
from pvspeaker import PvSpeaker


class AINoiseSuppressedRecorder(object):
    def __init__(self, access_key: str) -> None:
        self._koala = pvkoala.create(access_key=access_key)
        self._recorder = PvRecorder(frame_length=self._koala.frame_length)
        self._buffer = list()

    def start(self) -> None:
        self._recorder.start()

    def stop(self) -> None:
        self._recorder.stop()
        self._koala.reset()

    def read(self, n: int) -> Sequence[int]:
        res = list()

        if len(self._buffer) > 0:
            num_from_buffer = min(n, len(self._buffer))
            res.extend(self._buffer[:num_from_buffer])
            self._buffer = self._buffer[num_from_buffer:]

        while len(res) < n:
            frame = self._koala.process(self._recorder.read())

            remaining = n - len(res)
            if len(frame) <= remaining:
                res.extend(frame)
            else:
                res.extend(frame[:remaining])
                self._buffer.extend(frame[remaining:])

        return res

    def delete(self) -> None:
        self._recorder.delete()
        self._koala.delete()


class Steps(Enum):
    CHEETAH = "Cheetah"
    ORCA = "Orca"
    PORCUPINE = "Porcupine"
    RHINO = "Rhino"


class Step(object):
    def __init__(
            self,
            access_key: str,
            recorder: AINoiseSuppressedRecorder,
            speaker: PvSpeaker,
    ) -> None:
        self._access_key = access_key
        self._recorder = recorder
        self._speaker = speaker

    def run(self, **kwargs: Any) -> Optional[Dict[str, Any]]:
        raise NotImplementedError()

    def delete(self) -> None:
        raise NotImplementedError()

    def __str__(self) -> str:
        return self.__class__.__name__

    @classmethod
    def create(cls, step: Steps, **kwargs: Any) -> "Step":
        children = {
            Steps.CHEETAH: CheetahStep,
            Steps.ORCA: OrcaStep,
            Steps.PORCUPINE: PorcupineStep,
            Steps.RHINO: RhinoStep,
        }

        if step not in children:
            raise ValueError(f"Cannot create a {cls.__name__} of type `{step.value}`.")

        return children[step](**kwargs)


class CheetahStep(Step):
    def __init__(
            self,
            access_key: str,
            recorder: AINoiseSuppressedRecorder,
            speaker: PvSpeaker,
            model_path: Optional[str] = None,
            device: str = 'best',
            library_path: Optional[str] = None,
            endpoint_duration_sec: Optional[float] = 2.,
            enable_automatic_punctuation: bool = True,
            enable_text_normalization: bool = True
    ) -> None:
        super().__init__(
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
            access_key: str,
            recorder: AINoiseSuppressedRecorder,
            speaker: PvSpeaker,
            model_path: Optional[str] = None,
            device: str = 'best',
            library_path: Optional[str] = None
    ) -> None:
        super().__init__(
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
            access_key: str,
            recorder: AINoiseSuppressedRecorder,
            speaker: PvSpeaker,
            keyword_path: str,
            library_path: Optional[str] = None,
            model_path: Optional[str] = None,
            device: str = 'best',
            sensitivity: float = 0.5
    ) -> None:
        super().__init__(
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
            access_key: str,
            recorder: AINoiseSuppressedRecorder,
            speaker: PvSpeaker,
            context_path: str,
            library_path: Optional[str] = None,
            model_path: Optional[str] = None,
            device: str = 'best',
            sensitivity: float = 0.5,
            endpoint_duration_sec: float = .5,
            require_endpoint: bool = True
    ) -> None:
        super().__init__(
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


@dataclass
class Transition(object):
    outcome: Optional[Dict[str, Any]] = None
    next_state: Optional[str] = None
    next_state_kwargs: Optional[Dict[str, Any]] = None


class State(object):
    def __init__(self, step: Optional[Step] = None) -> None:
        self._step = step

    def run(
            self,
            outcomes: Sequence[Tuple[str, Optional[Dict[str, Any]]]] = None,
            **kwargs: Any) -> Transition:
        raise NotImplementedError()

    def __str__(self) -> str:
        return self.__class__.__name__


class Workflow(object):
    def __init__(
            self,
            access_key: str,
            steps: Dict[str, Tuple[Steps, Optional[Dict[str, Any]]]],
            states: Dict[str, Tuple[Type[State], Optional[str]]],
            start_state: str,
            start_state_kwargs: Optional[Dict[str, Any]] = None,
    ) -> None:
        self._recorder = AINoiseSuppressedRecorder(access_key=access_key)
        self._speaker = PvSpeaker(sample_rate=22050, bits_per_sample=16)

        self._steps = dict()
        for name, (step, kwargs) in steps.items():
            self._steps[name] = Step.create(
                step=step,
                recorder=self._recorder,
                speaker=self._speaker,
                access_key=access_key,
                **kwargs if kwargs is not None else dict())

        self._states = dict()
        for state_name, (state_class, step_name) in states.items():
            self._states[state_name] = state_class(self._steps[step_name]) if step_name is not None else state_class()
        print(sorted(self._states.keys()))
        self._start_state = self._states[start_state]
        self._start_state_kwargs = start_state_kwargs if start_state_kwargs is not None else dict()

        self._outcomes: List[Tuple[str, Optional[Dict[str, Any]]]] = list()

    def run(self) -> None:
        current_state = self._start_state
        current_state_kwargs = self._start_state_kwargs

        while current_state is not None:
            print(current_state)
            transition = current_state.run(**current_state_kwargs)
            self._outcomes.append((current_state.__class__.__name__, transition))
            print(transition.outcome)
            current_state = self._states[transition.next_state] if transition.next_state is not None else None
            current_state_kwargs = transition.next_state_kwargs if transition.next_state_kwargs is not None else dict()

    def reset(self) -> None:
        self._outcomes = list()

    def delete(self) -> None:
        self._recorder.stop()
        self._recorder.delete()

        self._speaker.stop()
        self._speaker.delete()

        for step in self._steps.values():
            step.delete()

    def __str__(self) -> str:
        return self.__class__.__name__


class StandbyState(State):
    def __init__(self, step: PorcupineStep) -> None:
        super().__init__(step=step)

    def run(
            self,
            outcomes: Sequence[Tuple[str, Optional[Dict[str, Any]]]] = None,
            **kwargs: Any
    ) -> Transition:
        self._step.run()

        return Transition(next_state=IdentifyUnitPromptState.__name__)


class IdentifyUnitPromptState(State):
    def __init__(self, step: OrcaStep) -> None:
        super().__init__(step=step)

    def run(
            self,
            outcomes: Sequence[Tuple[str, Optional[Dict[str, Any]]]] = None,
            prompt: Optional[str] = None
    ) -> Transition:
        if prompt is None:
            prompt = "What's the unit ID?"
        self._step.run(prompt=prompt)

        return Transition(next_state=IdentifyUnitReportState.__name__)


class IdentifyUnitReportState(State):
    def __init__(self, step: RhinoStep) -> None:
        super().__init__(step=step)

    def run(
            self,
            outcomes: Sequence[Tuple[str, Optional[Dict[str, Any]]]] = None,
            **kwargs: Any
    ) -> Transition:
        inference = self._step.run()

        if not inference['is_understood'] or inference['intent'] != 'identifyUnit':
            return Transition(
                next_state=IdentifyUnitPromptState.__name__,
                next_state_kwargs={'prompt': "I'm sorry, I didn't catch that. What's the unit ID again?"})
        else:
            return Transition(outcome=inference, next_state=CheckOilPromptState.__name__)


class CheckOilPromptState(State):
    def __init__(self, step: OrcaStep) -> None:
        super().__init__(step=step)

    def run(
            self,
            outcomes: Sequence[Tuple[str, Optional[Dict[str, Any]]]] = None,
            prompt: Optional[str] = None
    ) -> Transition:
        if prompt is None:
            prompt = "What's the oil level?"
        self._step.run(prompt=prompt)

        return Transition(next_state=CheckOilReportState.__name__)


class CheckOilReportState(State):
    def __init__(self, step: RhinoStep) -> None:
        super().__init__(step=step)

    def run(
            self,
            outcomes: Sequence[Tuple[str, Optional[Dict[str, Any]]]] = None,
            **kwargs: Any
    ) -> Transition:
        inference = self._step.run()

        if not inference['is_understood'] or inference['intent'] != 'reportFluidCondition':
            return Transition(
                next_state=CheckOilPromptState.__name__,
                next_state_kwargs={'prompt': "I'm sorry, I didn't catch that. What's the oil level again?"})
        else:
            return Transition(outcome=inference, next_state=CheckCoolantPromptState.__name__)


class CheckCoolantPromptState(State):
    def __init__(self, step: OrcaStep) -> None:
        super().__init__(step=step)

    def run(
            self,
            outcomes: Sequence[Tuple[str, Optional[Dict[str, Any]]]] = None,
            prompt: Optional[str] = None
    ) -> Transition:
        if prompt is None:
            prompt = "What's the coolant level?"
        self._step.run(prompt=prompt)

        return Transition(next_state=CheckCoolantReportState.__name__)


class CheckCoolantReportState(State):
    def __init__(self, step: RhinoStep) -> None:
        super().__init__(step=step)

    def run(
            self,
            outcomes: Sequence[Tuple[str, Optional[Dict[str, Any]]]] = None,
            **kwargs: Any
    ) -> Transition:
        inference = self._step.run()

        if not inference['is_understood'] or inference['intent'] != 'reportFluidCondition':
            return Transition(
                next_state=CheckCoolantPromptState.__name__,
                next_state_kwargs={'prompt': "I'm sorry, I didn't catch that. What's the coolant level again?"})
        else:
            return Transition(outcome=inference, next_state=FinalNotePromptState.__name__)


class FinalNotePromptState(State):
    def __init__(self, step: OrcaStep) -> None:
        super().__init__(step=step)

    def run(
            self,
            outcomes: Sequence[Tuple[str, Optional[Dict[str, Any]]]] = None,
            prompt: Optional[str] = None
    ) -> Transition:
        if prompt is None:
            prompt = "Any final notes?"
        self._step.run(prompt=prompt)

        return Transition(next_state=FinalNoteRecordState.__name__)


class FinalNoteRecordState(State):
    def __init__(self, step: CheetahStep) -> None:
        super().__init__(step=step)

    def run(
            self,
            outcomes: Sequence[Tuple[str, Optional[Dict[str, Any]]]] = None,
            **kwargs: Any
    ) -> Transition:
        transcription = self._step.run()

        return Transition(outcome=transcription, next_state=ReportCompilationState.__name__)


class ReportCompilationState(State):
    def __init__(self) -> None:
        super().__init__()

    def run(
            self,
            outcomes: Sequence[Tuple[str, Optional[Dict[str, Any]]]] = None,
            **kwargs: Any
    ) -> Transition:
        print("Inspection Report")
        for state, outcome in outcomes:
            if state == 'IdentifyUnitReportState' and outcome['is_understood'] and outcome['intent'] == 'identifyUnit':
                print(f"Unit ID: {outcome['slots']['unitId']}")
            elif state == 'CheckOilReportState' and outcome['is_understood'] and outcome['intent'] == 'reportFluidCondition':
                print(f"Oid: {outcome['slots']['fluidCondition']}")
            elif state == 'CheckCoolantReportState' and outcome['is_understood'] and outcome['intent'] == 'reportFluidCondition':
                print(f"Coolant: {outcome['slots']['fluidCondition']}")
            elif state == 'FinalNoteRecordState':
                print(f"Note: {outcome['text']}")
        return Transition()


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
            'TranscribeUser': (Steps.CHEETAH, None)
        },
        states={
            StandbyState.__name__: (StandbyState, 'Standby'),
            IdentifyUnitPromptState.__name__: (IdentifyUnitPromptState, 'PromptUser'),
            IdentifyUnitReportState.__name__: (IdentifyUnitReportState, 'RecordUser'),
            CheckOilPromptState.__name__: (CheckOilPromptState, 'PromptUser'),
            CheckOilReportState.__name__: (CheckOilReportState, 'RecordUser'),
            CheckCoolantPromptState.__name__: (CheckCoolantPromptState, 'PromptUser'),
            CheckCoolantReportState.__name__: (CheckCoolantReportState, 'RecordUser'),
            FinalNotePromptState.__name__: (FinalNotePromptState, 'PromptUser'),
            FinalNoteRecordState.__name__: (FinalNoteRecordState, 'TranscribeUser'),
            ReportCompilationState.__name__: (ReportCompilationState, None)
        },
        start_state=StandbyState.__name__,
        access_key=access_key)

    workflow.run()
    workflow.delete()


if __name__ == '__main__':
    main()
