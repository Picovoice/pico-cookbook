import shutil
import string
import sys
import time
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
    Literal,
    MutableSequence,
    Optional,
    Sequence,
    Tuple,
    Type,
    Union
)

from pvorca import Orca
from pvspeaker import PvSpeaker

from noise_suppressed_recorder import AINoiseSuppressedRecorder
from steps import Steps, Step, CheetahStep, OrcaStep, PorcupineStep, RhinoStep


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


@dataclass
class Transition(object):
    next_state: Optional[Enum] = None
    next_state_kwargs: Optional[Dict[str, Any]] = None


class State(object):
    def __init__(self, step: Optional[Step] = None) -> None:
        self._step = step

    def run(
            self,
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

    def run(self) -> None:
        current_state = self._start_state
        current_state_kwargs = self._start_state_kwargs

        while current_state is not None:
            transition = current_state.run(**current_state_kwargs)
            current_state = self._states[transition.next_state] if transition.next_state is not None else None
            current_state_kwargs = transition.next_state_kwargs if transition.next_state_kwargs is not None else dict()

    def reset(self) -> None:
        pass

    def delete(self) -> None:
        for step in reversed(self._steps.values()):
            step.delete()

        self._speaker.stop()
        self._speaker.delete()

        self._recorder.stop()
        self._recorder.delete()

    def __str__(self) -> str:
        return self.__class__.__name__


@dataclass
class OrderChange:
    to_item: str | None
    to_size: str | None
    to_combo: str | None


class OrderItem:
    size: str | None
    item_name: str

    def __init__(self, size: str | None, item_name: str):
        self.size = size
        self.item_name = item_name

    @staticmethod
    def parse_add_item_inference(inference) -> "OrderItem":
        size = inference['slots'].get('size', None)
        item = inference['slots'].get('item', None)
        combo = inference['slots'].get('combo', None)
        modifier = inference['slots'].get('modifier', None)
        quantity = int(inference['slots'].get('quantity', 1))

        if combo is not None:
            return ComboItem(
                quantity=quantity,
                size=size,
                item_name=item,
                combo_name=combo)
        else:
            return MenuItem(
                quantity=quantity,
                size=size,
                item_name=item,
                modifier=modifier)

    @staticmethod
    def parse_remove_item_inference(inference) -> "OrderItem":
        size = inference['slots'].get('size', None)
        item = inference['slots'].get('item', None)

        return OrderItem(size=size, item_name=item)

    @staticmethod
    def parse_change_item_inference(inference) -> Tuple[Union["OrderItem", Literal["LAST_ITEM"]], OrderChange]:
        from_item = inference['slots'].get('fromItem', None)
        to_size = inference['slots'].get('toSize', None)
        to_item = inference['slots'].get('toItem', None)
        to_combo = inference['slots'].get('combo', None)

        return (
            "LAST_ITEM" if from_item is None else OrderItem(size=None, item_name=from_item),
            OrderChange(to_item=to_item, to_size=to_size, to_combo=to_combo)
        )

    def find_from_end_in(self, order: Sequence["OrderItem"]) -> int | None:
        for i, order_item in reversed(list(enumerate(order))):
            same_size = (self.size is None) or (self.size == order_item.size)
            same_item = self.item_name == order_item.item_name
            if same_size and same_item:
                return i
        return None

    def __str__(self):
        if self.size is None:
            return f"{self.item_name}"
        else:
            return f"{self.size} {self.item_name}"


class ComboItem(OrderItem):
    quantity: int
    combo_name: str

    def __init__(
            self,
            size: str | None,
            item_name: str,
            quantity: int,
            combo_name: str):
        super().__init__(size, item_name)
        self.quantity = quantity
        self.combo_name = combo_name

    def __str__(self):
        response = f"{self.quantity} {super().__str__()} {self.combo_name}"

        if self.quantity != 1 and response[-1] != "s":
            response += "s"

        return response


class MenuItem(OrderItem):
    quantity: int
    modifier: str | None

    def __init__(
            self,
            size: str | None,
            item_name: str,
            quantity: int,
            modifier: str | None):
        super().__init__(size, item_name)
        self.quantity = quantity
        self.modifier = modifier

    def __str__(self):
        response = f"{self.quantity} {super().__str__()}"

        if self.quantity != 1 and response[-1] != "s":
            response += "s"

        if self.modifier is not None:
            response += f", {self.modifier}"

        return response


class RecipeSteps(Enum):
    STANDBY = "Standby"
    PROMPT_USER = "PromptUser"
    RECORD_USER = "RecordUser"


class RecipeStates(Enum):
    STANDBY = "Standby"
    LISTEN_FOR_ORDER = "ListenForOrder"
    ADD_ITEM = "AddItem"
    REMOVE_ITEM = "RemoveItem"
    CHANGE_ITEM = "ChangeItem"
    START_OVER = "StartOver"
    HELP = "Help"
    REPEAT_ORDER = "RepeatOrder"
    SPEAK_PROMPT = "SpeakPrompt"
    SILENT_USER = "SilentUser"
    END_ORDER = "EndOrder"


class RecipeState(State):
    @classmethod
    def create(
            cls,
            state: RecipeStates,
            **kwargs: Any
    ) -> "RecipeState":
        children = {
            RecipeStates.STANDBY: RecipeStandbyState,
            RecipeStates.LISTEN_FOR_ORDER: RecipeListenForOrderState,
            RecipeStates.ADD_ITEM: RecipeAddItemState,
            RecipeStates.REMOVE_ITEM: RecipeRemoveItemState,
            RecipeStates.CHANGE_ITEM: RecipeChangeItemState,
            RecipeStates.START_OVER: RecipeStartOverState,
            RecipeStates.HELP: RecipeHelpState,
            RecipeStates.REPEAT_ORDER: RecipeRepeatOrderState,
            RecipeStates.SPEAK_PROMPT: RecipeSpeakPromptState,
            RecipeStates.SILENT_USER: RecipeSilentUserState,
            RecipeStates.END_ORDER: RecipeEndOrderState,
        }

        if state not in children:
            raise ValueError(f"Cannot create a {cls.__name__} of type `{state.value}`.")

        return children[state](**kwargs)


class RecipePromptState(RecipeState):
    def __init__(self, step: OrcaStep) -> None:
        super().__init__(step=step)

    def _run_prompt(self, prompt: str) -> None:
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
        if timer_thread is not None:
            timer_thread.join()
        print_event.set()
        print_thread.join()


class RecipeStandbyState(RecipeState):
    def __init__(self, step: PorcupineStep) -> None:
        super().__init__(step=step)

    def run(
            self,
            **kwargs: Any
    ) -> Transition:
        text = "Listening for wake word"

        def get_text() -> str:
            return text

        event, thread = print_async(get_text=get_text)
        self._step.run()
        text = "Detected wake word. Listening for your order..."

        sleep(.1)
        event.set()
        thread.join()

        return Transition(
            next_state=RecipeStates.SPEAK_PROMPT,
            next_state_kwargs={
                'order': [],
                'prompt': "Welcome to McDonald's, can I take your order?"
            })


class RecipeListenForOrderState(RecipeState):
    def __init__(self, step: RhinoStep) -> None:
        super().__init__(step=step)

    def run(
            self,
            order: MutableSequence[OrderItem],
            just_asked: bool = False,
            **kwargs: Any
    ) -> Transition:
        text = "Listening for order"

        def get_text() -> str:
            return text

        event, thread = print_async(get_text=get_text)

        silence_timeout_s = 5
        volume_threshold = 0.0001
        start_time = [time.time()]

        while True:
            inference = None
            while inference is None:
                inference = self._step.run(
                    check_for_silence=(not just_asked) and (len(order) > 0),
                    silence_start=start_time,
                    silence_timeout=silence_timeout_s,
                    volume_threshold=volume_threshold)

            if inference == "TIMEOUT":
                event.set()
                thread.join()

                return Transition(
                    next_state=RecipeStates.SILENT_USER,
                    next_state_kwargs={
                        'order': order
                    })

            elif inference['is_understood'] and inference['intent'] == 'addItem':
                event.set()
                thread.join()

                return Transition(
                    next_state=RecipeStates.ADD_ITEM,
                    next_state_kwargs={
                        'order': order,
                        'item': OrderItem.parse_add_item_inference(inference)
                    })

            elif inference['is_understood'] and inference['intent'] == 'removeItem':
                event.set()
                thread.join()

                return Transition(
                    next_state=RecipeStates.REMOVE_ITEM,
                    next_state_kwargs={
                        'order': order,
                        'to_remove': OrderItem.parse_remove_item_inference(inference)
                    })

            elif inference['is_understood'] and inference['intent'] == 'changeItem':
                event.set()
                thread.join()

                item_from, change = OrderItem.parse_change_item_inference(inference)
                return Transition(
                    next_state=RecipeStates.CHANGE_ITEM,
                    next_state_kwargs={
                        'order': order,
                        'item_from': item_from,
                        'change': change
                    })

            elif inference['is_understood'] and inference['intent'] == 'startOver':
                event.set()
                thread.join()

                return Transition(
                    next_state=RecipeStates.START_OVER,
                    next_state_kwargs={})

            elif inference['is_understood'] and inference['intent'] == 'help':
                event.set()
                thread.join()

                return Transition(
                    next_state=RecipeStates.HELP,
                    next_state_kwargs={
                        'order': order
                    })

            elif inference['is_understood'] and inference['intent'] == 'repeatOrder':
                event.set()
                thread.join()

                return Transition(
                    next_state=RecipeStates.REPEAT_ORDER,
                    next_state_kwargs={
                        'order': order,
                        'order_finalized': False
                    })

            elif inference['is_understood'] and inference['intent'] == 'endOrder':
                event.set()
                thread.join()

                return Transition(
                    next_state=RecipeStates.REPEAT_ORDER,
                    next_state_kwargs={
                        'order': order,
                        'order_finalized': True
                    })

            elif inference['is_understood'] and (inference['intent'] == 'confirmation') and just_asked:
                event.set()
                thread.join()

                return Transition(
                    next_state=RecipeStates.REPEAT_ORDER,
                    next_state_kwargs={
                        'order': order,
                        'order_finalized': True
                    })


class RecipeAddItemState(RecipePromptState):
    def __init__(self, step: OrcaStep) -> None:
        super().__init__(step=step)

    def run(
            self,
            order: MutableSequence[OrderItem],
            item: OrderItem,
            **kwargs: Any
    ) -> Transition:
        order.append(item)

        prompt = f"Added \"{str(item)}\" to your order."
        self._run_prompt(prompt=prompt)

        return Transition(
            next_state=RecipeStates.LISTEN_FOR_ORDER,
            next_state_kwargs={
                'order': order
            })


class RecipeRemoveItemState(RecipePromptState):
    def __init__(self, step: OrcaStep) -> None:
        super().__init__(step=step)

    def run(
            self,
            order: MutableSequence[OrderItem],
            to_remove: OrderItem,
            **kwargs: Any
    ) -> Transition:
        match_index = to_remove.find_from_end_in(order)

        if match_index is None:
            prompt = f"\"{str(to_remove)}\" is not in your order."
            self._run_prompt(prompt=prompt)
        else:
            removed_item = order.pop(match_index)
            prompt = f"Removing \"{str(removed_item)}\" from your order."
            self._run_prompt(prompt=prompt)

        return Transition(
            next_state=RecipeStates.LISTEN_FOR_ORDER,
            next_state_kwargs={
                'order': order
            })


class RecipeChangeItemState(RecipePromptState):
    def __init__(self, step: OrcaStep) -> None:
        super().__init__(step=step)

    def run(
            self,
            order: MutableSequence[OrderItem],
            item_from: Union[OrderItem, Literal["LAST_ITEM"]],
            change: OrderChange,
            **kwargs: Any
    ) -> Transition:
        match_index = None
        if item_from == "LAST_ITEM":
            match_index = (len(order) - 1) if (len(order) > 0) else None
        else:
            match_index = item_from.find_from_end_in(order)

        if match_index is None:
            if item_from == "LAST_ITEM":
                prompt = "I couldn't change anything because your order is empty."
            else:
                prompt = f"I couldn't change anything because \"{str(item_from)}\" is not in your order."
            self._run_prompt(prompt=prompt)
        else:
            old_order_str = str(order[match_index])

            if isinstance(order[match_index], ComboItem):
                if change.to_combo is not None:
                    order[match_index].combo_name = change.to_combo

                if change.to_size is not None:
                    order[match_index].size = change.to_size

                if change.to_item is not None:
                    order[match_index].item_name = change.to_item

            elif isinstance(order[match_index], MenuItem):
                if change.to_combo is not None:
                    prev = order[match_index]
                    order[match_index] = ComboItem(
                        size=prev.size,
                        item_name=prev.item_name,
                        quantity=prev.quantity,
                        combo_name=change.to_combo)

                if change.to_size is not None:
                    order[match_index].size = change.to_size

                if change.to_item is not None:
                    order[match_index].item_name = change.to_item

            else:
                raise Exception(f"unknown order item {order[match_index]}")

            prompt = f"Changing \"{old_order_str}\" in your order to \"{str(order[match_index])}\""
            self._run_prompt(prompt=prompt)

        return Transition(
            next_state=RecipeStates.LISTEN_FOR_ORDER,
            next_state_kwargs={
                'order': order
            })


class RecipeStartOverState(RecipePromptState):
    def __init__(self, step: OrcaStep) -> None:
        super().__init__(step=step)

    def run(
            self,
            **kwargs: Any
    ) -> Transition:
        prompt = "Your order has been reset."
        self._run_prompt(prompt=prompt)

        return Transition(
            next_state=RecipeStates.STANDBY,
            next_state_kwargs={
                'order': []
            })


class RecipeHelpState(RecipePromptState):
    def __init__(self, step: OrcaStep) -> None:
        super().__init__(step=step)

    def run(
            self,
            order: MutableSequence[OrderItem],
            **kwargs: Any
    ) -> Transition:
        prompt = "A staff member has been notified. While help is on the way, you can continue ordering."
        self._run_prompt(prompt=prompt)

        return Transition(
            next_state=RecipeStates.LISTEN_FOR_ORDER,
            next_state_kwargs={
                'order': order
            })


class RecipeRepeatOrderState(RecipePromptState):
    def __init__(self, step: OrcaStep) -> None:
        super().__init__(step=step)

    def run(
            self,
            order: MutableSequence[OrderItem],
            order_finalized: bool,
            **kwargs: Any
    ) -> Transition:
        prompt_list = []
        if order_finalized:
            prompt_list += ["Alright!"]

        prompt_list += [
            "While we get everything ready, here's what you ordered:"
            if order_finalized
            else "Here's your order:"
        ]
        prompt_list += [f"Item {i+1}. {str(order_item)}" for i, order_item in enumerate(order)]

        if len(order) == 0:
            self._run_prompt(prompt="Your order is empty. Please add an item.")
            return Transition(
                next_state=RecipeStates.LISTEN_FOR_ORDER,
                next_state_kwargs={
                    'order': order
                })

        for prompt in prompt_list:
            self._run_prompt(prompt=prompt)

        if order_finalized:
            sleep(.8)
            return Transition(
                next_state=RecipeStates.END_ORDER,
                next_state_kwargs={
                    'order': order
                })
        else:
            return Transition(
                next_state=RecipeStates.LISTEN_FOR_ORDER,
                next_state_kwargs={
                    'order': order
                })


class RecipeSpeakPromptState(RecipePromptState):
    def __init__(self, step: OrcaStep) -> None:
        super().__init__(step=step)

    def run(
            self,
            order: MutableSequence[OrderItem],
            prompt: str,
            **kwargs: Any
    ) -> Transition:
        self._run_prompt(prompt=prompt)

        return Transition(
            next_state=RecipeStates.LISTEN_FOR_ORDER,
            next_state_kwargs={
                'order': order
            })


class RecipeSilentUserState(RecipePromptState):
    def __init__(self, step: OrcaStep) -> None:
        super().__init__(step=step)

    def run(
            self,
            order: MutableSequence[OrderItem],
            **kwargs: Any
    ) -> Transition:
        prompt = "Is that all? Do you want me to repeat your order?"
        self._run_prompt(prompt=prompt)

        return Transition(
            next_state=RecipeStates.LISTEN_FOR_ORDER,
            next_state_kwargs={
                'order': order,
                'just_asked': True
            })


class RecipeEndOrderState(RecipePromptState):
    def __init__(self, step: OrcaStep) -> None:
        super().__init__(step=step)

    def run(
            self,
            **kwargs: Any
    ) -> Transition:
        sleep(.4)
        prompt = "Done! Your order is ready."
        self._run_prompt(prompt=prompt)

        return Transition(next_state=None)


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
        },
        state_enum=RecipeStates,
        state_subclass=RecipeState,
        state_steps={
            RecipeStates.STANDBY: RecipeSteps.STANDBY,
            RecipeStates.LISTEN_FOR_ORDER: RecipeSteps.RECORD_USER,
            RecipeStates.ADD_ITEM: RecipeSteps.PROMPT_USER,
            RecipeStates.REMOVE_ITEM: RecipeSteps.PROMPT_USER,
            RecipeStates.CHANGE_ITEM: RecipeSteps.PROMPT_USER,
            RecipeStates.START_OVER: RecipeSteps.PROMPT_USER,
            RecipeStates.HELP: RecipeSteps.PROMPT_USER,
            RecipeStates.REPEAT_ORDER: RecipeSteps.PROMPT_USER,
            RecipeStates.SPEAK_PROMPT: RecipeSteps.PROMPT_USER,
            RecipeStates.SILENT_USER: RecipeSteps.PROMPT_USER,
            RecipeStates.END_ORDER: RecipeSteps.PROMPT_USER,
        },
        start_state=RecipeStates.STANDBY,
        start_state_kwargs={},
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
