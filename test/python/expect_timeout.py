import subprocess, sys

EXPECTED_TIMEOUT_S = 10
SUCCESS = 0
TIMEOUT_RETURN_CODE = 142

command = [sys.executable, f"../../{sys.argv[1]}/main.py"] + sys.argv[2:]
proc = subprocess.Popen(command)

try:
    proc.wait(timeout=EXPECTED_TIMEOUT_S)
    exit(SUCCESS)
except subprocess.TimeoutExpired:
    exit(TIMEOUT_RETURN_CODE)

