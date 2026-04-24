# Call Assist

Screen unknown calls with real-time transcription, natural voice responses, and on-device LLM reasoning. The assistant
answers an incoming call on behalf of the user, asks the caller to identify themselves and explain why they are calling,
transcribes the caller’s response, and uses a local LLM to extract the caller’s identity and reason for calling.

If the caller does not provide enough information, the assistant follows up automatically and asks for the missing
details. Once it has enough context, it summarizes the call for the user and lets them choose what to do next, such as
'connecting the call, declining it, asking for more details, requesting a text or email, asking the caller to call back
later, or blocking the caller.

The entire interaction is powered by on-device AI, combining real-time speech-to-text, text-to-speech, speech-to-intent,
and local language model inference to create a private, low-latency call-screening experience without sending audio or
call content to the cloud.

## Components

- [Cheetah Streaming Speech-to-Text](https://picovoice.ai/docs/cheetah/)
- [Orca Streaming Text-to-Speech](https://picovoice.ai/docs/orca/)
- [picoLLM Inference](https://picovoice.ai/docs/picollm/)
- [Rhino Speech-to-Intent](https://picovoice.ai/docs/rhino/)

## Implementations

- [Python](python)