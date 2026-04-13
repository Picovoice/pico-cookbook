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

class ViewModel: ObservableObject {

    private let ACCESS_KEY = "${YOUR_ACCESS_KEY_HERE}"

    private var bat: Bat?
    private var cheetah: Cheetah?
    private var zebra: Zebra?
    private var orca: Orca?

    private var audioStream: AudioPlayerStream?
    
    @Published var chatState: ChatState = .SELECTING

    @Published var selectedSourceLanguage: String = "en"
    @Published var selectedTargetLanguage: String = "de"
    
    
    static let modelLoadStatusTextDefault = """
Start by loading a `.pllm` model file.

You can download directly to your device or airdrop from a Mac.
"""
    @Published var modelLoadStatusText = modelLoadStatusTextDefault
    @Published var enableLoadModelButton = true
    @Published var showFileImporter = false
    @Published var selectedModelUrl: URL?

    @Published var enginesLoaded = false

    static let statusTextDefault = "Say `Picovoice`!"
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

    public func startDemo() {
        chatState = .LOADING
        loadEngines()
    }

    public func loadEngines() {
        errorMessage = ""
        modelLoadStatusText = ""
        enableLoadModelButton = false

        let sourceLanguage = "en"
        let targetLanguage = "de"

        DispatchQueue.global(qos: .userInitiated).async { [self] in
            let setStatusText = {(_ msg: String) in
                DispatchQueue.main.async { [self] in
                    modelLoadStatusText = msg
                }
            }
            do {
                setStatusText("Loading Cheetah \(sourceLanguage)...")
                let cheetahModelPath = Bundle(for: type(of: self)).path(forResource: "cheetah_params_\(sourceLanguage)", ofType: "pv")!
                cheetah = try Cheetah(accessKey: ACCESS_KEY, modelPath: cheetahModelPath)

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

                DispatchQueue.main.async { [self] in
                    chatState = .LISTENING
                }
            } catch {
                DispatchQueue.main.async { [self] in
                    unloadEngines()
                    errorMessage = "\(error.localizedDescription)"
                }
            }

            DispatchQueue.main.async { [self] in
                selectedModelUrl!.stopAccessingSecurityScopedResource()

                modelLoadStatusText = ViewModel.modelLoadStatusTextDefault
                enableLoadModelButton = true
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
        enginesLoaded = false
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

    private var completionQueue = DispatchQueue(label: "text-stream-queue")
    private var completionArray: [String] = []

    private let stopPhrases = [
        "</s>",  // Llama-2, Mistral, and Mixtral
        "<end_of_turn>",  // Gemma
        "<|endoftext|>",  // Phi-2
        "<|eot_id|>"  // Llama-3
    ]

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

    public func interrupt() {
        do {
            audioStream!.stopStreamPCM()
        } catch {
            DispatchQueue.main.async { [self] in
                errorMessage = "\(error.localizedDescription)"
            }
        }
    }

    public func clearText() {
        promptText = ""
        chatText.removeAll()
    }

    private func audioCallback(frame: [Int16]) {
        do {
            if chatState == .LISTENING {
                let partialTranscript = try self.cheetah!.process(frame)
                chatText.append(Message(speaker: "user", msg: partialTranscript.0))
            } else if chatState == .TRANSLATING {
            } else if chatState == .SPEAKING {
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
    var speaker: String
    var msg: String

    mutating func append(text: String) {
        self.msg.append(text)
    }
}
