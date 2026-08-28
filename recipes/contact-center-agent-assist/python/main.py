import os
from argparse import ArgumentParser
from collections import deque
from time import monotonic
from typing import (
    Any,
    Dict,
    Sequence
)

import pvcheetah
from pvrecorder import PvRecorder
from rich.console import Group
from rich.layout import Layout
from rich.live import Live
from rich.panel import Panel
from rich.table import Table
from rich.text import Text

from helpdesk import (
    get_caller,
    get_product_options,
    get_support_tags,
    load_helpdesk,
    search_help_center
)

DEFAULT_HELPDESK_PATH = os.path.abspath(os.path.join(os.path.dirname(__file__), "../res/helpdesk.json"))
DEFAULT_MODEL_PATH = os.path.abspath(os.path.join(os.path.dirname(__file__), "cheetah_model_ikea.pv"))

PRIMARY_COLOR = "#4da6ff"
ACCENT_STYLE = "bold yellow"


class DotsText:
    FRAMES = tuple(x.replace(" ", "\u00a0") for x in (" .  ", " .. ", " ...", "  ..", "   .", "    "))

    def __init__(self, text: Text) -> None:
        self._text = text

    def __rich_console__(self, console, options):
        frame = self.FRAMES[int(monotonic() * 10) % len(self.FRAMES)]
        yield Text.assemble(self._text, (frame, "dim"))


class FitGroup:
    def __init__(self, *variants) -> None:
        self._variants = variants

    def __rich_console__(self, console, options):
        measure_options = options.update(height=None)
        for variant in self._variants[:-1]:
            num_lines = len(console.render_lines(variant, measure_options, pad=False))
            if options.height is None or num_lines <= options.height:
                yield variant
                return
        yield self._variants[-1]


def build_vocabulary(
        product_options: Sequence[Dict[str, Any]],
        support_tags: Sequence[str],
):
    new_words = dict()
    boost_words = set(support_tags)

    for option in product_options:
        if "pronunciations" in option:
            new_words[option["name"]] = set(option["pronunciations"])
        else:
            boost_words.add(option["name"].lower())

    return new_words, boost_words


def header_view(company: str, caller: Dict[str, Any]) -> Panel:
    grid = Table.grid(expand=True)
    grid.add_column(justify="left")
    grid.add_column(justify="right")
    grid.add_row(
        Text(f"{company} Customer Support", style="bold white"),
        Text(f"{caller['name']} | {caller['account']}", style="grey70"))
    return Panel(grid, border_style=PRIMARY_COLOR, padding=(0, 2))


def transcript_view(lines: Sequence[str], current: str) -> Panel:
    def build(visible: Sequence[str]):
        text = Text()
        for line in visible:
            text.append("[CUSTOMER] ", style=f"bold {PRIMARY_COLOR}")
            text.append(f"{line}\n\n", style="white")

        if len(current) > 0:
            text.append("[CUSTOMER] ", style=f"bold {PRIMARY_COLOR}")
            text.append(current, style="white")
            text.append(" ▌", style=PRIMARY_COLOR)
            return text

        idle = Text()
        idle.append("[CUSTOMER] ", style=f"bold {PRIMARY_COLOR}")
        idle.append("(listening)", style="dim")
        return Group(text, DotsText(idle))

    variants = [build(list(lines)[skip:]) for skip in range(len(lines) + 1)]
    return Panel(FitGroup(*variants), title="[bold]Live Transcript[/bold]", border_style=PRIMARY_COLOR, padding=(1, 2))


def assist_view(articles) -> Panel:
    if articles is None:
        body = Text("\nNo suggestions yet", style="dim", justify="center")
    elif len(articles) == 1:
        article = articles[0]
        badge = Text()
        badge.append(f" {article['label_names'][0].upper()} ", style="black on yellow")
        badge.append("  ")
        badge.append(f"{article['id']}  {article['title']}", style=ACCENT_STYLE)
        facts = [Text(f"• {fact}", style="white") for fact in article["facts"]]
        spaced_facts = [part for fact in facts for part in (fact, Text(""))]
        next_step = Panel(
            Text(article["next_step"], style="white"),
            title="[bold green]Next step[/bold green]",
            border_style="green",
            padding=(0, 1))
        body = FitGroup(
            Group(badge, Text(""), *spaced_facts, next_step),
            Group(badge, Text(""), *facts, next_step),
            Group(badge, Text(""), *facts),
            Group(badge, Text(""), next_step))
    else:
        heading = Text("RELATED ARTICLES", style=ACCENT_STYLE)
        titles = [Text(f"• {article['id']}  {article['title']}", style="bold white") for article in articles]
        snippets = [Text(f"   {article['snippet']}", style="dim") for article in articles]
        entries = [part for entry in zip(titles, snippets) for part in entry]
        spaced_entries = [part for entry in zip(titles, snippets) for part in (*entry, Text(""))]
        body = FitGroup(
            Group(heading, Text(""), *spaced_entries),
            Group(heading, Text(""), *entries),
            Group(heading, Text(""), *titles))

    return Panel(body, title="[bold]Agent Assist[/bold]", border_style="green", padding=(1, 2))


def main() -> None:
    parser = ArgumentParser()
    parser.add_argument(
        "--access_key",
        help="AccessKey obtained from Picovoice Console (https://console.picovoice.ai/).")
    parser.add_argument(
        "--helpdesk_path",
        default=DEFAULT_HELPDESK_PATH,
        help="Path to the helpdesk data file with ticket fields, tags, and help-center articles. Default is "
             "res/helpdesk.json.")
    parser.add_argument(
        "--cheetah_model_path",
        default=DEFAULT_MODEL_PATH,
        help="Path to save and load the custom Cheetah model file (`.pv`). Default is cheetah_model_ikea.pv.")
    parser.add_argument(
        "--retrain",
        action="store_true",
        help="Retrain the custom model even if one already exists at `--cheetah_model_path`. Use after changing the "
             "helpdesk vocabulary.")
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
        '--disable_text_normalization',
        action='store_true',
        help='Disable text normalization in Streaming Speech-to-Text.')
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
    helpdesk_path = args.helpdesk_path
    cheetah_model_path = args.cheetah_model_path
    language = args.language
    endpoint_duration_sec = args.endpoint_duration_sec
    disable_text_normalization = args.disable_text_normalization

    if access_key is None:
        print('--access_key is a required argument')
        return

    cheetah = None
    recorder = None

    try:
        helpdesk = load_helpdesk(helpdesk_path)
        product_options = get_product_options(helpdesk)
        new_words, boost_words = build_vocabulary(
            product_options=product_options,
            support_tags=get_support_tags(helpdesk))

        num_articles = len(helpdesk["help_center_articles"])
        print(
            f"[OK] Loaded {helpdesk['company']} helpdesk configuration "
            f"({len(product_options)} products; {num_articles} help-center articles)")

        if not os.path.exists(cheetah_model_path) or args.retrain:
            company = helpdesk["company"]
            print(f"Training a custom model for {company} over the Cheetah Model API.")
            pvcheetah.train_model_from_words(
                access_key=access_key,
                output_path=cheetah_model_path,
                language=language,
                new_words=new_words,
                boost_words=boost_words)
            print(f"[OK] Trained custom model and saved it to `{cheetah_model_path}`")
        else:
            print(f"[OK] Found existing custom model at `{cheetah_model_path}`")

        cheetah = pvcheetah.create(
            access_key=access_key,
            model_path=cheetah_model_path,
            endpoint_duration_sec=endpoint_duration_sec,
            enable_automatic_punctuation=True,
            enable_text_normalization=not disable_text_normalization)
        print(f"[OK] Cheetah Streaming Speech-to-Text [V{cheetah.version}]")

        recorder = PvRecorder(
            device_index=args.audio_device_index,
            frame_length=cheetah.frame_length)
        recorder.start()

        lines = deque(maxlen=5)
        current = ""
        conversation = list()
        shown = None

        layout = Layout()
        layout.split_column(Layout(name="header", size=3), Layout(name="body"))
        layout["body"].split_row(Layout(name="transcript", ratio=1), Layout(name="assist", ratio=1))
        layout["header"].update(header_view(helpdesk["company"], get_caller(helpdesk)))
        layout["transcript"].update(transcript_view(lines, current))
        layout["assist"].update(assist_view(None))

        with Live(layout, refresh_per_second=10, screen=True):
            while True:
                partial, is_endpoint = cheetah.process(recorder.read())

                if len(partial) > 0:
                    current += partial
                    layout["transcript"].update(transcript_view(lines, current))

                if is_endpoint:
                    current += cheetah.flush()
                    lines.append(current)
                    conversation.append(current)
                    current = ""
                    layout["transcript"].update(transcript_view(lines, current))

                    results = search_help_center(helpdesk=helpdesk, conversation=conversation)
                    result = tuple(x["id"] for x in results)
                    if len(results) > 0 and result != shown:
                        layout["assist"].update(assist_view(results))
                        shown = result
    except KeyboardInterrupt:
        pass
    finally:
        if recorder is not None:
            recorder.stop()
            recorder.delete()

        if cheetah is not None:
            cheetah.delete()


if __name__ == "__main__":
    main()
