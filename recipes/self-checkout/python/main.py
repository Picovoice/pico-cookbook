import sys
import shutil
import string
from argparse import ArgumentParser
from dataclasses import dataclass
from enum import Enum
from threading import Event, Lock, Thread
from time import monotonic, sleep
from typing import (
    Any, Callable, Dict, List, Optional, Sequence, Tuple, Type
)

from pvorca import Orca
from pvspeaker import PvSpeaker

from noise_suppressed_recorder import AINoiseSuppressedRecorder
from steps import Steps, Step, CheetahStep, OrcaStep, PorcupineStep, RhinoStep


def print_async(get_text: Callable[[], str], refresh_sec: float = 0.1, end: str = "\n") -> Tuple[Event, Thread]:
    stop_event = Event()

    def wrap_text(text: str, width: int) -> list[str]:
        text = text.replace("\n", " ")
        if width <= 0:
            return [""]
        return [text[i:i + width] for i in range(0, len(text), width)] or [""]

    def clear_block(num_lines: int) -> None:
        if num_lines <= 0:
            return

        sys.stdout.write("\r")
        if num_lines > 1:
            sys.stdout.write(f"\033[{num_lines - 1}F")

        for i in range(num_lines):
            sys.stdout.write("\033[2K")
            if i < num_lines - 1:
                sys.stdout.write("\n")

        if num_lines > 1:
            sys.stdout.write(f"\033[{num_lines - 1}F")
        sys.stdout.write("\r")

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
                output = "\n".join(lines)

                clear_block(prev_num_lines)
                sys.stdout.write(output)
                sys.stdout.flush()

                prev_num_lines = len(lines)
                i = (i + 1) % len(dots_list)
                sleep(refresh_sec)

            text = get_text()
            width = max(1, shutil.get_terminal_size(fallback=(80, 24)).columns - 1)
            lines = wrap_text(f"{text}    ", width)
            output = "\n".join(lines)

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

            suffix = " " if i < (len(alignments) - 1) and (alignments[i + 1].word not in string.punctuation) else ""
            on_tick(x.word + suffix)

    thread = Thread(target=run, daemon=True)
    thread.start()
    return thread


@dataclass
class Transition(object):
    next_state: Optional[Enum] = None
    next_state_kwargs: Optional[Dict[str, Any]] = None


class State(object):
    def __init__(
            self,
            step: Optional[Step] = None
    ) -> None:
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
                self._states[state] = state_subclass.create(
                    state=state,
                    workflow=self,
                    step=self._steps[state_steps[state]])
            else:
                self._states[state] = state_subclass.create(state=state, workflow=self)

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
class Product:
    name: str
    price: float


SHOPPING_CART: List[Product] = [
    Product("Great Value Dark Chocolate Bar, 3.52 oz", 1.00),
    Product("SunChips Whole Grain Snacks, Original, 7 oz", 3.68),
    Product("V8 +ENERGY Pomegranate Blueberry Energy Drink, 8 oz Can (Pack of 12)", 9.38),
    Product("Alcatel Alcatel One Touch Idol 3, 16GB Unlocked Smartphone, Black", 99.47),
    Product("Impossible Plant Based Ground, Brick, 12oz", 5.96),
    Product("Fresh Cravings Roasted Red Pepper Hummus 10oz", 2.67),
]


class RecipeSteps(Enum):
    STANDBY = "Standby"
    PROMPT_USER = "PromptUser"
    RECORD_USER = "RecordUser"


class RecipeStates(Enum):
    STANDBY = "Standby"
    WELCOME_PROMPT = "WelcomePrompt"
    LISTEN_COMMAND = "ListenCommand"
    SCAN_ITEM_PROMPT = "ScanItemPrompt"

    DECIDE_ON_BAGGING = "DecideOnBagging"

    SELECT_PAYMENT_METHOD = "SelectPaymentMethod"
    LIST_ITEMS_PROMPT = "ListItemsPrompt"

    REPEAT_LAST_PROMPT = "RepeatLastPrompt"
    SPEAK_PROMPT = "SpeakPrompt"
    CHECKOUT_COMPLETE_PROMPT = "CheckoutCompletePrompt"


MAX_ORCA_SPEED = 1.3
MIN_ORCA_SPEED = 0.7

MAX_VOLUME = 4.0
MIN_VOLUME = 0.25


def parse_accessibility_intent(
        intent: string,
        workflow: Workflow,
        next_item_index: int,
        cart: List[Product],
        next_state: State,
        next_args: Dict = {}
) -> Optional[Transition]:
    orca_step = workflow._steps[RecipeSteps.PROMPT_USER]
    if intent == "speedUp":
        if orca_step.speed < MAX_ORCA_SPEED:
            orca_step.speed = min(orca_step.speed + 0.3, MAX_ORCA_SPEED)

            return Transition(
                next_state=RecipeStates.SPEAK_PROMPT,
                next_state_kwargs={
                    "next_item_index": next_item_index,
                    "cart": cart,
                    "prompt": "Voice speed increased.",
                    "next_state": next_state,
                    "next_args": next_args
                })
        else:
            return Transition(
                next_state=RecipeStates.SPEAK_PROMPT,
                next_state_kwargs={
                    "next_item_index": next_item_index,
                    "cart": cart,
                    "prompt": "Voice speed already at maximum.",
                    "next_state": next_state,
                    "next_args": next_args
                })
    elif intent == "slowDown":
        if orca_step.speed > MIN_ORCA_SPEED:
            orca_step.speed = max(orca_step.speed - 0.3, MIN_ORCA_SPEED)

            return Transition(
                next_state=RecipeStates.SPEAK_PROMPT,
                next_state_kwargs={
                    "next_item_index": next_item_index,
                    "cart": cart,
                    "prompt": "Voice speed decreased.",
                    "next_state": next_state,
                    "next_args": next_args
                })
        else:
            return Transition(
                next_state=RecipeStates.SPEAK_PROMPT,
                next_state_kwargs={
                    "next_item_index": next_item_index,
                    "cart": cart,
                    "prompt": "Voice speed already at minimum.",
                    "next_state": next_state,
                    "next_args": next_args
                })
    elif intent == "normalSpeed":
        orca_step.speed = 1.0
        return Transition(
            next_state=RecipeStates.SPEAK_PROMPT,
            next_state_kwargs={
                "next_item_index": next_item_index,
                "cart": cart,
                "prompt": "Voice speed reset.",
                "next_state": next_state,
                "next_args": next_args
            })
    elif intent == "speakLouder":
        if orca_step.volume < MAX_VOLUME:
            orca_step.volume = min(orca_step.volume * 2, MAX_VOLUME)

            return Transition(
                next_state=RecipeStates.SPEAK_PROMPT,
                next_state_kwargs={
                    "next_item_index": next_item_index,
                    "cart": cart,
                    "prompt": "Voice volume increased.",
                    "next_state": next_state,
                    "next_args": next_args
                })
        else:
            return Transition(
                next_state=RecipeStates.SPEAK_PROMPT,
                next_state_kwargs={
                    "next_item_index": next_item_index,
                    "cart": cart,
                    "prompt": "Voice volume already at maximum.",
                    "next_state": next_state,
                    "next_args": next_args
                })
    elif intent == "speakQuieter":
        if orca_step.volume > MIN_VOLUME:
            orca_step.volume = max(orca_step.volume * 0.5, MIN_VOLUME)

            return Transition(
                next_state=RecipeStates.SPEAK_PROMPT,
                next_state_kwargs={
                    "next_item_index": next_item_index,
                    "cart": cart,
                    "prompt": "Voice volume decreased.",
                    "next_state": next_state,
                    "next_args": next_args
                })
        else:
            return Transition(
                next_state=RecipeStates.SPEAK_PROMPT,
                next_state_kwargs={
                    "next_item_index": next_item_index,
                    "cart": cart,
                    "prompt": "Voice volume already at minimum.",
                    "next_state": next_state,
                    "next_args": next_args
                })
    elif intent == "normalVolume":
        orca_step.volume = 1.0
        return Transition(
            next_state=RecipeStates.SPEAK_PROMPT,
            next_state_kwargs={
                "next_item_index": next_item_index,
                "cart": cart,
                "prompt": "Voice volume reset.",
                "next_state": next_state,
                "next_args": next_args
            })
    elif intent == "repeat":
        return Transition(
            next_state=RecipeStates.REPEAT_LAST_PROMPT,
            next_state_kwargs={
                "next_item_index": next_item_index,
                "cart": cart,
                "next_state": next_state,
                "next_args": next_args
            })
    elif intent == "help":
        return Transition(
            next_state=RecipeStates.SPEAK_PROMPT,
            next_state_kwargs={
                "next_item_index": next_item_index,
                "cart": cart,
                "prompt": "A staff member has been notified and is on their way.",
                "next_state": RecipeStates.CHECKOUT_COMPLETE_PROMPT,
                "next_args": {"checkout_successful": False, **next_args}
            })

    return None


class RecipeState(State):
    @classmethod
    def create(
            cls,
            state: RecipeStates,
            workflow: Workflow,
            **kwargs: Any
    ) -> "RecipeState":
        children = {
            RecipeStates.STANDBY: RecipeStandbyState,
            RecipeStates.WELCOME_PROMPT: RecipeWelcomePromptState,
            RecipeStates.LISTEN_COMMAND: RecipeListenCommandState,
            RecipeStates.SCAN_ITEM_PROMPT: RecipeScanItemPromptState,

            RecipeStates.DECIDE_ON_BAGGING: RecipeDecideOnBaggingState,

            RecipeStates.SELECT_PAYMENT_METHOD: RecipeSelectPaymentMethodState,
            RecipeStates.LIST_ITEMS_PROMPT: RecipeListItemsPromptState,

            RecipeStates.REPEAT_LAST_PROMPT: RecipeRepeatLastPromptState,
            RecipeStates.SPEAK_PROMPT: RecipeSpeakPromptState,
            RecipeStates.CHECKOUT_COMPLETE_PROMPT: RecipeCheckoutCompletePromptState,
        }

        if state not in children:
            raise ValueError(f"Cannot create a {cls.__name__} of type `{state.value}`.")

        obj = children[state](**kwargs)
        obj._workflow = workflow
        return obj


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

    def _repeat_last_prompt(self) -> None:
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

        self._step.repeat_last(on_synthesis=on_synthesis)
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
        text = "Detected wake word. Starting self-checkout..."

        sleep(.1)
        event.set()
        thread.join()

        return Transition(
            next_state=RecipeStates.WELCOME_PROMPT,
            next_state_kwargs={
                "next_item_index": 0,
                "cart": []
            })


class RecipeWelcomePromptState(RecipePromptState):
    def run(
            self,
            next_item_index: int,
            cart: List[Product],
            **kwargs: Any
    ) -> Transition:
        self._run_prompt("Welcome to Walmart's self-checkout! I will announce when you scan each item.")
        self._run_prompt(
            "If you need me to change my speed, volume, or to repeat myself, "
            "let me know whenever I'm listening.")

        return Transition(
            next_state=RecipeStates.LISTEN_COMMAND,
            next_state_kwargs={
                "next_item_index": next_item_index,
                "cart": cart
            })


class RecipeListenCommandState(State):
    def __init__(self, step: RhinoStep) -> None:
        super().__init__(step=step)

    def run(
            self,
            next_item_index: int,
            cart: List[Product],
            **kwargs
    ) -> Transition:
        print(
            "- Scan (item)\n"
            "- Remove (last item)\n"
            "- (What is my) total\n"
            "- Start over\n"
            "- Pay (now)")

        text = ""

        def get_text() -> str:
            return text

        event, thread = print_async(get_text=get_text)

        while True:
            inference = self._step.run()
            intent = inference["intent"] if inference and inference["is_understood"] else None

            if intent == "scanNext":
                if next_item_index < len(SHOPPING_CART):
                    event.set()
                    thread.join()

                    return Transition(
                        next_state=RecipeStates.SCAN_ITEM_PROMPT,
                        next_state_kwargs={
                            "next_item_index": next_item_index,
                            "cart": cart
                        })
                else:
                    event.set()
                    thread.join()

                    return Transition(
                        next_state=RecipeStates.SPEAK_PROMPT,
                        next_state_kwargs={
                            "next_item_index": next_item_index,
                            "cart": cart,
                            "prompt": "You did not scan an item. Are you ready to pay?",
                            "next_state": RecipeStates.LISTEN_COMMAND,
                        })

            elif intent == "removeItem":
                if len(cart) == 0:
                    event.set()
                    thread.join()

                    return Transition(
                        next_state=RecipeStates.SPEAK_PROMPT,
                        next_state_kwargs={
                            "next_item_index": next_item_index,
                            "cart": cart,
                            "prompt": "No item to remove. Please start by scanning an item.",
                            "next_state": RecipeStates.LISTEN_COMMAND,
                        })

                event.set()
                thread.join()

                new_cart = list(cart[:-1]) if cart else []

                return Transition(
                    next_state=RecipeStates.SPEAK_PROMPT,
                    next_state_kwargs={
                        "next_item_index": next_item_index - 1,
                        "cart": new_cart,
                        "prompt": f"Removed {cart[-1].name} from scanned items.",
                        "next_state": RecipeStates.LISTEN_COMMAND,
                    })

            elif intent == "getTotal":
                event.set()
                thread.join()

                total = sum(item.price for item in cart)

                return Transition(
                    next_state=RecipeStates.SPEAK_PROMPT,
                    next_state_kwargs={
                        "next_item_index": next_item_index,
                        "cart": cart,
                        "prompt": f"Your current total is: ${total:.2f}.",
                        "next_state": RecipeStates.LISTEN_COMMAND,
                    })

            elif intent == "startOver":
                event.set()
                thread.join()

                return Transition(
                    next_state=RecipeStates.SPEAK_PROMPT,
                    next_state_kwargs={
                        "next_item_index": next_item_index,
                        "cart": cart,
                        "prompt": "Restarting your session.",
                        "next_state": RecipeStates.STANDBY,
                    })

            elif intent == "payNow":
                if len(cart) == 0:
                    event.set()
                    thread.join()

                    return Transition(
                        next_state=RecipeStates.SPEAK_PROMPT,
                        next_state_kwargs={
                            "next_item_index": next_item_index,
                            "cart": cart,
                            "prompt": "Your cart is empty. Please start by scanning an item.",
                            "next_state": RecipeStates.LISTEN_COMMAND,
                        })

                event.set()
                thread.join()

                return Transition(
                    next_state=RecipeStates.DECIDE_ON_BAGGING,
                    next_state_kwargs={
                        "next_item_index": next_item_index,
                        "cart": cart
                    })

            maybe_transition = parse_accessibility_intent(
                intent=intent,
                workflow=self._workflow,
                next_item_index=next_item_index,
                cart=cart,
                next_state=RecipeStates.LISTEN_COMMAND
            )
            if maybe_transition is not None:
                event.set()
                thread.join()

                return maybe_transition


class RecipeScanItemPromptState(RecipePromptState):
    def run(
            self,
            next_item_index: int,
            cart: List[Product],
            **kwargs: Any
    ) -> Transition:
        item = SHOPPING_CART[next_item_index]
        new_cart = list(cart) + [item]

        self._run_prompt(f"Scanned: {item.name}. Price: ${item.price:.2f}.")

        return Transition(
            next_state=RecipeStates.LISTEN_COMMAND,
            next_state_kwargs={
                "next_item_index": next_item_index + 1,
                "cart": new_cart
            })


class RecipeDecideOnBaggingState(State):
    def __init__(self, step: RhinoStep) -> None:
        super().__init__(step=step)

    def run(
            self,
            next_item_index: int,
            cart: List[Product],
            already_spoke: bool = False,
            **kwargs: Any
    ) -> Transition:
        if not already_spoke:
            return Transition(
                next_state=RecipeStates.SPEAK_PROMPT,
                next_state_kwargs={
                    "next_item_index": next_item_index,
                    "cart": cart,
                    "prompt": "Do you need a bag for 50¢?",
                    "next_state": RecipeStates.DECIDE_ON_BAGGING,
                    "next_args": {"already_spoke": True}
                })

        text = ""

        def get_text() -> str:
            return text

        event, thread = print_async(get_text=get_text)

        while True:
            inference = self._step.run()
            intent = inference["intent"] if inference and inference["is_understood"] else None

            if intent == "confirmation":
                event.set()
                thread.join()

                new_cart = cart + [Product("Plastic bag", 0.5)]

                return Transition(
                    next_state=RecipeStates.SPEAK_PROMPT,
                    next_state_kwargs={
                        "next_item_index": next_item_index,
                        "cart": new_cart,
                        "prompt": "A bag has been added to your total.",
                        "next_state": RecipeStates.LIST_ITEMS_PROMPT,
                    })
            elif intent == "skipBagging":
                event.set()
                thread.join()

                return Transition(
                    next_state=RecipeStates.LIST_ITEMS_PROMPT,
                    next_state_kwargs={
                        "next_item_index": next_item_index,
                        "cart": cart,
                    })
            elif intent == "goBack":
                event.set()
                thread.join()

                return Transition(
                    next_state=RecipeStates.SPEAK_PROMPT,
                    next_state_kwargs={
                        "next_item_index": next_item_index,
                        "cart": cart,
                        "prompt": "Going back.",
                        "next_state": RecipeStates.LISTEN_COMMAND,
                    })

            maybe_transition = parse_accessibility_intent(
                intent=intent,
                workflow=self._workflow,
                next_item_index=next_item_index,
                cart=cart,
                next_state=RecipeStates.DECIDE_ON_BAGGING
            )
            if maybe_transition is not None:
                event.set()
                thread.join()

                return maybe_transition


class RecipeListItemsPromptState(RecipePromptState):
    def run(
            self,
            next_item_index: int,
            cart: List[Product],
            **kwargs: Any
    ) -> Transition:
        prompt_list = []

        if len(cart) == 0:
            prompt_list = ["Your cart is currently empty."]
        else:
            plural = "s" if len(cart) != 1 else ""
            prompt_list.append(f"Your cart has {len(cart)} item{plural}: ")

            prompt_list += [f"Item {i+1}. {item.name} at ${item.price:.2f}" for i, item in enumerate(cart)]

            total = sum(item.price for item in cart)
            prompt_list.append(f"Running total: ${total:.2f}.")

        for prompt in prompt_list:
            self._run_prompt(prompt)

        return Transition(
            next_state=RecipeStates.SELECT_PAYMENT_METHOD,
            next_state_kwargs={
                "next_item_index": next_item_index,
                "cart": cart
            })


PAYMENT_METHODS = [
    "credit",
    "debit",
    "cash",
    "target circle",
    "apple pay",
]


class RecipeSelectPaymentMethodState(State):
    def __init__(self, step: RhinoStep) -> None:
        super().__init__(step=step)

    def run(
            self,
            next_item_index: int,
            cart: List[Product],
            already_spoke: bool = False,
            **kwargs: Any
    ) -> Transition:
        if not already_spoke:
            return Transition(
                next_state=RecipeStates.SPEAK_PROMPT,
                next_state_kwargs={
                    "next_item_index": next_item_index,
                    "cart": cart,
                    "prompt": "Please choose a payment method",
                    "next_state": RecipeStates.SELECT_PAYMENT_METHOD,
                    "next_args": {"already_spoke": True}
                })

        text = "Accepted Payment Methods: " + ", ".join(PAYMENT_METHODS)

        def get_text() -> str:
            return text

        event, thread = print_async(get_text=get_text)

        while True:
            inference = self._step.run()
            intent = inference["intent"] if inference and inference["is_understood"] else None

            if intent == "choosePayment":
                event.set()
                thread.join()

                payment_method = inference["slots"].get("payment")
                payment_method_capitalized = payment_method[0].upper() + payment_method[1:]

                return Transition(
                    next_state=RecipeStates.SPEAK_PROMPT,
                    next_state_kwargs={
                        "next_item_index": next_item_index,
                        "cart": cart,
                        "prompt": f"{payment_method_capitalized} selected.",
                        "next_state": RecipeStates.CHECKOUT_COMPLETE_PROMPT,
                        "next_args": {"checkout_successful": True}
                    })

            elif intent == "goBack":
                event.set()
                thread.join()

                return Transition(
                    next_state=RecipeStates.SPEAK_PROMPT,
                    next_state_kwargs={
                        "next_item_index": next_item_index,
                        "cart": cart,
                        "prompt": "Going back.",
                        "next_state": RecipeStates.DECIDE_ON_BAGGING,
                    })

            maybe_transition = parse_accessibility_intent(
                intent=intent,
                workflow=self._workflow,
                next_item_index=next_item_index,
                cart=cart,
                next_state=RecipeStates.SELECT_PAYMENT_METHOD,
                next_args={"already_spoke": True}
            )
            if maybe_transition is not None:
                event.set()
                thread.join()

                return maybe_transition


class RecipeRepeatLastPromptState(RecipePromptState):
    def run(
            self,
            next_item_index: int,
            cart: List[Product],
            next_state: RecipeStates,
            next_args: Dict = {},
            **kwargs: Any
    ) -> Transition:
        self._repeat_last_prompt()

        return Transition(
            next_state=next_state,
            next_state_kwargs={
                "next_item_index": next_item_index,
                "cart": cart,
                **next_args
            })


class RecipeSpeakPromptState(RecipePromptState):
    def run(
            self,
            next_item_index: int,
            cart: List[Product],
            prompt: str,
            next_state: RecipeStates,
            next_args: Dict = {},
            **kwargs: Any
    ) -> Transition:
        self._run_prompt(prompt)

        return Transition(
            next_state=next_state,
            next_state_kwargs={
                "next_item_index": next_item_index,
                "cart": cart,
                **next_args
            })


class RecipeCheckoutCompletePromptState(RecipePromptState):
    def run(
            self,
            cart: List[Product],
            checkout_successful: bool,
            **kwargs: Any
    ) -> Transition:
        if checkout_successful:
            total = sum(item.price for item in cart)
            plural = "s" if len(cart) != 1 else ""
            self._run_prompt(f"Transaction complete. You purchased {len(cart)} item{plural}.")
            self._run_prompt(f"Your total was ${total:.2f}.")
            self._run_prompt("Thank you for shopping with us. Goodbye!")
        else:
            self._run_prompt("Checkout ended.")

        return Transition(next_state=None)


def main() -> None:
    parser = ArgumentParser()
    parser.add_argument(
        "--access_key",
        required=True,
        help="AccessKey obtained from Picovoice Console (https://console.picovoice.ai/)")
    parser.add_argument(
        "--keyword_path",
        required=True,
        help="Absolute path to a Porcupine wake word file (.ppn)")
    parser.add_argument(
        "--context_path",
        required=True,
        help="Absolute path to a Rhino Speech-to-Intent context file (.rhn)")
    args = parser.parse_args()

    access_key = args.access_key
    keyword_path = args.keyword_path
    context_path = args.context_path

    workflow = Workflow(
        steps={
            RecipeSteps.STANDBY: (Steps.PORCUPINE, {"keyword_path": keyword_path}),
            RecipeSteps.PROMPT_USER: (Steps.ORCA, None),
            RecipeSteps.RECORD_USER: (Steps.RHINO, {"context_path": context_path}),
        },
        state_enum=RecipeStates,
        state_subclass=RecipeState,
        state_steps={
            RecipeStates.STANDBY: RecipeSteps.STANDBY,
            RecipeStates.WELCOME_PROMPT: RecipeSteps.PROMPT_USER,
            RecipeStates.LISTEN_COMMAND: RecipeSteps.RECORD_USER,
            RecipeStates.SCAN_ITEM_PROMPT: RecipeSteps.PROMPT_USER,

            RecipeStates.DECIDE_ON_BAGGING: RecipeSteps.RECORD_USER,

            RecipeStates.SELECT_PAYMENT_METHOD: RecipeSteps.RECORD_USER,
            RecipeStates.LIST_ITEMS_PROMPT: RecipeSteps.PROMPT_USER,

            RecipeStates.REPEAT_LAST_PROMPT: RecipeSteps.PROMPT_USER,
            RecipeStates.SPEAK_PROMPT: RecipeSteps.PROMPT_USER,
            RecipeStates.CHECKOUT_COMPLETE_PROMPT: RecipeSteps.PROMPT_USER,
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


if __name__ == "__main__":
    main()
