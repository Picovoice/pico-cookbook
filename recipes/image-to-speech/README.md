# Image to Speech

On-device image-to-speech reads the text in an image and reads it aloud in real time. It runs fully on-device with picoOCR, and Orca with no cloud and no data leaving the device.

picoOCR, running by picoLLM Inference, extracts the text from the image and streams it to Orca Streaming Text-to-Speech for playback as the text is recognized. The extracted text is also printed in the terminal for visual feedback, which makes it useful for turning screenshots, documents, signs, labels, forms, and other text-heavy images into readable and spoken output.

[![Image to Speech](https://img.youtube.com/vi/95TmY0dD68I/hqdefault.jpg)](https://youtu.be/95TmY0dD68I)

## Components
- [picoOCR Optical Character Recognition](https://picovoice.ai/products/vision/ocr/)
- [picoLLM Inference](https://picovoice.ai/docs/picollm/)
- [Orca Streaming Text-to-Speech](https://picovoice.ai/docs/orca/)

## Implementations
- [Python](python)

## How it works
1. [picoOCR](https://picovoice.ai/products/vision/ocr/), running on [picoLLM Inference](https://picovoice.ai/docs/picollm/), extracts the text from the image and prints the recognized text in the terminal as it is produced.
3. [Orca](https://picovoice.ai/docs/orca/) reads the text aloud as picoOCR streams the text.

## FAQ

**Does it run fully on-device?**
Yes. [picoOCR](https://picovoice.ai/products/vision/ocr/) on [picoLLM Inference](https://picovoice.ai/docs/picollm/) and [Orca](https://picovoice.ai/docs/orca/) both run on-device, with no cloud data processing and no data leaving the device.

**What images work best?**
Text-heavy images such as receipts, product labels, notes, screenshots, forms, posters, and scanned documents.

**Can I use Image-to-Speech for accessibility?**
Yes. It turns text in images into spoken output, improving accessibility.
