import json
import os
import string
import sys
from argparse import ArgumentParser
from typing import (
    Any,
    Dict,
    Optional,
    Sequence,
    Set
)

import pvcheetah
from pvrecorder import PvRecorder

DEFAULT_HELPDESK_PATH = os.path.abspath(os.path.join(os.path.dirname(__file__), "../res/helpdesk.json"))
DEFAULT_MODEL_PATH = os.path.abspath(os.path.join(os.path.dirname(__file__), "cheetah_model.pv"))

STOP_WORDS = {
    "a", "an", "and", "are", "as", "at", "be", "but", "by", "can", "do", "does", "for", "from", "get", "has", "have",
    "he", "her", "his", "how", "i", "in", "is", "it", "its", "me", "my", "no", "not", "of", "on", "or", "our", "she",
    "so", "that", "the", "their", "them", "they", "this", "to", "up", "was", "we", "what", "when", "where", "which",
    "who", "why", "will", "with", "you", "your"
}

CUSTOM_TERM_WEIGHT = 3
CONTENT_WORD_WEIGHT = 1
SUGGESTION_THRESHOLD = 2


def load_helpdesk(path: str) -> Dict[str, Any]:
    with open(path) as f:
        return json.load(f)


def build_vocabulary(helpdesk: Dict[str, Any]) -> tuple[Dict[str, Set[str]], Set[str]]:
    new_words = dict()
    for product in helpdesk["products"]:
        new_words[product["name"]] = set(product["pronunciations"])

    boost_words = set(helpdesk["boost_words"])

    return new_words, boost_words


def tokenize(text: str) -> Set[str]:
    words = set()
    for word in text.lower().split():
        word = word.strip(string.punctuation)
        if len(word) > 0 and word not in STOP_WORDS:
            words.add(word)
    return words


def index_article(article: Dict[str, Any]) -> Set[str]:
    return tokenize(f'{article["title"]} {" ".join(article["tags"])} {article["summary"]}')


def suggest_article(
        articles: Sequence[Dict[str, Any]],
        custom_terms: Set[str],
        utterance: str) -> Optional[Dict[str, Any]]:
    utterance_words = tokenize(utterance)

    best_article = None
    best_score = 0

    for article in articles:
        overlap = utterance_words & index_article(article)
        score = sum(CUSTOM_TERM_WEIGHT if word in custom_terms else CONTENT_WORD_WEIGHT for word in overlap)

        if score > best_score:
            best_article = article
            best_score = score

    if best_score < SUGGESTION_THRESHOLD:
        return None

    return best_article


def print_suggestion(article: Dict[str, Any]) -> None:
    print()
    print(f'[SUGGESTED] {article["id"]} — {article["title"]}')
    print(f'            {article["macro"]}')
    print()


def main() -> None:
    parser = ArgumentParser()
    parser.add_argument(
        "--access_key",
        help="AccessKey obtained from Picovoice Console (https://console.picovoice.ai/).")
    parser.add_argument(
        "--helpdesk_path",
        default=DEFAULT_HELPDESK_PATH,
        help="Path to the help desk export file with products, boosted words, and knowledge-base articles. Default is "
             "res/helpdesk.json.")
    parser.add_argument(
        "--model_path",
        default=DEFAULT_MODEL_PATH,
        help="Path to save and load the custom Cheetah model file (`.pv`). Default is cheetah_model.pv.")
    parser.add_argument(
        "--retrain",
        action="store_true",
        help="Retrain the custom model even if one already exists at `--model_path`. Use after changing the help desk "
             "export.")
    parser.add_argument(
        "--language",
        default="en",
        help="Two-character language code for the custom model (e.g., `en`, `fr`). See "
             "https://picovoice.ai/docs/model-api/cheetah/ for supported languages.")
    parser.add_argument(
        "--endpoint_duration_sec",
        type=float,
        default=1.0,
        help="Duration of silence, in seconds, required to detect the end of an utterance.")
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

    if args.access_key is None:
        print('--access_key is a required argument')
        return

    helpdesk = load_helpdesk(args.helpdesk_path)
    new_words, boost_words = build_vocabulary(helpdesk)
    custom_terms = {word.lower() for word in new_words} | {word.lower() for word in boost_words}

    if not os.path.exists(args.model_path) or args.retrain:
        print(f'Training a custom model for {helpdesk["company"]} over the Cheetah Model API. This one-time step '
              'requires internet connectivity and sends the vocabulary, not audio.')
        pvcheetah.train_model_from_words(
            access_key=args.access_key,
            output_path=args.model_path,
            language=args.language,
            new_words=new_words,
            boost_words=boost_words)
        print(f"[OK] Trained custom model and saved it to `{args.model_path}`")
    else:
        print(f"[OK] Found existing custom model at `{args.model_path}`")

    cheetah = None
    recorder = None

    try:
        cheetah = pvcheetah.create(
            access_key=args.access_key,
            model_path=args.model_path,
            endpoint_duration_sec=args.endpoint_duration_sec,
            enable_automatic_punctuation=True,
            enable_text_normalization=True)
        print(f"[OK] Cheetah Streaming Speech-to-Text [V{cheetah.version}]")

        recorder = PvRecorder(
            device_index=args.audio_device_index,
            frame_length=cheetah.frame_length)
        recorder.start()

        print()
        print("Listening. Speak as the caller. Press Ctrl+C to stop.")
        print()

        utterance = ""

        while True:
            partial_transcript, is_endpoint = cheetah.process(recorder.read())
            sys.stdout.write(partial_transcript)
            sys.stdout.flush()
            utterance += partial_transcript

            if is_endpoint:
                remainder = cheetah.flush()
                sys.stdout.write(remainder + '\n')
                sys.stdout.flush()
                utterance += remainder

                article = suggest_article(
                    articles=helpdesk["kb_articles"],
                    custom_terms=custom_terms,
                    utterance=utterance)
                if article is not None:
                    print_suggestion(article)

                utterance = ""

    except KeyboardInterrupt:
        print()

    finally:
        if recorder is not None:
            recorder.stop()
            recorder.delete()

        if cheetah is not None:
            cheetah.delete()


if __name__ == "__main__":
    main()
