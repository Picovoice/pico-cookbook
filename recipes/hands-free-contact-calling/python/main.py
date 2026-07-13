import os
import shutil
import string
import sys
from argparse import ArgumentParser
from csv import DictReader
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
    Dict,
    Callable,
    Sequence,
    Tuple, Optional
)

import pvorca
import pvporcupine
import pvrhino
from pvorca import Orca
from pvrecorder import PvRecorder
from pvrhino import Inference
from pvspeaker import PvSpeaker


def build_context_yml() -> None:
    contacts = set()
    companies = set()

    with open(os.path.join(str(os.path.dirname(__file__)), '../res/contacts.csv')) as f:
        for row in DictReader(f):
            first = row["first_name"].strip()
            last = row["last_name"].strip()
            nickname = row["nickname"].strip()
            company = row["company"].strip()

            if len(first) > 0:
                contacts.add(first)
            if len(last) > 0:
                contacts.add(last)
            if len(first) > 0 and len(last) > 0:
                contacts.add(f"{first} {last}")
            if len(nickname) > 0:
                contacts.add(nickname)

            if len(company) > 0:
                companies.add(company)

    contacts = sorted(contacts, key=str.lower)
    companies = sorted(companies, key=str.lower)

    with open(os.path.join(str(os.path.dirname(__file__)), '../res/context.template')) as f:
        template = f.read()

    context = template \
        .replace("{{CONTACTS}}", "\n".join(f'    - "{x}"' for x in contacts)) \
        .replace("{{COMPANIES}}", "\n".join(f'    - "{x}"' for x in companies))

    with open(os.path.join(str(os.path.dirname(__file__)), 'context.yml'), 'w') as f:
        f.write(context)


def normalize(text: str) -> str:
    return " ".join(text.lower().strip().split())


def contact_display_name(contact: dict[str, str]) -> str:
    return f'{contact["first_name"]} {contact["last_name"]}'.strip()


def runtime_contact_phrases(contact: dict[str, str]) -> set[str]:
    phrases = set()

    first = contact["first_name"].strip()
    last = contact["last_name"].strip()
    nickname = contact["nickname"].strip()

    if first:
        phrases.add(normalize(first))

    if first and last:
        phrases.add(normalize(f"{first} {last}"))

    if nickname:
        phrases.add(normalize(nickname))

    return phrases


def find_contacts(
        contacts: Sequence[Dict[str, str]],
        contact_name: str,
        company: str | None = None,
) -> list[dict[str, str]]:
    contact_name = normalize(contact_name)
    company = normalize(company or "")

    matches = []

    for contact in contacts:
        if contact_name not in runtime_contact_phrases(contact):
            continue

        if company:
            contact_company = normalize(contact.get("company", ""))
            if contact_company != company:
                continue

        matches.append(contact)

    return matches


def selection_index_to_int(selection: str) -> int | None:
    selection = normalize(selection)

    mapping = {
        "first": 0,
        "one": 0,
        "number one": 0,
        "option one": 0,

        "second": 1,
        "two": 1,
        "number two": 1,
        "option two": 1,

        "third": 2,
        "three": 2,
        "number three": 2,
        "option three": 2,

        "fourth": 3,
        "four": 3,
        "number four": 3,
        "option four": 3,

        "fifth": 4,
        "five": 4,
        "number five": 4,
        "option five": 4,
    }

    return mapping.get(selection)


def phone_field_from_type(phone: str | None) -> tuple[str, str]:
    phone = normalize(phone or "")

    if phone in {"work", "office"}:
        return "phone_work", "work"

    if phone in {"home", "house"}:
        return "phone_home", "home"

    return "phone_mobile", "mobile"


def choose_phone(contact: dict[str, str], phone: str | None) -> tuple[str | None, str]:
    if phone:
        field, label = phone_field_from_type(phone)
        number = contact.get(field, "").strip()
        return number or None, label

    default_phone = contact.get("default_phone", "mobile").strip()
    field, label = phone_field_from_type(default_phone)
    number = contact.get(field, "").strip()

    if number:
        return number, label

    for fallback_phone in ("mobile", "work", "home"):
        field, label = phone_field_from_type(fallback_phone)
        number = contact.get(field, "").strip()
        if number:
            return number, label

    return None, label


def build_call_response(
        contact: dict[str, str],
        phone: str | None,
) -> str:
    name = contact_display_name(contact)
    number, label = choose_phone(contact, phone)

    if number is None:
        return f"I found {name}, but there is no {label} phone number available."

    print(f"[CALL] {name} | {label} | {number}")
    return f"Calling {name} on {label}."


def build_options_response(matches: Sequence[Dict[str, str]]) -> str:
    options = ", ".join(contact_display_name(x) for x in matches)
    return f"I found {options}. Which one?"


def handle_inference(
        inference: Inference,
        contacts: Sequence[Dict[str, str]],
        pending_contacts: Sequence[Dict[str, str]],
        pending_phone: Optional[str],
) -> Tuple[str, Sequence[Dict[str, str]], Optional[str], bool]:
    if not inference.is_understood:
        return "Sorry, I did not understand that.", pending_contacts, pending_phone, False

    intent = inference.intent
    slots = inference.slots

    if intent == "callContact":
        contact_name = slots.get("contact")
        company = slots.get("company")
        phone = slots.get("phone")

        matches = find_contacts(
            contacts=contacts,
            contact_name=contact_name,
            company=company)

        if not matches:
            if company:
                return f"I could not find {contact_name} at {company}.", [], None, True
            return f"I could not find {contact_name}.", [], None, True

        if len(matches) == 1:
            return build_call_response(matches[0], phone), [], None, True

        matches = matches[:5]
        return build_options_response(matches), matches, phone, False

    if intent == "selectContact":
        if pending_contacts:
            if "selection_index" in slots:
                index = selection_index_to_int(slots["selection_index"])

                if index is None or index >= len(pending_contacts):
                    return "That option is not available.", pending_contacts, pending_phone, False

                return build_call_response(pending_contacts[index], pending_phone), [], None, True

            if "contact" in slots:
                matches = find_contacts(pending_contacts, slots["contact"])

                if len(matches) == 1:
                    return build_call_response(matches[0], pending_phone), [], None, True

                if len(matches) > 1:
                    matches = matches[:5]
                    return build_options_response(matches), matches, pending_phone, False

            return "Which contact did you mean?", pending_contacts, pending_phone, False

        contact_name = slots.get("contact")
        if not contact_name:
            return "I do not have a contact selection pending.", [], None, True

        matches = find_contacts(contacts, contact_name)

        if not matches:
            return f"I could not find {contact_name}.", [], None, True

        if len(matches) == 1:
            return build_call_response(matches[0], pending_phone), [], None, True

        matches = matches[:5]
        return build_options_response(matches), matches, pending_phone, False

    if intent == "selectPhone":
        phone = slots.get("phone")

        if not phone:
            return "Which number should I use?", pending_contacts, pending_phone, False

        if not pending_contacts:
            return f"Okay, use {phone}.", [], phone, False

        if len(pending_contacts) == 1:
            return build_call_response(pending_contacts[0], phone), [], None, True

        return f"Okay, {phone}. Which contact?", pending_contacts, phone, False

    return "Sorry, I do not know how to handle that command.", [], None, True


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
            if delay > 0:
                sleep(delay)

            suffix = ' ' if i < (len(alignments) - 1) and (alignments[i + 1].word not in string.punctuation) else ''
            on_tick(x.word + suffix)

    thread = Thread(target=run, daemon=True)
    thread.start()
    return thread


def main() -> None:
    parser = ArgumentParser()
    parser.add_argument(
        "--access_key",
        help="AccessKey obtained from Picovoice Console (https://console.picovoice.ai/)")
    parser.add_argument(
        '--keyword_path',
        help='Path to Porcupine Wake Word model trained on Picovoice Console (https://console.picovoice.ai/)')
    parser.add_argument(
        '--audio_device_index',
        type=int,
        default=-1,
        help='Index of input audio device')
    parser.add_argument(
        '--show_audio_devices',
        action='store_true',
        help='Only list available input audio devices and exit')
    args = parser.parse_args()

    access_key = args.access_key
    keyword_path = args.keyword_path
    audio_device_index = args.audio_device_index
    show_audio_devices = args.show_audio_devices

    if show_audio_devices:
        for index, name in enumerate(PvRecorder.get_available_devices()):
            print(f'{index}: {name}')
        return

    missing = [k for k, v in {"--access_key": access_key, "--keyword_path": keyword_path}.items() if v is None]

    if missing:
        parser.error(f"the following arguments are required unless --show_audio_devices is used: {", ".join(missing)}")

    with open(os.path.join(str(os.path.dirname(__file__)), '../res/contacts.csv')) as f:
        contacts = list(DictReader(f))

    build_context_yml()

    porcupine = None
    rhino = None
    orca = None
    recorder = None
    speaker = None

    try:
        porcupine = pvporcupine.create(
            access_key=access_key,
            keyword_paths=[keyword_path])
        print(f"[OK] Porcupine Wake Word[V{porcupine.version}]")

        pvrhino.train_context_from_yaml(
            access_key=access_key,
            output_path=os.path.join(str(os.path.dirname(__file__)), "context.rhn"),
            language='en',
            yaml_path=os.path.join(str(os.path.dirname(__file__)), "context.yml"))
        print(f"[OK] Rhino Speech-to-Intent Model API")

        rhino = pvrhino.create(
            access_key=access_key,
            context_path=os.path.join(str(os.path.dirname(__file__)), "context.rhn"))
        print(f"[OK] Rhino Speech-to-Intent[V{rhino.version}]")

        orca = pvorca.create(access_key=access_key)
        print(f"[OK] Orca Streaming Text-to-Speech[V{orca.version}]")

        recorder = PvRecorder(
            device_index=audio_device_index,
            frame_length=porcupine.frame_length)

        speaker = PvSpeaker(sample_rate=orca.sample_rate, bits_per_sample=16)
        speaker.start()

        wait_for_wake_word = True
        pending_contacts = []
        pending_phone = None

        print()

        while True:
            recorder.start()

            text = "Listening for wake word" if wait_for_wake_word else "Listening for follow-up"
            lock = Lock()

            def get_text() -> str:
                with lock:
                    return text

            print_event, print_thread = print_async(get_text)

            if wait_for_wake_word:
                is_detected = False
                while not is_detected:
                    is_detected = porcupine.process(recorder.read()) == 0

                with lock:
                    text = "Listening for voice command"

            while not rhino.process(recorder.read()):
                pass

            inference = rhino.get_inference()

            recorder.stop()
            print_event.set()
            print_thread.join()

            response, pending_contacts, pending_phone, is_complete = handle_inference(
                inference=inference,
                contacts=contacts,
                pending_contacts=pending_contacts,
                pending_phone=pending_phone)

            pcm, word_alignments = orca.synthesize(response)

            utterance = ""
            utterance_lock = Lock()

            def get_utterance() -> str:
                with utterance_lock:
                    return f"[AI] {utterance}"

            def update_utterance(chunk: str) -> None:
                nonlocal utterance
                with utterance_lock:
                    utterance += chunk

            utterance_event, utterance_thread = print_async(get_utterance)
            timer_thread = time_async(alignments=word_alignments, on_tick=update_utterance)

            speaker.flush(pcm)

            timer_thread.join()
            utterance_event.set()
            utterance_thread.join()

            wait_for_wake_word = is_complete
    except KeyboardInterrupt:
        pass
    finally:
        # Make the cursor visible again.
        sys.stdout.write("\033[?25h")
        sys.stdout.flush()

        if speaker is not None:
            speaker.stop()
            speaker.delete()

        if recorder is not None:
            recorder.stop()
            recorder.delete()

        if orca is not None:
            orca.delete()

        if rhino is not None:
            rhino.delete()

        if porcupine is not None:
            porcupine.delete()


if __name__ == "__main__":
    main()
