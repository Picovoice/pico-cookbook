import os
import re
import sys
from argparse import ArgumentParser
from threading import (
    Lock,
    Thread
)
from time import (
    monotonic,
    sleep
)
from typing import (
    List,
    Optional,
    Sequence,
    Set,
    Tuple
)

import picollm
import pvcheetah
import pvorca
import pvporcupine
from pvrecorder import PvRecorder
from pvspeaker import PvSpeaker

SYSTEM_PROMPT = (
    "You are Picovoice's chatbot on picovoice.ai. You are kind, polite and diplomatic, and you reply in English only. "
    "You help visitors understand Picovoice's on-device voice, language, and vision AI products, choose between them, "
    "get started, and find the right contact. Answer directly and completely from Picovoice's documentation. "
    "Never state prices, plan limits, or numbers that are not documented; if something is not documented, say so and "
    "point to the docs, Picovoice Console, GitHub, the contact form, or hello@picovoice.ai."
    "\n\n"
    "Before saying an engine has or lacks an SDK, language or platform, be sure; if you are not sure, say so and point "
    "to that engine's product page. Never invent product names, recipes, links, prices or features. Answer the "
    "question that was asked, and do not add limitations nobody asked about.")

DIALOG_HISTORY = 2
COMPLETION_TOKEN_LIMIT = 256
TEMPERATURE = 0
STOP_PHRASE = '<|eot_id|>'

LINK_WORDS = 'the link shown on screen'
LINK_START_PATTERN = re.compile(r'^(?:https?:|www\.)', re.IGNORECASE)
LINK_TAIL_PATTERN = re.compile(r'[.,!?:;]*$')
SENTENCE_END_PATTERN = re.compile(r'[.!?]\s+')
WORD_PATTERN = re.compile(r"[^\W_]+(?:'[^\W_]+)*")

MODELS_FOLDER = os.path.join(os.path.dirname(__file__), '..', 'res', 'models')

WAITING_PROMPT = 'Say the wake word to ask a question ...'
QUESTION_PROMPT = 'You > '
ANSWER_PROMPT = 'Assistant > '

WAKE_WORD_LISTEN_TIMEOUT_SEC = 8.0
FOLLOW_UP_WINDOW_SEC = 8.0

PLAYBACK_START_BUFFER_SEC = 0.5


def is_held(word: str) -> bool:
    lower = word.lower()
    return re.search(r'[.!?:]', word) is not None or 'https'.startswith(lower) or 'www'.startswith(lower)


def split_sentences(text: str) -> List[Tuple[str, bool]]:
    sentences = []
    last = 0
    for match in SENTENCE_END_PATTERN.finditer(text):
        sentences.append((text[last:match.end()], True))
        last = match.end()
    if last < len(text):
        sentences.append((text[last:], False))
    return sentences


class SpeechText:
    def __init__(self, valid_characters: Set[str]) -> None:
        self._valid_characters = valid_characters
        self._pending = ''
        self._is_in_link = False

    def push(self, text: str) -> str:
        self._pending += text
        if self._is_in_link:
            space = re.search(r'\s', self._pending)
            if space is None:
                return ''
            tail = LINK_TAIL_PATTERN.search(self._pending[:space.start()]).group()
            self._pending = tail + self._pending[space.start():]
            self._is_in_link = False

        start = re.search(r'\S*$', self._pending).start()
        word = self._pending[start:]
        if LINK_START_PATTERN.search(word) is not None:
            before = self._pending[:start]
            self._pending = word
            self._is_in_link = True
            return self._clean(before) + LINK_WORDS

        end = start if is_held(word) else len(self._pending)
        speech = self._pending[:end]
        self._pending = self._pending[end:]
        return self._clean(speech)

    def flush(self) -> str:
        speech = LINK_TAIL_PATTERN.search(self._pending).group() if self._is_in_link else self._pending
        self._pending = ''
        self._is_in_link = False
        return self._clean(speech)

    def _clean(self, text: str) -> str:
        text = ''.join(c if c in self._valid_characters else ' ' for c in text)
        return re.sub(r' {2,}', ' ', text)


class Player:
    def __init__(self, speaker: PvSpeaker) -> None:
        self._speaker = speaker
        self._lock = Lock()
        self._chunks: List[Sequence[int]] = []
        self._buffered_samples = 0
        self._written_chunks = 0
        self._written_samples = 0
        self._heard_before_sec = 0.
        self._started_at: Optional[float] = None
        self._is_playing = False
        self._is_text_done = False
        self._is_stopped = False
        self._thread = Thread(target=self._run, daemon=True)
        self._thread.start()

    def add(self, pcm: Sequence[int]) -> None:
        with self._lock:
            self._chunks.append(pcm)
            self._buffered_samples += len(pcm)
            if self._buffered_samples >= self._speaker.sample_rate * PLAYBACK_START_BUFFER_SEC:
                self._is_playing = True

    def finish_text(self) -> None:
        with self._lock:
            self._is_text_done = True
            self._is_playing = True

    def stop(self) -> None:
        with self._lock:
            self._is_stopped = True

    def join(self) -> None:
        self._thread.join()

    def heard_samples(self) -> int:
        with self._lock:
            return self._heard_samples()

    def is_done(self) -> bool:
        with self._lock:
            if self._is_stopped:
                return True
            is_written = self._written_chunks == len(self._chunks)
            is_heard = self._heard_samples() >= self._buffered_samples
            return self._is_text_done and is_written and is_heard

    def _heard_samples(self) -> int:
        heard_sec = self._heard_before_sec
        if self._started_at is not None:
            heard_sec += monotonic() - self._started_at
        return min(self._written_samples, int(heard_sec * self._speaker.sample_rate))

    def _run(self) -> None:
        while True:
            with self._lock:
                if self._is_stopped:
                    if self._started_at is not None:
                        self._heard_before_sec = self._heard_samples() / self._speaker.sample_rate
                        self._started_at = None
                        self._speaker.stop()
                    return
                is_writing = self._is_playing and self._written_chunks < len(self._chunks)
                if is_writing and self._started_at is None:
                    self._speaker.start()
                    self._started_at = monotonic()
                pcm = self._chunks[self._written_chunks] if is_writing else None

            if pcm is None:
                if self.is_done():
                    with self._lock:
                        if self._started_at is not None:
                            self._speaker.flush()
                            self._speaker.stop()
                        self._is_stopped = True
                    return
                sleep(0.01)
                continue

            offset = 0
            while offset < len(pcm):
                with self._lock:
                    if self._is_stopped:
                        break
                written = self._speaker.write(pcm[offset:])
                offset += written
                if written == 0:
                    sleep(0.01)
            with self._lock:
                if offset == len(pcm):
                    self._written_chunks += 1
                    self._written_samples += len(pcm)


class Segment:
    def __init__(self) -> None:
        self.text = ''
        self.synthesized_samples = 0
        self.is_complete = False


class Turn:
    def __init__(self, question: str) -> None:
        self.question = question
        self.segments: List[Segment] = []
        self.is_interrupted = False
        self.is_done = False
        self.player: Optional[Player] = None

    def spoken_text(self) -> str:
        heard = self.player.heard_samples() if self.player is not None else 0
        parts = []
        for segment in self.segments:
            if segment.is_complete and heard >= segment.synthesized_samples:
                parts.append(segment.text)
                heard -= segment.synthesized_samples
                continue
            if segment.synthesized_samples > 0 and heard > 0:
                words = segment.text.split()
                parts.append(' '.join(words[:int(len(words) * heard / segment.synthesized_samples)]))
            break
        return re.sub(r'\s+', ' ', ''.join(parts)).strip()


class Assistant:
    def __init__(
            self,
            porcupine: pvporcupine.Porcupine,
            cheetah: pvcheetah.Cheetah,
            pllm: picollm.PicoLLM,
            orca: pvorca.Orca,
            recorder: PvRecorder,
            audio_device_index: int) -> None:
        self._porcupine = porcupine
        self._cheetah = cheetah
        self._pllm = pllm
        self._orca = orca
        self._recorder = recorder
        self._audio_device_index = audio_device_index

        self._output_lock = Lock()
        self._line_prompt = ''
        self._is_line_empty = True
        self._mode = 'waiting'
        self._transcript = ''
        self._listen_deadline: Optional[float] = None
        self._exchanges: List[Tuple[str, str]] = []

        self._turn: Optional[Turn] = None
        self._generation: Optional[Thread] = None

        self._cheetah_buffer: List[int] = []

    def run(self) -> None:
        self._show(WAITING_PROMPT)
        self._recorder.start()
        try:
            while True:
                frame = self._recorder.read()
                if self._mode == 'waiting':
                    if self._porcupine.process(frame) == 0:
                        self._listen(after_wake_word=True)
                elif self._mode == 'listening':
                    if self._porcupine.process(frame) == 0:
                        self._listen(after_wake_word=True)
                    else:
                        self._feed_cheetah(frame, self._on_listening_transcript)
                elif self._mode == 'responding':
                    if self._porcupine.process(frame) == 0:
                        self._interrupt_turn()
                        self._listen(after_wake_word=True)
                self._tick()
        finally:
            self._recorder.stop()
            self._interrupt_turn()
            print('', flush=True)

    def _show(self, prompt: str) -> None:
        with self._output_lock:
            if self._is_line_empty:
                sys.stdout.write('\r' + ' ' * len(self._line_prompt) + '\r' + prompt)
            else:
                sys.stdout.write('\n' + prompt)
            sys.stdout.flush()
            self._line_prompt = prompt
            self._is_line_empty = True

    def _append(self, text: str) -> None:
        with self._output_lock:
            sys.stdout.write(text)
            sys.stdout.flush()
            self._is_line_empty = False

    def _tick(self) -> None:
        if self._mode == 'listening' and self._listen_deadline is not None and monotonic() > self._listen_deadline:
            if len(self._transcript.strip()) == 0:
                self._wait()
        if self._mode == 'responding' and self._turn is not None and self._turn.is_done:
            self._finish_turn()

    def _wait(self) -> None:
        self._mode = 'waiting'
        self._listen_deadline = None
        self._show(WAITING_PROMPT)

    def _listen(self, after_wake_word: bool) -> None:
        self._show(QUESTION_PROMPT)
        self._mode = 'listening'
        self._transcript = ''
        self._cheetah_buffer = []
        self._cheetah.flush()
        window_sec = WAKE_WORD_LISTEN_TIMEOUT_SEC if after_wake_word else FOLLOW_UP_WINDOW_SEC
        self._listen_deadline = monotonic() + window_sec

    def _feed_cheetah(self, frame: Sequence[int], on_transcript) -> None:
        self._cheetah_buffer.extend(frame)
        while len(self._cheetah_buffer) >= self._cheetah.frame_length:
            partial, is_endpoint = self._cheetah.process(self._cheetah_buffer[:self._cheetah.frame_length])
            del self._cheetah_buffer[:self._cheetah.frame_length]
            if is_endpoint:
                partial += self._cheetah.flush()
                self._cheetah_buffer = []
            on_transcript(partial, is_endpoint)
            if is_endpoint:
                return

    def _on_listening_transcript(self, partial: str, is_endpoint: bool) -> None:
        if len(partial) > 0:
            self._listen_deadline = None
            self._transcript += partial
            self._append(partial)
        if is_endpoint and len(self._transcript.strip()) > 0:
            self._start_turn(self._transcript.strip())

    def _add_exchange(self, question: str, answer: str) -> None:
        self._exchanges.append((question, answer))
        self._exchanges = self._exchanges[-DIALOG_HISTORY:]

    def _start_turn(self, question: str) -> None:
        self._mode = 'responding'
        self._listen_deadline = None
        self._transcript = ''

        if self._generation is not None:
            self._generation.join()

        self._turn = Turn(question)
        self._generation = Thread(target=self._respond, args=(self._turn,), daemon=True)
        self._generation.start()
        self._show(ANSWER_PROMPT)

    def _respond(self, turn: Turn) -> None:
        dialog = self._pllm.get_dialog(history=DIALOG_HISTORY, system=SYSTEM_PROMPT)
        for question, answer in self._exchanges:
            dialog.add_human_request(question)
            dialog.add_llm_response(answer)
        dialog.add_human_request(turn.question)

        speaker = PvSpeaker(
            sample_rate=self._orca.sample_rate,
            bits_per_sample=16,
            device_index=self._audio_device_index)
        player = Player(speaker)
        turn.player = player
        speech_text = SpeechText(set(self._orca.valid_characters))
        stream = self._orca.stream_open()
        texts: List[Optional[Tuple[str, bool, str]]] = []
        texts_lock = Lock()

        def speak(text: str, is_sentence_end: bool, raw: str) -> None:
            if len(turn.segments) == 0 or (turn.segments[-1].is_complete and len(text.strip()) > 0):
                turn.segments.append(Segment())
            segment = turn.segments[-1]
            segment.text += raw
            if len(text) > 0:
                pcm = stream.synthesize(text)
                if pcm is not None:
                    segment.synthesized_samples += len(pcm)
                    player.add(pcm)
            if is_sentence_end or text.endswith(LINK_WORDS):
                pcm = stream.flush()
                if pcm is not None:
                    segment.synthesized_samples += len(pcm)
                    player.add(pcm)
            if is_sentence_end:
                segment.is_complete = True

        def synthesize() -> None:
            while True:
                with texts_lock:
                    item = texts.pop(0) if len(texts) > 0 else False
                if turn.is_interrupted or item is None:
                    return
                if item is False:
                    sleep(0.01)
                    continue
                speak(*item)

        synthesizer = Thread(target=synthesize, daemon=True)
        synthesizer.start()

        raw = ['']
        is_text_done = [False]

        def queue_text(text: str) -> None:
            raw[0] += text
            for sentence, is_end in split_sentences(speech_text.push(text)):
                with texts_lock:
                    texts.append((sentence, is_end, raw[0]))
                raw[0] = ''

        def finish_text() -> None:
            if is_text_done[0]:
                return
            is_text_done[0] = True
            with texts_lock:
                for sentence, is_end in split_sentences(speech_text.flush()):
                    texts.append((sentence, is_end, raw[0]))
                    raw[0] = ''
                texts.append(('', True, raw[0]))
                texts.append(None)

        def stream_callback(token: str) -> None:
            text = token.replace(STOP_PHRASE, '')
            if turn.is_interrupted:
                self._pllm.interrupt()
                return
            if is_text_done[0]:
                return
            if len(text) == 0:
                if STOP_PHRASE in token:
                    finish_text()
                return
            self._append(text)
            queue_text(text)

        completion = None
        if not turn.is_interrupted:
            completion = self._pllm.generate(
                prompt=dialog.prompt(),
                completion_token_limit=COMPLETION_TOKEN_LIMIT,
                temperature=TEMPERATURE,
                stop_phrases={STOP_PHRASE},
                stream_callback=stream_callback)
        finish_text()
        synthesizer.join()
        if turn.is_interrupted:
            stream.flush()
        stream.close()

        if not turn.is_interrupted:
            player.finish_text()
            while not player.is_done() and not turn.is_interrupted:
                sleep(0.05)

        player.stop()
        player.join()
        speaker.delete()
        if not turn.is_interrupted and completion is not None:
            self._add_exchange(turn.question, completion.completion.replace(STOP_PHRASE, '').strip())
            turn.is_done = True

    def _finish_turn(self) -> None:
        self._turn = None
        self._listen(after_wake_word=False)

    def _interrupt_turn(self) -> bool:
        if self._turn is None:
            return False
        turn = self._turn
        self._turn = None
        turn.is_interrupted = True
        self._pllm.interrupt()
        if turn.player is not None:
            turn.player.stop()

        spoken = turn.spoken_text()
        if len(spoken) > 0:
            self._add_exchange(turn.question, spoken)
        self._append(' (interrupted)')
        return True


def main() -> None:
    parser = ArgumentParser()
    parser.add_argument(
        '--access_key',
        help='AccessKey obtained from Picovoice Console (https://console.picovoice.ai/).')
    parser.add_argument(
        '--keyword_path',
        help='Absolute path to the wake word model file (`.ppn`).')
    parser.add_argument(
        '--porcupine_sensitivity',
        type=float,
        default=0.7,
        help='Sensitivity for detecting the wake word.')
    parser.add_argument(
        '--cheetah_model_path',
        default=os.path.join(MODELS_FOLDER, 'cheetah_params.pv'),
        help='Absolute path to the Cheetah model file (`.pv`).')
    parser.add_argument(
        '--orca_model_path',
        default=os.path.join(MODELS_FOLDER, 'orca_params.pv'),
        help='Absolute path to the Orca model file (`.pv`).')
    parser.add_argument(
        '--picollm_model_path',
        help='Absolute path to the picoLLM model file (`.pllm`).')
    parser.add_argument(
        '--picollm_device',
        default='best',
        help="String representation of the device to use for picoLLM inference. If set to `best`, picoLLM picks the "
             "most suitable device. If set to `gpu`, picoLLM uses the first available GPU. To select a specific GPU, "
             "set this argument to `gpu:${GPU_INDEX}`, where `${GPU_INDEX}` is the index of the target GPU. If set to "
             "`cpu`, picoLLM runs on the CPU with the default number of threads. To specify the number of threads, set "
             "this argument to `cpu:${NUM_THREADS}`, where `${NUM_THREADS}` is the desired number of threads.")
    parser.add_argument(
        '--endpoint_duration_sec',
        type=float,
        default=1.0,
        help="Duration of silence, in seconds, required to detect the end of the user's question.")
    parser.add_argument(
        '--audio_device_index',
        type=int,
        default=-1,
        help='Index of input audio device')
    parser.add_argument(
        '--output_device_index',
        type=int,
        default=-1,
        help='Index of output audio device')
    parser.add_argument(
        '--show_audio_devices',
        action='store_true',
        help='Only list available input and output audio devices and exit')
    args = parser.parse_args()

    if args.show_audio_devices:
        print('Input devices:')
        for index, name in enumerate(PvRecorder.get_available_devices()):
            print('Device #%d: %s' % (index, name))
        print('Output devices:')
        for index, name in enumerate(PvSpeaker.get_available_devices()):
            print('Device #%d: %s' % (index, name))
        return

    if args.access_key is None or args.keyword_path is None or args.picollm_model_path is None:
        print('--access_key, --keyword_path and --picollm_model_path are required arguments')
        return

    porcupine = None
    cheetah = None
    pllm = None
    orca = None
    recorder = None

    try:
        porcupine = pvporcupine.create(
            access_key=args.access_key,
            keyword_paths=[args.keyword_path],
            sensitivities=[args.porcupine_sensitivity])
        print(f"[OK] Porcupine Wake Word [V{porcupine.version}]")

        cheetah = pvcheetah.create(
            access_key=args.access_key,
            model_path=args.cheetah_model_path,
            endpoint_duration_sec=args.endpoint_duration_sec,
            enable_automatic_punctuation=True,
            enable_text_normalization=True)
        print(f"[OK] Cheetah Streaming Speech-to-Text [V{cheetah.version}]")

        pllm = picollm.create(
            access_key=args.access_key,
            model_path=args.picollm_model_path,
            device=args.picollm_device,
            enable_context_caching=True)
        dialog = pllm.get_dialog(history=DIALOG_HISTORY, system=SYSTEM_PROMPT)
        dialog.add_human_request('')
        pllm.generate(prompt=dialog.prompt(), completion_token_limit=1)
        print(f"[OK] picoLLM Inference [V{pllm.version}]")

        orca = pvorca.create(
            access_key=args.access_key,
            model_path=args.orca_model_path)
        print(f"[OK] Orca Streaming Text-to-Speech [V{orca.version}]")

        recorder = PvRecorder(
            device_index=args.audio_device_index,
            frame_length=porcupine.frame_length)

        Assistant(
            porcupine=porcupine,
            cheetah=cheetah,
            pllm=pllm,
            orca=orca,
            recorder=recorder,
            audio_device_index=args.output_device_index).run()
    except KeyboardInterrupt:
        pass
    finally:
        if recorder is not None:
            recorder.delete()
        if orca is not None:
            orca.delete()
        if pllm is not None:
            pllm.release()
        if cheetah is not None:
            cheetah.delete()
        if porcupine is not None:
            porcupine.delete()


if __name__ == '__main__':
    main()
