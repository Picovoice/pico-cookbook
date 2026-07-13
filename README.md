# Pico Cookbook: On-device AI Examples

[![GitHub](https://img.shields.io/github/license/Picovoice/pico-cookbook)](https://github.com/Picovoice/pico-cookbook)

Made in Vancouver, Canada by [Picovoice](https://picovoice.ai)

[![Twitter URL](https://img.shields.io/twitter/url?label=%40AiPicovoice&style=social&url=https%3A%2F%2Ftwitter.com%2FAiPicovoice)](https://twitter.com/AiPicovoice)<!-- markdown-link-check-disable-line -->
[![YouTube Channel Views](https://img.shields.io/youtube/channel/views/UCAdi9sTCXLosG1XeqDwLx7w?label=YouTube&style=social)](https://www.youtube.com/channel/UCAdi9sTCXLosG1XeqDwLx7w)

On-device AI recipes for enterprise developers building private, real-time apps. Recipes replicate real-world production apps, built with real-time voice, language, and vision understanding.

Enterprise-ready, open-source, and ready to fork and adapt, these on-device AI examples run local inference engines that execute models privately with no cloud dependency, covering a variety of applications: voice assistants, speaker analysis, personalization, and RAG.

## Voice Assistants
- [LLM-Powered Voice Assistant](recipes/llm-voice-assistant): Private, zero-network latency, on-device LLM voice assistant. Runs a local large language model for hands-free, real-time voice-to-voice conversation with no cloud processing.
- [Microcontroller Voice Assistant](recipes/microcontroller-voice-assistant): On-device voice assistant for microcontrollers (MCU). Runs custom wake words and voice commands on constrained embedded and IoT hardware.

## Personalization
- [Personalized Wake Word](recipes/personalized-wake-word): On-device personalized wake word for single-user devices. A custom voice trigger that responds only to one enrolled user, like "Personalized Hey Siri".
- [Speaker-Aware Wake Word](recipes/speaker-aware-wake-word): On-device speaker-aware wake word for shared devices. Identifies which enrolled user spoke the trigger phrase to personalize the experience based on user profile.
- [Speaker-Aware Voice Assistant](recipes/speaker-aware-voice-assistant): On-device voice assistant for shared devices. Recognizes who is speaking and personalizes each response to that user.

## Real-Time Translation
- [Live Captioning and Translation](recipes/live-captioning-and-translation): On-device live captioning with real-time translation, showing source-language and translated captions side by side.
- [Live Conversation Translation](recipes/live-conversation-translation): Real-time two-way conversation translation, fully on-device. Each person speaks their own language and hears the translation.
- [Speech-to-Speech Translation](recipes/speech-to-speech-translation): On-device speech-to-speech translation with automatic language detection. While others speak any language, users hear it in their own.

## Call Screening & Assistance
- [Call Screen](recipes/call-screen): On-device call screening that transcribes and summarizes incoming calls, so users can decide whether to answer, ignore, or block.
- [Call Assist](recipes/call-assist): On-device call assistant that screens and summarizes calls, then acts on them. It can flag likely spam and reject calls.
- [Hands-Free Contact Caller](recipes/hands-free-contact-calling): Call contacts by name, resolve ambiguity, and handle follow-up clarification.

## Document & Image AI (Multimodal)
- [Document Q&A](recipes/document-qa): On-device document question answering with private, local RAG. Embeds and retrieves over your own documents and answers grounded in them, asked by voice and answered aloud, with no data leaving the device.
- [Image Question Answering](recipes/image-qa): On-device image question answering with a vision-language model. Ask about an image by voice and hear the answer spoken back in real time, fully private and offline.
- [Image to Speech](recipes/image-to-speech): On-device OCR-to-speech that reads the text in an image aloud. A local picoLLM OCR model extracts the text, and streaming text-to-speech speaks it, for accessibility and hands-free reading.

## Meeting Intelligence & Transcription
- [Speaker Identification Across Meetings](recipes/speaker-identification-across-meetings): On-device speaker diarization and recognition for meeting recordings. Labels who spoke when and identifies recurring speakers across meetings by voiceprint, processing audio locally with no meeting bot.
- [Voice Memo Assistant](recipes/voice-memo-assistant): On-device voice memo and note-taking assistant. Transcribes spoken memos with speech-to-text, then cleans up and summarizes them with a local LLM, fully private and offline.

## Industrial Voice AI
- [Voice Guided Field Reporting](recipes/voice-guided-field-reporting): Hands-free voice field reporting for on-site data capture. Voice prompts guide the report while speech-to-text transcribes spoken entries with automatic punctuation, fully offline.
- [Voice Guided Maintenance & Inspection](recipes/voice-guided-maintenance-and-inspection): Voice-guided inspection and maintenance workflows for field technicians. Step-by-step voice prompts run the checklist while spoken findings are transcribed hands-free, fully offline.
- [Voice Picking](recipes/voice-picking): Hands-free, voice-directed picking for warehouse and logistics workflows, fully on-device.

## Retail & Commerce
- [Food Ordering](recipes/food-ordering): On-device voice ordering for QSR drive-thru and self-order kiosks. Wake word plus speech-to-intent handles add, remove, and change items, combos, sizes, and quantities, with spoken confirmations and built-in noise suppression for loud counters.
- [Self-Checkout](recipes/self-checkout): Accessible voice self-checkout for grocery and retail kiosks. Reads cart items and prices aloud and takes voice commands for cart management, with adjustable speech rate and volume.
- [Retail Associate](recipes/retail-associate): On-device voice assistant for retail floor associates. Answers hands-free product and aisle lookups, coworker location and shift status, and task assignments.

## Audio Enhancement
- [Real-Time Microphone Noise Removal](recipes/real-time-microphone-noise-removal): On-device, real-time microphone noise suppression that removes background noise from a live mic.


## FAQ
You can find the FAQ on [Picovoice website](https://picovoice.ai/docs/faq/general/).
