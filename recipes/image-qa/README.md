# Image Question Answering

Ask questions about an image using your voice and hear the answer spoken back in real time.

This demo combines on-device speech-to-text, vision-language inference, and streaming text-to-speech to create a
hands-free image question answering experience. Cheetah transcribes the user's spoken question, picoLLM analyzes the
image and generates an answer, and Orca reads the response aloud as it is being generated.

Use it to ask natural questions such as what objects are visible in an image, what is happening in the scene, where
something is located, or how the image should be described.

## Components

- [Cheetah Streaming Speech-to-Text](https://picovoice.ai/docs/cheetah/)
- [Orca Streaming Text-to-Speech](https://picovoice.ai/docs/orca/)
- [picoLLM Inference](https://picovoice.ai/docs/picollm/)

## Implementations

- [Python](python)
