import signal
import concurrent.futures
import time
from argparse import ArgumentParser
from collections import deque
from itertools import chain
from multiprocessing import (
    Pipe,
    Process,
)
from typing import (
    Optional,
    Sequence,
)

import picollm
import pvcheetah
import pvorca
import pvporcupine
from pvrecorder import PvRecorder
from pvspeaker import PvSpeaker


class RTFProfiler:
    def __init__(self, sample_rate: int) -> None:
        self._sample_rate = sample_rate
        self._compute_sec = 0.
        self._audio_sec = 0.
        self._tick_sec = 0.

    def tick(self) -> None:
        self._tick_sec = time.perf_counter()

    def tock(self, audio: Optional[Sequence[int]] = None) -> None:
        self._compute_sec += time.perf_counter() - self._tick_sec
        self._audio_sec += (len(audio) / self._sample_rate) if audio is not None else 0.

    def rtf(self) -> float:
        rtf = self._compute_sec / self._audio_sec
        self._compute_sec = 0.
        self._audio_sec = 0.
        return rtf


class TPSProfiler(object):
    def __init__(self) -> None:
        self._num_tokens = 0
        self._start_sec = 0.

    def tock(self) -> None:
        if self._start_sec == 0.:
            self._start_sec = time.perf_counter()
        else:
            self._num_tokens += 1

    def tps(self) -> float:
        tps = self._num_tokens / (time.perf_counter() - self._start_sec)
        self._num_tokens = 0
        self._start_sec = 0.
        return tps


class CompletionText(object):
    def __init__(self, stop_phrases: list) -> None:
        self.stop_phrases = stop_phrases
        self.start: int = 0
        self.text: str = ''
    def append(self, text: str) -> str:
        self.text += text
        end = len(self.text)

        for stop_phrase in self.stop_phrases:
            if stop_phrase in self.text:
                contains = self.text.index(stop_phrase)
                if end > contains:
                    end = contains
            for i in range(len(stop_phrase) - 1, 0, -1):
                if self.text.endswith(stop_phrase[:i]):
                    ends = len(self.text) - i
                    if end > ends:
                        end = ends
                    break

        start = self.start
        self.start = end
        return self.text[start:end]


def orca_worker(access_key: str, connection, warmup_sec: float, stream_frame_sec: int = 0.03) -> None:
    orca = pvorca.create(access_key=access_key)
    orca_stream = orca.stream_open()

    texts = list()
    pcm_deque = deque()
    warmup = [False]
    synthesize = False
    flush = False
    close = False
    interrupt = False
    utterance_end_sec = 0.
    delay_sec = [-1.]

    speaker = PvSpeaker(sample_rate=orca.sample_rate, bits_per_sample=16, buffer_size_secs=20)

    connection.send({'version': orca.version})

    orca_profiler = RTFProfiler(orca.sample_rate)

    def buffer_pcm(pcm_chunk: Optional[Sequence[int]]) -> None:
        if pcm_chunk is not None:
            if delay_sec[0] == -1:
                delay_sec[0] = time.perf_counter() - utterance_end_sec

            pcm_deque.append(pcm_chunk)

    def play_buffered_pcm() -> None:
        if warmup[0]:
            if len(list(chain.from_iterable(pcm_deque))) < int(warmup_sec * orca.sample_rate):
                return
            else:
                warmup[0] = False

        if len(pcm_deque) > 0:
            pcm_chunk = list(chain.from_iterable(pcm_deque))
            pcm_deque.clear()

            written = speaker.write(pcm_chunk)
            if written < len(pcm_chunk):
                pcm_deque.appendleft(pcm_chunk[written:])

    while True:
        if synthesize and len(texts) > 0:
            orca_profiler.tick()
            pcm = orca_stream.synthesize(texts.pop(0))
            orca_profiler.tock(pcm)
            buffer_pcm(pcm)
            play_buffered_pcm()
        elif flush:
            while len(texts) > 0:
                orca_profiler.tick()
                pcm = orca_stream.synthesize(texts.pop(0))
                orca_profiler.tock(pcm)
                buffer_pcm(pcm)
                play_buffered_pcm()
            orca_profiler.tick()
            pcm = orca_stream.flush()
            orca_profiler.tock(pcm)
            buffer_pcm(pcm)
            play_buffered_pcm()
            connection.send({'rtf': orca_profiler.rtf(), 'delay': delay_sec[0]})
            flush = False
            speaker.flush(list(chain.from_iterable(pcm_deque)))
            pcm_deque.clear()
            speaker.stop()
            delay_sec[0] = -1
            connection.send({'done': True})
        elif close:
            break
        elif interrupt:
            orca_profiler.tick()
            pcm = orca_stream.flush()
            orca_profiler.tock(pcm)
            connection.send({'rtf': orca_profiler.rtf(), 'delay': delay_sec[0]})
            interrupt = False
            pcm_deque.clear()
            speaker.stop()
            delay_sec[0] = -1
            connection.send({'done': True})
        else:
            time.sleep(stream_frame_sec)

        while connection.poll():
            message = connection.recv()
            if message['command'] == 'synthesize':
                texts.append(message['text'])
                if not speaker.is_started:
                    speaker.start()
                    warmup[0] = True
                utterance_end_sec = message['utterance_end_sec']
                synthesize = True
            elif message['command'] == 'flush':
                synthesize = False
                flush = True
            elif message['command'] == 'close':
                close = True
            elif message['command'] == 'interrupt':
                interrupt = True

    speaker.delete()
    orca_stream.close()
    orca.delete()


def main() -> None:
    parser = ArgumentParser()
    parser.add_argument(
        '--access_key',
        required=True,
        help='`AccessKey` obtained from `Picovoice Console` (https://console.picovoice.ai/).')
    parser.add_argument(
        '--picollm_model_path',
        required=True,
        help='Absolute path to the file containing LLM parameters (`.pllm`).')
    parser.add_argument(
        '--keyword-model_path',
        help='Absolute path to the keyword model file (`.ppn`). If not set, `Picovoice` will be the wake phrase')
    parser.add_argument(
        '--cheetah_endpoint_duration_sec',
        type=float,
        default=1.,
        help="Duration of silence (pause) after the user's utterance to consider it the end of the utterance.")
    parser.add_argument(
        '--picollm_device',
        help="String representation of the device (e.g., CPU or GPU) to use for inference. If set to `best`, picoLLM "
             "picks the most suitable device. If set to `gpu`, the engine uses the first available GPU device. To "
             "select a specific GPU device, set this argument to `gpu:${GPU_INDEX}`, where `${GPU_INDEX}` is the index "
             "of the target GPU. If set to `cpu`, the engine will run on the CPU with the default number of threads. "
             "To specify the number of threads, set this argument to `cpu:${NUM_THREADS}`, where `${NUM_THREADS}` is "
             "the desired number of threads.")
    parser.add_argument(
        '--picollm_completion_token_limit',
        type=int,
        default=256,
        help="Maximum number of tokens in the completion. Set to `None` to impose no limit.")
    parser.add_argument(
        '--picollm_presence_penalty',
        type=float,
        default=0.,
        help="It penalizes logits already appearing in the partial completion if set to a positive value. If set to "
             "`0.0`, it has no effect.")
    parser.add_argument(
        '--picollm_frequency_penalty',
        type=float,
        default=0.,
        help="If set to a positive floating-point value, it penalizes logits proportional to the frequency of their "
             "appearance in the partial completion. If set to `0.0`, it has no effect.")
    parser.add_argument(
        '--picollm_temperature',
        type=float,
        default=0.,
        help="Sampling temperature. Temperature is a non-negative floating-point value that controls the randomness of "
             "the sampler. A higher temperature smoothens the samplers' output, increasing the randomness. In "
             "contrast, a lower temperature creates a narrower distribution and reduces variability. Setting it to "
             "`0` selects the maximum logit during sampling.")
    parser.add_argument(
        '--picollm_top_p',
        type=float,
        default=1.,
        help="A positive floating-point number within (0, 1]. It restricts the sampler's choices to high-probability "
             "logits that form the `top_p` portion of the probability mass. Hence, it avoids randomly selecting "
             "unlikely logits. A value of `1.` enables the sampler to pick any token with non-zero probability, "
             "turning off the feature.")
    parser.add_argument(
        '--orca_warmup_sec',
        type=float,
        default=0.,
        help="Duration of the synthesized audio to buffer before streaming it out. A higher value helps slower "
             "(e.g., Raspberry Pi) to keep up with real-time at the cost of increasing the initial delay.")
    parser.add_argument('--profile', action='store_true', help='Show runtime profiling information.')
    parser.add_argument('--short_answers', action='store_true')
    args = parser.parse_args()

    access_key = args.access_key
    picollm_model_path = args.picollm_model_path
    keyword_model_path = args.keyword_model_path
    cheetah_endpoint_duration_sec = args.cheetah_endpoint_duration_sec
    picollm_device = args.picollm_device
    picollm_completion_token_limit = args.picollm_completion_token_limit
    picollm_presence_penalty = args.picollm_presence_penalty
    picollm_frequency_penalty = args.picollm_frequency_penalty
    picollm_temperature = args.picollm_temperature
    picollm_top_p = args.picollm_top_p
    orca_warmup_sec = args.orca_warmup_sec
    profile = args.profile
    short_answers = args.short_answers

    if keyword_model_path is None:
        porcupine = pvporcupine.create(access_key=access_key, keywords=['picovoice'])
    else:
        porcupine = pvporcupine.create(access_key=access_key, keyword_paths=[keyword_model_path])
    print(f"→ Porcupine v{porcupine.version}")

    cheetah = pvcheetah.create(
        access_key=access_key,
        endpoint_duration_sec=cheetah_endpoint_duration_sec,
        enable_automatic_punctuation=True)
    print(f"→ Cheetah v{cheetah.version}")

    pllm = picollm.create(access_key=access_key, model_path=picollm_model_path, device=picollm_device)
    dialog = pllm.get_dialog()
    print(f"→ picoLLM v{pllm.version} <{pllm.model}>")

    main_connection, orca_process_connection = Pipe()
    orca_process = Process(target=orca_worker, args=(access_key, orca_process_connection, orca_warmup_sec))
    orca_process.start()
    while not main_connection.poll():
        time.sleep(0.01)
    print(f"→ Orca v{main_connection.recv()['version']}")

    mic = PvRecorder(frame_length=porcupine.frame_length)
    mic.start()

    print(f"\n$ Say {'`Picovoice`' if keyword_model_path is None else 'the wake word'} ...")

    stop = [False]

    def handler(_, __) -> None:
        stop[0] = True

    signal.signal(signal.SIGINT, handler)

    def llm_task(dialog, user_request, utterance_end_sec, main_connection):
        short_answers_instruction = \
            "You are a voice assistant and your answers are very short but informative"
        dialog.add_human_request(
            f"{short_answers_instruction}. {user_request}" if short_answers else user_request)

        picollm_profiler = TPSProfiler()

        stop_phrases = {
            '</s>',  # Llama-2, Mistral, and Mixtral
            '<end_of_turn>',  # Gemma
            '<|endoftext|>',  # Phi-2
            '<|eot_id|>',  # Llama-3
            '<|end|>', '<|user|>', '<|assistant|>',  # Phi-3
        }

        completion = CompletionText(stop_phrases)

        def llm_callback(text: str) -> None:
            picollm_profiler.tock()
            diff = completion.append(text)
            if len(diff) > 0:
                main_connection.send({
                    'command': 'synthesize',
                    'text': diff.replace('\n', ' . '),
                    'utterance_end_sec': utterance_end_sec})
                print(f'{diff}', end='', flush=True)

        print(
            f"\nLLM (say {'`Picovoice`' if keyword_model_path is None else 'the wake word'} to interrupt) > ",
            end='',
            flush=True)
        res = pllm.generate(
            prompt=dialog.prompt(),
            completion_token_limit=picollm_completion_token_limit,
            stop_phrases=stop_phrases,
            presence_penalty=picollm_presence_penalty,
            frequency_penalty=picollm_frequency_penalty,
            temperature=picollm_temperature,
            top_p=picollm_top_p,
            stream_callback=llm_callback)

        if res.endpoint == picollm.PicoLLMEndpoints.INTERRUPTED:
            main_connection.send({'command': 'interrupt'})
        else:
            main_connection.send({'command': 'flush'})

        print('\n')
        dialog.add_llm_response(res.completion)

        if profile:
            print(f"[picoLLM TPS: {picollm_profiler.tps():.2f}]")

        while not main_connection.poll():
            time.sleep(0.01)
        message = main_connection.recv()
        if profile:
            print(f"[Orca RTF: {message['rtf']:.2f}]")
            print(f"[Delay: {message['delay']:.2f} sec]")
        while not main_connection.poll():
            time.sleep(0.01)
        assert main_connection.recv()['done']

        return res

    wake_word_detected = False
    user_request = ''
    endpoint_reached = False

    porcupine_profiler = RTFProfiler(porcupine.sample_rate)
    cheetah_profiler = RTFProfiler(cheetah.sample_rate)

    try:
        while True:
            if stop[0]:
                break
            elif not wake_word_detected:
                pcm = mic.read()
                porcupine_profiler.tick()
                wake_word_detected = porcupine.process(pcm) == 0
                porcupine_profiler.tock(pcm)
                if wake_word_detected:
                    if profile:
                        print(f"[Porcupine RTF: {porcupine_profiler.rtf():.3f}]")
                    print("$ Wake word detected, utter your request or question ...\n")
                    print("User > ", end='', flush=True)
            elif not endpoint_reached:
                pcm = mic.read()
                cheetah_profiler.tick()
                partial_transcript, endpoint_reached = cheetah.process(pcm)
                cheetah_profiler.tock(pcm)
                print(partial_transcript, end='', flush=True)
                user_request += partial_transcript
                if endpoint_reached:
                    utterance_end_sec = time.perf_counter()
                    cheetah_profiler.tick()
                    remaining_transcript = cheetah.flush()
                    cheetah_profiler.tock()
                    user_request += remaining_transcript
                    print(remaining_transcript, end='\n')
                    if profile:
                        print(f"[Cheetah RTF: {cheetah_profiler.rtf():.3f}]")
                    with concurrent.futures.ThreadPoolExecutor() as executor:
                        llm_future = executor.submit(
                            llm_task,
                            dialog,
                            user_request,
                            utterance_end_sec,
                            main_connection)

                        while not llm_future.done():
                            pcm = mic.read()
                            porcupine_profiler.tick()
                            wake_word_detected = porcupine.process(pcm) == 0
                            porcupine_profiler.tock(pcm)
                            if wake_word_detected:
                                pllm.interrupt()
                                break

                        llm_result = llm_future.result()
                        if llm_result.endpoint == picollm.PicoLLMEndpoints.INTERRUPTED:
                            wake_word_detected = True
                            print("$ Wake word detected, utter your request or question ...\n")
                            print("User > ", end='', flush=True)
                        else:
                            wake_word_detected = False
                            print(f"$ Say {'`Picovoice`' if keyword_model_path is None else 'the wake word'} ...")
                        user_request = ''
                        endpoint_reached = False

    finally:
        main_connection.send({'command': 'close'})
        mic.delete()
        pllm.release()
        cheetah.delete()
        porcupine.delete()
        orca_process.join()


if __name__ == '__main__':
    main()
