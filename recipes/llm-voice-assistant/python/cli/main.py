import json
import os
import signal
import sys
import time
from argparse import ArgumentParser
from itertools import chain
from multiprocessing import Pipe, Process, Queue, active_children
# noinspection PyProtectedMember
from multiprocessing.connection import Connection
from threading import Thread
from typing import Optional, Sequence, Set


import picollm
import pvcheetah
import pvorca
import pvporcupine
from pvrecorder import PvRecorder
from pvspeaker import PvSpeaker


class Commands:
    START = 'start'
    CLOSE = 'close'
    PROCESS = 'process'
    SYNTHESIZE = 'synthesize'
    SPEAK = 'speak'
    FLUSH = 'flush'
    INTERRUPT = 'interrupt'


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

    def reset(self) -> None:
        self._compute_sec = 0.
        self._audio_sec = 0.
        self._tick_sec = 0.


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

    def reset(self) -> None:
        self._num_tokens = 0
        self._start_sec = 0.


class CompletionText(object):
    def __init__(self, stop_phrases: Set[str]) -> None:
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


class Speaker:
    def __init__(
            self,
            speaker: PvSpeaker,
            config):
        self.speaker = speaker
        self.config = config
        self.orca_warmup = self.speaker.sample_rate * self.config['orca_warmup_sec']
        self.started = False
        self.speaking = False
        self.flushing = False
        self.pcm_buffer = []
        self.future = None

    def close(self):
        self.interrupt()

    def start(self):
        self.started = True

    def process(self, pcm: Optional[Sequence[int]]):
        if self.started and pcm is not None:
            self.pcm_buffer.extend(pcm)

    def flush(self):
        self.flushing = True

    def interrupt(self):
        self.started = False
        if self.speaking:
            self.speaking = False
            self.flushing = False
            self.pcm_buffer.clear()
            self.speaker.stop()

    def tick(self):
        def stop():
            self.speaker.flush()
            self.speaker.stop()
            ppn_prompt = self.config['ppn_prompt']
            print(f'$ Say {ppn_prompt} ...', flush=True)
        if not self.speaking and len(self.pcm_buffer) > self.orca_warmup:
            self.speaking = True
            self.speaker.start()
        if self.speaking and len(self.pcm_buffer) > 0:
            written = self.speaker.write(self.pcm_buffer)
            if written > 0:
                del self.pcm_buffer[:written]
        elif self.speaking and self.flushing and len(self.pcm_buffer) == 0:
            self.started = False
            self.speaking = False
            self.flushing = False
            Thread(target=stop).start()


class Synthesizer:
    def __init__(
            self,
            speaker: Speaker,
            orca_connection: Connection,
            orca_process: Process,
            config):
        self.speaker = speaker
        self.orca_connection = orca_connection
        self.orca_process = orca_process
        self.config = config

    def close(self):
        try:
            self.orca_connection.send({'command': Commands.CLOSE})
            self.orca_process.join(1.0)
        except Exception as e:
            sys.stderr.write(str(e))
            self.orca_process.kill()

    def start(self, utterance_end_sec):
        self.speaker.start()
        self.orca_connection.send({'command': Commands.START, 'utterance_end_sec': utterance_end_sec})

    def process(self, text: str):
        self.orca_connection.send({'command': Commands.PROCESS, 'text': text})

    def flush(self):
        self.orca_connection.send({'command': Commands.FLUSH})

    def interrupt(self):
        try:
            self.orca_connection.send({'command': Commands.INTERRUPT})
            self.speaker.interrupt()
        except Exception as e:
            sys.stderr.write(str(e))

    def tick(self):
        while self.orca_connection.poll():
            message = self.orca_connection.recv()
            if message['command'] == Commands.SPEAK:
                self.speaker.process(message['pcm'])
            elif message['command'] == Commands.FLUSH:
                if self.config['profile']:
                    rtf = message['profile']
                    delay = message['delay']
                    print(f'[Orca RTF: {round(rtf, 2)}]')
                    print(f"[Delay: {round(delay, 2)} sec]")
                self.speaker.flush()

    @staticmethod
    def create_worker(config):
        main_connection, process_connection = Pipe()
        process = Process(target=Synthesizer.worker, args=(process_connection, config))
        process.start()
        return main_connection, process

    @staticmethod
    def worker(connection: Connection, config):
        def handler(_, __) -> None:
            pass
        signal.signal(signal.SIGINT, handler)

        orca = pvorca.create(access_key=config['access_key'])
        orca_stream = orca.stream_open(speech_rate=config['orca_speech_rate'])
        connection.send(orca.sample_rate)
        connection.send({'version': orca.version})

        orca_profiler = RTFProfiler(orca.sample_rate)
        utterance_end_sec = 0
        delay_sec = -1

        try:
            close = False
            synthesizing = False
            flushing = False
            text_queue = Queue()
            while not close:
                time.sleep(0.1)
                while connection.poll():
                    message = connection.recv()
                    if message['command'] == Commands.CLOSE:
                        close = True
                        synthesizing = False
                        flushing = False
                        while not text_queue.empty():
                            text_queue.get()
                    elif message['command'] == Commands.START:
                        synthesizing = True
                        utterance_end_sec = message['utterance_end_sec']
                    elif message['command'] == Commands.PROCESS:
                        if synthesizing:
                            text_queue.put(message['text'])
                    elif message['command'] == Commands.FLUSH:
                        flushing = True
                    elif message['command'] == Commands.INTERRUPT:
                        synthesizing = False
                        flushing = False
                        while not text_queue.empty():
                            text_queue.get()
                        orca_stream.flush()
                        orca_profiler.reset()
                        utterance_end_sec = 0
                        delay_sec = -1
                while not text_queue.empty():
                    text = text_queue.get()
                    if synthesizing:
                        orca_profiler.tick()
                        pcm = orca_stream.synthesize(text)
                        orca_profiler.tock(pcm)
                        if pcm is not None:
                            connection.send({'command': Commands.SPEAK, 'pcm': pcm})
                            if delay_sec == -1:
                                delay_sec = time.perf_counter() - utterance_end_sec
                if synthesizing and flushing and text_queue.empty():
                    synthesizing = False
                    flushing = False
                    orca_profiler.tick()
                    pcm = orca_stream.flush()
                    orca_profiler.tock(pcm)
                    connection.send({'command': Commands.SPEAK, 'pcm': pcm})
                    connection.send({'command': Commands.FLUSH, 'profile': orca_profiler.rtf(), 'delay': delay_sec})
                    utterance_end_sec = 0
                    delay_sec = -1
                elif flushing:
                    flushing = False
        finally:
            orca_stream.close()
            orca.delete()


class Generator:
    def __init__(
            self,
            synthesizer: Synthesizer,
            pllm_connection: Connection,
            pllm_process: Process,
            config):
        self.synthesizer = synthesizer
        self.pllm_connection = pllm_connection
        self.pllm_process = pllm_process
        self.config = config

    def close(self):
        try:
            self.pllm_connection.send({'command': Commands.CLOSE})
            self.pllm_process.join(1.0)
        except Exception as e:
            sys.stderr.write(str(e))
            self.pllm_process.kill()

    def process(self, text: str, utterance_end_sec):
        ppn_prompt = self.config['ppn_prompt']
        print(f'LLM (say {ppn_prompt} to interrupt) > ', end='', flush=True)

        self.synthesizer.start(utterance_end_sec)
        self.pllm_connection.send({'command': Commands.PROCESS, 'text': text})

    def interrupt(self):
        self.pllm_connection.send({'command': Commands.INTERRUPT})
        self.synthesizer.interrupt()

    def tick(self):
        while self.pllm_connection.poll():
            message = self.pllm_connection.recv()
            if message['command'] == Commands.SYNTHESIZE:
                print(message['text'], end='', flush=True)
                self.synthesizer.process(message['text'])
            elif message['command'] == Commands.FLUSH:
                print('', flush=True)
                if self.config['profile']:
                    tps = message['profile']
                    print(f'[picoLLM TPS: {round(tps, 2)}]')
                self.synthesizer.flush()

    @staticmethod
    def create_worker(config):
        main_connection, process_connection = Pipe()
        process = Process(target=Generator.worker, args=(process_connection, config))
        process.start()
        return main_connection, process

    @staticmethod
    def worker(connection: Connection, config):
        def handler(_, __) -> None:
            pass
        signal.signal(signal.SIGINT, handler)

        pllm = picollm.create(
            access_key=config['access_key'],
            model_path=config['picollm_model_path'],
            device=config['picollm_device'])

        connection.send({'version': pllm.version, 'model': pllm.model})

        if config['picollm_system_prompt'] is not None:
            dialog = pllm.get_dialog(system=config['picollm_system_prompt'])
        else:
            dialog = pllm.get_dialog()

        pllm_profiler = TPSProfiler()

        stop_phrases = {
            '</s>',  # Llama-2, Mistral, and Mixtral
            '<end_of_turn>',  # Gemma
            '<|endoftext|>',  # Phi-2
            '<|eot_id|>',  # Llama-3
            '<|end|>', '<|user|>', '<|assistant|>',  # Phi-3
        }
        completion = CompletionText(stop_phrases)

        def llm_callback(text):
            pllm_profiler.tock()
            completion.append(text)
            new_tokens = completion.get_new_tokens()
            if len(new_tokens) > 0:
                connection.send({'command': Commands.SYNTHESIZE, 'text': new_tokens})

        close = [False]
        prompt = [None]

        def event_manager():
            while not close[0]:
                message = connection.recv()
                if message['command'] == Commands.CLOSE:
                    close[0] = True
                    pllm.interrupt()
                    return
                elif message['command'] == Commands.INTERRUPT:
                    pllm.interrupt()
                elif message['command'] == Commands.PROCESS:
                    prompt[0] = message['text']
        Thread(target=event_manager).start()

        try:
            while not close[0]:
                if prompt[0] is not None:
                    short_answers_instruction = \
                        "You are a voice assistant and your answers are very short but informative"
                    dialog.add_human_request(
                        f"{short_answers_instruction}. {prompt[0]}" if config['short_answers'] else prompt[0])
                    prompt[0] = None

                    completion.reset()
                    result = pllm.generate(
                        prompt=dialog.prompt(),
                        completion_token_limit=config['picollm_completion_token_limit'],
                        stop_phrases=stop_phrases,
                        presence_penalty=config['picollm_presence_penalty'],
                        frequency_penalty=config['picollm_frequency_penalty'],
                        temperature=config['picollm_temperature'],
                        top_p=config['picollm_top_p'],
                        stream_callback=llm_callback)

                    dialog.add_llm_response(result.completion)
                    if result.endpoint != picollm.PicoLLMEndpoints.INTERRUPTED:
                        connection.send({'command': Commands.FLUSH, 'profile': pllm_profiler.tps()})
                else:
                    time.sleep(0.25)
        finally:
            pllm.release()


class Listener:
    def __init__(
            self,
            generator: Generator,
            porcupine: pvporcupine.Porcupine,
            cheetah: pvcheetah.Cheetah,
            config):
        self.generator = generator
        self.porcupine = porcupine
        self.cheetah = cheetah
        self.config = config
        self.porcupine_profiler = RTFProfiler(porcupine.sample_rate)
        self.cheetah_profiler = RTFProfiler(cheetah.sample_rate)

        self.sleeping = True
        self.listening = False
        self.user_request = ''
        self.tick_count = 0

    def close(self):
        pass

    def process(self, pcm: Optional[Sequence[int]]):
        if self.sleeping:
            self.porcupine_profiler.tick()
            wake_word_detected = self.porcupine.process(pcm) == 0
            self.porcupine_profiler.tock(pcm)
            if wake_word_detected:
                self.sleeping = False
                self.tick_count = 4
                self.generator.interrupt()
                if self.config['profile']:
                    print(f'[Porcupine RTF: {round(self.porcupine_profiler.rtf(), 2)}]')
                self.porcupine_profiler.reset()
                self.cheetah_profiler.reset()
        elif self.listening:
            self.cheetah_profiler.tick()
            partial_transcript, endpoint_reached = self.cheetah.process(pcm)
            self.cheetah_profiler.tock(pcm)
            if len(partial_transcript) > 0:
                self.user_request += partial_transcript
                print(partial_transcript, end='', flush=True)
            if endpoint_reached:
                utterance_end_sec = time.perf_counter()
                self.sleeping = True
                self.listening = False
                self.cheetah_profiler.tick()
                remaining_transcript = self.cheetah.flush()
                self.cheetah_profiler.tock()
                if len(remaining_transcript) > 0:
                    self.user_request += remaining_transcript
                print(remaining_transcript, flush=True)
                if self.config['profile']:
                    print(f'[Cheetah RTF: {round(self.cheetah_profiler.rtf(), 2)}]')
                self.generator.process(self.user_request, utterance_end_sec)
                self.user_request = ''
        elif self.tick_count > 0:
            self.tick_count -= 1
        else:
            self.listening = True
            print('\n$ Wake word detected, utter your request or question ...', flush=True)
            print('User > ', end='', flush=True)


class Recorder:
    def __init__(
            self,
            listener: Listener,
            recorder: PvRecorder):
        self.listener = listener
        self.recorder = recorder
        self.recording = False

    def close(self):
        if self.recording:
            self.recorder.stop()

    def tick(self):
        if not self.recording:
            self.recording = True
            self.recorder.start()
        pcm = self.recorder.read()
        self.listener.process(pcm)


REQUIRED_ARGS = [
    'access_key',
    'picollm_model_path'
]
DEFAULT_ARGS = {
    'access_key': '',
    'picollm_model_path': '',
    'cheetah_endpoint_duration_sec': 1,
    'picollm_device': 'best',
    'picollm_completion_token_limit': 256,
    'picollm_presence_penalty': 0,
    'picollm_frequency_penalty': 0,
    'picollm_temperature': 0,
    'picollm_top_p': 1,
    'picollm_system_prompt': None,
    'orca_warmup_sec': 0,
    'orca_speech_rate': 1.0,
    'porcupine_sensitivity': 0.5,
    'short_answers': False,
    'profile': False
}


def main():
    parser = ArgumentParser()
    parser.add_argument(
        '--config',
        help='path to a json config file to load the arguments from')
    parser.add_argument(
        '--access_key',
        help='`AccessKey` obtained from `Picovoice Console` (https://console.picovoice.ai/).')
    parser.add_argument(
        '--picollm_model_path',
        help='Absolute path to the file containing LLM parameters (`.pllm`).')
    parser.add_argument(
        '--keyword_model_path',
        help='Absolute path to the keyword model file (`.ppn`). If not set, `Jarvis` will be the wake phrase')
    parser.add_argument(
        '--cheetah_endpoint_duration_sec',
        type=float,
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
        help="Maximum number of tokens in the completion. Set to `None` to impose no limit.")
    parser.add_argument(
        '--picollm_presence_penalty',
        type=float,
        help="It penalizes logits already appearing in the partial completion if set to a positive value. If set to "
             "`0.0`, it has no effect.")
    parser.add_argument(
        '--picollm_frequency_penalty',
        type=float,
        help="If set to a positive floating-point value, it penalizes logits proportional to the frequency of their "
             "appearance in the partial completion. If set to `0.0`, it has no effect.")
    parser.add_argument(
        '--picollm_temperature',
        type=float,
        help="Sampling temperature. Temperature is a non-negative floating-point value that controls the randomness of "
             "the sampler. A higher temperature smoothens the samplers' output, increasing the randomness. In "
             "contrast, a lower temperature creates a narrower distribution and reduces variability. Setting it to "
             "`0` selects the maximum logit during sampling.")
    parser.add_argument(
        '--picollm_top_p',
        type=float,
        help="A positive floating-point number within (0, 1]. It restricts the sampler's choices to high-probability "
             "logits that form the `top_p` portion of the probability mass. Hence, it avoids randomly selecting "
             "unlikely logits. A value of `1.` enables the sampler to pick any token with non-zero probability, "
             "turning off the feature.")
    parser.add_argument(
        '--picollm_system_prompt',
        type=str,
        help="A text prompt to give to the llm prior to it's input to instruct it on how to behave."
    )
    parser.add_argument(
        '--orca_warmup_sec',
        type=float,
        help="Duration of the synthesized audio to buffer before streaming it out. A higher value helps slower "
             "(e.g., Raspberry Pi) to keep up with real-time at the cost of increasing the initial delay.")
    parser.add_argument(
        '--orca_speech_rate',
        type=float,
        help="Rate of speech of the generated audio.")
    parser.add_argument(
        '--porcupine_sensitivity',
        type=float,
        help="Sensitivity for detecting keywords.")
    parser.add_argument('--short_answers', action='store_true')
    parser.add_argument('--profile', action='store_true', help='Show runtime profiling information.')
    args = parser.parse_args()

    if args.config is not None:
        config_path = os.path.realpath(args.config)
    else:
        config_path = os.path.join(os.path.dirname(os.path.realpath(__file__)), 'config.json')

    if os.path.exists(config_path):
        with open(config_path, 'r') as fd:
            config = json.load(fd)
    elif args.config is not None:
        parser.error(f'File {config_path} does not exist')
    else:
        config = {}

    for key in chain(REQUIRED_ARGS, DEFAULT_ARGS):
        arg = getattr(args, key)
        if arg is not None:
            config[key] = arg

    missing = [f'--{arg}' for arg in REQUIRED_ARGS if arg not in config]
    if len(missing) > 0:
        parser.error('the following arguments are required: ' + ', '.join(missing))

    for key in DEFAULT_ARGS:
        if key not in config:
            config[key] = DEFAULT_ARGS[key]

    stop = [False]

    def handler(_, __) -> None:
        stop[0] = True
    signal.signal(signal.SIGINT, handler)

    pllm_connection, pllm_process = Generator.create_worker(config)
    orca_connection, orca_process = Synthesizer.create_worker(config)

    if 'keyword_model_path' not in config:
        porcupine = pvporcupine.create(
            access_key=config['access_key'],
            keywords=['picovoice'],
            sensitivities=[config['porcupine_sensitivity']])
        config['ppn_prompt'] = '`Picovoice`'
    else:
        porcupine = pvporcupine.create(
            access_key=config['access_key'],
            keyword_paths=[config['keyword_model_path']],
            sensitivities=[config['porcupine_sensitivity']])
        config['ppn_prompt'] = 'the wake word'

    print(f"→ Porcupine v{porcupine.version}")

    cheetah = pvcheetah.create(
        access_key=config['access_key'],
        endpoint_duration_sec=config['cheetah_endpoint_duration_sec'],
        enable_automatic_punctuation=True)

    print(f"→ Cheetah v{cheetah.version}")

    pv_recorder = PvRecorder(frame_length=porcupine.frame_length)
    pv_speaker = PvSpeaker(sample_rate=int(orca_connection.recv()), bits_per_sample=16, buffer_size_secs=1)

    pllm_info = pllm_connection.recv()
    print(f"→ picoLLM v{pllm_info['version']} <{pllm_info['model']}>")

    orca_info = orca_connection.recv()
    print(f"→ Orca v{orca_info['version']}")

    speaker = Speaker(pv_speaker, config)
    synthesizer = Synthesizer(speaker, orca_connection, orca_process, config)
    generator = Generator(synthesizer, pllm_connection, pllm_process, config)
    listener = Listener(generator, porcupine, cheetah, config)
    recorder = Recorder(listener, pv_recorder)

    ppn_prompt = config['ppn_prompt']
    print(f'$ Say {ppn_prompt} ...', flush=True)

    try:
        while not stop[0]:
            if not pllm_process.is_alive() or not orca_process.is_alive():
                break

            recorder.tick()
            generator.tick()
            synthesizer.tick()
            speaker.tick()
    finally:
        recorder.close()
        listener.close()
        generator.close()
        synthesizer.close()
        speaker.close()

        for child in active_children():
            child.kill()

        porcupine.delete()
        cheetah.delete()
        pv_recorder.delete()
        pv_speaker.delete()


if __name__ == '__main__':
    main()
