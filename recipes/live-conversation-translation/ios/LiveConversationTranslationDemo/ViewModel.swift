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
import Zebra
import ios_voice_processor

import Combine
import Foundation

enum ChatState {
    case SELECTING
    case LOADING
    case LISTENING
    case TRANSLATING
    case ERROR
}

enum TranslationDirection {
    case ltr
    case rtl
}

let LANGUAGE_DISPLAY: [String: String] = [
    "de": "German",
    "en": "English",
    "es": "Spanish",
    "fr": "French",
    "it": "Italian"
]

let LANGUAGE_PAIRS: [String: [String]] = [
  "de": [
    "en",
    "es",
    "fr",
    "it"
  ],
  "en": [
    "de",
    "es",
    "fr",
    "it"
  ],
  "es": [
    "de",
    "en",
    "fr",
    "it"
  ],
  "fr": [
    "de",
    "en",
    "es"
  ],
  "it": [
    "de",
    "en",
    "es"
  ]
]

let DOTS = [
    " .  ",
    " .. ",
    " ...",
    "  ..",
    "   .",
    "    "
]

let BAT_THRESHOLD: Float32 = 0.75

class ViewModel: ObservableObject {

    private let ACCESS_KEY = "${YOUR_ACCESS_KEY_HERE}"

    private var cheetah_0: Cheetah?
    private var cheetah_1: Cheetah?

    private var zebra_0: Zebra?
    private var zebra_1: Zebra?

    private var orca_0: Orca?
    private var orca_1: Orca?

    private var audioStream: AudioPlayerStream?

    private var direction: TranslationDirection = .ltr

    @Published var dotIndex = 0
    private var timer: Timer?

    @Published var chatState: ChatState = .SELECTING

    @Published var selectedSourceLanguage: String = "invalid"
    @Published var selectedTargetLanguage: String = "invalid"

    @Published var isPaused = false

    static let statusTextDefault = ""
    @Published var statusText = statusTextDefault

    @Published var chatText: [Message] = []

    @Published var errorMessage = ""

    deinit {
        unloadEngines()
    }

    func withDots(_ content: String, dots: Bool) -> String {
        if dots && !isPaused {
            return content + DOTS[dotIndex]
        } else {
            return content
        }
    }

    init() {
        timer = Timer.scheduledTimer(withTimeInterval: 0.4, repeats: true) { [weak self] _ in
            self!.dotIndex = (self!.dotIndex + 1) % DOTS.count
        }
    }

    public func selectedSourceLanguageChange() {
        selectedTargetLanguage = "invalid"
    }

    public func selectedTargetLanguageChange() {
        if chatState != .SELECTING {
            unloadEngines()
        }
        if selectedTargetLanguage != "invalid" {
            startDemo()
        }
    }

    public func startDemo() {
        chatState = .LOADING
        loadEngines()
    }

    public func loadEngines() {
        errorMessage = ""
        statusText = ""

        let sourceLanguage = selectedSourceLanguage
        let targetLanguage = selectedTargetLanguage

        DispatchQueue.global(qos: .userInitiated).async { [self] in
            let setStatusText = {(_ msg: String) in
                DispatchQueue.main.async { [self] in
                    statusText = msg
                }
            }
            do {
                setStatusText("Loading Cheetah \(sourceLanguage)...")
                let cheetahModelPath_0 = Bundle(for: type(of: self))
                    .path(forResource: "cheetah_params_\(sourceLanguage)", ofType: "pv")!
                cheetah_0 = try Cheetah(
                    accessKey: ACCESS_KEY,
                    modelPath: cheetahModelPath_0,
                    endpointDuration: 1.0,
                    enableAutomaticPunctuation: true,
                    enableTextNormalization: true)

                setStatusText("Loading Cheetah \(targetLanguage)...")
                let cheetahModelPath_1 = Bundle(for: type(of: self))
                    .path(forResource: "cheetah_params_\(targetLanguage)", ofType: "pv")!
                cheetah_1 = try Cheetah(
                    accessKey: ACCESS_KEY,
                    modelPath: cheetahModelPath_1,
                    endpointDuration: 1.0,
                    enableAutomaticPunctuation: true,
                    enableTextNormalization: true)

                setStatusText("Loading Zebra \(sourceLanguage)_\(targetLanguage)...")
                let zebraModelPath_0 = Bundle(for: type(of: self))
                    .path(forResource: "zebra_params_\(sourceLanguage)_\(targetLanguage)", ofType: "pv")!
                zebra_0 = try Zebra(accessKey: ACCESS_KEY, modelPath: zebraModelPath_0)

                setStatusText("Loading Zebra \(targetLanguage)_\(sourceLanguage)...")
                let zebraModelPath_1 = Bundle(for: type(of: self))
                    .path(forResource: "zebra_params_\(targetLanguage)_\(sourceLanguage)", ofType: "pv")!
                zebra_1 = try Zebra(accessKey: ACCESS_KEY, modelPath: zebraModelPath_1)

                setStatusText("Loading Orca \(targetLanguage)...")
                let orcaModelPath_0 = Bundle(for: type(of: self))
                    .path(forResource: "orca_params_\(targetLanguage)_male", ofType: "pv")!
                orca_0 = try Orca(accessKey: ACCESS_KEY, modelPath: orcaModelPath_0)

                setStatusText("Loading Orca \(sourceLanguage)...")
                let orcaModelPath_1 = Bundle(for: type(of: self))
                    .path(forResource: "orca_params_\(sourceLanguage)_male", ofType: "pv")!
                orca_1 = try Orca(accessKey: ACCESS_KEY, modelPath: orcaModelPath_1)

                setStatusText("Loading Audio Player...")
                audioStream = try AudioPlayerStream(sampleRate: Double(self.orca_0!.sampleRate!))

                setStatusText("Loading Voice Processor...")
                VoiceProcessor.instance.addFrameListener(VoiceProcessorFrameListener(audioCallback))
                VoiceProcessor.instance.addErrorListener(VoiceProcessorErrorListener(errorCallback))
                startAudioRecording()

                DispatchQueue.main.async { [self] in
                    isPaused = false
                }

                setStatusText(ViewModel.statusTextDefault)
                DispatchQueue.main.async { [self] in
                    chatState = .LISTENING
                    direction = .ltr
                    chatText.removeAll()
                    chatText.append(Message(transcript: "", direction: direction))
                }
            } catch {
                DispatchQueue.main.async { [self] in
                    unloadEngines()
                    errorMessage = "\(error.localizedDescription)"
                }
            }
        }
    }

    public func unloadEngines() {
        stopAudioRecording()
        VoiceProcessor.instance.clearFrameListeners()
        VoiceProcessor.instance.clearErrorListeners()

        if cheetah_0 != nil {
            cheetah_0!.delete()
        }
        if cheetah_1 != nil {
            cheetah_1!.delete()
        }

        if zebra_0 != nil {
            zebra_0!.delete()
        }
        if zebra_1 != nil {
            zebra_1!.delete()
        }

        if orca_0 != nil {
            orca_0!.delete()
        }
        if orca_1 != nil {
            orca_1!.delete()
        }

        cheetah_0 = nil
        cheetah_1 = nil
        zebra_0 = nil
        zebra_1 = nil
        orca_0 = nil
        orca_1 = nil

        errorMessage = ""
        chatText.removeAll()

        chatState = .SELECTING
    }

    public func pauseDemo() {
        if isPaused {
            VoiceProcessor.instance.addFrameListener(VoiceProcessorFrameListener(audioCallback))
            isPaused = false
        } else {
            VoiceProcessor.instance.clearFrameListeners()
            isPaused = true
            flush()
        }
    }

    private func startAudioRecording() {
        DispatchQueue.main.sync {
            do {
                try VoiceProcessor.instance.start(
                    frameLength: Cheetah.frameLength,
                    sampleRate: Cheetah.sampleRate)
            } catch {
                errorMessage = "\(error.localizedDescription)"
            }
        }
    }

    private func stopAudioRecording() {
        do {
            try VoiceProcessor.instance.stop()
        } catch {
            DispatchQueue.main.async { [self] in
                errorMessage = "\(error.localizedDescription)"
            }
        }
    }

    private func appendChatText(text: String, translated: Bool) {
        DispatchQueue.main.async { [self] in
            if chatText.count > 0 {
                if translated {
                    chatText[chatText.count - 1].appendTranslated(text: text)
                } else {
                    chatText[chatText.count - 1].appendTranscript(text: text)
                }
            }
        }
    }

    private func translateAndSpeak() {
        DispatchQueue.main.async { [self] in
            chatState = .TRANSLATING
        }

        DispatchQueue.global(qos: .userInitiated).async { [self] in
            Task {
                do {
                    let zebra = direction == .ltr ? self.zebra_0 : self.zebra_1
                    let orca = direction == .ltr ? self.orca_0 : self.orca_1

                    let translation = try zebra!.translate(text: chatText[chatText.count - 1].transcript)
                    let audio = try orca!.synthesize(text: translation)

                    try audioStream!.playStreamPCM(audio.pcm)

                    var currentTime: Float = 0.0
                    for (index, word) in audio.wordArray.enumerated() {
                        let duration = Int((word.startSec - currentTime) * 1000)
                        try await Task.sleep(for: .milliseconds(duration))
                        currentTime = word.startSec

                        appendChatText(text: word.word, translated: true)

                        if index + 1 < audio.wordArray.count && !audio.wordArray[index + 1].word.first!.isPunctuation {
                            appendChatText(text: " ", translated: true)
                        }
                    }

                    let duration = Int((audio.wordArray.last!.endSec - currentTime) * 1000)
                    try await Task.sleep(for: .milliseconds(duration))

                    DispatchQueue.main.async { [self] in
                        chatState = .LISTENING
                        direction = direction == .ltr ? .rtl : .ltr
                        chatText.append(Message(transcript: "", direction: direction))
                    }
                } catch {
                    DispatchQueue.main.async { [self] in
                        errorMessage = "\(error.localizedDescription)"
                    }
                }
            }
        }
    }

    private func audioCallback(frame: [Int16]) {
        do {
            if chatState == .LISTENING {
                let cheetah = direction == .ltr ? self.cheetah_0 : self.cheetah_1

                let partialTranscript = try cheetah!.process(frame)
                appendChatText(text: partialTranscript.0, translated: false)

                if partialTranscript.1 {
                    flush()
                }
            }
        } catch {
            DispatchQueue.main.async { [self] in
                errorMessage = "\(error.localizedDescription)"
            }
        }
    }

    private func flush() {
        do {
            let cheetah = direction == .ltr ? self.cheetah_0 : self.cheetah_1

            let finalTranscript = try cheetah!.flush()
            appendChatText(text: finalTranscript, translated: false)
            appendChatText(text: " ", translated: false)

            if chatText.count > 0 && !chatText[chatText.count - 1].transcript.isEmpty {
                translateAndSpeak()
            }
        } catch {
            DispatchQueue.main.async { [self] in
                errorMessage = "\(error.localizedDescription)"
            }
        }
    }
    
    private func errorCallback(error: VoiceProcessorError) {
        DispatchQueue.main.async { [self] in
            errorMessage = "\(error.localizedDescription)"
        }
    }
}

struct Message: Equatable {
    var transcript: String
    var translated: String?
    var direction: TranslationDirection

    mutating func appendTranscript(text: String) {
        self.transcript.append(text)
    }

    mutating func appendTranslated(text: String) {
        if self.translated != nil {
            self.translated!.append(text)
        } else {
            self.translated = text
        }
    }
}
