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
