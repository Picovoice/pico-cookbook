from typing import (
    Optional,
    Sequence
)

import pvporcupine
from pvporcupine import Porcupine


class Workflow(object):
    pass


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


class PorcupineStep(Step):
    def __init__(
            self,
            name: Optional[str] = None,
            access_key: Optional[str] = None,
            model_path: Optional[str] = None,
            keyword_paths: Optional[Sequence[str]] = None,
            sensitivities: Optional[Sequence[float]] = None,
            porcupine: Optional[Porcupine] = None
    ) -> None:
        super().__init__(name=name)

        if porcupine is None:
            self._porcupine = pvporcupine.create(
                access_key=access_key,
                model_path=model_path,
                keyword_paths=keyword_paths,
                sensitivities=sensitivities)
        else:
            self._porcupine = porcupine

    def run(self) -> None:
        pass

    def __str__(self) -> str:
        raise NotImplementedError()


class RhinoStep(Step):
    pass


class CheetahStep(Step):
    pass


def main() -> None:
    pass


if __name__ == '__main__':
    main()
