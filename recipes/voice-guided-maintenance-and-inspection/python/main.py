import shutil
import string
import sys
from argparse import ArgumentParser
from dataclasses import dataclass
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
    Any,
    Callable,
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
from pvorca import Orca
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
        raise NotImplementedError()

    @classmethod
    def create(cls, step: Steps, **kwargs: Any) -> "Step":
        children = {
            Steps.CHEETAH: CheetahStep,
            Steps.ORCA: OrcaStep,
            Steps.PORCUPINE: PorcupineStep,
            Steps.RHINO: RhinoStep,
        }

        if step not in children:
            raise NotImplementedError(f"Cannot create a {cls.__name__} of type `{step.value}`.")

        return children[step](**kwargs)


class CheetahStep(Step):
    def __init__(
            self,
            access_key: str,
            recorder: AINoiseSuppressedRecorder,
            speaker: PvSpeaker,
            model_path: Optional[str] = None,
            endpoint_duration_sec: Optional[float] = 1.,
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
            endpoint_duration_sec=endpoint_duration_sec,
            enable_automatic_punctuation=enable_automatic_punctuation,
            enable_text_normalization=enable_text_normalization)

    def run(
            self,
            on_partial: Optional[Callable[[str], None]] = None,
            on_endpoint: Optional[Callable[[str], None]] = None
    ) -> Optional[Dict[str, Any]]:
        partials = list()

        try:
            self._recorder.start()

            is_endpoint = False
            while not is_endpoint:
                partial, is_endpoint = self._cheetah.process(self._recorder.read(self._cheetah.frame_length))
                partials.append(partial)
                if on_partial is not None:
                    on_partial(partial)

                if is_endpoint:
                    remainder = self._cheetah.flush()
                    partials.append(remainder)
                    if on_endpoint is not None:
                        on_endpoint(remainder)
        except KeyboardInterrupt:
            remainder = self._cheetah.flush()
            partials.append(remainder)
            if on_endpoint is not None:
                on_endpoint(remainder)
        finally:
            self._recorder.stop()

        return {
            "text": ''.join(partials)
        }

    def delete(self) -> None:
        self._cheetah.delete()

    def __str__(self):
        return f"""{self.__class__.__name__} {{
  {self._cheetah.__class__.__name__}[V{self._cheetah.version}]
}}
"""


class OrcaStep(Step):
    def __init__(
            self,
            access_key: str,
            recorder: AINoiseSuppressedRecorder,
            speaker: PvSpeaker,
            model_path: Optional[str] = None,
    ) -> None:
        super().__init__(
            access_key=access_key,
            recorder=recorder,
            speaker=speaker)

        self._orca = pvorca.create(
            access_key=access_key,
            model_path=model_path)

    def run(
            self,
            prompt: str,
            on_synthesis: Optional[Callable[[Sequence[Orca.WordAlignment]], None]] = None
    ) -> Optional[Dict[str, Any]]:
        try:
            self._speaker.start()

            pcm, alignment = self._orca.synthesize(text=prompt)
            if on_synthesis is not None:
                on_synthesis(alignment)
            self._speaker.flush(pcm)
        except KeyboardInterrupt:
            pass
        finally:
            self._speaker.stop()

    def delete(self) -> None:
        self._orca.delete()

    def __str__(self):
        return f"""{self.__class__.__name__} {{
  {self._orca.__class__.__name__}[V{self._orca.version}]
}}
"""


class PorcupineStep(Step):
    def __init__(
            self,
            access_key: str,
            recorder: AINoiseSuppressedRecorder,
            speaker: PvSpeaker,
            keyword_path: str,
            model_path: Optional[str] = None,
            sensitivity: float = 0.5
    ) -> None:
        super().__init__(
            access_key=access_key,
            recorder=recorder,
            speaker=speaker)

        self._recorder = recorder

        self._porcupine = pvporcupine.create(
            access_key=access_key,
            model_path=model_path,
            keyword_paths=[keyword_path],
            sensitivities=[sensitivity])

    def run(self) -> Optional[Dict[str, Any]]:
        try:
            self._recorder.start()

            is_detected = False
            while not is_detected:
                is_detected = self._porcupine.process(self._recorder.read(self._porcupine.frame_length)) == 0
        except KeyboardInterrupt:
            pass
        finally:
            self._recorder.stop()

    def delete(self) -> None:
        self._porcupine.delete()

    def __str__(self):
        return f"""{self.__class__.__name__} {{
  {self._porcupine.__class__.__name__}[V{self._porcupine.version}]
}}
"""


class RhinoStep(Step):
    def __init__(
            self,
            access_key: str,
            recorder: AINoiseSuppressedRecorder,
            speaker: PvSpeaker,
            context_path: str,
            model_path: Optional[str] = None,
            sensitivity: float = 0.5,
            endpoint_duration_sec: float = .5,
            require_endpoint: bool = False
    ) -> None:
        super().__init__(
            access_key=access_key,
            recorder=recorder,
            speaker=speaker)

        self._rhino = pvrhino.create(
            access_key=access_key,
            context_path=context_path,
            model_path=model_path,
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
        except KeyboardInterrupt:
            pass
        finally:
            self._recorder.stop()

    def delete(self) -> None:
        self._rhino.delete()

    def __str__(self):
        return f"""{self.__class__.__name__} {{
  {self._rhino.__class__.__name__}[V{self._rhino.version}]
}}
"""


@dataclass
class Transition(object):
    outcome: Optional[Dict[str, Any]] = None
    next_state: Optional[Enum] = None
    next_state_kwargs: Optional[Dict[str, Any]] = None


class State(object):
    def __init__(self, step: Optional[Step] = None) -> None:
        self._step = step

    def run(
            self,
            outcomes: Sequence[Tuple[Enum, Optional[Dict[str, Any]]]] = None,
            **kwargs: Any
    ) -> Transition:
        raise NotImplementedError()

    def __str__(self) -> str:
        return self.__class__.__name__

    @classmethod
    def create(
            cls,
            state: Enum,
            **kwargs: Any
    ) -> "State":
        raise NotImplementedError()


class Workflow(object):
    def __init__(
            self,
            access_key: str,
            steps: Dict[Enum, Tuple[Steps, Optional[Dict[str, Any]]]],
            state_enum: Type[Enum],
            state_subclass: Type[State],
            state_steps: Dict[Enum, Enum],
            start_state: Enum,
            start_state_kwargs: Optional[Dict[str, Any]] = None,
    ) -> None:
        self._recorder = AINoiseSuppressedRecorder(access_key=access_key)
        self._speaker = PvSpeaker(sample_rate=22050, bits_per_sample=16)

        self._steps = dict()
        for uid, (step, kwargs) in steps.items():
            self._steps[uid] = Step.create(
                step=step,
                access_key=access_key,
                recorder=self._recorder,
                speaker=self._speaker,
                **kwargs if kwargs is not None else dict())
            print(f"[OK] {self._steps[uid]}")

        self._states = dict()
        self._state_uids = dict()
        for state in state_enum:
            if state in state_steps:
                self._states[state] = state_subclass.create(state=state, step=self._steps[state_steps[state]])
            else:
                self._states[state] = state_subclass.create(state=state)

            self._state_uids[self._states[state]] = state

        self._start_state = self._states[start_state]
        self._start_state_kwargs = start_state_kwargs if start_state_kwargs is not None else dict()

        self._outcomes: List[Tuple[Enum, Optional[Dict[str, Any]]]] = list()

    def run(self) -> None:
        current_state = self._start_state
        current_state_kwargs = self._start_state_kwargs

        while current_state is not None:
            transition = current_state.run(outcomes=self._outcomes, **current_state_kwargs)
            self._outcomes.append((self._state_uids[current_state], transition.outcome))
            current_state = self._states[transition.next_state] if transition.next_state is not None else None
            current_state_kwargs = transition.next_state_kwargs if transition.next_state_kwargs is not None else dict()

    def reset(self) -> None:
        self._outcomes = list()

    def delete(self) -> None:
        for step in reversed(self._steps.values()):
            step.delete()

        self._speaker.stop()
        self._speaker.delete()

        self._recorder.stop()
        self._recorder.delete()

    def __str__(self) -> str:
        return self.__class__.__name__


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
            if delay > 0.:
                sleep(delay)

            suffix = ' ' if i < (len(alignments) - 1) and (alignments[i + 1].word not in string.punctuation) else ''
            on_tick(x.word + suffix)

    thread = Thread(target=run, daemon=True)
    thread.start()
    return thread


class RecipeSteps(Enum):
    STANDBY = "Standby"
    PROMPT_USER = "PromptUser"
    RECORD_USER = "RecordUser"
    TRANSCRIBE_USER = "TranscribeUser"


class RecipeStates(Enum):
    STANDBY = "Standby"
    IDENTIFY_UNIT_PROMPT = "IdentifyUnitPrompt"
    IDENTIFY_UNIT_REPORT = "IdentifyUnitReport"
    CHECK_OIL_PROMPT = "CheckOilPrompt"
    CHECK_OIL_REPORT = "CheckOilReport"
    CHECK_TIRE_PROMPT = "CheckTirePrompt"
    CHECK_TIRE_REPORT = "CheckTireReport"
    CHECK_SERVICE_STATUS_PROMPT = "CheckServiceStatusPrompt"
    CHECK_SERVICE_STATUS_REPORT = "CheckServiceStatusReport"
    FINAL_NOTE_PROMPT = "FinalNotePrompt"
    FINAL_NOTE_REPORT = "FinalNoteReport"
    REPORT_COMPILATION = "ReportCompilation"


class RecipeState(State):
    @classmethod
    def create(
            cls,
            state: RecipeStates,
            **kwargs: Any
    ) -> "RecipeState":
        children = {
            RecipeStates.STANDBY: RecipeStandbyState,
            RecipeStates.IDENTIFY_UNIT_PROMPT: RecipeIdentifyUnitPromptState,
            RecipeStates.IDENTIFY_UNIT_REPORT: RecipeIdentifyUnitReportState,
            RecipeStates.CHECK_OIL_PROMPT: RecipeCheckOilPromptState,
            RecipeStates.CHECK_OIL_REPORT: RecipeCheckOilReportState,
            RecipeStates.CHECK_TIRE_PROMPT: RecipeCheckTirePromptState,
            RecipeStates.CHECK_TIRE_REPORT: RecipeCheckTireReportState,
            RecipeStates.CHECK_SERVICE_STATUS_PROMPT: RecipeCheckServiceStatusPromptState,
            RecipeStates.CHECK_SERVICE_STATUS_REPORT: RecipeCheckServiceStatusReportState,
            RecipeStates.FINAL_NOTE_PROMPT: RecipeFinalNotePromptState,
            RecipeStates.FINAL_NOTE_REPORT: RecipeFinalNoteRecordState,
            RecipeStates.REPORT_COMPILATION: RecipeReportCompilationState,
        }

        if state not in children:
            raise ValueError(f"Cannot create a {cls.__name__} of type `{state.value}`.")

        return children[state](**kwargs)


class RecipePromptState(RecipeState):
    def __init__(
            self,
            step: OrcaStep,
            prompt: str,
            next_state: RecipeStates
    ) -> None:
        super().__init__(step=step)

        self._prompt = prompt
        self._next_state = next_state

    def run(
            self,
            outcomes: Sequence[Tuple[Enum, Optional[Dict[str, Any]]]] = None,
            prompt: Optional[str] = None
    ) -> Transition:
        if prompt is None:
            prompt = self._prompt

        text = ""
        lock = Lock()

        def get_text() -> str:
            with lock:
                return f"[AI] {text}"

        print_event, print_thread = print_async(get_text)

        def on_tick(chunk: str) -> None:
            nonlocal text
            with lock:
                text += chunk

        timer_thread = None

        def on_synthesis(alignments: Sequence[Orca.WordAlignment]) -> None:
            nonlocal timer_thread
            timer_thread = time_async(alignments=alignments, on_tick=on_tick)

        self._step.run(prompt=prompt, on_synthesis=on_synthesis)
        # noinspection PyUnresolvedReferences
        timer_thread.join()
        print_event.set()
        print_thread.join()

        return Transition(next_state=self._next_state)


class RecipeReportState(RecipeState):
    def __init__(
            self,
            step: RhinoStep,
            listening_prompt: str,
            expected_intent: str,
            success_prompt: Callable[[Dict[str, Any]], str],
            success_next_state: RecipeStates,
            failure_prompt: Callable[[Dict[str, Any]], str],
            failure_next_state: RecipeStates,
            success_next_state_kwargs: Optional[Dict[str, Any]] = None,
            failure_next_state_kwargs: Optional[Dict[str, Any]] = None,
    ) -> None:
        super().__init__(step=step)

        self._listening_prompt = listening_prompt
        self._expected_intent = expected_intent
        self._success_prompt = success_prompt
        self._success_next_state = success_next_state
        self._failure_prompt = failure_prompt
        self._failure_next_state = failure_next_state
        self._success_next_state_kwargs = success_next_state_kwargs
        self._failure_next_state_kwargs = failure_next_state_kwargs

    def run(
            self,
            outcomes: Sequence[Tuple[Enum, Optional[Dict[str, Any]]]] = None,
            **kwargs: Any
    ) -> Transition:
        text = self._listening_prompt

        def get_text() -> str:
            return text

        event, thread = print_async(get_text=get_text)
        inference = self._step.run()

        if inference['is_understood'] and inference['intent'] == self._expected_intent:
            text = self._success_prompt(inference)
            sleep(.1)
            event.set()
            thread.join()

            return Transition(outcome=inference, next_state=self._success_next_state)
        else:
            text = self._failure_prompt(inference)
            sleep(.1)
            event.set()
            thread.join()

            return Transition(
                outcome=inference,
                next_state=self._failure_next_state,
                next_state_kwargs=self._failure_next_state_kwargs)


class RecipeStandbyState(RecipeState):
    def __init__(self, step: PorcupineStep) -> None:
        super().__init__(step=step)

    def run(
            self,
            outcomes: Sequence[Tuple[Enum, Optional[Dict[str, Any]]]] = None,
            **kwargs: Any
    ) -> Transition:
        text = "Listening for wake word"

        def get_text() -> str:
            return text

        event, thread = print_async(get_text=get_text)
        self._step.run()
        text = "Detected wake word. Starting inspection..."
        sleep(.1)
        event.set()
        thread.join()

        return Transition(next_state=RecipeStates.IDENTIFY_UNIT_PROMPT)


class RecipeIdentifyUnitPromptState(RecipePromptState):
    def __init__(self, step: OrcaStep) -> None:
        super().__init__(
            step=step,
            prompt="What's the unit ID?",
            next_state=RecipeStates.IDENTIFY_UNIT_REPORT)


class RecipeIdentifyUnitReportState(RecipeReportState):
    def __init__(self, step: RhinoStep) -> None:
        super().__init__(
            step=step,
            listening_prompt="Listening for unit ID",
            expected_intent='identifyUnit',
            success_prompt=lambda x: f"Unit ID is {x['slots']['unitId']}.",
            success_next_state=RecipeStates.CHECK_OIL_PROMPT,
            failure_prompt=lambda x: "Failed to capture unit ID. Retrying...",
            failure_next_state=RecipeStates.IDENTIFY_UNIT_PROMPT,
            failure_next_state_kwargs={'prompt': "I'm sorry, I didn't catch that. What's the unit ID again?"})


class RecipeCheckOilPromptState(RecipePromptState):
    def __init__(self, step: OrcaStep) -> None:
        super().__init__(
            step=step,
            prompt="What's the oil level?",
            next_state=RecipeStates.CHECK_OIL_REPORT)


class RecipeCheckOilReportState(RecipeReportState):
    def __init__(self, step: RhinoStep) -> None:
        super().__init__(
            step=step,
            listening_prompt="Listening for oil status",
            expected_intent='reportOilCondition',
            success_prompt=lambda x: f"Oil level is {x['slots']['fluidCondition']}.",
            success_next_state=RecipeStates.CHECK_TIRE_PROMPT,
            failure_prompt=lambda x: "Failed to capture oil condition. Retrying...",
            failure_next_state=RecipeStates.CHECK_OIL_PROMPT,
            failure_next_state_kwargs={'prompt': "I'm sorry, I didn't catch that. What's the oil level again?"})


class RecipeCheckTirePromptState(RecipePromptState):
    def __init__(self, step: OrcaStep) -> None:
        super().__init__(
            step=step,
            prompt="What's the tire condition?",
            next_state=RecipeStates.CHECK_TIRE_REPORT)


class RecipeCheckTireReportState(RecipeReportState):
    def __init__(self, step: RhinoStep) -> None:
        super().__init__(
            step=step,
            listening_prompt="Listening for tire condition",
            expected_intent='reportTireCondition',
            success_prompt=lambda x: f"Tire condition is {x['slots']['tireCondition']}.",
            success_next_state=RecipeStates.CHECK_SERVICE_STATUS_PROMPT,
            failure_prompt=lambda x: "Failed to capture tire condition. Retrying...",
            failure_next_state=RecipeStates.CHECK_TIRE_PROMPT,
            failure_next_state_kwargs={'prompt': "I'm sorry, I didn't catch that. What's the tire condition again?"})


class RecipeCheckServiceStatusPromptState(RecipePromptState):
    def __init__(self, step: OrcaStep) -> None:
        super().__init__(
            step=step,
            prompt="What's the vehicle status?",
            next_state=RecipeStates.CHECK_SERVICE_STATUS_REPORT)


class RecipeCheckServiceStatusReportState(RecipeReportState):
    def __init__(self, step: RhinoStep) -> None:
        super().__init__(
            step=step,
            listening_prompt="Listening for vehicle status",
            expected_intent='reportServiceStatus',
            success_prompt=lambda x: f"Vehicle status is {x['slots']['serviceStatus']}.",
            success_next_state=RecipeStates.FINAL_NOTE_PROMPT,
            failure_prompt=lambda x: "Failed to capture vehicle status. Retrying...",
            failure_next_state=RecipeStates.CHECK_SERVICE_STATUS_PROMPT,
            failure_next_state_kwargs={'prompt': "I'm sorry, I didn't catch that. What's the vehicle status again?"})


class RecipeFinalNotePromptState(RecipePromptState):
    def __init__(self, step: OrcaStep) -> None:
        super().__init__(
            step=step,
            prompt="Please provide final notes.",
            next_state=RecipeStates.FINAL_NOTE_REPORT)


class RecipeFinalNoteRecordState(RecipeState):
    def __init__(self, step: CheetahStep) -> None:
        super().__init__(step=step)

    def run(
            self,
            outcomes: Sequence[Tuple[Enum, Optional[Dict[str, Any]]]] = None,
            **kwargs: Any
    ) -> Transition:
        text = ""
        text_lock = Lock()

        def get_text() -> str:
            with text_lock:
                display_text = "(listening)" if text == "" else text
                return display_text

        text_event, text_thread = print_async(get_text)

        def on_partial(partial: str) -> None:
            nonlocal text
            text += partial

        def on_endpoint(endpoint: str) -> None:
            nonlocal text
            text += endpoint

        transcription = self._step.run(on_partial=on_partial, on_endpoint=on_endpoint)
        text_event.set()
        text_thread.join()

        return Transition(outcome=transcription, next_state=RecipeStates.REPORT_COMPILATION)


class RecipeReportCompilationState(RecipeState):
    def __init__(self) -> None:
        super().__init__()

    def run(
            self,
            outcomes: Sequence[Tuple[Enum, Optional[Dict[str, Any]]]] = None,
            **kwargs: Any
    ) -> Transition:
        print("\n\nInspection Report:")
        for state, outcome in outcomes:
            if state == RecipeStates.IDENTIFY_UNIT_REPORT:
                if outcome['is_understood'] and outcome['intent'] == 'identifyUnit':
                    print(f"Unit ID: {outcome['slots']['unitId']}")
            elif state == RecipeStates.CHECK_OIL_REPORT:
                if outcome['is_understood'] and outcome['intent'] == 'reportOilCondition':
                    print(f"Oil: {outcome['slots']['fluidCondition']}")
            elif state == RecipeStates.CHECK_TIRE_REPORT:
                if outcome['is_understood'] and outcome['intent'] == 'reportTireCondition':
                    print(f"Tires: {outcome['slots']['tireCondition']}")
            elif state == RecipeStates.CHECK_SERVICE_STATUS_REPORT:
                if outcome['is_understood'] and outcome['intent'] == 'reportServiceStatus':
                    print(f"Vehicle Status: {outcome['slots']['serviceStatus']}")
            elif state == RecipeStates.FINAL_NOTE_REPORT:
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
            RecipeSteps.STANDBY: (Steps.PORCUPINE, {'keyword_path': keyword_path}),
            RecipeSteps.PROMPT_USER: (Steps.ORCA, None),
            RecipeSteps.RECORD_USER: (Steps.RHINO, {'context_path': context_path}),
            RecipeSteps.TRANSCRIBE_USER: (Steps.CHEETAH, None)
        },
        state_enum=RecipeStates,
        state_subclass=RecipeState,
        state_steps={
            RecipeStates.STANDBY: RecipeSteps.STANDBY,
            RecipeStates.IDENTIFY_UNIT_PROMPT: RecipeSteps.PROMPT_USER,
            RecipeStates.IDENTIFY_UNIT_REPORT: RecipeSteps.RECORD_USER,
            RecipeStates.CHECK_OIL_PROMPT: RecipeSteps.PROMPT_USER,
            RecipeStates.CHECK_OIL_REPORT: RecipeSteps.RECORD_USER,
            RecipeStates.CHECK_TIRE_PROMPT: RecipeSteps.PROMPT_USER,
            RecipeStates.CHECK_TIRE_REPORT: RecipeSteps.RECORD_USER,
            RecipeStates.CHECK_SERVICE_STATUS_PROMPT: RecipeSteps.PROMPT_USER,
            RecipeStates.CHECK_SERVICE_STATUS_REPORT: RecipeSteps.RECORD_USER,
            RecipeStates.FINAL_NOTE_PROMPT: RecipeSteps.PROMPT_USER,
            RecipeStates.FINAL_NOTE_REPORT: RecipeSteps.TRANSCRIBE_USER,
        },
        start_state=RecipeStates.STANDBY,
        access_key=access_key)

    try:
        workflow.run()
    except KeyboardInterrupt:
        pass
    finally:
        # Make the cursor visible again.
        sys.stdout.write("\033[?25h")
        sys.stdout.flush()

        workflow.delete()


if __name__ == '__main__':
    main()
