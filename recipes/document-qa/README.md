# Document Q&A with RAG

On-device document question answering: ask questions about a local document by voice and hear the answer spoken back. It runs as a private, local RAG pipeline, fully on-device with no cloud and no data leaving the device.

picoLLM handles both retrieval and generation. It embeds the document, retrieves the passages most relevant to your question, and generates an answer grounded in them. Cheetah Streaming Speech-to-Text transcribes the spoken question, and Orca Streaming Text-to-Speech speaks the answer as it is generated.

[![Document Question Answering](https://img.youtube.com/vi/zauxKghiqKE/hqdefault.jpg)](https://www.youtube.com/watch?v=zauxKghiqKE)

## Components

- [Cheetah Streaming Speech-to-Text](https://picovoice.ai/docs/cheetah/)
- [Orca Streaming Text-to-Speech](https://picovoice.ai/docs/orca/)
- [picoLLM Inference](https://picovoice.ai/docs/picollm/)

## Implementations

- [Android](android)
- [iOS](ios)
- [Python](python)
- [Web](web)
  
## How it works
1. Chunk the document into overlapping passages.
2. Embed each chunk with [picoLLM](https://picovoice.ai/docs/picollm/) and cache the embeddings on disk, so later runs skip re-embedding.
3. Transcribe the spoken question with [Cheetah](https://picovoice.ai/docs/cheetah/), embed it, and retrieve the most similar passages by semantic search.
4. Generate the answer with [picoLLM](https://picovoice.ai/docs/picollm/), grounded only in the retrieved passages. If the answer is not in the document, it says so instead of guessing.
5. Stream the answer to [Orca](https://picovoice.ai/docs/orca/) and speak it sentence by sentence as it is produced.

## FAQ

**Does it run fully on-device?**
Yes. Indexing, retrieval, transcription, answer generation, and speech all run on-device, with no network calls and no data leaving the device.

**How does it avoid making up answers?**
It answers only from the passages retrieved from your document. If the answer is not in them, it tells you it does not know rather than guessing.

**Does it re-read the whole document for every question?**
No. The document is chunked and embedded once, then the embeddings are cached on disk and reused across questions and runs.

**Can I ask questions by voice?**
Yes. You ask out loud, [Cheetah](https://picovoice.ai/docs/cheetah/) transcribes the question, and [Orca](https://picovoice.ai/docs/orca/) speaks the grounded answer back as it streams.

More FAQs can be found on [Picovoice website](https://picovoice.ai/cookbook/on-device-ai-call-screening/).
