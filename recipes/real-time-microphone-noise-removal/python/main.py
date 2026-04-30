import wave
from argparse import ArgumentParser
from typing import (
    BinaryIO,
    Sequence
)

import pvkoala
from pvrecorder import PvRecorder


class AINoiseSuppressedRecorder(object):
    def __init__(self, access_key: str) -> None:
        self._koala = pvkoala.create(access_key=access_key)
        self._recorder = PvRecorder(frame_length=self._koala.frame_length)

    @property
    def sample_rate(self) -> int:
        return self._koala.sample_rate

    def start(self) -> None:
        self._recorder.start()

    def stop(self) -> None:
        self._recorder.stop()
        self._koala.reset()

    def read(self) -> tuple[Sequence[int], Sequence[int]]:
        raw_pcm = self._recorder.read()
        enhanced_pcm = self._koala.process(raw_pcm)
        return raw_pcm, enhanced_pcm

    def delete(self) -> None:
        self._recorder.delete()
        self._koala.delete()


class WavWriter(object):
    def __init__(self, path: str, sample_rate: int) -> None:
        self._file: BinaryIO = open(path, "wb")
        self._wav = wave.open(self._file, "wb")
        self._wav.setnchannels(1)
        self._wav.setsampwidth(2)
        self._wav.setframerate(sample_rate)

    def write(self, pcm: Sequence[int]) -> None:
        self._wav.writeframes(
            b''.join(sample.to_bytes(2, byteorder="little", signed=True) for sample in pcm))

    def close(self) -> None:
        self._wav.close()
        self._file.close()


def main() -> None:
    parser = ArgumentParser()
    parser.add_argument(
        "--access_key",
        required=True,
        help="AccessKey obtained from Picovoice Console (https://console.picovoice.ai/).")
    parser.add_argument(
        "--raw_output_path",
        default="raw.wav",
        help="Path to save the raw microphone recording. Default is raw.wav.")
    parser.add_argument(
        "--enhanced_output_path",
        default="noise_suppressed.wav",
        help="Path to save the Koala noise-suppressed recording. Default is noise_suppressed.wav.")
    args = parser.parse_args()

    recorder = None
    raw_writer = None
    enhanced_writer = None

    try:
        recorder = AINoiseSuppressedRecorder(access_key=args.access_key)
        raw_writer = WavWriter(path=args.raw_output_path, sample_rate=recorder.sample_rate)
        enhanced_writer = WavWriter(path=args.enhanced_output_path, sample_rate=recorder.sample_rate)

        print("Recording started.")
        print("Speak into the microphone. Press Ctrl+C to stop.")
        print(f"Raw audio will be saved to `{args.raw_output_path}`.")
        print(f"Noise-suppressed audio will be saved to `{args.enhanced_output_path}`.")

        recorder.start()

        while True:
            raw_pcm, enhanced_pcm = recorder.read()
            raw_writer.write(raw_pcm)
            enhanced_writer.write(enhanced_pcm)

    except KeyboardInterrupt:
        print()
        print("Recording stopped.")

    finally:
        if recorder is not None:
            recorder.stop()
            recorder.delete()

        if raw_writer is not None:
            raw_writer.close()

        if enhanced_writer is not None:
            enhanced_writer.close()

        print(f"Saved raw audio to `{args.raw_output_path}`.")
        print(f"Saved noise-suppressed audio to `{args.enhanced_output_path}`.")


if __name__ == "__main__":
    main()
