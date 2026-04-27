import os
import re
import shutil
import string
import sys
from argparse import ArgumentParser
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
    Callable,
    Sequence,
    Tuple
)

import picollm
import pvcheetah
import pvorca
from pvorca import Orca
from pvrecorder import PvRecorder
from pvspeaker import PvSpeaker


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


def chunk_document(
        text: str,
        chunk_size: int = 1200,
        chunk_overlap: int = 250,
) -> Sequence[str]:
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    text = re.sub(r"\n{3,}", "\n\n", text).strip()

    chunks = list()
    start = 0

    while start < len(text):
        end = min(start + chunk_size, len(text))

        if end < len(text):
            paragraph_break = text.rfind("\n\n", start, end)
            if paragraph_break > start + int(chunk_size * 0.5):
                end = paragraph_break

        chunk = text[start:end].strip()

        if len(chunk) > 0:
            chunks.append(chunk)

        if end >= len(text):
            break

        start = max(0, end - chunk_overlap)

    return chunks


def as_vector(x: object) -> Sequence[float]:
    if hasattr(x, "embedding"):
        x = getattr(x, "embedding")
    return [float(y) for y in x]


def normalize_vector(vector: Sequence[float]) -> Sequence[float]:
    norm = sum(x * x for x in vector) ** 0.5
    if norm == 0:
        raise ValueError("Cannot normalize zero vector.")

    return [x / norm for x in vector]


def dot_product(a: Sequence[float], b: Sequence[float]) -> float:
    return sum(x * y for x, y in zip(a, b))


def retrieve_chunks(
        question: str,
        embedding_llm: picollm.PicoLLM,
        chunks: Sequence[str],
        embeddings: Sequence[Sequence[float]],
        top_k: int,
) -> Sequence[Tuple[float, str]]:
    question_embedding = normalize_vector(as_vector(embedding_llm.generate_embeddings(question)))

    scored = [
        (dot_product(question_embedding, embedding), chunk)
        for embedding, chunk in zip(embeddings, chunks)
    ]

    scored.sort(key=lambda x: x[0], reverse=True)
    return scored[:top_k]


def build_prompt(
        chat_llm: picollm.PicoLLM,
        question: str,
        retrieved_chunks: Sequence[Tuple[float, str]],
) -> str:
    context = "\n\n".join(
        f"[Excerpt {i}]\n{chunk}"
        for i, (_, chunk) in enumerate(retrieved_chunks, start=1)
    )

    dialog = chat_llm.get_dialog(
        system=(
            "You are a document question-answering assistant. "
            "Answer only using the provided document excerpts. "
            "If the answer is not in the excerpts, say that you do not know from the provided document. "
            "Do not give legal advice. "
            "Keep the answer concise."
        )
    )

    dialog.add_human_request(
        f"Document excerpts:\n\n{context}\n\n"
        f"Question:\n{question}"
    )

    return dialog.prompt()


def main() -> None:
    parser = ArgumentParser()
    parser.add_argument(
        "--access_key",
        required=True,
        help="AccessKey obtained from Picovoice Console (https://console.picovoice.ai/).")
    parser.add_argument(
        '--picollm_embedding_model_path',
        required=True,
        help='Absolute path to the picoLLM embedding model file (`.pllm`).')
    parser.add_argument(
        '--picollm_chat_model_path',
        required=True,
        help='Absolute path to the picoLLM chat model file (`.pllm`).')
    parser.add_argument(
        '--document_path',
        default=os.path.join(os.path.dirname(__file__), '..', 'res', 'CPAL-1.0.txt'),
        help="Absolute or relative path to the document to index.")
    parser.add_argument(
        '--cheetah_model_path',
        help="Absolute path to the Cheetah model file.")
    parser.add_argument(
        "--endpoint_duration_sec",
        type=float,
        default=1.0,
        help="Duration of silence, in seconds, required to detect the end of the question.")
    parser.add_argument(
        '--picollm_device',
        default="best",
        help="String representation of the device to use for picoLLM inference. If set to `best`, picoLLM picks the "
             "most suitable device. If set to `gpu`, picoLLM uses the first available GPU. To select a specific GPU, "
             "set this argument to `gpu:${GPU_INDEX}`, where `${GPU_INDEX}` is the index of the target GPU. If set to "
             "`cpu`, picoLLM runs on the CPU with the default number of threads. To specify the number of threads, set "
             "this argument to `cpu:${NUM_THREADS}`, where `${NUM_THREADS}` is the desired number of threads.")
    parser.add_argument(
        "--top_k",
        type=int,
        default=5,
        help="Number of document chunks to retrieve for each question.")
    parser.add_argument(
        "--completion_token_limit",
        type=int,
        default=256,
        help="Maximum number of completion tokens generated by picoLLM.")
    args = parser.parse_args()

    access_key = args.access_key
    picollm_embedding_model_path = args.picollm_embedding_model_path
    picollm_chat_model_path = args.picollm_chat_model_path
    document_path = args.document_path
    cheetah_model_path = args.cheetah_model_path
    endpoint_duration_sec = args.endpoint_duration_sec
    picollm_device = args.picollm_device
    top_k = args.top_k
    completion_token_limit = args.completion_token_limit

    embedding_llm = None
    cheetah = None
    chat_llm = None
    orca = None
    recorder = None
    speaker = None

    try:
        embedding_llm = picollm.create(
            access_key=access_key,
            model_path=picollm_embedding_model_path,
            device=picollm_device)
        print(
            f"[OK] picoLLM Inference [V{embedding_llm.version}] "
            f"[{os.path.basename(picollm_embedding_model_path).replace('.pllm', '')}]")

        cheetah = pvcheetah.create(
            access_key=access_key,
            model_path=cheetah_model_path,
            endpoint_duration_sec=endpoint_duration_sec,
            enable_automatic_punctuation=True,
            enable_text_normalization=True)
        print(f"[OK] Cheetah Streaming Speech-to-Text [V{cheetah.version}]")

        chat_llm = picollm.create(
            access_key=access_key,
            model_path=picollm_chat_model_path,
            device=picollm_device)
        print(
            f"[OK] picoLLM Inference [V{chat_llm.version}] "
            f"[{os.path.basename(picollm_chat_model_path).replace('.pllm', '')}]")

        orca = pvorca.create(access_key=access_key)
        print(f"[OK] Orca Streaming Text-to-Speech [V{orca.version}]")

        recorder = PvRecorder(frame_length=cheetah.frame_length)

        speaker = PvSpeaker(sample_rate=orca.sample_rate, bits_per_sample=16)
        speaker.start()

        with open(document_path, 'r', encoding='utf-8') as f:
            chunks = chunk_document(f.read())
        print(f"[OK] Broke `{os.path.basename(document_path)}` into {len(chunks)} chunks")

        embeddings = [
            normalize_vector(as_vector(embedding_llm.generate_embeddings(x)))
            for x in chunks
        ]
        print("[OK] Generated embeddings")

        print()

        while True:
            recorder.start()
            question = ""
            question_lock = Lock()

            def get_question() -> str:
                with question_lock:
                    display_question = "(listening)" if question == "" else question
                    return f"[Q] {display_question}"

            question_event, question_thread = print_async(get_question)

            while True:
                partial, is_endpoint = cheetah.process(recorder.read())
                with question_lock:
                    question += partial

                if is_endpoint:
                    remainder = cheetah.flush()
                    with question_lock:
                        question += remainder

                    question_event.set()
                    question_thread.join()

                    recorder.stop()
                    break

            question = question.strip()
            if len(question) == 0:
                continue

            status = "Thinking"
            status_lock = Lock()

            def get_status() -> str:
                with status_lock:
                    return status

            status_event, status_thread = print_async(get_status)

            retrieved_chunks = retrieve_chunks(
                question=question,
                embedding_llm=embedding_llm,
                chunks=chunks,
                embeddings=embeddings,
                top_k=top_k)

            prompt = build_prompt(
                chat_llm=chat_llm,
                question=question,
                retrieved_chunks=retrieved_chunks)

            completion = chat_llm.generate(
                prompt=prompt,
                completion_token_limit=completion_token_limit,
                stop_phrases={'<|eot_id|>'})
            answer_text = completion.completion.strip().replace('<|eot_id|>', '')

            if len(answer_text) == 0:
                answer_text = "I don't know from the provided document."

            with status_lock:
                status = f"[A] {answer_text}"

            status_event.set()
            status_thread.join()

            pcm, alignments = orca.synthesize(text=answer_text)

            spoken_answer = ""
            spoken_answer_lock = Lock()

            def get_spoken_answer() -> str:
                with spoken_answer_lock:
                    return f"[A] {spoken_answer}"

            def update_spoken_answer(chunk: str) -> None:
                nonlocal spoken_answer
                with spoken_answer_lock:
                    spoken_answer += chunk

            answer_event, answer_thread = print_async(get_spoken_answer)

            timer_thread = time_async(
                alignments=alignments,
                on_tick=update_spoken_answer)

            speaker.flush(pcm)
            timer_thread.join()
            answer_event.set()
            answer_thread.join()

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

        if chat_llm is not None:
            chat_llm.release()

        if cheetah is not None:
            cheetah.delete()

        if embedding_llm is not None:
            embedding_llm.release()


if __name__ == '__main__':
    main()
