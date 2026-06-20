# Image Question Answering

On-device image question answering: ask questions about an image by voice and hear the answer spoken back in real time. It runs fully on-device with picoVLM, Cheetah, and Orca.

picoVLM analyzes the image and answers your question, running on picoLLM Inference. Cheetah Streaming Speech-to-Text transcribes the spoken question, and Orca Streaming Text-to-Speech speaks the answer as it is generated.

[![Image Question Answering](https://img.youtube.com/vi/SWNxAVX1IWo/hqdefault.jpg)](https://youtu.be/SWNxAVX1IWo)

## Components
- [Cheetah Streaming Speech-to-Text](https://picovoice.ai/docs/cheetah/)
- [Orca Streaming Text-to-Speech](https://picovoice.ai/docs/orca/)
- [picoLLM Inference](https://picovoice.ai/docs/picollm/)
- [picoVLM Vision Language Model](https://picovoice.ai/products/vision/vlm/)

## Implementations
- [Python](python)

## How it works
1. [Cheetah](https://picovoice.ai/docs/cheetah/) transcribes spoken questions.
2. [picoLLM Inference](https://picovoice.ai/docs/picollm/) runs [picoVLM](https://picovoice.ai/products/vision/vlm/) which analyzes the image and generates an answer about the image.
3. [Orca](https://picovoice.ai/docs/orca/) reads the answer out loud as the answer is produced.

## FAQ

**Does it run fully on-device?**
Yes. [Cheetah](https://picovoice.ai/docs/cheetah/), [picoVLM](https://picovoice.ai/products/vision/vlm/) on [picoLLM Inference](https://picovoice.ai/docs/picollm/), and [Orca](https://picovoice.ai/docs/orca/) all run on-device, with no cloud processing and no data leaving the device.

**Can I ask questions by voice?**
Yes. You ask out loud, [Cheetah](https://picovoice.ai/docs/cheetah/) transcribes the question, [picoVLM](https://picovoice.ai/products/vision/vlm/) answers about the image, and [Orca](https://picovoice.ai/docs/orca/) speaks the answer back as it streams.

**What kind of questions can it answer?**
Natural questions about the image, such as what objects are present, what is happening in the scene, where something is located, or how the image should be described.

More FAQs can be found on [Picovoice website](https://picovoice.ai/cookbook/image-question-answering/).
