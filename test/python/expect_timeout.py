import subprocess, sys

from pvrecorder import PvRecorder
from pvspeaker import PvSpeaker

EXPECTED_TIMEOUT_S = 20
SUCCESS = 0
ERROR = 1
TIMEOUT_RETURN_CODE = 142

command = [sys.executable, f"../../{sys.argv[1]}/main.py"] + sys.argv[2:]
proc = subprocess.Popen(command)

try:
    pv_recorder = PvRecorder(frame_length=1024)
except RuntimeError:
    print("This platform doesn't support PvRecorder")
    exit(SUCCESS)

try:
    pv_speaker = PvSpeaker(sample_rate=8000, bits_per_sample=16, buffer_size_secs=1)
except RuntimeError:
    print("This platform doesn't support PvSpeaker")
    exit(SUCCESS)

try:
    proc.wait(timeout=EXPECTED_TIMEOUT_S)
    print("The process exited unexpectedly")
    exit(TIMEOUT_RETURN_CODE)
except subprocess.TimeoutExpired:
    print("Timeout expired successfully")
    exit(proc.returncode)
except Exception as e:
    print(f"Failed with: {e}")
    exit(ERROR)
