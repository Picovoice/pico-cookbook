//
//  Copyright 2024-2025 Picovoice Inc.
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
    case SYNTHESISING
    case SPEAKING
    case ERROR
}

let LANGUAGE_DISPLAY: [String:String] = [
    "automatic": "Automatic",
    "de": "German",
    "en": "English",
    "es": "Spanish",
    "fr": "French",
    "it": "Italian"
]

let LANGUAGE_PAIRS: [String:[String]] = [
  "automatic": [
    "de",
    "en",
    "es",
    "fr",
    "it",
  ],
  "de": [
    "en",
    "es",
    "fr",
    "it",
  ],
  "en": [
    "de",
    "es",
    "fr",
    "it",
  ],
  "es": [
    "de",
    "en",
    "fr",
    "it",
  ],
  "fr": [
    "de",
    "en",
    "es",
  ],
  "it": [
    "de",
    "en",
    "es",
  ]
]

class ViewModel: ObservableObject {

    private let ACCESS_KEY = "${YOUR_ACCESS_KEY_HERE}"

    private var bat: Bat?
    private var cheetah: Cheetah?
    private var zebra: Zebra?
    private var orca: Orca?

    private var audioStream: AudioPlayerStream?

    @Published var chatState: ChatState = .SELECTING

    @Published var selectedSourceLanguage: String = "automatic"
    @Published var selectedTargetLanguage: String = "invalid"

    static let modelLoadStatusTextDefault = """
Start by loading a `.pllm` model file.

You can download directly to your device or airdrop from a Mac.
"""
    @Published var modelLoadStatusText = modelLoadStatusTextDefault
    @Published var enableLoadModelButton = true
    @Published var showFileImporter = false
    @Published var selectedModelUrl: URL?

    @Published var enginesLoaded = false

    static let statusTextDefault = ""
    @Published var statusText = statusTextDefault

    @Published var promptText = ""
    @Published var enableGenerateButton = true

    @Published var chatText: [Message] = []

    @Published var errorMessage = ""

    deinit {
//        if picollm != nil {
//            picollm!.delete()
//        }
    }

    public func selectedSourceLanguageChange() {
        selectedTargetLanguage = "invalid"
    }
    
    public func selectedTargetLanguageChange() {
//        unloadEngines() // TODO: Need to handle this correctly, causes crash!
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
        enableLoadModelButton = false
        
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
                let cheetahModelPath = Bundle(for: type(of: self)).path(forResource: "cheetah_params_\(sourceLanguage)", ofType: "pv")!
                cheetah = try Cheetah(accessKey: ACCESS_KEY, modelPath: cheetahModelPath, endpointDuration: 1.0)

                setStatusText("Loading Zebra \(sourceLanguage)_\(targetLanguage)...")
                let zebraModelPath = Bundle(for: type(of: self)).path(forResource: "zebra_params_\(sourceLanguage)_\(targetLanguage)", ofType: "pv")!
                zebra = try Zebra(accessKey: ACCESS_KEY, modelPath: zebraModelPath)

                setStatusText("Loading Orca \(targetLanguage)...")
                let orcaModelPath = Bundle(for: type(of: self)).path(forResource: "orca_params_\(targetLanguage)_male", ofType: "pv")!
                orca = try Orca(accessKey: ACCESS_KEY, modelPath: orcaModelPath)

                setStatusText("Loading Audio Player...")
                audioStream = try AudioPlayerStream(sampleRate: Double(self.orca!.sampleRate!))

                setStatusText("Loading Voice Processor...")
                VoiceProcessor.instance.addFrameListener(VoiceProcessorFrameListener(audioCallback))
                VoiceProcessor.instance.addErrorListener(VoiceProcessorErrorListener(errorCallback))
                startAudioRecording()

                setStatusText(ViewModel.statusTextDefault)
                DispatchQueue.main.async { [self] in
                    chatState = .LISTENING
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
        enginesLoaded = false
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

//    private func streamCallback(completion: String) {
//        DispatchQueue.main.async { [self] in
//
//            if self.stopPhrases.contains(completion) || chatState != .GENERATE {
//                return
//            }
//
//            completionQueue.async {
//                self.completionArray.append(completion)
//            }
//            chatText[chatText.count - 1].append(text: completion)
//        }
//    }

//    public func generate() {
//        errorMessage = ""
//
//        enableGenerateButton = false
//
//        DispatchQueue.global(qos: .userInitiated).async { [self] in
//            do {
//
//                DispatchQueue.main.async { [self] in
//                    chatText.append(Message(speaker: "picoLLM:", msg: ""))
//                }
//
//                let result = try picollm!.generate(
//                    prompt: dialog!.prompt(),
//                    completionTokenLimit: 128,
//                    streamCallback: streamCallback)
//
//                try dialog!.addLLMResponse(content: result.completion)
//
//                DispatchQueue.main.async { [self] in
//                    if result.endpoint == .interrupted {
//                        statusText = "Listening..."
//                        chatText.append(Message(speaker: "You:", msg: ""))
//                        chatState = .STT
//
//                        promptText = ""
//                        enableGenerateButton = true
//                    } else {
//                        statusText = ViewModel.statusTextDefault
//                        chatState = .WAKEWORD
//
//                        promptText = ""
//                        enableGenerateButton = true
//                    }
//                }
//            } catch {
//                DispatchQueue.main.async { [self] in
//                    errorMessage = "\(error.localizedDescription)"
//                }
//            }
//        }
//
//        DispatchQueue.global(qos: .userInitiated).async { [self] in
//            do {
//                audioStream!.resetAudioPlayer()
//                let orcaStream = try self.orca!.streamOpen()
//
//                var warmup = true
//                var warmupBuffer: [Int16] = []
//
//                var itemsRemaining = true
//                while chatState == .GENERATE || itemsRemaining {
//                    completionQueue.sync {
//                        itemsRemaining = !self.completionArray.isEmpty
//                    }
//
//                    if itemsRemaining {
//                        var token = ""
//                        completionQueue.sync {
//                            token = completionArray[0]
//                            completionArray.removeFirst()
//                        }
//
//                        orcaProfiler.tick()
//                        let pcm = try orcaStream.synthesize(text: token)
//                        orcaProfiler.tock(pcm: pcm)
//                        if pcm != nil {
//                            if warmup {
//                                warmupBuffer.append(contentsOf: pcm!)
//                                if warmupBuffer.count >= (1 * orca!.sampleRate!) {
//                                    try audioStream!.playStreamPCM(pcm!)
//                                    warmupBuffer.removeAll()
//                                    warmup = false
//                                }
//                            } else {
//                                try audioStream!.playStreamPCM(pcm!)
//                            }
//                        }
//                    }
//                }
//
//                if !warmupBuffer.isEmpty {
//                    try audioStream!.playStreamPCM(warmupBuffer)
//                }
//
//                orcaProfiler.tick()
//                let pcm = try orcaStream.flush()
//                orcaProfiler.tock(pcm: pcm)
//                if pcm != nil {
//                    try audioStream!.playStreamPCM(pcm!)
//                }
//                print(String(format: "RTF: %.2f", orcaProfiler.rtf()))
//                orcaStream.close()
//            } catch {
//                DispatchQueue.main.async { [self] in
//                    errorMessage = "\(error.localizedDescription)"
//                }
//            }
//        }
//    }
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
                        
                        DispatchQueue.main.async { [self] in
                            chatText[chatText.count - 1].appendTranslated(text: word.word)
                        }
                        
                        if index + 1 < audio.wordArray.count && !audio.wordArray[index + 1].word.first!.isPunctuation {
                            DispatchQueue.main.async { [self] in
                                chatText[chatText.count - 1].appendTranslated(text: " ")
                            }
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

    public func interrupt() {
        do {
            audioStream!.stopStreamPCM()
        } catch {
            DispatchQueue.main.async { [self] in
                errorMessage = "\(error.localizedDescription)"
            }
        }
    }

    private func audioCallback(frame: [Int16]) {
        do {
            if chatState == .LISTENING {
                let partialTranscript = try self.cheetah!.process(frame)
                DispatchQueue.main.async { [self] in
                    chatText[chatText.count - 1].appendTranscript(text: partialTranscript.0)
                }
                
                if partialTranscript.1 {
                    let finalTranscript = try self.cheetah!.flush()
                    DispatchQueue.main.async { [self] in
                        chatText[chatText.count - 1].appendTranscript(text: finalTranscript)
                    }
                    
                    if !chatText[chatText.count - 1].transcript.isEmpty {
                        translateAndSpeak()
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
