import csv
import sys
from argparse import ArgumentParser
from pathlib import Path

import pvorca
import pvporcupine
import pvrhino
from pvrecorder import PvRecorder
from pvspeaker import PvSpeaker


def yaml_list(values, indent="      "):
    return "\n".join(f'{indent}- "{value}"' for value in values)


def build_contact_phrases(row):
    phrases = set()

    first = row["first_name"].strip()
    last = row["last_name"].strip()
    nickname = row["nickname"].strip()

    if first:
        phrases.add(first)

    if first and last:
        phrases.add(f"{first} {last}")

    if nickname:
        phrases.add(nickname)

    return phrases


def build_context(
        template_path: str,
        contacts_csv_path: str,
        output_path: str,
):
    contact_values = set()
    company_values = set()

    with open(contacts_csv_path, newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)

        for row in reader:
            contact_values.update(build_contact_phrases(row))

            company = row["company"].strip()
            if company:
                company_values.add(company)

    contact_values = sorted(contact_values, key=str.lower)
    company_values = sorted(company_values, key=str.lower)

    template = Path(template_path).read_text(encoding="utf-8")

    context = (
        template
        .replace("{{CONTACT_SLOT_VALUES}}", yaml_list(contact_values))
        .replace("{{COMPANY_SLOT_VALUES}}", yaml_list(company_values))
    )

    Path(output_path).write_text(context, encoding="utf-8")

    return output_path


def main() -> None:
    parser = ArgumentParser()
    parser.add_argument(
        "--access_key",
        help="AccessKey obtained from Picovoice Console (https://console.picovoice.ai/).")
    parser.add_argument(
        '--keyword_path',
        help='Path to Porcupine Wake Word model trained on Picovoice Console (https://console.picovoice.ai/).')
    parser.add_argument(
        '--context_path',
        help="Path to Rhino Speech-to-Intent context trained on Picovoice Console (https://console.picovoice.ai/).")
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

    if args.show_audio_devices:
        for index, name in enumerate(PvRecorder.get_available_devices()):
            print('Device #%d: %s' % (index, name))
        return

    access_key = args.access_key
    keyword_path = args.keyword_path
    context_path = args.context_path
    audio_device_index = args.audio_device_index

    if access_key is None or keyword_path is None or context_path is None:
        print('--access_key, --keyword_path and --context_path are required arguments')
        return

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

        rhino = pvrhino.create(
            access_key=access_key,
            context_path=context_path,
            require_endpoint=False)
        print(f"[OK] Rhino Speech-to-Intent[V{rhino.version}]")

        orca = pvorca.create(access_key=access_key)
        print(f"[OK] Orca Streaming Text-to-Speech[V{orca.version}]")

        recorder = PvRecorder(
            device_index=audio_device_index,
            frame_length=porcupine.frame_length)

        speaker = PvSpeaker(sample_rate=orca.sample_rate, bits_per_sample=16)
        speaker.start()

        print()

        while True:
            pcm, word_alignments = orca.synthesize(action.prompt(username_orca))

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

            if action.is_terminal():
                break

            text = ""
            text_lock = Lock()

            def get_text():
                with text_lock:
                    display_text = "(listening)" if text == "" else text
                    return f"[CALLER] {display_text}"

            text_event, text_thread = print_async(get_text)

            recorder.start()
            is_endpoint = False
            while not is_endpoint:
                partial, is_endpoint = cheetah.process(recorder.read())
                with text_lock:
                    text += partial
            recorder.stop()
            remainder = cheetah.flush()
            with text_lock:
                text += remainder

            recorder.stop()
            text_event.set()
            text_thread.join()

            print()

            for x in Actions:
                print(f"- {x.value}")

            text = ""
            text_lock = Lock()

            def get_text():
                with text_lock:
                    return "[AI] Select one of the call-assist actions above"

            text_event, text_thread = print_async(get_text)

            recorder.start()
            action = None
            while action is None:
                while not rhino.process(recorder.read()):
                    pass
                inference = rhino.get_inference()
                if inference.is_understood and inference.intent == 'chooseAction':
                    action = Actions(inference.slots['action'])
            recorder.stop()
            text_event.set()
            text_thread.join()
            print(f"[{username.upper()}] {action.value}.\n")
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
