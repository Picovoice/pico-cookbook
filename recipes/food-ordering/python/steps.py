import math
import re
import time
from enum import Enum
from typing import (
    Any,
    Callable,
    Dict,
    List,
    Literal,
    Optional,
    Sequence,
    Set,
    Type
)

import pvcheetah
import pvorca
import pvporcupine
import pvrhino
from pvorca import Orca
from pvspeaker import PvSpeaker

from noise_suppressed_recorder import AINoiseSuppressedRecorder


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

    @staticmethod
    def sanitize_for_orca(text: str, valid_characters: Set[str]) -> str:
        valid_character_set = set(valid_characters)

        replacements = {
            "\n": " ",
            "\r": " ",
            "\t": " ",
            "“": '"',
            "”": '"',
            "‘": "'",
            "’": "'",
            "—": "-",
            "–": "-",
            "…": "...",
            "<": " ",
            ">": " ",
        }

        text = "".join(replacements.get(x, x) for x in text)
        text = "".join(x if x in valid_character_set else " " for x in text)
        text = re.sub(r"\s+", " ", text)

        return text

    def run(
            self,
            prompt: str,
            on_synthesis: Optional[Callable[[Sequence[Orca.WordAlignment]], None]] = None
    ) -> Optional[Dict[str, Any]]:
        try:
            self._speaker.start()

            pcm, alignment = self._orca.synthesize(text=OrcaStep.sanitize_for_orca(
                prompt,
                self._orca.valid_characters))
            if on_synthesis is not None:
                on_synthesis(alignment)
            self._speaker.flush(pcm)
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
            require_endpoint: bool = True
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

    @staticmethod
    def rms(frame: list[int]) -> float:
        total = 0.0
        for sample in frame:
            total += (sample / 32768.0) * (sample / 32768.0)
        return math.sqrt(total / len(frame))

    def run(
            self,
            check_for_silence: bool,
            silence_start: list[float],
            silence_timeout: float,
            volume_threshold: float) -> Dict[str, Any] | Literal["TIMEOUT"] | None:
        try:
            self._recorder.start()

            running_silence_start = silence_start[0]

            while True:
                frame = self._recorder.read(self._rhino.frame_length)

                if check_for_silence:
                    volume = RhinoStep.rms(frame)
                    if volume > volume_threshold:
                        running_silence_start = time.time()
                    elif (time.time() - running_silence_start) > silence_timeout:
                        return "TIMEOUT"

                if self._rhino.process(frame):
                    break

            silence_start[0] = running_silence_start

            inference = self._rhino.get_inference()
            return {
                'is_understood': inference.is_understood,
                'intent': inference.intent,
                'slots': inference.slots,
            }
        finally:
            self._recorder.stop()

    def delete(self) -> None:
        self._rhino.delete()

    def __str__(self):
        return f"""{self.__class__.__name__} {{
  {self._rhino.__class__.__name__}[V{self._rhino.version}]
}}
"""
