import subprocess, sys

EXPECTED_TIMEOUT_S = 25
SUCCESS = 0
ERROR = 1
TIMEOUT_RETURN_CODE = 142

command = [sys.executable, f"../../{sys.argv[1]}/main.py"] + sys.argv[2:]
proc = subprocess.Popen(command)

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
