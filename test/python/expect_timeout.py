import subprocess, sys

from pvrecorder import PvRecorder
from pvspeaker import PvSpeaker

EXPECTED_TIMEOUT_S = 20
SUCCESS = 0
ERROR = 1
ERROR_EXITED_BEFORE_TIMEOUT = 2
ERROR_UNSUPPORTED_PLATFORM = 3

def main():
    command = [sys.executable, f"../../{sys.argv[1]}/main.py"] + sys.argv[2:]
    proc = subprocess.Popen(command)

    try:
        _ = PvRecorder(frame_length=1024)
    except RuntimeError:
        print("This device doesn't support PvRecorder")
        exit(ERROR_UNSUPPORTED_PLATFORM)

    try:
        _ = PvSpeaker(sample_rate=8000, bits_per_sample=16, buffer_size_secs=1)
    except RuntimeError:
        print("This device doesn't support PvSpeaker")
        exit(ERROR_UNSUPPORTED_PLATFORM)

    try:
        proc.wait(timeout=EXPECTED_TIMEOUT_S)
        print("The process exited unexpectedly")
        exit(ERROR_EXITED_BEFORE_TIMEOUT)
    except subprocess.TimeoutExpired:
        print("Timeout expired successfully")
        exit(proc.returncode)
    except Exception as e:
        print(f"Failed with: {e}")
        exit(ERROR)


if __name__ == "__main__":
    main()
