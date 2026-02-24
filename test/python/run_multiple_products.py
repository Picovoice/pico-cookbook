import sys
from argparse import ArgumentParser

import pvorca
import pvleopard

PROMPT = 'hello my name is'
PATH = './hello_my_name_is.wav'

def main():
    sys.stdout.reconfigure(encoding='utf-8')

    parser = ArgumentParser()
    parser.add_argument(
        '--access_key',
        help='`AccessKey` obtained from `Picovoice Console` (https://console.picovoice.ai/).')
    args = parser.parse_args()

    orca = pvorca.create(access_key=args.access_key)
    leopard = pvleopard.create(access_key=args.access_key)

    _ = orca.synthesize_to_file(text=PROMPT, output_path=PATH)
    _, words = leopard.process_file(PATH)
    result_text = " ".join([w.word for w in words])

    if result_text != PROMPT:
        raise RuntimeError(f"Unexpected result: {result_text} (expected: {PROMPT})")

    print("Orca and Leopard work together properly")

if __name__ == "__main__":
    main()
