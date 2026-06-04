import sys
import shutil
import string
import random
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


# Data adapted from https://www.kaggle.com/datasets/polartech/walmart-grocery-product-dataset
PRODUCT_DB = [
    {'department': 'Deli', 'product_name': 'Original Sliced Honey Ham', 'size': '2 Oz.', 'brand': 'Buddig', 'price': 0.8, 'aisle': 5, 'stock': 7},
    {'department': 'Deli', 'product_name': 'Colby Jack Cheese', 'size': 'unknown', 'brand': 'Prima Della', 'price': 8.64, 'aisle': 2, 'stock': 12},
    {'department': 'Deli', 'product_name': 'Corned Beef', 'size': 'unknown', 'brand': 'Prima Della', 'price': 11.87, 'aisle': 11, 'stock': 5},
    {'department': 'Deli', 'product_name': 'Swiss Cheese', 'size': 'unknown', 'brand': 'Kerrygold', 'price': 5.28, 'aisle': 5, 'stock': 9},
    {'department': 'Deli', 'product_name': 'Deli Sliced Garlic Bologna', 'size': 'unknown', 'brand': 'Eckrich', 'price': 5.12, 'aisle': 6, 'stock': 14},
    {'department': 'Deli', 'product_name': 'Sweet Kale Chopped Salad Kit', 'size': '12 oz', 'brand': 'Taylor Farms', 'price': 3.98, 'aisle': 11, 'stock': 8},
    {'department': 'Deli', 'product_name': 'Nashville Inspired Hot Chicken Pizza', 'size': '25.8 oz (Fresh)', 'brand': 'Marketside', 'price': 7.0, 'aisle': 4, 'stock': 6},
    {'department': 'Deli', 'product_name': 'Hottie Bites', 'size': '3.25 FL oz', 'brand': 'OH SNAP!', 'price': 1.47, 'aisle': 10, 'stock': 22},
    {'department': 'Deli', 'product_name': 'Beef Bologna Deli Lunch Meat', 'size': '16 oz Package', 'brand': 'Oscar Mayer', 'price': 6.84, 'aisle': 14, 'stock': 11},
    {'department': 'Beverages', 'product_name': 'Organic Honey Lemon Daily Wellness Tea Bags', 'size': '12 Ct', 'brand': 'Celestial Seasonings', 'price': 3.44, 'aisle': 2, 'stock': 17},
    {'department': 'Beverages', 'product_name': 'Punched Watermelon Energy Drink', 'size': '16 oz', 'brand': 'Rockstar', 'price': 1.88, 'aisle': 9, 'stock': 30},
    {'department': 'Beverages', 'product_name': '100% Carrot Vegetable Juice', 'size': '11 oz', 'brand': 'Bolthouse Farms', 'price': 2.18, 'aisle': 8, 'stock': 13},
    {'department': 'Beverages', 'product_name': 'Jammers Cherry Artificially Flavored Drink', 'size': '10 ct Box, 6 oz Pouches', 'brand': 'Kool-Aid', 'price': 2.5, 'aisle': 5, 'stock': 16},
    {'department': 'Beverages', 'product_name': '100% Arabica Colombian Medium Dark Ground Coffee', 'size': '24.2 oz', 'brand': 'Great Value', 'price': 9.76, 'aisle': 13, 'stock': 10},
    {'department': 'Beverages', 'product_name': 'Energy Drink', 'size': '8.4 Fl Oz (4 pack)', 'brand': 'Red Bull', 'price': 7.24, 'aisle': 3, 'stock': 28},
    {'department': 'Beverages', 'product_name': 'Energy Drink', 'size': '16 oz', 'brand': 'Rockstar', 'price': 1.88, 'aisle': 6, 'stock': 25},
    {'department': 'Beverages', 'product_name': '100% Pomegranate Juice', 'size': '48 Fl Oz', 'brand': 'Pom Wonderful', 'price': 9.48, 'aisle': 15, 'stock': 7},
    {'department': 'Beverages', 'product_name': '100% Apple Juice', 'size': '64 Oz.', 'brand': 'Great Value', 'price': 1.62, 'aisle': 4, 'stock': 20},
    {'department': 'Beverages', 'product_name': '100% Apple Juice', 'size': '42 Oz.', 'brand': 'Great Value', 'price': 1.33, 'aisle': 4, 'stock': 3},
    {'department': 'Beverages', 'product_name': '100% Apple Juice', 'size': '36 Oz.', 'brand': 'Great Value', 'price': 0.94, 'aisle': 4, 'stock': 26},
    {'department': 'Baking', 'product_name': 'Aqua Blue Vanilla Frosting', 'size': '15.6 Oz Tub', 'brand': 'Pillsbury', 'price': 1.74, 'aisle': 16, 'stock': 15},
    {'department': 'Baking', 'product_name': 'Organic Unbleached White All-Purpose Flour', 'size': '5 lbs', 'brand': "Bob's Red Mill", 'price': 7.58, 'aisle': 12, 'stock': 9},
    {'department': 'Baking', 'product_name': 'Active Dry Yeast', 'size': '0.75 Oz, 3 Pack', 'brand': "Fleischmann's", 'price': 1.72, 'aisle': 2, 'stock': 23},
    {'department': 'Baking', 'product_name': 'Frozen Coconut Flakes', 'size': '6 Oz', 'brand': 'Birdseye', 'price': 1.62, 'aisle': 8, 'stock': 11},
    {'department': 'Baking', 'product_name': '100% Extra Virgin Olive Oil', 'size': '8.5 oz', 'brand': 'Iberia', 'price': 3.26, 'aisle': 16, 'stock': 18},
    {'department': 'Baking', 'product_name': 'Chocolate Chunk Muffin Mix', 'size': '18.25 OZ Box', 'brand': 'Krusteaz', 'price': 2.97, 'aisle': 6, 'stock': 14},
    {'department': 'Baking', 'product_name': 'Organic Coconut Flour', 'size': '36 oz', 'brand': 'Great Value Organic', 'price': 7.83, 'aisle': 13, 'stock': 8},
    {'department': 'Baking', 'product_name': 'Easy Pumpkin Pie Mix', 'size': '30 oz', 'brand': "Libby's", 'price': 3.28, 'aisle': 10, 'stock': 6},
    {'department': 'Baking', 'product_name': 'Vanilla Frosting', 'size': '15.6 Oz Tub', 'brand': 'Pillsbury', 'price': 1.74, 'aisle': 15, 'stock': 15},
    {'department': 'Baking', 'product_name': 'Gluten Free Chocolate Frosting', 'size': '16 oz', 'brand': 'Betty Crocker', 'price': 1.58, 'aisle': 9, 'stock': 12},
    {'department': 'Meat & Seafood', 'product_name': 'Black Bean Burger Patties Plant Based', 'size': '1/4 lb 2Ct', 'brand': 'Gardein', 'price': 3.84, 'aisle': 2, 'stock': 10},
    {'department': 'Meat & Seafood', 'product_name': 'Peeled Tail on Extra Large Shrimp', 'size': '12 Oz ', 'brand': 'Walmart Seafood', 'price': 5.84, 'aisle': 8, 'stock': 7},
    {'department': 'Meat & Seafood', 'product_name': 'All Natural Fresh Bone In Chicken Thighs', 'size': '4.75 - 6.0 lb', 'brand': 'Tyson', 'price': 9.89, 'aisle': 4, 'stock': 16},
    {'department': 'Meat & Seafood', 'product_name': 'Cracked Pepper Turkey Breast', 'size': 'unknown', 'brand': 'Prima Della', 'price': 5.44, 'aisle': 14, 'stock': 5},
    {'department': 'Meat & Seafood', 'product_name': 'Meatloaf with Tomato Sauce Refrigerated Entre', 'size': '15 oz', 'brand': 'Hormel', 'price': 6.97, 'aisle': 10, 'stock': 9},
    {'department': 'Meat & Seafood', 'product_name': 'Country Style Ribs', 'size': '1.25 - 2.99 lb', 'brand': 'Fresh Beef', 'price': 12.2, 'aisle': 16, 'stock': 4},
    {'department': 'Meat & Seafood', 'product_name': 'Beef Smoked Sausage Links', 'size': '13.5 oz, 6 Ct', 'brand': 'Hillshire Farm', 'price': 2.98, 'aisle': 14, 'stock': 13},
    {'department': 'Meat & Seafood', 'product_name': 'Italian Garlic & Fennel Plant-Based Sausage', 'size': 'unknown', 'brand': 'Field Roast', 'price': 5.33, 'aisle': 13, 'stock': 8},
    {'department': 'Meat & Seafood', 'product_name': 'Hot Italian Pork Sausage', 'size': '19 oz., 1.19lb. (Fresh)', 'brand': 'Premio Foods', 'price': 4.78, 'aisle': 1, 'stock': 11},
    {'department': 'Candy', 'product_name': 'Mexican Chili Lollipops', 'size': '15.8 oz Bag', 'brand': 'Gudu', 'price': 2.97, 'aisle': 2, 'stock': 17},
    {'department': 'Candy', 'product_name': 'Polar Ice Sugar Free Chewing Gum', 'size': '15 ct (3 Pack)', 'brand': 'Extra', 'price': 3.48, 'aisle': 5, 'stock': 24},
    {'department': 'Candy', 'product_name': 'Milk Chocolate with Almonds Bar', 'size': '1.45 Oz', 'brand': "Hershey's", 'price': 1.14, 'aisle': 11, 'stock': 35},
    {'department': 'Candy', 'product_name': 'Twist 4 Assorted Flavors Gum', 'size': '16 Oz', 'brand': 'Dubble Bubble', 'price': 2.68, 'aisle': 2, 'stock': 19},
    {'department': 'Candy', 'product_name': 'Pink Buttermints', 'size': '2.75 lbs', 'brand': 'Party Sweets', 'price': 10.14, 'aisle': 12, 'stock': 6},
    {'department': 'Candy', 'product_name': 'Halloween Candy Variety Pack', 'size': '170 Ct Bag, 62.02oz', 'brand': 'Mixed', 'price': 16.98, 'aisle': 4, 'stock': 22},
    {'department': 'Candy', 'product_name': 'Chewy Candy Sticks', 'size': '7.78 oz 21ct', 'brand': 'Starburst', 'price': 3.78, 'aisle': 16, 'stock': 18},
    {'department': 'Snacks', 'product_name': 'Organic Sesame Street Peanut Butter Baked Corn Puffs', 'size': '2.5 oz Bag', 'brand': "Earth's Best", 'price': 3.63, 'aisle': 11, 'stock': 13},
    {'department': 'Snacks', 'product_name': 'Potato Crisps Chips, Variety Pack', 'size': '16 Ct, 22 Oz, Pack', 'brand': 'Pringles', 'price': 13.16, 'aisle': 8, 'stock': 9},
    {'department': 'Snacks', 'product_name': 'Chunky Habanero Salsa', 'size': '15.5 oz Jar', 'brand': 'Tostitos', 'price': 5.38, 'aisle': 4, 'stock': 16},
    {'department': 'Snacks', 'product_name': 'Caramel Almond & Sea Salt', 'size': '1.4 Oz, 12 Count', 'brand': 'KIND Bars', 'price': 15.46, 'aisle': 4, 'stock': 7},
    {'department': 'Snacks', 'product_name': 'Gluten-Free Sea Salt Microwave Popcorn', 'size': '2.8 oz, 6 Count', 'brand': 'SkinnyPop', 'price': 4.84, 'aisle': 11, 'stock': 18},
    {'department': 'Snacks', 'product_name': 'Beef Jerky, Hickory Smoked', 'size': '2.85oz', 'brand': "Jack Link's", 'price': 4.98, 'aisle': 3, 'stock': 11},
    {'department': 'Snacks', 'product_name': 'Glacier Ranch Tortilla Chips', 'size': '9.75 oz', 'brand': 'Great Value', 'price': 1.74, 'aisle': 2, 'stock': 27},
    {'department': 'Dairy & Eggs', 'product_name': 'Skyr, Coconut', 'size': '5.3 Ounce', 'brand': 'Icelandic Provisions', 'price': 1.48, 'aisle': 1, 'stock': 14},
    {'department': 'Dairy & Eggs', 'product_name': 'Strawberry Cheesecake Snacks', 'size': '2 ct Pack, 3.25 oz Cups', 'brand': 'Philadelphia', 'price': 2.98, 'aisle': 8, 'stock': 0},
    {'department': 'Dairy & Eggs', 'product_name': 'Light Spread', 'size': '15 oz', 'brand': "I Can't Believe It's Not Butter!", 'price': 3.98, 'aisle': 4, 'stock': 9},
    {'department': 'Dairy & Eggs', 'product_name': 'Butter with Olive Oil and Sea Salt', 'size': '7 oz', 'brand': "Land O'Lakes", 'price': 2.37, 'aisle': 16, 'stock': 18},
    {'department': 'Dairy & Eggs', 'product_name': 'Liquid Hazelnut Sugar Free Coffee Creamer', 'size': '32 oz', 'brand': 'Coffeemate', 'price': 3.98, 'aisle': 2, 'stock': 11},
    {'department': 'Dairy & Eggs', 'product_name': 'Organic YoBaby Apple & Blueberry Baby Yogurt with Probiotics', 'size': '6-4 oz. Cups', 'brand': 'Stonyfield Farm', 'price': 4.33, 'aisle': 16, 'stock': 7},
    {'department': 'Bakery & Bread', 'product_name': 'Small Fajita Flour Tortillas', 'size': '22.5 oz', 'brand': 'Great Value', 'price': 1.98, 'aisle': 15, 'stock': 22},
    {'department': 'Bakery & Bread', 'product_name': 'Gluten Free Bread', 'size': '18 oz Loaf', 'brand': 'Canyon Bakehouse', 'price': 6.18, 'aisle': 8, 'stock': 6},
    {'department': 'Bakery & Bread', 'product_name': 'Plain Cake Donuts', 'size': '2 Count', 'brand': 'Freshness Guaranteed', 'price': 1.46, 'aisle': 2, 'stock': 18},
    {'department': 'Bakery & Bread', 'product_name': 'Pecan Tiny Pies', 'size': '16.2 oz, 16 Count', 'brand': 'Freshness Guaranteed', 'price': 5.94, 'aisle': 2, 'stock': 10},
    {'department': 'Bakery & Bread', 'product_name': 'Whole Grains Healthy Multi-Grain Bread', 'size': '24 oz', 'brand': 'Arnold', 'price': 3.97, 'aisle': 4, 'stock': 14},
    {'department': 'Bakery & Bread', 'product_name': 'Sesame French Bread', 'size': '14 oz', 'brand': 'Freshness Guaranteed', 'price': 1.78, 'aisle': 16, 'stock': 20},
    {'department': 'Bakery & Bread', 'product_name': 'SNOBALLS Single Serve', 'size': '2 count, 3.5 oz', 'brand': 'Hostess', 'price': 1.58, 'aisle': 4, 'stock': 26},
    {'department': 'Bakery & Bread', 'product_name': 'Vanilla Strawberry Ice Cream Krunch Cake', 'size': '40 oz', 'brand': "Friendly's", 'price': 14.98, 'aisle': 2, 'stock': 5},
    {'department': 'Bakery & Bread', 'product_name': 'Assorted Sweet Rolls', 'size': '24 oz, 6 Rolls', 'brand': 'Freshness Guaranteed', 'price': 3.98, 'aisle': 14, 'stock': 15},
    {'department': 'Bakery & Bread', 'product_name': 'Texas Toast Bread', 'size': '22 oz.', 'brand': "Aunt Millie's", 'price': 2.14, 'aisle': 10, 'stock': 17},
    {'department': 'Breakfast & Cereal', 'product_name': 'Green Tea, Lemon Ginseng', 'size': 'unknown', 'brand': 'Lipton', 'price': 2.78, 'aisle': 5, 'stock': 19},
    {'department': 'Breakfast & Cereal', 'product_name': 'Queso Fresco Fresh Crumbling Cheese', 'size': '24 Oz', 'brand': 'La Morenita', 'price': 6.22, 'aisle': 8, 'stock': 8},
    {'department': 'Breakfast & Cereal', 'product_name': 'Old Fashioned Oatmeal, Whole Grain', 'size': '42 oz', 'brand': 'Quaker', 'price': 5.68, 'aisle': 1, 'stock': 13},
    {'department': 'Breakfast & Cereal', 'product_name': 'Instant Oatmeal, Variety', 'size': '12.1 Oz, 12 Count', 'brand': 'Quaker', 'price': 3.23, 'aisle': 8, 'stock': 16},
    {'department': 'Breakfast & Cereal', 'product_name': 'Mini Croissants, Chocolate, Non-GMO', 'size': '2.29oz (Pack of 4 Pouches)', 'brand': '7DAYS', 'price': 3.68, 'aisle': 1, 'stock': 21},
    {'department': 'Breakfast & Cereal', 'product_name': 'Original Cream Cheese Spread', 'size': '12 oz Tub', 'brand': 'Philadelphia', 'price': 5.78, 'aisle': 6, 'stock': 11},
    {'department': 'Breakfast & Cereal', 'product_name': 'Go-Gurt, Low Fat Yogurt, Berry & Strawberry Variety Pack', 'size': '32 oz', 'brand': 'Yoplait', 'price': 4.97, 'aisle': 6, 'stock': 14},
    {'department': 'Breakfast & Cereal', 'product_name': '100% Orange Juice', 'size': '64 oz', 'brand': 'Great Value', 'price': 2.98, 'aisle': 13, 'stock': 23},
    {'department': 'Breakfast & Cereal', 'product_name': 'Organic Whipped Buttery Spread', 'size': '13 oz.', 'brand': 'Earth Balance', 'price': 5.73, 'aisle': 2, 'stock': 9},
    {'department': 'Breakfast & Cereal', 'product_name': 'Croissant Sandwiches Sausage Egg and Cheese', 'size': '4 Count', 'brand': 'Great Value', 'price': 5.78, 'aisle': 3, 'stock': 0},
    {'department': 'Pantry', 'product_name': 'Zesty Robusto Italian Dressing', 'size': '15 oz', 'brand': 'Wish-Bone', 'price': 2.58, 'aisle': 9, 'stock': 15},
    {'department': 'Pantry', 'product_name': 'Sunny Cranberry Pepitas Salad Topping', 'size': '5.5oz', 'brand': 'Salad Pizazz!', 'price': 2.98, 'aisle': 3, 'stock': 10},
    {'department': 'Pantry', 'product_name': 'Chipotle Peppers in Adobo Sauce', 'size': '7.0 oz', 'brand': 'GOYA', 'price': 2.18, 'aisle': 8, 'stock': 18},
    {'department': 'Pantry', 'product_name': 'Real Bacon Pieces', 'size': '9 oz', 'brand': 'Great Value', 'price': 5.72, 'aisle': 15, 'stock': 12},
    {'department': 'Pantry', 'product_name': 'Peach Halves', 'size': '29 oz Can', 'brand': 'Great Value', 'price': 2.44, 'aisle': 10, 'stock': 16},
    {'department': 'Pantry', 'product_name': 'Mango Habanero', 'size': 'unknown', 'brand': 'Buffalo Wild Wings', 'price': 3.58, 'aisle': 5, 'stock': 11},
    {'department': 'Pantry', 'product_name': 'Organic Creamy California Raw Almond Butter', 'size': '12 oz', 'brand': 'MaraNatha', 'price': 16.84, 'aisle': 4, 'stock': 5},
    {'department': 'Pantry', 'product_name': 'Southwest Style Salad with Chicken', 'size': 'unknown', 'brand': 'Marketside', 'price': 4.47, 'aisle': 2, 'stock': 8},
    {'department': 'Pantry', 'product_name': 'Mediterranean Pitted Olive Mix', 'size': 'unknown', 'brand': 'Asaro', 'price': 5.99, 'aisle': 9, 'stock': 7},
    {'department': 'Coffee', 'product_name': 'Vanilla Flavored Coffee, Ground Coffee', 'size': '11 oz', 'brand': 'Starbucks', 'price': 8.98, 'aisle': 14, 'stock': 13},
    {'department': 'Coffee', 'product_name': 'Baking Blend, Sugar Substitute', 'size': '12 oz. Bag', 'brand': 'Whole Earth Sweetener', 'price': 7.68, 'aisle': 15, 'stock': 8},
    {'department': 'Coffee', 'product_name': 'Organic Powdered Raw Cane Sugar', 'size': '16 oz Pouch', 'brand': 'Florida Crystals', 'price': 3.12, 'aisle': 14, 'stock': 20},
    {'department': 'Coffee', 'product_name': 'Cold Cup with Cold Brew Birthday Everyday Gift', 'size': 'unknown', 'brand': 'Starbucks', 'price': 13.98, 'aisle': 14, 'stock': 4},
    {'department': 'Coffee', 'product_name': 'French Vanilla Instant Cappuccino Coffee Drink Mix', 'size': '16 Oz Canister', 'brand': 'Hills Bros. Coffee', 'price': 3.72, 'aisle': 13, 'stock': 0},
    {'department': 'Coffee', 'product_name': 'Caramel Macchiato Iced Coffee', 'size': '64 Oz.', 'brand': 'International Delight', 'price': 4.28, 'aisle': 8, 'stock': 14},
    {'department': 'Coffee', 'product_name': 'French Vanilla Coffee Creamer Singles', 'size': '48 Count', 'brand': 'International Delight', 'price': 5.12, 'aisle': 11, 'stock': 19},
    {'department': 'Frozen', 'product_name': 'Dark Sweet Cherries, Frozen, Pitted', 'size': '16 oz', 'brand': 'Great Value', 'price': 3.88, 'aisle': 12, 'stock': 17},
    {'department': 'Frozen', 'product_name': 'Dairy Free Meatless Pepperoni Gluten Free Pizza', 'size': '16.7oz', 'brand': 'Daiya', 'price': 6.94, 'aisle': 13, 'stock': 9},
    {'department': 'Frozen', 'product_name': 'Ezekiel 4:9 Sprouted Whole Grain Bread', 'size': '24oz', 'brand': 'Food for Life', 'price': 6.18, 'aisle': 9, 'stock': 6},
    {'department': 'Frozen', 'product_name': 'Home Menu Chicken Lo Mein Skillet Meal', 'size': '22 OZ', 'brand': "P.F. Chang's", 'price': 7.78, 'aisle': 15, 'stock': 0},
    {'department': 'Frozen', 'product_name': 'Cauliflower Crust Margherita', 'size': 'unknown', 'brand': 'Great Value', 'price': 4.96, 'aisle': 9, 'stock': 10},
    {'department': 'Frozen', 'product_name': '100% Beef Sliced Steaks', 'size': '31.5 oz', 'brand': 'Steak-umm', 'price': 10.56, 'aisle': 1, 'stock': 8},
    {'department': 'Frozen', 'product_name': 'Big Burrito Red Hot Beef', 'size': 'unknown', 'brand': "Tina's", 'price': 1.27, 'aisle': 2, 'stock': 28},
    {'department': 'Frozen', 'product_name': 'Pizza Rolls, Triple Cheese Flavored', 'size': '24.8 oz', 'brand': "Totino's", 'price': 4.98, 'aisle': 11, 'stock': 0},
    {'department': 'Frozen', 'product_name': 'Supreme, Rising Crust Pizza', 'size': '31.4 oz', 'brand': 'DiGiorno', 'price': 6.92, 'aisle': 16, 'stock': 14},
    {'department': 'Fresh Produce', 'product_name': 'Watermelon Chunks', 'size': '42 oz', 'brand': 'Marketside', 'price': 8.97, 'aisle': 7, 'stock': 6},
    {'department': 'Fresh Produce', 'product_name': 'Organic Garlic Sleeve', 'size': '3 Count', 'brand': 'Spice World', 'price': 2.98, 'aisle': 8, 'stock': 15},
    {'department': 'Fresh Produce', 'product_name': 'Napa Cabbage', 'size': 'unknown', 'brand': 'No Name', 'price': 3.97, 'aisle': 4, 'stock': 9},
    {'department': 'Fresh Produce', 'product_name': 'Rose Plus Bouquet', 'size': 'unknown', 'brand': 'No Name', 'price': 9.97, 'aisle': 4, 'stock': 4},
    {'department': 'Fresh Produce', 'product_name': 'Organic Asparagus', 'size': 'unknown', 'brand': 'No Name', 'price': 5.96, 'aisle': 8, 'stock': 7},
    {'department': 'Fresh Produce', 'product_name': 'Chunky Blue Cheese Dressing & Dip', 'size': '13 oz Jar', 'brand': 'Litehouse', 'price': 3.96, 'aisle': 5, 'stock': 11},
    {'department': 'Fresh Produce', 'product_name': 'Minced Garlic', 'size': '32 Oz', 'brand': 'Great Value', 'price': 7.76, 'aisle': 13, 'stock': 19},
    {'department': 'Fresh Produce', 'product_name': 'White Nectarines', 'size': 'unknown', 'brand': 'No Name', 'price': 1.06, 'aisle': 11, 'stock': 8},
    {'department': 'Fresh Produce', 'product_name': 'Fresh Long English Cucumber', 'size': 'unknown', 'brand': 'No Name', 'price': 1.38, 'aisle': 8, 'stock': 12},
    {'department': 'Alcohol', 'product_name': 'Beer', 'size': '30 Pack 12 oz', 'brand': 'Bud Ice', 'price': 19.98, 'aisle': 13, 'stock': 18},
    {'department': 'Alcohol', 'product_name': 'Hard Seltzer Variety Pack 1', 'size': '12 Pack, 12 oz Cans', 'brand': 'Vizzy', 'price': 17.98, 'aisle': 9, 'stock': 14},
    {'department': 'Alcohol', 'product_name': 'Bourbon Barrel Aged Cabernet Sauvignon Red Wine', 'size': '750 mL Bottle', 'brand': 'Robert Mondavi Private Selection', 'price': 11.98, 'aisle': 13, 'stock': 9},
    {'department': 'Alcohol', 'product_name': 'White Wine', 'size': '3 L Box', 'brand': 'Black Box', 'price': 18.98, 'aisle': 8, 'stock': 7},
    {'department': 'Alcohol', 'product_name': 'Cabernet Sauvignon Wine', 'size': '750 mL', 'brand': 'JUSTIN', 'price': 24.48, 'aisle': 14, 'stock': 5},
    {'department': 'Alcohol', 'product_name': 'Beer', 'size': '6 Pack, 16 oz Cans', 'brand': 'Old Style', 'price': 6.73, 'aisle': 14, 'stock': 20},
    {'department': 'Alcohol', 'product_name': 'Petit Petite Wine', 'size': '750 mL', 'brand': 'Michael David Winery', 'price': 13.48, 'aisle': 11, 'stock': 6},
    {'department': 'Alcohol', 'product_name': 'Prosecco Sparkling White Wine', 'size': '375ml', 'brand': 'LA MARCA', 'price': 9.98, 'aisle': 14, 'stock': 11},
    {'department': 'Alcohol', 'product_name': 'Merlot Wine', 'size': '1.5 L', 'brand': 'Barefoot', 'price': 9.98, 'aisle': 4, 'stock': 8},
    {'department': 'Alcohol', 'product_name': 'Light Beer', 'size': '30 Pack, 12 oz Cans', 'brand': 'Keystone', 'price': 17.99, 'aisle': 3, 'stock': 0}
]


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
    product["lookup_name"] = "".join([
        ch for ch
        in product["product_name"]
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
    ])

    if product["brand"] in PRONUNCIATION_MAP:
        product["lookup_brand"] = PRONUNCIATION_MAP[product["brand"]]
    else:
        product["lookup_brand"] = "".join([
            ch for ch
            in product["brand"]
            if ch not in ["!", "7", "."]
        ])


SHIFT_STATUS_LIST = [
    "on duty",
    "on break",
    "off duty"
]


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
    "The front"
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
        "shift_status": random.choice(SHIFT_STATUS_LIST)
    }

    if COWORKER_DATA[coworker]["shift_status"] == "off duty":
        COWORKER_DATA[coworker]["location"] = ""
    elif COWORKER_DATA[coworker]["shift_status"] == "on break":
        COWORKER_DATA[coworker]["location"] = "The back room"


TASK_LIST = [
    f"Restock {item['brand']} {item['product_name']} in aisle {item['aisle']}."
    for item in PRODUCT_DB[:len(COWORKER_LIST)]
] + [
    f"Check if {name} needs help in {data['location']}."
    for name, data in COWORKER_DATA.items()
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
        return items[0]
    elif len(items) == 2:
        return f"{items[0]} and {items[1]}"

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
            cls,
            state: RecipeStates,
            workflow: Workflow,
            **kwargs: Any
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

    def run(
            self,
            **kwargs: Any
    ) -> Transition:
        text = "Listening for wake word"

        def get_text() -> str:
            return text

        event, thread = print_async(get_text=get_text)
        self._step.run()
        text = "Detected wake word. Starting..."

        sleep(.1)
        event.set()
        thread.join()

        return Transition(
            next_state=RecipeStates.WELCOME_PROMPT,
            next_state_kwargs={
                "next_task_index": 0
            })


class RecipeWelcomePromptState(RecipePromptState):
    def run(
            self,
            next_task_index: int,
            **kwargs: Any
    ) -> Transition:
        self._run_prompt("Walmart retail associate activated.")

        return Transition(
            next_state=RecipeStates.LISTEN_COMMAND,
            next_state_kwargs={
                "next_task_index": next_task_index
            })


class RecipeListenCommandState(State):
    def __init__(self, step: RhinoStep) -> None:
        super().__init__(step=step)

    def run(
            self,
            next_task_index: int,
            **kwargs
    ) -> Transition:

        print(
            "- Where is (product)\n"
            "- How many (product) are in stock\n"
            "- Price check on (product)\n"
            "- Where is (coworker)\n"
            "- Ask (coworker) to come to (location)\n"
            "- Call for help at (location)\n"
            "- Get next task\n"
            "- [start shift, on break, end shift]")

        text = ""

        def get_text() -> str:
            return text

        event, thread = print_async(get_text=get_text)

        while True:
            inference = self._step.run()
            intent = inference["intent"] if inference and inference["is_understood"] else None

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
                    prompt = f"{ident} is in: "
                    plural = lambda r: "" if r['stock'] == 1 else "s"
                    prompt += list_to_spoken([
                        f"{row['department']}, aisle {row['aisle']}: {row['stock']} item{plural(row)} ({row['size']})"
                        for row in bucket
                    ])
                    prompt_list.append(prompt)

                if len(brand_product_buckets) == 0:
                    continue

                return Transition(
                    next_state=RecipeStates.SPEAK_PROMPT,
                    next_state_kwargs={
                        "next_task_index": next_task_index,
                        "prompt_list": prompt_list,
                        "next_state": RecipeStates.LISTEN_COMMAND,
                    })

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
                    prompt += list_to_spoken([
                        f"{row['stock']} items ({row['size']})"
                        for row in products
                    ])

                return Transition(
                    next_state=RecipeStates.SPEAK_PROMPT,
                    next_state_kwargs={
                        "next_task_index": next_task_index,
                        "prompt": prompt,
                        "next_state": RecipeStates.LISTEN_COMMAND,
                    })

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
                    prompt += list_to_spoken([
                        f"${row['price']} ({row['size']})"
                        for row in bucket
                    ])
                    prompt_list.append(prompt)

                return Transition(
                    next_state=RecipeStates.SPEAK_PROMPT,
                    next_state_kwargs={
                        "next_task_index": next_task_index,
                        "prompt_list": prompt_list,
                        "next_state": RecipeStates.LISTEN_COMMAND,
                    })

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
                    })

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

                prompt_list = [
                    initial_prompt, "Message sent."
                ]

                return Transition(
                    next_state=RecipeStates.SPEAK_PROMPT,
                    next_state_kwargs={
                        "next_task_index": next_task_index,
                        "prompt_list": prompt_list,
                        "next_state": RecipeStates.LISTEN_COMMAND,
                    })

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
                    })

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
                    })

            elif intent == "startShift":
                event.set()
                thread.join()

                return Transition(
                    next_state=RecipeStates.SPEAK_PROMPT,
                    next_state_kwargs={
                        "next_task_index": next_task_index,
                        "prompt": "Status updated to \"on shift\".",
                        "next_state": RecipeStates.LISTEN_COMMAND,
                    })

            elif intent == "onBreak":
                event.set()
                thread.join()

                return Transition(
                    next_state=RecipeStates.SPEAK_PROMPT,
                    next_state_kwargs={
                        "next_task_index": next_task_index,
                        "prompt": "Status updated to \"on break\".",
                        "next_state": RecipeStates.LISTEN_COMMAND,
                    })

            elif intent == "endShift":
                event.set()
                thread.join()

                return Transition(
                    next_state=RecipeStates.SPEAK_PROMPT,
                    next_state_kwargs={
                        "next_task_index": next_task_index,
                        "prompt": "Status updated to \"completed shift\".",
                        "next_state": RecipeStates.SHIFT_OVER,
                    })


class RecipeSpeakPromptState(RecipePromptState):
    def run(
            self,
            next_task_index: int,
            next_state: RecipeStates,
            prompt: str | None = None,
            prompt_list: str | None = None,
            next_args: Dict = {},
            **kwargs: Any
    ) -> Transition:
        if prompt is not None:
            self._run_prompt(prompt)

        if prompt_list is not None:
            for prompt in prompt_list:
                self._run_prompt(prompt)

        return Transition(
            next_state=next_state,
            next_state_kwargs={
                "next_task_index": next_task_index,
                **next_args
            })


class RecipeShiftOverPromptState(RecipePromptState):
    def run(
            self,
            **kwargs: Any
    ) -> Transition:
        self._run_prompt("Assistant powering off.")

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
            RecipeStates.SPEAK_PROMPT: RecipeSteps.PROMPT_USER,
            RecipeStates.SHIFT_OVER: RecipeSteps.PROMPT_USER,
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