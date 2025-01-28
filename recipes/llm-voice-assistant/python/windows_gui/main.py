import json
import math
import os
import psutil
import signal
import subprocess
import sys
import time
from argparse import ArgumentParser
from itertools import chain
from multiprocessing import Event, Pipe, Process, Queue, active_children
from multiprocessing.connection import Connection
from threading import Thread
from typing import Optional, Sequence


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
    def __init__(
            self,
            queue: Queue,
            speaker: PvSpeaker,
            orca_warmup_sec: int):
        self.queue = queue
        self.speaker = speaker
        self.orca_warmup = self.speaker.sample_rate * orca_warmup_sec
        self.started = False
        self.speaking = False
        self.flushing = False
        self.pcmBuffer = []
        self.future = None

    def close(self):
        self.interrupt()

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
            self.queue.put({'command': Commands.TEXT_STATE, 'state': 1})
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
            Thread(target=stop).start()


class Synthesizer:
    def __init__(
            self,
            queue: Queue,
            speaker: Speaker,
            orca_connection: Connection,
            orca_process: Process):
        self.queue = queue
        self.speaker = speaker
        self.orca_connection = orca_connection
        self.orca_process = orca_process

    def close(self):
        try:
            self.orca_connection.send({'command': Commands.CLOSE})
            self.orca_process.join(1.0)
        except Exception as e:
            sys.stderr.write(str(e))
            self.orca_process.kill()

    def start(self):
        self.speaker.start()
        self.orca_connection.send({'command': Commands.START})

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
                while not text_queue.empty():
                    text = text_queue.get()
                    if synthesizing:
                        pcm = orca_stream.synthesize(text)
                        if pcm is not None:
                            connection.send({'command': Commands.SPEAK, 'pcm': pcm})
                if synthesizing and flushing and text_queue.empty():
                    synthesizing = False
                    flushing = False
                    pcm = orca_stream.flush()
                    connection.send({'command': Commands.SPEAK, 'pcm': pcm})
                    connection.send({'command': Commands.FLUSH})
                elif not synthesizing and flushing and text_queue.empty():
                    flushing = False
        finally:
            orca_stream.close()
            orca.delete()


class Generator:
    def __init__(
            self,
            queue: Queue,
            synthesizer: Synthesizer,
            pllm_connection: Connection,
            pllm_process: Process):
        self.queue = queue
        self.synthesizer = synthesizer
        self.pllm_connection = pllm_connection
        self.pllm_process = pllm_process

    def close(self):
        try:
            self.pllm_connection.send({'command': Commands.CLOSE})
            self.pllm_process.join(1.0)
        except Exception as e:
            sys.stderr.write(str(e))
            self.pllm_process.kill()

    def process(self, text: str):
        self.synthesizer.start()
        self.pllm_connection.send({'command': Commands.PROCESS, 'text': text})

    def interrupt(self):
        self.pllm_connection.send({'command': Commands.INTERRUPT})
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
        if config['picollm_system_prompt'] is not None:
            dialog = pllm.get_dialog(system=config['picollm_system_prompt'])
        else:
            dialog = pllm.get_dialog()

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
                        connection.send({'command': Commands.FLUSH})
                else:
                    time.sleep(0.25)
        finally:
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
    def __init__(
            self,
            queue: Queue,
            listener: Listener,
            recorder: PvRecorder):
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


class Window:
    @staticmethod
    def reset():
        os.system('cls' if os.name == 'nt' else 'clear')

    @staticmethod
    def goto(y, x):
        return f"\u001B[{y + 1};{x + 1}H"

    @staticmethod
    def color(col):
        return f"\u001B[{';'.join([str(arg) for arg in col])}m"

    @staticmethod
    def present():
        sys.stdout.flush()

    def __init__(self, height, width, y=0, x=0):
        self.height = height
        self.width = width
        self.y = y
        self.x = x

    def subwin(self, height, width, y, x):
        return Window(height, width, self.y + y, self.x + x)

    def clear(self):
        display = ' ' * self.width
        sys.stdout.write(Window.color([0]))
        for i in range(self.height):
            sys.stdout.write(Window.goto(self.y + i, self.x))
            sys.stdout.write(display)

    def write(self, y, x, *args):
        sys.stdout.write(Window.goto(self.y + y, self.x + x))
        sys.stdout.write(Window.color([0]))
        for text in args:
            sys.stdout.write(text)

    def box(self):
        top = '┌' + '─' * (self.width - 2) + '┐'
        row = '│' + ' ' * (self.width - 2) + '│'
        bottom = '└' + '─' * (self.width - 2) + '┘'
        sys.stdout.write(Window.color([0]))
        sys.stdout.write(Window.goto(self.y, self.x) + top)
        for i in range(1, self.height - 1):
            sys.stdout.write(Window.goto(self.y + i, self.x) + row)
        sys.stdout.write(Window.goto(self.y + self.height - 1, self.x) + bottom)


class VerticalBar:
    def __init__(self, window: Window, title: str, color: list = [0]):
        self.window = window
        self.title = title
        self.color = color
        self.prev = None

    def set_title(self, title: str):
        self.title = title
        self.window.write(1, 1, self.title.center(self.window.width - 2))

    def update(self, value):
        current = round(value * (self.window.height - 3))
        display = '▄' * (self.window.width - 4)

        if self.prev != current:
            self.prev = current
            self.window.box()
            self.window.write(1, 1, self.title.center(self.window.width - 2))
            for i in range(current):
                self.window.write(self.window.height - i - 2, 2, Window.color(self.color), display)


class HorizontalBar:
    def __init__(self, window: Window, title: str, color: list = [0]):
        self.window = window
        self.title = title
        self.color = color
        self.prev = None

    def update(self, value, text=''):
        current = (round(value * (self.window.width - 4)), text)
        display0 = '▖' * current[0]
        display1 = '▌' * current[0]

        if self.prev != current:
            self.prev = current
            self.window.box()
            self.window.write(1, 2, self.title.ljust(12) + text.rjust(self.window.width - 16))
            self.window.write(2, 2, Window.color(self.color), display0)
            for i in range(3, self.window.height - 1):
                self.window.write(i, 2, Window.color(self.color), display1)


class Display:
    def __init__(self, queue: Queue, config):
        self.queue = queue
        self.config = config
        self.screen = None
        self.prev_time = 0
        self.current_time = time.time()

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

        self.prompt_text = [
            'Loading...',
            'Say `Jarvis`',
            'Ask a Question',
            'Say `Jarvis` to Interrupt'
        ]

        self.title_text = [
            '',
            '░█▀█░▀█▀░█▀▀░█▀█░█░█░█▀█░▀█▀░█▀▀░█▀▀░',
            '░█▀▀░░█░░█░░░█░█░▀▄▀░█░█░░█░░█░░░█▀▀░',
            '░▀░░░▀▀▀░▀▀▀░▀▀▀░░▀░░▀▀▀░▀▀▀░▀▀▀░▀▀▀░',
            ''
        ]

    def set_display_size(self, height, width):
        self.display_width = width
        self.display_height = height

    def generate_gui(self):
        Window.reset()
        self.screen = Window(self.display_height, self.display_width, 0, 0)
        self.title = self.screen.subwin(6, self.screen.width - 4, 1, 2)
        self.prompt = self.screen.subwin(1, self.screen.width - 2, self.screen.height - 2, 1)
        self.widgets = {}

        vu_bar_width = min(20, (self.screen.width - 5) // 2)
        perf_bar_height = min(6, (self.screen.height - 11) // 2)
        perf_offset = 7

        show_vu = False
        show_cpu = False
        show_gpu = False
        show_ram = False

        if self.display_height >= 19 and self.display_width >= 41:
            show_vu = True
            if self.display_width >= 80:
                show_cpu = True
                show_ram = True
                if self.display_height >= 30 and sys.platform.lower().startswith('win'):
                    show_gpu = True

        if show_vu:
            self.widgets['pcm_in'] = VerticalBar(
                self.screen.subwin(self.screen.height - 10, vu_bar_width, 7, 2),
                'You',
                [38, 2, 55, 255, 125])
            self.widgets['pcm_out'] = VerticalBar(
                self.screen.subwin(self.screen.height - 10, vu_bar_width, 7, 3 + vu_bar_width),
                'AI',
                [38, 2, 55, 125, 255])

        if show_cpu:
            self.widgets['CPU'] = HorizontalBar(
                self.screen.subwin(perf_bar_height, self.screen.width - 47, perf_offset, 45),
                'CPU')
            perf_offset += perf_bar_height + 1

        if show_gpu:
            self.widgets['GPU'] = HorizontalBar(
                self.screen.subwin(perf_bar_height, self.screen.width - 47, perf_offset, 45),
                'GPU')
            perf_offset += perf_bar_height + 1

        if show_ram:
            self.widgets['RAM'] = HorizontalBar(
                self.screen.subwin(perf_bar_height, self.screen.width - 47, perf_offset, 45),
                'RAM')

        self.screen.clear()
        if self.screen.height >= 19 and self.screen.width >= 41:
            self.screen.box()
            for i, line in enumerate(self.title_text):
                display = line.center(self.title.width, '░')
                self.title.write(i, 0, display)
            self.render_prompt(0)
        else:
            self.screen.write(0, 0, f'Screen too small ({self.display_height}, {self.display_width}) please resize')

        for key in self.widgets:
            self.widgets[key].update(0.0)

        self.screen.write(1, 2)
        Window.present()

    def start(self, pids: list):
        self.should_close = Event()
        self.processes = [
            Process(target=Display.worker_cpu, args=(self.queue, self.should_close, pids)),
            Process(target=Display.worker_ram, args=(self.queue, self.should_close, pids)),
            Process(target=Display.worker_gpu, args=(self.queue, self.should_close, pids))
        ]
        for process in self.processes:
            process.start()

    def close(self):
        self.should_close.set()
        for process in self.processes:
            process.join(1.0)
        Window.reset()

    def render_prompt(self, text_state=None):
        if text_state:
            self.text_state = text_state

        self.prompt.clear()
        self.prompt.write(0, 1,
                          Window.color([90]) if self.in_blink else '', '> ',
                          Window.color([0]), self.prompt_text[self.text_state])

    def tick(self):
        if self.screen is None or self.display_height != self.screen.height or self.display_width != self.screen.width:
            self.generate_gui()

        self.prev_time = self.current_time
        self.current_time = time.time()
        delta = self.current_time - self.prev_time

        while not self.queue.empty():
            message = self.queue.get()
            if message['command'] == Commands.TEXT_STATE:
                self.render_prompt(int(message['state']))
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
                if name in self.widgets:
                    text = message['text']
                    bar = max(0, min(1, message['bar']))
                    self.widgets[name].update(bar, text)
            elif message['command'] == Commands.MODEL_NAME:
                if 'pcm_out' in self.widgets:
                    if message['name'] and len(message['name']) < 18:
                        self.widgets['pcm_out'].set_title(message['name'])

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

        if 'pcm_in' in self.widgets:
            self.widgets['pcm_in'].update(volume_in)
        if 'pcm_out' in self.widgets:
            self.widgets['pcm_out'].update(volume_out)

        self.screen.write(1, 2)
        Window.present()

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

        try:
            while not should_close.is_set():
                cpu_usage = sum([psutil.Process(pid).cpu_percent(0.25) for pid in pids]) / psutil.cpu_count()
                queue.put({
                    'command': Commands.USAGE,
                    'name': 'CPU',
                    'text': f"{math.ceil(cpu_usage)}%",
                    'bar': (cpu_usage / 100)
                })
        except Exception as e:
            sys.stderr.write(str(e))

    @staticmethod
    def worker_gpu(queue: Queue, should_close, pids: list):
        def handler(_, __) -> None:
            pass
        signal.signal(signal.SIGINT, handler)

        if not sys.platform.lower().startswith('win'):
            return

        try:
            gpu_usage_counters_format = r'"\GPU Engine(pid_{}_*)\Utilization Percentage"'
            gpu_usage_counters = ', '.join([gpu_usage_counters_format.format(pid) for pid in pids])
            gpu_usage_cmd = r'(((Get-Counter {}).CounterSamples | where CookedValue).CookedValue | measure -sum).sum'
            gpu_usage_cmd = gpu_usage_cmd.format(gpu_usage_counters)
            while not should_close.is_set():
                gpu_usage = Display.run_command(gpu_usage_cmd)
                if gpu_usage is not None:
                    gpu_usage = max(0, min(100, gpu_usage))
                    queue.put({
                        'command': Commands.USAGE,
                        'name': 'GPU',
                        'text': f"{math.ceil(gpu_usage)}%",
                        'bar': (float(gpu_usage) / 100)
                    })
        except Exception as e:
            sys.stderr.write(str(e))

    @staticmethod
    def worker_ram(queue: Queue, should_close, pids: list):
        def handler(_, __) -> None:
            pass
        signal.signal(signal.SIGINT, handler)

        try:
            ram_total = psutil.virtual_memory().total / 1024 / 1024 / 1024
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
        except Exception as e:
            sys.stderr.write(str(e))


def main(config):
    stop = [False]
    queue = Queue()
    display = Display(queue, config)

    terminal_width, terminal_height = os.get_terminal_size()
    terminal_width = min(terminal_width, 120)
    terminal_height = min(terminal_height, 30)
    display.set_display_size(terminal_height, terminal_width)

    def handler(_, __) -> None:
        stop[0] = True
    signal.signal(signal.SIGINT, handler)

    if not sys.platform.lower().startswith('win'):
        def resize_handler(_, __):
            terminal_width, terminal_height = os.get_terminal_size()
            terminal_width = min(terminal_width, 120)
            terminal_height = min(terminal_height, 30)
            display.set_display_size(terminal_height, terminal_width)
        signal.signal(signal.SIGWINCH, resize_handler)

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
            if not pllm_process.is_alive() or not orca_process.is_alive():
                break

            recorder.tick()
            generator.tick()
            synthesizer.tick()
            speaker.tick()
            display.tick()
    finally:
        display.close()
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
    args = parser.parse_args()

    if args.config is not None:
        config_path = os.path.realpath(args.config)
    else:
        config_path = os.path.join(os.path.dirname(os.path.realpath(__file__)), 'config.json')

    if os.path.exists(config_path):
        with open(config_path, 'r') as fd:
            config = json.load(fd)
    elif args.config is not None:
        print(parser.error(f'File {config_path} does not exist'))
        exit(1)
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
        'picollm_system_prompt': None,
        'orca_warmup_sec': 0,
        'orca_speech_rate': 1.0,
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
