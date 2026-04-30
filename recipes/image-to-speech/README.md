# Image to Speech

Read text from an image and hear it spoken aloud in real time.

This demo uses picoLLM OCR to extract text from an image, then streams the recognized text to Orca for text-to-speech
playback as the OCR result is generated. It also prints the extracted text in the terminal, making it useful for quickly
turning screenshots, documents, signs, labels, forms, or other text-heavy images into readable and spoken output.

Use it to read text from images such as receipts, product labels, notes, screenshots, forms, posters, or scanned
documents.

## Components

- [Cheetah Streaming Speech-to-Text](https://picovoice.ai/docs/cheetah/)
- [picoLLM Inference](https://picovoice.ai/docs/picollm/)

## Implementations

- [Python](python)
