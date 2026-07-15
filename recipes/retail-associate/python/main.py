import sys
import shutil
import string
import random
from argparse import ArgumentParser
from dataclasses import dataclass
from enum import Enum
from threading import Event, Lock, Thread
from time import monotonic, sleep
from typing import Any, Callable, Dict, List, Optional, Sequence, Tuple, Type

import pvporcupine
from pvorca import Orca
from pvrecorder import PvRecorder
from pvspeaker import PvSpeaker

from steps import Steps, Step, CheetahStep, OrcaStep, PorcupineStep, RhinoStep
from products import PRODUCT_DB


def print_async(
    get_text: Callable[[], str], refresh_sec: float = 0.1, end: str = "\n"
) -> Tuple[Event, Thread]:
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


def time_async(
    alignments: Sequence[Orca.WordAlignment], on_tick: Callable[[str], None]
) -> Thread:
    def run() -> None:
        start_sec = monotonic()

        for i, x in enumerate(alignments):
            delay = float(x.start_sec) - (monotonic() - start_sec)
            if delay > 0.0:
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
    def __init__(self, step: Optional[Step] = None) -> None:
        self._step = step

    def run(self, **kwargs: Any) -> Transition:
        raise NotImplementedError()

    def __str__(self) -> str:
        return self.__class__.__name__

    @classmethod
    def create(cls, state: Enum, **kwargs: Any) -> "State":
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
        porcupine_kwargs = next(kw for _, (st, kw) in steps.items() if st == Steps.PORCUPINE)
        porcupine = pvporcupine.create(
            access_key=access_key,
            keyword_paths=[porcupine_kwargs["keyword_path"]],
            model_path=porcupine_kwargs.get("model_path"),
            sensitivities=[porcupine_kwargs.get("sensitivity", 0.5)])
        self._recorder = PvRecorder(frame_length=porcupine.frame_length)
        self._speaker = PvSpeaker(sample_rate=22050, bits_per_sample=16)

        self._steps = dict()
        for uid, (step, kwargs) in steps.items():
            kwargs = dict(kwargs) if kwargs is not None else dict()
            if step == Steps.PORCUPINE:
                kwargs = {"porcupine": porcupine}
            self._steps[uid] = Step.create(
                step=step,
                access_key=access_key,
                recorder=self._recorder,
                speaker=self._speaker,
                **kwargs,
            )
            print(f"[OK] {self._steps[uid]}")

        self._states = dict()
        self._state_uids = dict()
        for state in state_enum:
            if state in state_steps:
                self._states[state] = state_subclass.create(
                    state=state, workflow=self, step=self._steps[state_steps[state]]
                )
            else:
                self._states[state] = state_subclass.create(state=state, workflow=self)

            self._state_uids[self._states[state]] = state

        self._start_state = self._states[start_state]
        self._start_state_kwargs = (
            start_state_kwargs if start_state_kwargs is not None else dict()
        )

    def run(self) -> None:
        current_state = self._start_state
        current_state_kwargs = self._start_state_kwargs

        while current_state is not None:
            transition = current_state.run(**current_state_kwargs)
            current_state = (
                self._states[transition.next_state]
                if transition.next_state is not None
                else None
            )
            current_state_kwargs = (
                transition.next_state_kwargs
                if transition.next_state_kwargs is not None
                else dict()
            )

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


PRONUNCIATION_MAP = {
    "Buddig": "bud dig",
    "Kerrygold": "Kerry gold",
    "Marketside": "Market side",
    "Kool-Aid": "cool aid",
    "Rockstar": "Rock star",
    "Fleischmann's": "Flesh men's",
    "Krusteaz": "Crust tea's",
    "Pillsbury": "Pills bury",
    "Gardein": "Guard dean",
    "Hillshire Farm": "Hill shire Farm",
    "Gudu": "Goo do",
    "Tostitos": "Toast eat toes",
    "Bridgford": "Bridge ford",
    "SkinnyPop": "Skinny Pop",
    "Land O'Lakes": "Land Oh Lakes",
    "Coffeemate": "Coffee mate",
    "Yoplait": "Yo plate",
    "Wish-Bone": "Wish Bone",
    "Daiya": "Die yeah",
    "Steak-umm": "Steak umm",
    "DiGiorno": "Di Giorno",
    "Litehouse": "Lighthouse",
}

for product in PRODUCT_DB:
    product["lookup_name"] = "".join(
        [
            ch
            for ch in product["product_name"]
            .replace("&", "and")
            .replace("100%", "one hundred percent")
            .replace("-", " ")
            .replace("4:9", "four nine")
            .replace("4", "four")
            .replace("1", "one")
            .replace("Buttermints", "Butter mints")
            .replace("YoBaby", "Yo Baby")
            .replace("SNOBALLS", "Snow balls")
            .replace("Krunch", "crunch")
            if ch not in ["!", ","]
        ]
    )

    if product["brand"] in PRONUNCIATION_MAP:
        product["lookup_brand"] = PRONUNCIATION_MAP[product["brand"]]
    else:
        product["lookup_brand"] = "".join(
            [ch for ch in product["brand"] if ch not in ["!", "7", "."]]
        )


SHIFT_STATUS_LIST = ["on duty", "on break", "off duty"]


LOCATION_LIST = [
    "Produce",
    "Dairy",
    "Frozen",
    "Bakery",
    "Deli",
    "Meat",
    "Electronics",
    "Pharmacy",
    "Apparel",
    "Home and Furniture",
    "Lawn and Garden",
    "Sports and Outdoors",
    "Health and Beauty",
    "Auto Care",
    "Grocery Pickup",
    "Self Checkout",
    "Customer Service",
    "the front",
]


COWORKER_LIST = [
    "Anya",
    "Chen",
    "Diego",
    "Elena",
    "Jose",
    "Lee",
    "Mohamad",
    "Pablo",
    "Patel",
    "Pepe",
    "Priya",
    "Singh",
    "Tomas",
    "Wei",
    "Wong",
    "Zhang",
    "Jane",
    "Mary",
    "James",
    "John",
    "Ali",
    "Michael",
    "Diana",
]


COWORKER_DATA = {}


for coworker in COWORKER_LIST:
    COWORKER_DATA[coworker] = {
        "location": random.choice(LOCATION_LIST),
        "shift_status": random.choice(SHIFT_STATUS_LIST),
    }

    if COWORKER_DATA[coworker]["shift_status"] == "off duty":
        COWORKER_DATA[coworker]["location"] = ""
    elif COWORKER_DATA[coworker]["shift_status"] == "on break":
        COWORKER_DATA[coworker]["location"] = "the back room"


TASK_LIST = [
    f"Restock {item['brand']} {item['product_name']} in aisle {item['aisle']}."
    for item in PRODUCT_DB[: len(COWORKER_LIST)]
] + [
    f"Check if {name} needs help in {data['location']}."
    for name, data in COWORKER_DATA.items() if data["shift_status"] == "on duty"
]
random.shuffle(TASK_LIST)


def get_products(db: Dict, product_name: str, brand: Optional[str]) -> List[Dict]:
    target_rows = []

    for row in db:
        if row["lookup_name"] == product_name:
            if brand is None or (row["lookup_brand"] == brand):
                target_rows.append(row)

    return target_rows


def get_brand_product_buckets(target_rows: List[Dict]) -> Dict[str, List]:
    brand_product_buckets = {}
    for row in target_rows:
        ident = f"{row['brand']} {row['product_name']}"
        if ident not in brand_product_buckets:
            brand_product_buckets[ident] = []

        brand_product_buckets[ident].append(row)

    return brand_product_buckets


def list_to_spoken(items: List[str]) -> str:
    result = ""
    if len(items) == 1:
        return items[0] + "."
    elif len(items) == 2:
        return f"{items[0]} and {items[1]}."

    for i, item in enumerate(items):
        result += item

        if i == len(items) - 2:
            result += ", and "
        elif i != len(items) - 1:
            result += ", "

    return result + "."


class RecipeSteps(Enum):
    STANDBY = "Standby"
    PROMPT_USER = "PromptUser"
    RECORD_USER = "RecordUser"


class RecipeStates(Enum):
    STANDBY = "Standby"
    WELCOME_PROMPT = "WelcomePrompt"
    LISTEN_COMMAND = "ListenCommand"
    SPEAK_PROMPT = "SpeakPrompt"
    SHIFT_OVER = "ShiftOver"


class RecipeState(State):
    @classmethod
    def create(
        cls, state: RecipeStates, workflow: Workflow, **kwargs: Any
    ) -> "RecipeState":
        children = {
            RecipeStates.STANDBY: RecipeStandbyState,
            RecipeStates.WELCOME_PROMPT: RecipeWelcomePromptState,
            RecipeStates.LISTEN_COMMAND: RecipeListenCommandState,
            RecipeStates.SPEAK_PROMPT: RecipeSpeakPromptState,
            RecipeStates.SHIFT_OVER: RecipeShiftOverPromptState,
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

    def run(self, **kwargs: Any) -> Transition:
        text = "Listening for wake word"

        def get_text() -> str:
            return text

        event, thread = print_async(get_text=get_text)
        self._step.run()
        text = "Detected wake word. Starting..."

        sleep(0.1)
        event.set()
        thread.join()

        return Transition(
            next_state=RecipeStates.WELCOME_PROMPT,
            next_state_kwargs={"next_task_index": 0},
        )


class RecipeWelcomePromptState(RecipePromptState):
    def run(self, next_task_index: int, **kwargs: Any) -> Transition:
        self._run_prompt("Walmart retail associate activated.")

        print(
            "- Where is (product)\n"
            "- Are we out of (product)\n"
            "- Price check on (product)\n"
            "- Where is (coworker)\n"
            "- Ask (coworker) to come to (location)\n"
            "- Call for help at (location)\n"
            "- Get next task\n"
            "- [start shift, on break, end shift]"
        )

        return Transition(
            next_state=RecipeStates.LISTEN_COMMAND,
            next_state_kwargs={"next_task_index": next_task_index},
        )


class RecipeListenCommandState(State):
    def __init__(self, step: RhinoStep) -> None:
        super().__init__(step=step)

    def run(self, next_task_index: int, **kwargs) -> Transition:
        text = "Listening for command"

        def get_text() -> str:
            return text

        event, thread = print_async(get_text=get_text)

        while True:
            inference = self._step.run()
            intent = (
                inference["intent"]
                if inference and inference["is_understood"]
                else None
            )

            if intent == "findProduct":
                event.set()
                thread.join()

                brand = inference["slots"].get("brand", None)
                product = inference["slots"].get("product", None)
                assert product is not None

                products = get_products(PRODUCT_DB, product, brand)
                brand_product_buckets = get_brand_product_buckets(products)

                prompt_list = []
                for ident, bucket in brand_product_buckets.items():
                    prompt = f"{ident} is in "

                    def plural(r):
                        return "" if r["stock"] == 1 else "s"

                    prompt += list_to_spoken(
                        [
                            f"{row['department']}, aisle {row['aisle']}. "
                            f"{row['stock']} item{plural(row)} left (at {row['size']})"
                            for row in bucket
                        ]
                    )
                    prompt_list.append(prompt)

                if len(brand_product_buckets) == 0:
                    continue

                return Transition(
                    next_state=RecipeStates.SPEAK_PROMPT,
                    next_state_kwargs={
                        "next_task_index": next_task_index,
                        "prompt_list": prompt_list,
                        "next_state": RecipeStates.LISTEN_COMMAND,
                    },
                )

            elif intent == "checkStock":
                event.set()
                thread.join()

                brand = inference["slots"].get("brand", None)
                product = inference["slots"].get("product", None)
                assert product is not None

                products = get_products(PRODUCT_DB, product, brand)

                if len(products) == 1:
                    prompt = (
                        f"We have {products[0]['stock']} units of "
                        f"{products[0]['brand']} {products[0]['product_name']}. "
                        f"(Only in {products[0]['size']})"
                    )
                else:
                    prompt = (
                        f"We have {sum(row['stock'] for row in products)} total units of "
                        f"{products[0]['product_name']}. "
                    )
                    prompt += list_to_spoken(
                        [f"{row['stock']} items (at {row['size']})" for row in products]
                    )

                return Transition(
                    next_state=RecipeStates.SPEAK_PROMPT,
                    next_state_kwargs={
                        "next_task_index": next_task_index,
                        "prompt": prompt,
                        "next_state": RecipeStates.LISTEN_COMMAND,
                    },
                )

            elif intent == "checkPrice":
                event.set()
                thread.join()

                brand = inference["slots"].get("brand", None)
                product = inference["slots"].get("product")
                assert product is not None

                products = get_products(PRODUCT_DB, product, brand)
                brand_product_buckets = get_brand_product_buckets(products)

                prompt_list = []
                for ident, bucket in brand_product_buckets.items():
                    prompt = f"{ident} costs "
                    prompt += list_to_spoken(
                        [f"${row['price']} (at {row['size']})" for row in bucket]
                    )
                    prompt_list.append(prompt)

                return Transition(
                    next_state=RecipeStates.SPEAK_PROMPT,
                    next_state_kwargs={
                        "next_task_index": next_task_index,
                        "prompt_list": prompt_list,
                        "next_state": RecipeStates.LISTEN_COMMAND,
                    },
                )

            elif intent == "findAssociate":
                event.set()
                thread.join()

                coworker = inference["slots"].get("coworker")
                assert coworker is not None

                data = COWORKER_DATA[coworker]
                location, shift_status = data["location"], data["shift_status"]

                if shift_status == "off duty":
                    prompt = f"{coworker} is off duty."
                elif shift_status == "on break":
                    prompt = f"{coworker} is in {location}, on break."
                else:
                    prompt = f"{coworker} is in {location}."

                return Transition(
                    next_state=RecipeStates.SPEAK_PROMPT,
                    next_state_kwargs={
                        "next_task_index": next_task_index,
                        "prompt": prompt,
                        "next_state": RecipeStates.LISTEN_COMMAND,
                    },
                )

            elif intent == "messageAssociate":
                event.set()
                thread.join()

                coworker = inference["slots"].get("coworker")
                to_location = inference["slots"].get("location", None)
                to_aisle_number = inference["slots"].get("aisleNumber", None)
                to_register_number = inference["slots"].get("registerNumber", None)
                bring_brand = inference["slots"].get("brand", None)
                bring_product = inference["slots"].get("product", None)
                assert coworker is not None

                to_string = ""
                if to_location is not None:
                    to_string = f"{to_location}"
                elif to_aisle_number is not None:
                    to_string = f"aisle {to_aisle_number}"
                elif to_register_number is not None:
                    to_string = f"register {to_register_number}"
                else:
                    continue

                if bring_brand is not None:
                    assert bring_product is not None
                    initial_prompt = f"Requesting {coworker} to bring {bring_brand} {bring_product} to {to_string}."
                elif bring_product is not None:
                    initial_prompt = f"Requesting {coworker} to bring any {bring_product} to {to_string}."
                else:
                    initial_prompt = f"Requesting {coworker} to come to {to_string}."

                prompt_list = [initial_prompt, "Message sent."]

                return Transition(
                    next_state=RecipeStates.SPEAK_PROMPT,
                    next_state_kwargs={
                        "next_task_index": next_task_index,
                        "prompt_list": prompt_list,
                        "next_state": RecipeStates.LISTEN_COMMAND,
                    },
                )

            elif intent == "callForHelp":
                event.set()
                thread.join()

                to_location = inference["slots"].get("location", None)
                to_aisle_number = inference["slots"].get("aisleNumber", None)
                to_register_number = inference["slots"].get("registerNumber", None)

                if to_location is not None:
                    prompt = f"Requesting for help in {to_location}."
                elif to_aisle_number is not None:
                    prompt = f"Requesting for help in aisle {to_aisle_number}."
                elif to_register_number is not None:
                    prompt = f"Requesting for help at register {to_register_number}."
                else:
                    continue

                return Transition(
                    next_state=RecipeStates.SPEAK_PROMPT,
                    next_state_kwargs={
                        "next_task_index": next_task_index,
                        "prompt": prompt,
                        "next_state": RecipeStates.LISTEN_COMMAND,
                    },
                )

            elif intent == "getNextTask":
                event.set()
                thread.join()

                if next_task_index > len(TASK_LIST):
                    prompt = "You have no tasks left."
                else:
                    prompt = "Starting next task: " + TASK_LIST[next_task_index]

                return Transition(
                    next_state=RecipeStates.SPEAK_PROMPT,
                    next_state_kwargs={
                        "next_task_index": next_task_index + 1,
                        "prompt": prompt,
                        "next_state": RecipeStates.LISTEN_COMMAND,
                    },
                )

            elif intent == "startShift":
                event.set()
                thread.join()

                return Transition(
                    next_state=RecipeStates.SPEAK_PROMPT,
                    next_state_kwargs={
                        "next_task_index": next_task_index,
                        "prompt": 'Status updated to "on shift".',
                        "next_state": RecipeStates.LISTEN_COMMAND,
                    },
                )

            elif intent == "onBreak":
                event.set()
                thread.join()

                return Transition(
                    next_state=RecipeStates.SPEAK_PROMPT,
                    next_state_kwargs={
                        "next_task_index": next_task_index,
                        "prompt": 'Status updated to "on break".',
                        "next_state": RecipeStates.LISTEN_COMMAND,
                    },
                )

            elif intent == "endShift":
                event.set()
                thread.join()

                return Transition(
                    next_state=RecipeStates.SPEAK_PROMPT,
                    next_state_kwargs={
                        "next_task_index": next_task_index,
                        "prompt": 'Status updated to "completed shift".',
                        "next_state": RecipeStates.SHIFT_OVER,
                    },
                )


class RecipeSpeakPromptState(RecipePromptState):
    def run(
        self,
        next_task_index: int,
        next_state: RecipeStates,
        prompt: str | None = None,
        prompt_list: str | None = None,
        next_args: Dict = {},
        **kwargs: Any,
    ) -> Transition:
        if prompt is not None:
            self._run_prompt(prompt)

        if prompt_list is not None:
            for prompt in prompt_list:
                self._run_prompt(prompt)

        return Transition(
            next_state=next_state,
            next_state_kwargs={"next_task_index": next_task_index, **next_args},
        )


class RecipeShiftOverPromptState(RecipePromptState):
    def run(self, **kwargs: Any) -> Transition:
        self._run_prompt("Assistant powering off.")

        return Transition(next_state=None)


def main() -> None:
    parser = ArgumentParser()
    parser.add_argument(
        "--access_key",
        required=True,
        help="AccessKey obtained from Picovoice Console (https://console.picovoice.ai/)",
    )
    parser.add_argument(
        "--keyword_path",
        required=True,
        help="Absolute path to a Porcupine wake word file (.ppn)",
    )
    parser.add_argument(
        "--context_path",
        required=True,
        help="Absolute path to a Rhino Speech-to-Intent context file (.rhn)",
    )
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
            RecipeStates.SPEAK_PROMPT: RecipeSteps.PROMPT_USER,
            RecipeStates.SHIFT_OVER: RecipeSteps.PROMPT_USER,
        },
        start_state=RecipeStates.STANDBY,
        start_state_kwargs={},
        access_key=access_key,
    )

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
