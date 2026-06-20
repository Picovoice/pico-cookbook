# Call Screen

On-device AI call screening with real-time transcription and natural voice responses, no LLM required. Call Screen answers an incoming call for you, plays spoken prompts to the caller, and transcribes what they say live on screen so you can see who is calling and why.

You stay in control: based on the transcription, you choose what happens next with a voice command, connect, decline, ask the caller for more details, or block them.

The whole interaction runs on-device, combining real-time speech-to-text, text-to-speech, and speech-to-intent for private, low-latency call screening with no audio or call content sent to the cloud.

[![Call Screen](https://img.youtube.com/vi/2UZtCNGHPFM/0.jpg)](https://www.youtube.com/watch?v=2UZtCNGHPFM)

## Components

- [Cheetah Streaming Speech-to-Text](https://picovoice.ai/docs/cheetah/)
- [Orca Streaming Text-to-Speech](https://picovoice.ai/docs/orca/)
- [Rhino Speech-to-Intent](https://picovoice.ai/docs/rhino/)

## Implementations

- [Android](android)
- [iOS](ios)
- [Python](python)
- [Web](web)

## How it works
1. [Orca](https://picovoice.ai/docs/orca/) answers the call and greets the caller, asking for their name and reason for calling.
2. [Cheetah](https://picovoice.ai/docs/cheetah/) transcribes what the caller says in real time and shows it on screen.
3. [Rhino](https://picovoice.ai/docs/rhino/) recognizes your spoken command: connect, decline, ask for more details, ask the caller to text, email, or call back, or block the caller.
4. [Orca](https://picovoice.ai/docs/orca/) speaks the chosen response to the caller. If you ask for more details, Cheetah transcribes the caller again; terminal actions end the call.

## FAQ

**Is it fully on-device?**
Yes. Cheetah transcription, Orca prompts, and Rhino intent detection all run on-device, with no audio or call content sent to the cloud.

**Does it hallucinate?**
No. Call Screen transcribes the caller's speech as-is and lets the end users decide what to do.

**What can it do with a call?**
Connect it, decline it, block the caller, ask the caller to text, email, or call back, or ask for more details, each triggered by a spoken command. You can fork and change the commands, e.g., add new actions or remove the existing ones.

**Can the caller's response be saved or read back?**
Cheetah transcribes the caller live on screen, so end users can read who is calling and why before making a decision. Nothing is sent off-device.

More FAQs can be found on [Picovoice website](https://picovoice.ai/cookbook/on-device-ai-call-screening/).
