import json
import os
import queue
import re
import shutil
import sys
from argparse import ArgumentParser
from pathlib import Path
from threading import (
    Event,
    Lock,
    Thread
)
from typing import (
    Callable,
    Optional,
    Sequence,
    Set,
    Tuple
)

import picollm
import pvcheetah
import pvorca
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
                stop_event.wait(refresh_sec)

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


def chunk_document(
        text: str,
        chunk_size: int = 1200,
        chunk_overlap: int = 250,
) -> Sequence[str]:
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    text = re.sub(r"\n{3,}", "\n\n", text).strip()

    if chunk_overlap >= chunk_size:
        raise ValueError("`chunk_overlap` must be smaller than `chunk_size`.")

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


def generate_embeddings(
        embedding_llm: picollm.PicoLLM,
        chunks: Sequence[str],
) -> Sequence[Sequence[float]]:
    status = f"Generating embeddings 0/{len(chunks)}"
    status_lock = Lock()

    def get_status() -> str:
        with status_lock:
            return status

    status_event, status_thread = print_async(get_status)

    embeddings = list()

    try:
        for i, chunk in enumerate(chunks):
            embedding = normalize_vector(as_vector(embedding_llm.generate_embeddings(chunk)))
            embeddings.append(embedding)

            with status_lock:
                status = f"Generating embeddings {i + 1}/{len(chunks)}"

    finally:
        status_event.set()
        status_thread.join()

    return embeddings


def save_embeddings(
        path: str,
        document_path: str,
        chunk_size: int,
        chunk_overlap: int,
        chunks: Sequence[str],
        embeddings: Sequence[Sequence[float]],
) -> None:
    output_path = Path(path)
    output_path.parent.mkdir(parents=True, exist_ok=True)

    output_path.write_text(
        json.dumps(
            {
                "document_path": document_path,
                "chunk_size": chunk_size,
                "chunk_overlap": chunk_overlap,
                "chunks": list(chunks),
                "embeddings": [list(x) for x in embeddings],
            },
            ensure_ascii=False,
        ),
        encoding="utf-8")

    print(f"[OK] Saved embeddings to `{output_path}`")


def load_embeddings(
        path: str,
        chunks: Sequence[str],
) -> Sequence[Sequence[float]]:
    input_path = Path(path)
    data = json.loads(input_path.read_text(encoding="utf-8"))

    loaded_chunks = data.get("chunks")
    embeddings = data.get("embeddings")

    if not isinstance(loaded_chunks, list):
        raise ValueError("Invalid embeddings file: missing `chunks` list.")

    if not isinstance(embeddings, list):
        raise ValueError("Invalid embeddings file: missing `embeddings` list.")

    if len(loaded_chunks) != len(chunks):
        raise ValueError(
            f"Embeddings file has {len(loaded_chunks)} chunks, but the current document produced {len(chunks)} chunks.")

    for i, (loaded_chunk, current_chunk) in enumerate(zip(loaded_chunks, chunks)):
        if loaded_chunk != current_chunk:
            raise ValueError(
                f"Embeddings file does not match the current document. Chunk {i} is different.")

    normalized_embeddings = [
        normalize_vector(as_vector(x))
        for x in embeddings
    ]

    print(f"[OK] Loaded embeddings from `{input_path}`")
    return normalized_embeddings


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
            "Keep the answer concise. "
            "Do not use Markdown formatting. "
            "Do not use bullet points. "
            "Use plain text only."
        )
    )

    dialog.add_human_request(
        f"Document excerpts:\n\n{context}\n\n"
        f"Question:\n{question}"
    )

    return dialog.prompt()


def sanitize_for_orca(text: str, valid_characters: Set[str]) -> str:
    valid_character_set = set(valid_characters)

    replacements = {
        "\n": " ",
        "\r": " ",
        "\t": " ",
        "“": '"',
        "”": '"',
        "‘": "'",
        "’": "'",
        "—": "-",
        "–": "-",
        "…": "...",
    }

    text = "".join(replacements.get(x, x) for x in text)
    text = "".join(x if x in valid_character_set else " " for x in text)
    text = re.sub(r"\s+", " ", text)

    return text


def stream_answer(
        chat_llm: picollm.PicoLLM,
        orca: pvorca.Orca,
        speaker: PvSpeaker,
        prompt: str,
        completion_token_limit: int,
) -> str:
    text_queue: queue.Queue[Optional[str]] = queue.Queue()
    tts_error_queue: queue.Queue[BaseException] = queue.Queue()

    answer = ""
    answer_lock = Lock()

    pending_speech_text = ""
    pending_speech_lock = Lock()

    sentence_end_pattern = re.compile(r"([.!?;:])\s+")

    def get_answer() -> str:
        with answer_lock:
            if len(answer) == 0:
                return "[A] (thinking)"
            return f"[A] {answer}"

    def pop_speakable_text(force: bool = False) -> str:
        nonlocal pending_speech_text

        with pending_speech_lock:
            text = pending_speech_text

            if len(text.strip()) == 0:
                return ""

            last_end = -1
            for match in sentence_end_pattern.finditer(text):
                last_end = match.end()

            if last_end > 0:
                speakable = text[:last_end]
                pending_speech_text = text[last_end:]
                return speakable

            if len(text) >= 180:
                split = text.rfind(" ", 0, 180)
                if split > 60:
                    speakable = text[:split + 1]
                    pending_speech_text = text[split + 1:]
                    return speakable

            if force:
                speakable = text
                pending_speech_text = ""
                return speakable

            return ""

    def tts_worker() -> None:
        stream = None

        try:
            stream = orca.stream_open()

            while True:
                text = text_queue.get()
                if text is None:
                    break

                speech_text = sanitize_for_orca(
                    text=text,
                    valid_characters=orca.valid_characters)

                if len(speech_text.strip()) == 0:
                    continue

                pcm = stream.synthesize(speech_text)
                if pcm is not None and len(pcm) > 0:
                    speaker.flush(pcm)

            pcm = stream.flush()
            if pcm is not None and len(pcm) > 0:
                speaker.flush(pcm)

        except BaseException as e:
            tts_error_queue.put(e)

        finally:
            if stream is not None:
                stream.close()

    def on_llm_stream(text: str) -> None:
        nonlocal answer
        nonlocal pending_speech_text

        text = text.replace('<|eot_id|>', '')
        if len(text) == 0:
            return

        with answer_lock:
            answer += text

        with pending_speech_lock:
            pending_speech_text += text

        while True:
            speakable_text = pop_speakable_text(force=False)
            if len(speakable_text) == 0:
                break
            text_queue.put(speakable_text)

    answer_event, answer_thread = print_async(get_answer)
    tts_thread = Thread(target=tts_worker, daemon=True)
    tts_thread.start()

    try:
        completion = chat_llm.generate(
            prompt=prompt,
            completion_token_limit=completion_token_limit,
            stop_phrases={'<|eot_id|>'},
            stream_callback=on_llm_stream)

        final_answer = completion.completion.strip().replace('<|eot_id|>', '')

        with answer_lock:
            if len(final_answer) > 0:
                answer = final_answer
            elif len(answer.strip()) == 0:
                answer = "I don't know from the provided document."

        remaining_speech_text = pop_speakable_text(force=True)
        if len(remaining_speech_text) > 0:
            text_queue.put(remaining_speech_text)

        text_queue.put(None)
        tts_thread.join()

        if not tts_error_queue.empty():
            raise tts_error_queue.get()

        answer_event.set()
        answer_thread.join()

        with answer_lock:
            return answer.strip()

    except BaseException:
        text_queue.put(None)
        tts_thread.join(timeout=1.0)
        answer_event.set()
        answer_thread.join()
        raise


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
        default=3,
        help="Number of document chunks to retrieve for each question.")
    parser.add_argument(
        "--completion_token_limit",
        type=int,
        default=256,
        help="Maximum number of completion tokens generated by picoLLM.")
    parser.add_argument(
        "--chunk_size",
        type=int,
        default=1200,
        help="Maximum number of characters per document chunk.")
    parser.add_argument(
        "--chunk_overlap",
        type=int,
        default=250,
        help="Number of overlapping characters between adjacent chunks.")
    parser.add_argument(
        "--save_embeddings_path",
        help="Path to save generated document embeddings as JSON.")
    parser.add_argument(
        "--load_embeddings_path",
        help="Path to load document embeddings from JSON instead of regenerating them.")
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
    chunk_size = args.chunk_size
    chunk_overlap = args.chunk_overlap
    save_embeddings_path = args.save_embeddings_path
    load_embeddings_path = args.load_embeddings_path

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
            chunks = chunk_document(
                text=f.read(),
                chunk_size=chunk_size,
                chunk_overlap=chunk_overlap)

        print(f"[OK] Broke `{os.path.basename(document_path)}` into {len(chunks)} chunks")

        if load_embeddings_path is not None:
            embeddings = load_embeddings(
                path=load_embeddings_path,
                chunks=chunks)
        else:
            embeddings = generate_embeddings(
                embedding_llm=embedding_llm,
                chunks=chunks)
            print("[OK] Generated embeddings")

            if save_embeddings_path is not None:
                save_embeddings(
                    path=save_embeddings_path,
                    document_path=document_path,
                    chunk_size=chunk_size,
                    chunk_overlap=chunk_overlap,
                    chunks=chunks,
                    embeddings=embeddings)

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

            stream_answer(
                chat_llm=chat_llm,
                orca=orca,
                speaker=speaker,
                prompt=prompt,
                completion_token_limit=completion_token_limit)

    except KeyboardInterrupt:
        pass
    finally:
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
