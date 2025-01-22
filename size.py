import os
import time

while True:
    width, height = os.get_terminal_size()
    print(f'{height, width}', end='\r', flush=True)
    time.sleep(0.25)