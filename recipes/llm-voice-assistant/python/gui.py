import os
import json
import time
import math
import signal
import subprocess
import psutil
from argparse import ArgumentParser
from multiprocessing import Event, Pipe, Process, Queue, active_children
from multiprocessing.connection import Connection
from concurrent.futures import ThreadPoolExecutor
from typing import Optional, Sequence
from itertools import chain
import curses

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
    USAGE = 'usage'
    TEXT_STATE = 'text-state'
    PCM_IN = 'pcm-in'
    PCM_OUT = 'pcm-out'
    MODEL_NAME = 'model-name'
    PID = 'pid'


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


class Speaker:
    def __init__(self, queue: Queue, speaker: PvSpeaker, orca_warmup_sec: int):
        self.queue = queue
        self.speaker = speaker
        self.orca_warmup = self.speaker.sample_rate * orca_warmup_sec
        self.started = False
        self.speaking = False
        self.flushing = False
        self.pcmBuffer = []
        self.executor = ThreadPoolExecutor()
        self.future = None

    def close(self):
        self.executor.shutdown()

    def start(self):
        self.started = True

    def process(self, pcm: Optional[Sequence[int]]):
        if self.started and pcm is not None:
            self.pcmBuffer.extend(pcm)

    def flush(self):
        self.flushing = True

    def interrupt(self):
        self.started = False
        if self.speaking:
            self.speaking = False
            self.flushing = False
            self.pcmBuffer.clear()
            self.speaker.stop()

    def tick(self):
        def stop():
            self.speaker.flush()
            self.speaker.stop()
        if not self.speaking and len(self.pcmBuffer) > self.orca_warmup:
            self.speaking = True
            self.speaker.start()
        if self.speaking and len(self.pcmBuffer) > 0:
            written = self.speaker.write(self.pcmBuffer)
            if written > 0:
                self.queue.put({
                    'command': Commands.PCM_OUT,
                    'pcm': self.pcmBuffer[:written],
                    'sample-rate': self.speaker.sample_rate})
                del self.pcmBuffer[:written]
        elif self.speaking and self.flushing and len(self.pcmBuffer) == 0:
            self.started = False
            self.speaking = False
            self.flushing = False
            self.future = self.executor.submit(stop)
        if self.future and self.future.done():
            self.future = None
            self.queue.put({'command': Commands.TEXT_STATE, 'state': 1})


class Synthesizer:
    def __init__(self, queue: Queue, speaker: Speaker, orca_connection: Connection, orca_process: Process):
        self.queue = queue
        self.speaker = speaker
        self.orca_connection = orca_connection
        self.orca_process = orca_process

    def close(self):
        self.orca_connection.send({'command': Commands.CLOSE})
        self.orca_process.join()

    def start(self):
        self.speaker.start()
        self.orca_connection.send({'command': Commands.START})

    def process(self, text: str):
        self.orca_connection.send({'command': Commands.PROCESS, 'text': text})

    def flush(self):
        self.orca_connection.send({'command': Commands.FLUSH})

    def interrupt(self):
        self.orca_connection.send({'command': Commands.INTERRUPT})
        while self.orca_connection.poll() and self.orca_connection.recv()['command'] != Commands.INTERRUPT:
            time.sleep(0.01)
        self.speaker.interrupt()

    def tick(self):
        while self.orca_connection.poll():
            message = self.orca_connection.recv()
            if message['command'] == Commands.SPEAK:
                self.speaker.process(message['pcm'])
            elif message['command'] == Commands.FLUSH:
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
        orca_stream = orca.stream_open()
        connection.send(orca.sample_rate)

        try:
            close = False
            synthesizing = False
            flushing = False
            textQueue = Queue()
            while not close:
                while connection.poll():
                    message = connection.recv()
                    if message['command'] == Commands.CLOSE:
                        close = True
                    elif message['command'] == Commands.START:
                        synthesizing = True
                    elif message['command'] == Commands.PROCESS:
                        if synthesizing:
                            textQueue.put(message['text'])
                    elif message['command'] == Commands.FLUSH:
                        flushing = True
                    elif message['command'] == Commands.INTERRUPT:
                        synthesizing = False
                        flushing = False
                        while not textQueue.empty():
                            textQueue.get()
                        orca_stream.flush()
                        connection.send({'command': Commands.INTERRUPT})
                if not textQueue.empty():
                    text = textQueue.get()
                    pcm = orca_stream.synthesize(text)
                    if pcm is not None:
                        connection.send({'command': Commands.SPEAK, 'pcm': pcm})
                if synthesizing and flushing and textQueue.empty():
                    synthesizing = False
                    flushing = False
                    pcm = orca_stream.flush()
                    connection.send({'command': Commands.SPEAK, 'pcm': pcm})
                    connection.send({'command': Commands.FLUSH})
                elif flushing:
                    flushing = False
        finally:
            orca_stream.close()
            orca.delete()


class Generator:
    def __init__(self, queue: Queue, synthesizer: Synthesizer, pllm_connection: Connection, pllm_process: Process):
        self.queue = queue
        self.synthesizer = synthesizer
        self.pllm_connection = pllm_connection
        self.pllm_process = pllm_process

    def close(self):
        self.pllm_connection.send({'command': Commands.CLOSE})
        self.pllm_process.join()

    def process(self, text: str):
        self.synthesizer.start()
        self.pllm_connection.send({'command': Commands.PROCESS, 'text': text})

    def interrupt(self):
        self.pllm_connection.send({'command': Commands.INTERRUPT})
        while self.pllm_connection.poll() and self.pllm_connection.recv()['command'] != Commands.INTERRUPT:
            time.sleep(0.01)
        self.synthesizer.interrupt()

    def tick(self):
        while self.pllm_connection.poll():
            message = self.pllm_connection.recv()
            if message['command'] == Commands.SYNTHESIZE:
                self.synthesizer.process(message['text'])
            elif message['command'] == Commands.FLUSH:
                self.synthesizer.flush()
            elif message['command'] == Commands.MODEL_NAME:
                self.queue.put(message)

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
        dialog = pllm.get_dialog()
        generating = False

        connection.send({'command': Commands.MODEL_NAME, 'name': pllm.model.split(' ')[0]})

        stop_phrases = {
            '</s>',  # Llama-2, Mistral, and Mixtral
            '<end_of_turn>',  # Gemma
            '<|endoftext|>',  # Phi-2
            '<|eot_id|>',  # Llama-3
            '<|end|>', '<|user|>', '<|assistant|>',  # Phi-3
        }
        completion = CompletionText(stop_phrases)

        def llm_callback(text):
            if generating:
                completion.append(text)
                new_tokens = completion.get_new_tokens()
                if len(new_tokens) > 0:
                    connection.send({'command': Commands.SYNTHESIZE, 'text': new_tokens})

        def llm_task(text):
            short_answers_instruction = \
                "You are a voice assistant and your answers are very short but informative"
            dialog.add_human_request(
                f"{short_answers_instruction}. {text}" if config['short_answers'] else text)

            completion.reset()
            return pllm.generate(
                prompt=dialog.prompt(),
                completion_token_limit=config['picollm_completion_token_limit'],
                stop_phrases=stop_phrases,
                presence_penalty=config['picollm_presence_penalty'],
                frequency_penalty=config['picollm_frequency_penalty'],
                temperature=config['picollm_temperature'],
                top_p=config['picollm_top_p'],
                stream_callback=llm_callback)

        try:
            close = False
            executor = ThreadPoolExecutor()
            llm_future = None
            interrupting = False
            while not close:
                while connection.poll():
                    message = connection.recv()
                    if message['command'] == Commands.CLOSE:
                        close = True
                    elif message['command'] == Commands.PROCESS:
                        generating = True
                        text = message['text']
                        llm_future = executor.submit(llm_task, text)
                    elif message['command'] == Commands.INTERRUPT:
                        interrupting = True
                        generating = False
                        pllm.interrupt()
                if llm_future and llm_future.done():
                    generating = False
                    llm_result = llm_future.result()
                    dialog.add_llm_response(llm_result.completion)
                    if llm_result.endpoint == picollm.PicoLLMEndpoints.INTERRUPTED:
                        interrupting = False
                        connection.send({'command': Commands.INTERRUPT})
                    else:
                        connection.send({'command': Commands.FLUSH})
                    llm_future = None
                if not llm_future and interrupting:
                    interrupting = False
                    connection.send({'command': Commands.INTERRUPT})
        finally:
            while llm_future and llm_future.done():
                time.sleep(0.01)
            del executor
            pllm.release()


class Listener:
    def __init__(
            self,
            queue: Queue,
            generator: Generator,
            porcupine: pvporcupine.Porcupine,
            cheetah: pvcheetah.Cheetah):
        self.queue = queue
        self.generator = generator
        self.porcupine = porcupine
        self.cheetah = cheetah

        self.sleeping = True
        self.listening = False
        self.user_request = ''
        self.tick_count = 0

    def close(self):
        pass

    def process(self, pcm: Optional[Sequence[int]]):
        if self.sleeping:
            if self.porcupine.process(pcm) == 0:
                self.sleeping = False
                self.tick_count = 4
                self.generator.interrupt()
                self.queue.put({'command': Commands.INTERRUPT})
        elif self.listening:
            partial_transcript, endpoint_reached = self.cheetah.process(pcm)
            if len(partial_transcript) > 0:
                self.user_request += partial_transcript
            if endpoint_reached:
                self.sleeping = True
                self.listening = False
                remaining_transcript = self.cheetah.flush()
                if len(remaining_transcript) > 0:
                    self.user_request += remaining_transcript
                self.generator.process(self.user_request)
                self.user_request = ''
                self.queue.put({'command': Commands.TEXT_STATE, 'state': 3})
        elif self.tick_count > 0:
            self.tick_count -= 1
        else:
            self.listening = True
            self.queue.put({'command': Commands.TEXT_STATE, 'state': 2})


class Recorder:
    def __init__(self, queue: Queue, listener: Listener, recorder: PvRecorder):
        self.queue = queue
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
        self.queue.put({'command': Commands.PCM_IN, 'pcm': pcm, 'sample-rate': self.recorder.sample_rate})


class Display:
    def __init__(self, queue: Queue, config):
        self.queue = queue
        self.config = config
        self.prev_time = 0
        self.current_time = time.time()
        self.model_name = None

        self.screen = curses.initscr()
        self.height, self.width = self.screen.getmaxyx()

        if self.height < 30 or self.width < 120:
            print(f'Error: Console window not large enough was ({self.height}, {self.width}) needs (30, 120)')
            exit(1)

        self.last_blink = 0.0
        self.in_blink = False
        self.text_state = 0

        self.sample_rate_in = 1
        self.samples_in = []
        self.volume_in = [0.0] * 4
        self.volume_index_in = 0
        self.sample_rate_out = 1
        self.samples_out = []
        self.volume_out = [0.0] * 12
        self.volume_index_out = 0

        curses.curs_set(0)
        curses.start_color()
        curses.use_default_colors()
        curses.init_color(128, 500, 500, 500)
        curses.init_color(129, 215, 489, 999)
        curses.init_color(130, 215, 999, 489)
        curses.init_pair(1, 128, curses.COLOR_BLACK)
        curses.init_pair(2, 129, curses.COLOR_BLACK)
        curses.init_pair(3, 130, curses.COLOR_BLACK)

        self.window = curses.newwin(self.height, self.width)
        self.prompt = self.window.subwin(1, self.width - 2, self.height - 2, 1)
        self.pcm_in = self.window.subwin(self.height - 10, 20, 7, 2)
        self.pcm_out = self.window.subwin(self.height - 10, 20, 7, 23)

        self.usage = {
            'CPU': self.window.subwin(6, self.width - 47, 7, 45),
            'GPU': self.window.subwin(6, self.width - 47, 14, 45),
            'RAM': self.window.subwin(6, self.width - 47, 21, 45),
        }

        for key in self.usage:
            self.usage[key].box()
            self.usage[key].addstr(1, 2, key)

    def start(self, pids: list):
        self.should_close = Event()
        self.processes = [
            Process(target=Display.worker_cpu, args=(self.queue, self.should_close, pids)),
            Process(target=Display.worker_gpu, args=(self.queue, self.should_close, pids)),
            Process(target=Display.worker_ram, args=(self.queue, self.should_close, pids)),
        ]
        for process in self.processes:
            process.start()

    def close(self):
        self.should_close.set()
        for process in self.processes:
            process.join()
        curses.endwin()

    def render_prompt(self):
        TEXT_STATES = [
            'Loading...',
            'Say `Jarvis`',
            'Ask a Question',
            'Say `Jarvis` to Interrupt'
        ]

        self.prompt.clear()
        self.prompt.addstr(0, 3, TEXT_STATES[self.text_state])
        self.prompt.addch(0, 1, '>', curses.color_pair(1) if self.in_blink else 0)

    def tick(self):
        self.prev_time = self.current_time
        self.current_time = time.time()
        delta = self.current_time - self.prev_time

        while not self.queue.empty():
            message = self.queue.get()
            if message['command'] == Commands.TEXT_STATE:
                self.text_state = int(message['state'])
                self.render_prompt()
            elif message['command'] == Commands.PCM_IN:
                self.samples_in = message['pcm']
                self.sample_rate_in = message['sample-rate']
            elif message['command'] == Commands.PCM_OUT:
                self.samples_out.extend(message['pcm'])
                self.sample_rate_out = message['sample-rate']
            elif message['command'] == Commands.INTERRUPT:
                self.samples_out.clear()
            elif message['command'] == Commands.USAGE:
                name = message['name']
                text = message['text']
                bar = message['bar']
                height, width = self.usage[name].getmaxyx()
                bar_width = round((width - 4) * max(0, min(1, bar)))
                self.usage[name].clear()
                self.usage[name].box()
                text0 = f'{text}'.rjust(width - 12)
                self.usage[name].addstr(1, 2, f'{name:<8}{text0}')
                for j in range(height - 3):
                    for i in range(bar_width):
                        self.usage[name].addch(2 + j, 2 + i, '▖' if j == 0 else '▌')
                self.usage[name].refresh()
            elif message['command'] == Commands.MODEL_NAME:
                self.model_name = message['name']

        if self.current_time > self.last_blink + 0.5:
            self.last_blink = self.current_time
            self.in_blink = not self.in_blink
            self.render_prompt()

        if len(self.samples_out) > 0:
            if len(self.samples_out) > self.sample_rate_out * 2:
                del self.samples_out[:-(self.sample_rate_out * 2)]

        def compute_amplitude(samples, sample_max=32768, scale=1.0):
            rms = math.sqrt(sum([(x / sample_max) ** 2 for x in samples]) / len(samples))
            dbfs = 20 * math.log10(max(rms, 1e-9))
            dbfs = min(0, dbfs)
            dbfs = max(0, dbfs + 40)
            return min(1, (dbfs / 40) * scale)

        if len(self.samples_in) > 0:
            volume_in = compute_amplitude(self.samples_in)
            self.volume_in[self.volume_index_in] = volume_in
            self.volume_index_in = (self.volume_index_in + 1) % len(self.volume_in)
        else:
            self.volume_in[self.volume_index_in] = 0
            self.volume_index_in = (self.volume_index_in + 1) % len(self.volume_in)

        if len(self.samples_out) > 0:
            frame_size_out = min(len(self.samples_out), int(delta * self.sample_rate_out + 1))
            frame_out = self.samples_out[:frame_size_out]
            del self.samples_out[:frame_size_out]
            volume_out = compute_amplitude(frame_out)
            self.volume_out[self.volume_index_out] = volume_out
            self.volume_index_out = (self.volume_index_out + 1) % len(self.volume_out)
        else:
            self.volume_out[self.volume_index_out] = 0
            self.volume_index_out = (self.volume_index_out + 1) % len(self.volume_out)

        volume_in = sum(self.volume_in) / len(self.volume_in)
        volume_out = sum(self.volume_out) / len(self.volume_out)

        self.pcm_in.clear()
        self.pcm_out.clear()
        self.pcm_in.box()
        self.pcm_out.box()
        height_in, width_in = self.pcm_in.getmaxyx()
        height_out, width_out = self.pcm_out.getmaxyx()
        self.pcm_in.addstr(1, 1, 'You'.center(18))
        self.pcm_out.addstr(1, 1, (f'{self.model_name}' if self.model_name else 'AI').center(18))
        for j in range(width_in - 4):
            for i in range(int(volume_in * (height_in - 4))):
                self.pcm_in.addch(height_in - 2 - i, 2 + j, '▄', curses.color_pair(3))
        for j in range(width_out - 4):
            for i in range(int(volume_out * (height_out - 4))):
                self.pcm_out.addch(height_out - 2 - i, 2 + j, '▄', curses.color_pair(2))

        TITLE = [
            '',
            '░█▀█░▀█▀░█▀▀░█▀█░█░█░█▀█░▀█▀░█▀▀░█▀▀░',
            '░█▀▀░░█░░█░░░█░█░▀▄▀░█░█░░█░░█░░░█▀▀░',
            '░▀░░░▀▀▀░▀▀▀░▀▀▀░░▀░░▀▀▀░▀▀▀░▀▀▀░▀▀▀░',
            ''
        ]

        self.title = self.window.subwin(6, self.width - 4, 1, 2)
        for i, line in enumerate(TITLE):
            display = line.center(self.width - 4, '░')
            self.title.addstr(i, 0, display)

        self.window.box()
        self.window.refresh()

    @staticmethod
    def run_command(command):
        val = subprocess.run(['powershell', '-Command', command], capture_output=True).stdout.decode("ascii")
        try:
            return float(val.strip().replace(',', '.'))
        except Exception:
            return None

    @staticmethod
    def worker_cpu(queue: Queue, should_close, pids: list):
        def handler(_, __) -> None:
            pass
        signal.signal(signal.SIGINT, handler)

        while not should_close.is_set():
            cpu_usage = sum([psutil.Process(pid).cpu_percent(0.25) for pid in pids]) / os.cpu_count()
            queue.put({
                'command': Commands.USAGE,
                'name': 'CPU',
                'text': f"{round(cpu_usage, 2)}%",
                'bar': (cpu_usage / 100)
            })

    @staticmethod
    def worker_gpu(queue: Queue, should_close, pids: list):
        def handler(_, __) -> None:
            pass
        signal.signal(signal.SIGINT, handler)

        gpu_usage_counters = ', '.join([r'"\GPU Engine(pid_{}_*)\Utilization Percentage"'.format(pid) for pid in pids])
        gpu_usage_cmd = r'(((Get-Counter {}).CounterSamples | where CookedValue).CookedValue | measure -sum).sum'
        gpu_usage_cmd = gpu_usage_cmd.format(gpu_usage_counters)
        while not should_close.is_set():
            gpu_usage = Display.run_command(gpu_usage_cmd)
            if gpu_usage is not None:
                gpu_usage = max(0, min(100, gpu_usage))
                queue.put({
                    'command': Commands.USAGE,
                    'name': 'GPU',
                    'text': f"{round(gpu_usage, 2)}%",
                    'bar': (float(gpu_usage) / 100)
                })

    @staticmethod
    def worker_ram(queue: Queue, should_close, pids: list):
        def handler(_, __) -> None:
            pass
        signal.signal(signal.SIGINT, handler)

        cpu_mem_total_cmd = r'(Get-WMIObject Win32_OperatingSystem).TotalVisibleMemorySize / 1MB'

        ram_total = Display.run_command(cpu_mem_total_cmd)
        while not should_close.is_set():
            time.sleep(0.25)
            ram_usage = sum([psutil.Process(pid).memory_info().rss for pid in pids]) / 1024 / 1024 / 1024
            if ram_usage is not None:
                queue.put({
                    'command': Commands.USAGE,
                    'name': 'RAM',
                    'text': f"{round(ram_usage, 2)}GB / {round(ram_total, 2)}GB",
                    'bar': (float(ram_usage) / float(ram_total))
                })


def main(config):
    stop = [False]
    queue = Queue()
    display = Display(queue, config)

    def handler(_, __) -> None:
        stop[0] = True
    signal.signal(signal.SIGINT, handler)

    pllm_connection, pllm_process = Generator.create_worker(config)
    orca_connection, orca_process = Synthesizer.create_worker(config)

    display.start([os.getpid(), pllm_process.pid, orca_process.pid])
    display.tick()

    if 'keyword_model_path' not in config:
        porcupine = pvporcupine.create(
            access_key=config['access_key'],
            keywords=['jarvis'],
            sensitivities=[config['porcupine_sensitivity']])
    else:
        porcupine = pvporcupine.create(
            access_key=config['access_key'],
            keyword_paths=[config['keyword_model_path']],
            sensitivities=[config['porcupine_sensitivity']])

    cheetah = pvcheetah.create(
        access_key=config['access_key'],
        endpoint_duration_sec=config['cheetah_endpoint_duration_sec'],
        enable_automatic_punctuation=True)

    pv_recorder = PvRecorder(frame_length=porcupine.frame_length)
    pv_speaker = PvSpeaker(sample_rate=int(orca_connection.recv()), bits_per_sample=16, buffer_size_secs=1)

    speaker = Speaker(queue, pv_speaker, config['orca_warmup_sec'])
    synthesizer = Synthesizer(queue, speaker, orca_connection, orca_process)
    generator = Generator(queue, synthesizer, pllm_connection, pllm_process)
    listener = Listener(queue, generator, porcupine, cheetah)
    recorder = Recorder(queue, listener, pv_recorder)

    queue.put({'command': Commands.TEXT_STATE, 'state': 1})
    display.tick()

    try:
        while not stop[0]:
            recorder.tick()
            generator.tick()
            synthesizer.tick()
            speaker.tick()
            display.tick()
    finally:
        generator.interrupt()
        generator.tick()
        synthesizer.tick()
        speaker.tick()
        display.tick()

        display.close()
        recorder.close()
        listener.close()
        generator.close()
        synthesizer.close()
        speaker.close()

        for child in active_children():
            child.terminate()

        porcupine.delete()
        cheetah.delete()
        pv_recorder.delete()
        pv_speaker.delete()


if __name__ == '__main__':
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
        '--keyword-model_path',
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
        '--orca_warmup_sec',
        type=float,
        help="Duration of the synthesized audio to buffer before streaming it out. A higher value helps slower "
             "(e.g., Raspberry Pi) to keep up with real-time at the cost of increasing the initial delay.")
    parser.add_argument(
        '--porcupine_sensitivity',
        type=float,
        help="Sensitivity for detecting keywords.")
    parser.add_argument('--short_answers', action='store_true')
    args = parser.parse_args()

    if args.config is not None:
        config_path = os.path.realpath(args.config)
    else:
        config_path = os.path.join(os.path.dirname(os.path.realpath(__file__)), 'config.json')

    if os.path.exists(config_path):
        with open(config_path, 'r') as fd:
            config = json.load(fd)
    else:
        config = {}

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
        'orca_warmup_sec': 0,
        'porcupine_sensitivity': 0.5,
        'short_answers': False
    }

    for key in chain(REQUIRED_ARGS, DEFAULT_ARGS):
        arg = getattr(args, key)
        if arg is not None:
            config[key] = arg

    missing = [f'--{arg}' for arg in REQUIRED_ARGS if arg not in config]
    if len(missing) > 0:
        print(parser.error('the following arguments are required: ' + ', '.join(missing)))
        exit(1)

    for key in DEFAULT_ARGS:
        if key not in config:
            config[key] = DEFAULT_ARGS[key]

    main(config)
