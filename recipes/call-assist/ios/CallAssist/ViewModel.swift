//
//  Copyright 2026 Picovoice Inc.
//  You may not use this file except in compliance with the license. A copy of the license is located in the "LICENSE"
//  file accompanying this source.
//  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
//  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
//  specific language governing permissions and limitations under the License.
//

import Cheetah
import Orca
import PicoLLM
import Rhino
import ios_voice_processor

import Foundation

enum ViewState {
  case loading, main
}

enum ListenState {
    case idle, caller, command
}

enum TextMode {
    case caller, ai, user
}

struct DottedText: Identifiable {
    let id: UUID = UUID()
    var content: String = ""
    var withDots: Bool = true
}

let DOTS = [
    " .  ",
    " .. ",
    " ...",
    "  ..",
    "   .",
    "    "
]

enum Action: String {
    case GREET = "Greet"
    case CONNECT_CALL = "Connect Call"
    case DECLINE_CALL = "Decline Call"
    case ASK_FOR_DETAILS = "Ask for Details"
    case ASK_TO_TEXT = "Ask to Text"
    case ASK_TO_EMAIL = "Ask to Email"
    case ASK_TO_CALL_BACK = "Ask to Call Back"
    case BLOCK_CALLER = "Block Caller"

    public func prompt(name: String) -> String {
        switch self {
        case .GREET:
            return "Hi, \(name) can't answer right now. Please say your name and why you're calling."
        case .CONNECT_CALL:
            return "Okay, one moment while I connect you."
        case .DECLINE_CALL:
            return "Sorry, \(name) is unavailable right now."
        case .ASK_FOR_DETAILS:
            return "Can you briefly say who you are and what this is regarding?"
        case .ASK_TO_TEXT:
            return "\(name) can't talk right now. Please send a text message instead."
        case .ASK_TO_EMAIL:
            return "Please send the details by email. Thank you."
        case .ASK_TO_CALL_BACK:
            return "\(name) can't take your call right now. Please call back later."
        case .BLOCK_CALLER:
            return "This number is not accepting calls."
        }
    }
}

let SYSTEM = """
Extract call information.

Return exactly two lines:
caller: <one short value>
reason: <one short value>

Rules:
- Use exactly one value for caller.
- Use exactly one value for reason.
- Do not list alternatives.
- Do not use commas.
- Do not explain.
- If the caller says a company or organization, use that as caller.
- If the caller says only a generic role like customer service, use that as caller.
- If the caller does not say who they are, use unknown.
- If the caller does not say why they are calling, use unknown.
- Use lowercase unless the caller gives a proper name.

Examples:
Caller said: "I'm calling from the bank."
caller: bank
reason: unknown

Caller said: "This is UPS with a package delivery."
caller: UPS
reason: package delivery

Caller said: "This is customer service."
caller: customer service
reason: unknown

Caller said: "I'm calling about your credit card."
caller: unknown
reason: credit card

Caller said: "Hello, can you hear me?"
caller: unknown
reason: unknown
"""

class ViewModel: ObservableObject {
    @Published var viewState: ViewState = .loading
    @Published var statusText: String = ""
    @Published var enginesLoaded: Bool = false

    @Published var callerTextHistory = [DottedText]()
    @Published var aiTextHistory = DottedText()
    @Published var userTextHistory = DottedText()
    private var activeText = ""

    @Published var dotIndex = 0
    private var timer: Timer?

    @Published var listenState: ListenState = .idle
    private var callerTranscript: String = ""
    private let retryLimit = 2
    private var retryCount = 0

    private let ACCESS_KEY = "${YOUR_ACCESS_KEY_HERE}"

    private let USERNAME = "${YOUR_USERNAME_HERE}"

    private var cheetah: Cheetah?
    private var orca: Orca?
    private var picollm: PicoLLM?
    private var rhino: Rhino?

    private var audioStream: AudioPlayerStream?

    func setStatusText(text: String) {
        DispatchQueue.main.async { [self] in
            viewState = .loading
            statusText = text
        }
    }

    func sendText(content: String, mode: TextMode) {
        DispatchQueue.main.async { [self] in
            if mode == .caller {
                if callerTextHistory.isEmpty || !callerTextHistory.last!.withDots {
                    callerTextHistory.append(DottedText())
                }

                callerTextHistory[callerTextHistory.count - 1].content += content
            } else if mode == .ai {
                if !aiTextHistory.withDots {
                    aiTextHistory = DottedText()
                }

                aiTextHistory.content += content
            } else if mode == .user {
                if !userTextHistory.withDots {
                    userTextHistory = DottedText()
                }

                userTextHistory.content += content
            }
        }
    }

    func flushText(mode: TextMode) {
        DispatchQueue.main.async { [self] in
            if mode == .caller {
                callerTextHistory[callerTextHistory.count - 1].withDots = false
            } else if mode == .ai {
                aiTextHistory.withDots = false
            } else if mode == .user {
                userTextHistory.withDots = false
            }
        }
    }

    func startDemo() {
        DispatchQueue.main.async { [self] in
            callerTextHistory.removeAll()
            aiTextHistory = DottedText()
            userTextHistory = DottedText()
            retryCount = 0

            viewState = .main
            actionGreet()
        }
    }

    func stopDemo(delay: Float = 0.0) {
        DispatchQueue.main.async { [self] in
            Task {
                do {
                    try await Task.sleep(for: .milliseconds(Int(delay * 1000)))
                } catch {}

                viewState = .loading
            }
        }
    }

    func setListenState(state: ListenState) {
        DispatchQueue.main.async { [self] in
            listenState = state
        }
    }

    func withDots(item: DottedText) -> String {
        if item.content.isEmpty {
            return ""
        } else if item.withDots {
            return item.content + DOTS[dotIndex]
        } else {
            return item.content
        }
    }

    init() {
        timer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
            self!.dotIndex = (self!.dotIndex + 1) % DOTS.count
        }

        loadEngines()
    }

    func loadEngines() {
        unloadEngines()

        DispatchQueue.global(qos: .userInitiated).async { [self] in
            do {
                setStatusText(text: "Loading Cheetah...")
                guard let cheetahModelPath = Bundle(for: type(of: self)).path(forResource: "cheetah_params", ofType: "pv") else {
                    throw NSError(domain: "cheetah_params not found", code: 0)
                }
                cheetah = try Cheetah(accessKey: ACCESS_KEY, modelPath: cheetahModelPath, device: "cpu:1")

                setStatusText(text: "Loading Orca...")
                guard let orcaModelPath = Bundle(for: type(of: self)).path(forResource: "orca_params_en_female", ofType: "pv") else {
                    throw NSError(domain: "orca_params_female not found", code: 0)
                }
                orca = try Orca(accessKey: ACCESS_KEY, modelPath: orcaModelPath, device: "cpu:1")

                setStatusText(text: "Loading picoLLM...")
                guard let picollmModelPath = Bundle(for: type(of: self)).path(forResource: "picollm_model", ofType: "pllm") else {
                    throw NSError(domain: "picollm_model not found", code: 0)
                }
                picollm = try PicoLLM(accessKey: ACCESS_KEY, modelPath: picollmModelPath, device: "cpu:2")

                setStatusText(text: "Loading Rhino...")
                guard let rhinoModelPath = Bundle(for: type(of: self)).path(forResource: "rhino_model", ofType: "rhn") else {
                    throw NSError(domain: "rhino_model not found", code: 0)
                }
                rhino = try Rhino(accessKey: ACCESS_KEY, contextPath: rhinoModelPath, device: "cpu:1")

                setStatusText(text: "Loading Audio Player...")
                audioStream = try AudioPlayerStream(sampleRate: Double(self.orca!.sampleRate!))

                setStatusText(text: "Loading Voice Processor...")
                VoiceProcessor.instance.addFrameListener(VoiceProcessorFrameListener(audioCallback))

                startRecording()

                DispatchQueue.main.async { [self] in
                    setStatusText(text: "Press the Start Demo button to begin.")
                    enginesLoaded = true
                }
            } catch {
                DispatchQueue.main.async { [self] in
                    setStatusText(text: error.localizedDescription)
                    unloadEngines()
                }
            }
        }
    }

    func unloadEngines() {
        enginesLoaded = false

        stopRecording()

        audioStream = nil
        VoiceProcessor.instance.clearFrameListeners()
        VoiceProcessor.instance.clearErrorListeners()

        if cheetah != nil {
            cheetah!.delete()
            cheetah = nil
        }

        if orca != nil {
            orca!.delete()
            orca = nil
        }

        if picollm != nil {
            picollm!.delete()
            picollm = nil
        }

        if rhino != nil {
            rhino!.delete()
            rhino = nil
        }
    }

    deinit {
        unloadEngines()
    }

    func startRecording() {
        DispatchQueue.main.sync {
            do {
                try VoiceProcessor.instance.start(
                    frameLength: Cheetah.frameLength,
                    sampleRate: Cheetah.sampleRate)
            } catch {
                setStatusText(text: error.localizedDescription)
            }
        }
    }

    func stopRecording() {
        do {
            try VoiceProcessor.instance.stop()
        } catch {
            DispatchQueue.main.async { [self] in
                setStatusText(text: error.localizedDescription)
            }
        }
    }

    func audioCallback(frame: [Int16]) {
        if listenState == .caller {
            listenCaller(frame: frame)
        } else if listenState == .command {
            listenCommand(frame: frame)
        }
    }

    func listenCaller(frame: [Int16]) {
        do {
            let (transcript, endpoint) = try cheetah!.process(frame)
            sendText(content: transcript, mode: .caller)
            callerTranscript += transcript

            if endpoint {
                let flush = try cheetah!.flush()
                sendText(content: flush, mode: .caller)
                callerTranscript += flush
                flushText(mode: .caller)
                setListenState(state: .idle)

                processCaller(transcript: callerTranscript)
                callerTranscript = ""
            }
        } catch {
            setStatusText(text: error.localizedDescription)
        }
    }

    func listenCommand(frame: [Int16]) {
        do {
            if try rhino!.process(pcm: frame) {
                let inference = try rhino!.getInference()
                if inference.isUnderstood && inference.intent == "chooseAction" {
                    setListenState(state: .idle)
                    flushText(mode: .user)

                    let action = Action(rawValue: inference.slots["action"]!)!
                    switch action {
                    case .GREET:
                        actionGreet()
                    case .CONNECT_CALL:
                        actionTerminal(action: action)
                    case .DECLINE_CALL:
                        actionTerminal(action: action)
                    case .ASK_FOR_DETAILS:
                        actionAskForDetails()
                    case .ASK_TO_TEXT:
                        actionTerminal(action: action)
                    case .ASK_TO_EMAIL:
                        actionTerminal(action: action)
                    case .ASK_TO_CALL_BACK:
                        actionTerminal(action: action)
                    case .BLOCK_CALLER:
                        actionTerminal(action: action)
                    }
                }
            }
        } catch {
            setStatusText(text: error.localizedDescription)
        }
    }

    func actionGreet() {
        DispatchQueue.global(qos: .userInitiated).async { [self] in
            sendText(content: "[AI] ", mode: .caller)
            speak(
                text: Action.GREET.prompt(name: USERNAME),
                mode: .caller,
                after: { [self] in
                    sendText(content: "[CALLER] ", mode: .caller)
                    setListenState(state: .caller)
                })
        }
    }

    func actionAskForDetails() {
        DispatchQueue.global(qos: .userInitiated).async { [self] in
            sendText(content: "[AI] ", mode: .caller)
            speak(
                text: Action.ASK_FOR_DETAILS.prompt(name: USERNAME),
                mode: .caller,
                after: { [self] in
                    sendText(content: "[CALLER] ", mode: .caller)
                    setListenState(state: .caller)
                })
        }
    }

    func actionTerminal(action: Action) {
        DispatchQueue.global(qos: .userInitiated).async { [self] in
            sendText(content: "[AI] ", mode: .caller)
            speak(
                text: action.prompt(name: USERNAME),
                mode: .caller,
                after: { [self] in
                    stopDemo(delay: 1.0)
                })
        }
    }

    func processCaller(transcript: String) {
        sendText(content: "[AI] ", mode: .ai)

        DispatchQueue.global(qos: .userInitiated).async { [self] in
            do {
                let dialog = try picollm!.getDialog(system: SYSTEM)
                try dialog.addHumanRequest(content: "Caller Said: \"\(transcript)\"")
                let prompt = try dialog.prompt()

                let completion = try picollm!.generate(
                    prompt: prompt,
                    stopPhrases: ["<|eot_id|>"])
                let completionText = completion.completion
                    .trimmingCharacters(in: .whitespacesAndNewlines)
                    .replacingOccurrences(of: "<|eot_id|>", with: "")
                let (caller, reason) = extractCallerReason(completion: completionText)
                processCallerReason(caller: caller, reason: reason)
            } catch {
                setStatusText(text: error.localizedDescription)
            }
        }
    }

    func extractCallerReason(completion: String) -> (String, String) {
        let lines = completion.split(separator: "\n")
        if lines.count != 2 {
            return ("unknown", "unknown")
        }

        if !lines[0].lowercased().starts(with: "caller: ") {
            return ("unknown", "unknown")
        }

        if !lines[1].lowercased().starts(with: "reason: ") {
            return ("unknown", "unknown")
        }

        let caller = lines[0].dropFirst(8)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let reason = lines[1].dropFirst(8)
            .trimmingCharacters(in: .whitespacesAndNewlines)

        if  caller.isEmpty || reason.isEmpty {
            return ("unknown", "unknown")
        }

        return (caller, reason)
    }

    func processCallerReason(caller: String, reason: String) {
        if caller == "unknown" || reason == "unknown" {
            if retryCount < retryLimit {
                let outputText = formatAskForDetails(caller: caller, reason: reason)
                sendText(content: outputText, mode: .ai)
                flushText(mode: .ai)

                retryCount += 1
                actionAskForDetails()
            } else {
                retryCount = 0
                let outputText = "Couldn't understand caller's identity and agenda after \(retryLimit) inquiries. Declining their call."
                speak(
                    text: outputText,
                    mode: .ai,
                    after: {[self] in
                        actionTerminal(action: Action.DECLINE_CALL)
                    })
            }
        } else {
            speak(
                text: "\(caller) is trying to speak to you about \(reason)",
                mode: .ai,
                after: {[self] in
                    sendText(content: "[\(USERNAME)] ", mode: .user)
                    setListenState(state: .command)
                }
            )
        }
    }

    func formatAskForDetails(caller: String, reason: String) -> String {
        if caller == "unknown" && reason == "unknown" {
            return "Unknown caller with no specified reason. I will ask for more information."
        } else if caller == "unknown" {
            return "Unknown caller is trying to speak to you about `\(reason)`. I will ask for their identity."
        } else if reason == "unknown" {
            return "`\(caller)` is trying to speak with you. I will ask for their reason."
        } else {
            return ""
        }
    }

    func speak(text: String, mode: TextMode, after: (() -> Void)?) {
        DispatchQueue.global(qos: .userInitiated).async { [self] in
            Task {
                do {
                    let audio = try orca!.synthesize(text: text)

                    try audioStream!.playStreamPCM(audio.pcm)

                    var currentTime: Float = 0.0
                    for (index, word) in audio.wordArray.enumerated() {
                        let duration = Int((word.startSec - currentTime) * 1000)
                        try await Task.sleep(for: .milliseconds(duration))
                        currentTime = word.startSec

                        sendText(content: word.word, mode: mode)

                        if index + 1 < audio.wordArray.count && !audio.wordArray[index + 1].word.first!.isPunctuation {
                            sendText(content: " ", mode: mode)
                        }
                    }

                    let duration = Int((audio.wordArray.last!.endSec - currentTime) * 1000)
                    try await Task.sleep(for: .milliseconds(duration))

                    flushText(mode: mode)
                } catch {
                    setStatusText(text: error.localizedDescription)
                }

                if after != nil {
                    after!()
                }
            }
        }
    }
}
