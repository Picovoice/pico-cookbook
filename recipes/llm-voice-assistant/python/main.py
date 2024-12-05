import signal
import concurrent.futures
import time
from argparse import ArgumentParser
from collections import deque
from multiprocessing import Process, Queue
from typing import Optional, Sequence

import picollm
import pvcheetah
import pvorca
import pvporcupine
from pvrecorder import PvRecorder
from pvspeaker import PvSpeaker


class Commands:
    INIT = 'init'
    CLOSE = 'close'
    START = 'start'
    INTERRUPT = 'interrupt'
    TEXT = 'text'
    GENERATE = 'generate'
    SYNTHESIZE_START = 'synthesize-start'
    SYNTHESIZE = 'synthesize'
    SYNTHESIZE_FLUSH = 'synthesize-flush'
    PROFILE = 'profile'


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
        if self._audio_sec > 0:
            rtf = self._compute_sec / self._audio_sec
        else:
            rtf = 0
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
        self.new_tokens: str = ''

    def reset(self):
        self.start: int = 0
        self.text: str = ''
        self.new_tokens: str = ''

    def append(self, text: str) -> None:
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
        self.new_tokens = self.text[start:end]

    def get_new_tokens(self) -> str:
        return self.new_tokens


def listen_worker(main_queue, listen_queue, access_key, keyword_model_path, cheetah_endpoint_duration_sec):
    def handler(_, __) -> None:
        main_queue.put({'command': Commands.CLOSE})

    signal.signal(signal.SIGINT, handler)

    if keyword_model_path is None:
        porcupine = pvporcupine.create(access_key=access_key, keywords=['picovoice'])
    else:
        porcupine = pvporcupine.create(access_key=access_key, keyword_paths=[keyword_model_path])
    porcupine_profiler = RTFProfiler(porcupine.sample_rate)

    main_queue.put({'command': Commands.INIT, 'name': 'Porcupine', 'version': porcupine.version})

    cheetah = pvcheetah.create(
        access_key=access_key,
        endpoint_duration_sec=cheetah_endpoint_duration_sec,
        enable_automatic_punctuation=True)
    cheetah_profiler = RTFProfiler(cheetah.sample_rate)

    main_queue.put({'command': Commands.INIT, 'name': 'Cheetah', 'version': cheetah.version})

    mic = PvRecorder(frame_length=porcupine.frame_length)
    mic.start()

    main_queue.put({'command': Commands.INIT, 'name': 'PvRecorder', 'version': mic.version})

    while listen_queue.empty():
        time.sleep(0.01)

    try:
        close = False
        listening = False
        user_request = ''
        while not close:
            if listen_queue.empty():
                time.sleep(0.01)

            while not listen_queue.empty():
                message = listen_queue.get()
                if message['command'] == Commands.CLOSE:
                    close = True

            pcm = mic.read()
            if not listening:
                porcupine_profiler.tick()
                wake_word_detected = porcupine.process(pcm) == 0
                porcupine_profiler.tock(pcm)
                if wake_word_detected:
                    listening = True
                    main_queue.put({
                        'command': Commands.PROFILE,
                        'text': f"[Porcupine RTF: {porcupine_profiler.rtf():.3f}]"})
                    main_queue.put({'command': Commands.INTERRUPT})
            else:
                cheetah_profiler.tick()
                partial_transcript, endpoint_reached = cheetah.process(pcm)
                cheetah_profiler.tock(pcm)
                if len(partial_transcript) > 0:
                    user_request += partial_transcript
                    main_queue.put({'command': Commands.TEXT, 'text': partial_transcript})
                if endpoint_reached:
                    utterance_end_sec = time.perf_counter()
                    cheetah_profiler.tick()
                    remaining_transcript = cheetah.flush()
                    cheetah_profiler.tock(pcm)
                    user_request += remaining_transcript
                    main_queue.put({'command': Commands.TEXT, 'text': remaining_transcript})
                    main_queue.put({
                        'command': Commands.GENERATE,
                        'text': user_request,
                        'utterance_end_sec': utterance_end_sec})
                    main_queue.put({
                        'command': Commands.PROFILE,
                        'text': f"[Cheetah RTF: {cheetah_profiler.rtf():.3f}]"})
                    user_request = ''
                    listening = False
    finally:
        porcupine.delete()
        cheetah.delete()
        mic.delete()


def generate_worker(
        main_queue,
        generate_queue,
        access_key,
        picollm_model_path,
        picollm_device,
        picollm_completion_token_limit,
        picollm_presence_penalty,
        picollm_frequency_penalty,
        picollm_temperature,
        picollm_top_p,
        short_answers):
    def handler(_, __) -> None:
        main_queue.put({'command': Commands.CLOSE})

    signal.signal(signal.SIGINT, handler)

    pllm = picollm.create(access_key=access_key, model_path=picollm_model_path, device=picollm_device)
    pllm_profiler = TPSProfiler()
    dialog = pllm.get_dialog()
    generating = False

    main_queue.put({'command': Commands.INIT, 'name': 'picoLLM', 'version': f"{pllm.version} <{pllm.model}>"})

    stop_phrases = {
        '</s>',  # Llama-2, Mistral, and Mixtral
        '<end_of_turn>',  # Gemma
        '<|endoftext|>',  # Phi-2
        '<|eot_id|>',  # Llama-3
        '<|end|>', '<|user|>', '<|assistant|>',  # Phi-3
    }

    completion = CompletionText(stop_phrases)

    def llm_callback(text: str):
        pllm_profiler.tock()
        completion.append(text)
        new_tokens = completion.get_new_tokens()
        if len(new_tokens) > 0 and generating:
            main_queue.put({'command': Commands.SYNTHESIZE, 'text': new_tokens})

    def llm_task(user_request, utterance_end_sec):
        short_answers_instruction = \
            "You are a voice assistant and your answers are very short but informative"
        dialog.add_human_request(
            f"{short_answers_instruction}. {user_request}" if short_answers else user_request)

        main_queue.put({'command': Commands.SYNTHESIZE_START, 'utterance_end_sec': utterance_end_sec})

        res = pllm.generate(
            prompt=dialog.prompt(),
            completion_token_limit=picollm_completion_token_limit,
            stop_phrases=stop_phrases,
            presence_penalty=picollm_presence_penalty,
            frequency_penalty=picollm_frequency_penalty,
            temperature=picollm_temperature,
            top_p=picollm_top_p,
            stream_callback=llm_callback)

        dialog.add_llm_response(res.completion)

        if res.endpoint != picollm.PicoLLMEndpoints.INTERRUPTED:
            main_queue.put({'command': Commands.SYNTHESIZE_FLUSH})

        main_queue.put({'command': Commands.PROFILE, 'text': f"[picoLLM TPS: {pllm_profiler.tps():.2f}]"})

        return res

    executor = concurrent.futures.ThreadPoolExecutor()

    while generate_queue.empty():
        time.sleep(0.01)

    try:
        close = False
        llm_future = None
        while not close:
            if generate_queue.empty():
                time.sleep(0.01)

            while not generate_queue.empty():
                message = generate_queue.get()
                if message['command'] == Commands.CLOSE:
                    close = True
                elif message['command'] == Commands.GENERATE:
                    generating = True
                    completion.reset()
                    llm_future = executor.submit(
                        llm_task,
                        message['text'],
                        message['utterance_end_sec'])
                elif message['command'] == Commands.INTERRUPT and generating:
                    generating = False
                    pllm.interrupt()

                if llm_future and llm_future.done():
                    llm_future = None
                    generating = False
    finally:
        while llm_future and not llm_future.done():
            time.sleep(0.01)

        executor.shutdown(True)
        pllm.release()


def speak_worker(main_queue, speak_queue, access_key, warmup_sec):
    def handler(_, __) -> None:
        main_queue.put({'command': Commands.CLOSE})

    signal.signal(signal.SIGINT, handler)

    orca = pvorca.create(access_key=access_key)
    orca_stream = orca.stream_open()
    orca_profiler = RTFProfiler(orca.sample_rate)
    warmup_size = int(warmup_sec * orca.sample_rate)

    main_queue.put({'command': Commands.INIT, 'name': 'Orca', 'version': orca.version})

    speaker = PvSpeaker(sample_rate=orca.sample_rate, bits_per_sample=16, buffer_size_secs=1)

    main_queue.put({'command': Commands.INIT, 'name': 'PvSpeaker', 'version': speaker.version})

    while speak_queue.empty():
        time.sleep(0.01)

    try:
        close = False
        synthesizing = False
        speaking = False
        flush = False
        text_queue = deque()
        pcm_queue = list()
        delay_sec = -1
        utterance_end_sec = 0
        while not close:
            if speak_queue.empty():
                time.sleep(0.01)

            while not speak_queue.empty():
                message = speak_queue.get()
                if message['command'] == Commands.CLOSE:
                    close = True
                elif message['command'] == Commands.SYNTHESIZE_START:
                    synthesizing = True
                    utterance_end_sec = message['utterance_end_sec']
                    delay_sec = -1
                elif message['command'] == Commands.SYNTHESIZE:
                    text_queue.append(message['text'].replace('\n', ' . '))
                elif message['command'] == Commands.INTERRUPT:
                    if synthesizing:
                        orca_profiler.tick()
                        pcm = orca_stream.flush()
                        orca_profiler.tock(pcm)
                        main_queue.put({
                            'command': Commands.PROFILE,
                            'text': f"[Orca RTF: {orca_profiler.rtf():.2f}]\n[Delay: {delay_sec:.2f} sec]"})
                    if speaking:
                        speaker.stop()
                    text_queue.clear()
                    pcm_queue.clear()
                    synthesizing = False
                    speaking = False
                    flush = False
                elif message['command'] == Commands.SYNTHESIZE_FLUSH:
                    flush = True

            while len(text_queue) > 0:
                text = text_queue.popleft()
                orca_profiler.tick()
                pcm = orca_stream.synthesize(text)
                orca_profiler.tock(pcm)
                if pcm is not None:
                    if delay_sec == -1:
                        delay_sec = time.perf_counter() - utterance_end_sec
                    pcm_queue.extend(pcm)

            if flush and synthesizing:
                orca_profiler.tick()
                pcm = orca_stream.flush()
                orca_profiler.tock(pcm)
                synthesizing = False
                if pcm is not None:
                    pcm_queue.extend(pcm)
                main_queue.put({
                    'command': Commands.PROFILE,
                    'text': f"[Orca RTF: {orca_profiler.rtf():.2f}]\n[Delay: {delay_sec:.2f} sec]"})

            if not speaking and len(pcm_queue) > warmup_size:
                speaker.start()
                speaking = True

            if speaking and len(pcm_queue) > 0:
                written = speaker.write(pcm_queue)
                if written > 0:
                    del pcm_queue[:written]

            if speaking and flush and len(pcm_queue) == 0:
                speaker.flush(pcm_queue)
                speaker.stop()
                speaking = False
                flush = False
                main_queue.put({'command': Commands.START})
    finally:
        orca_stream.close()
        orca.delete()
        speaker.delete()


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

    main_queue = Queue()
    listen_queue = Queue()
    generate_queue = Queue()
    speak_queue = Queue()

    listen_process = Process(target=listen_worker, args=(
        main_queue,
        listen_queue,
        access_key,
        keyword_model_path,
        cheetah_endpoint_duration_sec
    ))
    generate_process = Process(target=generate_worker, args=(
        main_queue,
        generate_queue,
        access_key,
        picollm_model_path,
        picollm_device,
        picollm_completion_token_limit,
        picollm_presence_penalty,
        picollm_frequency_penalty,
        picollm_temperature,
        picollm_top_p,
        short_answers
    ))
    speak_process = Process(target=speak_worker, args=(
        main_queue,
        speak_queue,
        access_key,
        orca_warmup_sec
    ))

    def handler(_, __) -> None:
        main_queue.put({'command': Commands.CLOSE})

    signal.signal(signal.SIGINT, handler)

    generate_process.start()
    listen_process.start()
    speak_process.start()

    modules = [
        'Porcupine',
        'Cheetah',
        'PvRecorder',
        'picoLLM',
        'Orca',
        'PvSpeaker'
    ]

    try:
        close = False
        listening = False
        generating = False
        while not close:
            while main_queue.empty():
                time.sleep(0.01)

            message = main_queue.get(block=True)
            if message['command'] == Commands.CLOSE:
                close = True
            elif message['command'] == Commands.INIT:
                print(f"→ {message['name']} v{message['version']}")
                modules.remove(message['name'])
                if len(modules) == 0:
                    main_queue.put({'command': Commands.START})
                    listen_queue.put({'command': Commands.START})
                    generate_queue.put({'command': Commands.START})
                    speak_queue.put({'command': Commands.START})
            elif message['command'] == Commands.START:
                if not listening:
                    print(f"$ Say {'`Picovoice`' if keyword_model_path is None else 'the wake word'} ...")
            elif message['command'] == Commands.INTERRUPT:
                if generating:
                    print()
                    generating = False
                print("$ Wake word detected, utter your request or question ...")
                print("User > ", end='', flush=True)
                generate_queue.put(message)
                speak_queue.put(message)
                listening = True
            elif message['command'] == Commands.TEXT:
                print(message['text'], end='', flush=True)
            elif message['command'] == Commands.GENERATE:
                print()
                generate_queue.put(message)
                listening = False
            elif message['command'] == Commands.SYNTHESIZE_START:
                wake_word = '`Picovoice`' if keyword_model_path is None else 'the wake word'
                print(f"LLM (say {wake_word} to interrupt) > ", end='', flush=True)
                speak_queue.put(message)
                generating = True
            elif message['command'] == Commands.SYNTHESIZE:
                print(message['text'], end='', flush=True)
                speak_queue.put(message)
            elif message['command'] == Commands.SYNTHESIZE_FLUSH:
                print()
                speak_queue.put(message)
                generating = False
            elif message['command'] == Commands.PROFILE:
                if profile:
                    print(message['text'])
    finally:
        generate_queue.put({'command': Commands.INTERRUPT})
        speak_queue.put({'command': Commands.INTERRUPT})

        listen_queue.put({'command': Commands.CLOSE})
        generate_queue.put({'command': Commands.CLOSE})
        speak_queue.put({'command': Commands.CLOSE})

        listen_process.join()
        generate_process.join()
        speak_process.join()


if __name__ == '__main__':
    main()
