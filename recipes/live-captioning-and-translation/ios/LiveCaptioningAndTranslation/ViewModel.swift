//
//  Copyright 2026 Picovoice Inc.
//  You may not use this file except in compliance with the license. A copy of the license is located in the "LICENSE"
//  file accompanying this source.
//  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
//  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
//  specific language governing permissions and limitations under the License.
//

import Cheetah
import Zebra
import ios_voice_processor

import Combine
import Foundation

enum ChatState {
    case SELECTING
    case LOADING
    case LISTENING
    case ERROR
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

class ViewModel: ObservableObject {

    private let ACCESS_KEY = "${YOUR_ACCESS_KEY_HERE}"

    private var cheetah: Cheetah?
    private var zebra: Zebra?

    private var audioStream: AudioPlayerStream?

    @Published var chatState: ChatState = .SELECTING

    @Published var selectedSourceLanguage: String = "invalid"
    @Published var selectedTargetLanguage: String = "invalid"

    static let statusTextDefault = ""
    @Published var statusText = statusTextDefault

    @Published var promptText = ""
    @Published var enableGenerateButton = true

    @Published var chatText: [Message] = []
    var translatedIndex: Int = 0

    @Published var errorMessage = ""

    deinit {
        unloadEngines()
    }

    public func selectedSourceLanguageChange() {
        selectedTargetLanguage = "invalid"
    }

    public func selectedTargetLanguageChange() {
        if chatState != .SELECTING {
            unloadEngines()
        }
        if selectedTargetLanguage != "invalid" && selectedSourceLanguage != "invalid" {
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

                setStatusText("Loading Audio Player...")
                audioStream = try AudioPlayerStream(sampleRate: Double(Cheetah.sampleRate))

                setStatusText("Loading Voice Processor...")
                VoiceProcessor.instance.addFrameListener(VoiceProcessorFrameListener(audioCallback))
                VoiceProcessor.instance.addErrorListener(VoiceProcessorErrorListener(errorCallback))
                startAudioRecording()

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
        cheetah = nil
        zebra = nil

        errorMessage = ""
        promptText = ""
        chatText.removeAll()

        chatState = .SELECTING
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

    private func appendChatText(text: String) {
        DispatchQueue.main.async { [self] in
            if chatText.count > 0 {
                chatText[chatText.count - 1].appendTranscript(text: text)
            }
        }
    }

    private func flushChatText() {
        DispatchQueue.main.async { [self] in
            if chatText.count > 0 && !chatText[chatText.count - 1].transcript.isEmpty {
                chatText[chatText.count - 1].appendTranslated(text: "")
                chatText.append(Message(transcript: ""))
                translate()
            }
        }
    }
    
    private func translate() {
        DispatchQueue.global(qos: .userInitiated).async { [self] in
            do {
                let index = translatedIndex;
                translatedIndex += 1;
                
                let translation = try self.zebra!.translate(text: chatText[index].transcript)
                
                DispatchQueue.main.async { [self] in
                    if chatText.count > 0 {
                        chatText[index].appendTranslated(text: translation)
                    }
                }
            } catch {
                DispatchQueue.main.async { [self] in
                    errorMessage = "\(error.localizedDescription)"
                }
            }
        }
    }

    private func audioCallback(frame: [Int16]) {
        do {
            if chatState == .LISTENING {
                let partialTranscript = try self.cheetah!.process(frame)
                appendChatText(text: partialTranscript.0)

                if partialTranscript.1 {
                    let finalTranscript = try self.cheetah!.flush()
                    appendChatText(text: finalTranscript)
                    flushChatText()
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
