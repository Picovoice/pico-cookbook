# Call Assist

On-device AI call screening that understands who is calling and why. Call Assist answers an incoming call for you, asks the caller to identify themselves and explain why they're calling, transcribes the response in real time, and uses a local LLM to extract the caller's identity and reason.

If the caller doesn't give enough detail, it follows up automatically and asks for what's missing. Once it has enough, it tells you who is calling and why, then carries out your spoken choice: connect, decline, ask for more details, or block the caller.

The whole interaction runs on-device, combining real-time speech-to-text, text-to-speech, speech-to-intent, and local LLM inference for private, low-latency call screening with no audio or call content sent to the cloud.

[![Call Assist demo](https://img.youtube.com/vi/ZC19XkgqJCg/hqdefault.jpg)](https://www.youtube.com/shorts/ZC19XkgqJCg)

## Components

- [Cheetah Streaming Speech-to-Text](https://picovoice.ai/docs/cheetah/)
- [Orca Streaming Text-to-Speech](https://picovoice.ai/docs/orca/)
- [picoLLM Inference](https://picovoice.ai/docs/picollm/)
- [Rhino Speech-to-Intent](https://picovoice.ai/docs/rhino/)

## Implementations

- [Android](android)
- [iOS](ios)
- [Python](python)
- [Web](web)

## How it works
1. [Orca](https://picovoice.ai/docs/orca/) answers the call and greets the caller, asking them to say who they are and why they're calling.
2. [Cheetah](https://picovoice.ai/docs/cheetah/) transcribes the caller's response in real time.
3. [picoLLM](https://picovoice.ai/docs/picollm/) extracts the caller's identity and reason from the transcript.
4. If either is missing, Call Assist asks the caller for the missing detail and tries again, up to a retry limit. If it still doesn't have enough, it declines the call.
5. [Orca](https://picovoice.ai/docs/orca/) announces who is calling and why.
6. [Rhino](https://picovoice.ai/docs/rhino/) recognizes your spoken command and acts on it: connect, decline, ask for more details, ask the caller to text, email, or call back, or block the caller.

## FAQ

**Is it fully on-device?**
Yes. Cheetah, picoLLM, Rhino, and Orca, all run on-device, with no call content (audio or text) sent to the cloud.

**What happens if the caller won't identify themselves?**
Call Assist asks again for the missing details, then declines the call. You can fork and adjust it to change the number of times the app asks or trigger a different action.

**What can it do with a call?**
Connect it, decline it, block the caller, ask the caller to text, email, or call back, or ask for more details, each triggered by your spoken command.

More FAQs can be found on [Picovoice website](https://picovoice.ai/cookbook/ai-call-assist/).
