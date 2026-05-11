//
//  Copyright 2026 Picovoice Inc.
//  You may not use this file except in compliance with the license. A copy of the license is located in the "LICENSE"
//  file accompanying this source.
//  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
//  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
//  specific language governing permissions and limitations under the License.
//

import Bat
import Cheetah
import Orca
import Zebra
import ios_voice_processor

import Combine
import Foundation

enum ChatState {
    case SELECTING
    case LOADING
    case DETECTING
    case LISTENING
    case TRANSLATING
    case ERROR
}

let LANGUAGE_DISPLAY: [String: String] = [
    "automatic": "Automatic",
    "de": "German",
    "en": "English",
    "es": "Spanish",
    "fr": "French",
    "it": "Italian"
]

let LANGUAGE_PAIRS: [String: [String]] = [
  "automatic": [
    "de",
    "en",
    "es",
    "fr",
    "it"
  ],
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

    private var bat: Bat?
    private var cheetah: Cheetah?
    private var zebra: Zebra?
    private var orca: Orca?

    private var audioStream: AudioPlayerStream?

    private var pcmBuffer: [Int16] = []

    @Published var dotIndex = 0
    private var timer: Timer?

    @Published var chatState: ChatState = .SELECTING

    @Published var selectedSourceLanguage: String = "automatic"
    @Published var selectedTargetLanguage: String = "invalid"

    @Published var isPaused = false

    static let statusTextDefault = ""
    @Published var statusText = statusTextDefault

    @Published var promptText = ""
    @Published var enableGenerateButton = true

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
        if chatState != .DETECTING {
            selectedTargetLanguage = "invalid"
        } else {
            startDemo()
        }
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
        if selectedSourceLanguage == "automatic" {
            loadBat()
        } else {
            loadEngines()
        }
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
                let cheetahModelPath = Bundle(for: type(of: self))
                    .path(forResource: "cheetah_params_\(sourceLanguage)", ofType: "pv")!
                cheetah = try Cheetah(
                    accessKey: ACCESS_KEY,
                    modelPath: cheetahModelPath,
                    endpointDuration: 1.0,
                    enableAutomaticPunctuation: true,
                    enableTextNormalization: true)

                setStatusText("Loading Zebra \(sourceLanguage)_\(targetLanguage)...")
                let zebraModelPath = Bundle(for: type(of: self))
                    .path(forResource: "zebra_params_\(sourceLanguage)_\(targetLanguage)", ofType: "pv")!
                zebra = try Zebra(accessKey: ACCESS_KEY, modelPath: zebraModelPath)

                setStatusText("Loading Orca \(targetLanguage)...")
                let orcaModelPath = Bundle(for: type(of: self))
                    .path(forResource: "orca_params_\(targetLanguage)_male", ofType: "pv")!
                orca = try Orca(accessKey: ACCESS_KEY, modelPath: orcaModelPath)

                setStatusText("Loading Audio Player...")
                audioStream = try AudioPlayerStream(sampleRate: Double(self.orca!.sampleRate!))

                setStatusText("Loading Voice Processor...")
                if bat != nil {
                    bat!.delete()
                } else {
                    VoiceProcessor.instance.addFrameListener(VoiceProcessorFrameListener(audioCallback))
                    VoiceProcessor.instance.addErrorListener(VoiceProcessorErrorListener(errorCallback))
                    startAudioRecording()
                }
                DispatchQueue.main.async { [self] in
                    isPaused = false
                }

                setStatusText(ViewModel.statusTextDefault)
                DispatchQueue.main.async { [self] in
                    chatState = .LISTENING
                    chatText.removeAll()
                    chatText.append(Message(transcript: ""))
                }
            } catch {
                DispatchQueue.main.async { [self] in
                    unloadEngines()
                    errorMessage = "\(error.localizedDescription)"
                }
            }
        }
    }

    public func loadBat() {
        errorMessage = ""
        statusText = ""

        DispatchQueue.global(qos: .userInitiated).async { [self] in
            let setStatusText = {(_ msg: String) in
                DispatchQueue.main.async { [self] in
                    statusText = msg
                }
            }
            do {
                setStatusText("Loading Bat...")
                bat = try Bat(accessKey: ACCESS_KEY)

                setStatusText("Loading Voice Processor...")
                VoiceProcessor.instance.addFrameListener(VoiceProcessorFrameListener(audioCallback))
                VoiceProcessor.instance.addErrorListener(VoiceProcessorErrorListener(errorCallback))
                startAudioRecording()

                setStatusText("Start speaking in source language")
                DispatchQueue.main.async { [self] in
                    chatState = .DETECTING
                    chatText.removeAll()
                    chatText.append(Message(transcript: ""))
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

        if cheetah != nil {
            cheetah!.delete()
        }
        if zebra != nil {
            zebra!.delete()
        }
        if orca != nil {
            orca!.delete()
        }
        cheetah = nil
        zebra = nil
        orca = nil

        errorMessage = ""
        promptText = ""
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
                    let translation = try self.zebra!.translate(text: chatText[chatText.count - 1].transcript)

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
                        chatText.append(Message(transcript: ""))
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
                pcmBuffer.append(contentsOf: frame)

                var isFlushed = false
                while pcmBuffer.count >= Cheetah.frameLength {
                    let partialTranscript = try self.cheetah!.process(Array(pcmBuffer[0..<Int(Cheetah.frameLength)]))
                    pcmBuffer.removeFirst(Int(Cheetah.frameLength))
                    appendChatText(text: partialTranscript.0, translated: false)

                    if partialTranscript.1 {
                        let finalTranscript = try self.cheetah!.flush()
                        appendChatText(text: finalTranscript, translated: false)
                        appendChatText(text: " ", translated: false)

                        if chatText.count > 0 && !chatText[chatText.count - 1].transcript.isEmpty {
                            isFlushed = true
                        }
                    }
                }
                if isFlushed {
                    translateAndSpeak()
                }
            } else if chatState == .DETECTING {
                pcmBuffer.append(contentsOf: frame)

                var foundLanguage = BatLanguages.UNKNOWN
                if pcmBuffer.count >= Bat.frameLength {
                    let bufferStart = pcmBuffer.count - Int(Bat.frameLength)
                    let bufferEnd = pcmBuffer.count

                    let scores = try bat!.process(Array(pcmBuffer[bufferStart..<bufferEnd]))
                    if scores != nil {
                        for (identified, confidence) in scores! where confidence >= BAT_THRESHOLD {
                            foundLanguage = identified
                        }
                    }
                }

                if foundLanguage != BatLanguages.UNKNOWN {
                    let foundLanguageString = foundLanguage.toString()
                    if LANGUAGE_PAIRS.keys.contains(foundLanguageString) &&
                        LANGUAGE_PAIRS[foundLanguageString]!.contains(selectedTargetLanguage) {

                        DispatchQueue.main.async { [self] in
                            selectedSourceLanguage = foundLanguageString
                        }
                    } else {
                        DispatchQueue.main.async { [self] in
                            statusText = "Cannot translate from \(foundLanguageString) to \(selectedTargetLanguage)"
                        }
                    }
                }
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
