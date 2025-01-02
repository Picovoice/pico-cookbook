//
//  Copyright 2024 Picovoice Inc.
//  You may not use this file except in compliance with the license. A copy of the license is located in the "LICENSE"
//  file accompanying this source.
//  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
//  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
//  specific language governing permissions and limitations under the License.
//

import Porcupine
import Cheetah
import PicoLLM
import Orca
import ios_voice_processor

import Combine
import Foundation

enum ChatState {
    case WAKEWORD
    case STT
    case GENERATE
    case ERROR
}

class ViewModel: ObservableObject {

    private let ACCESS_KEY = "NejFRnq+pZsa3QbiMsziYXYsisrR5xoPQ2aDT9aEqcgZkQnFq70aEg=="

    private var porcupine: Porcupine?
    private var cheetah: Cheetah?
    private var orca: Orca?

    private var picollm: PicoLLM?
    private var dialog: PicoLLMDialog?

    private var chatState: ChatState = .WAKEWORD

    private var audioStream: AudioPlayerStream?

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
        if picollm != nil {
            picollm!.delete()
        }
    }

    public func extractModelFile() {
        showFileImporter = true
    }

    public func loadEngines() {
        errorMessage = ""
        modelLoadStatusText = ""
        enableLoadModelButton = false

        let modelAccess = selectedModelUrl!.startAccessingSecurityScopedResource()
        if !modelAccess {
            errorMessage = "Can't get permissions to access model file"
            enableLoadModelButton = true
            return
        }

        DispatchQueue.global(qos: .userInitiated).async { [self] in
            let setStatusText = {(_ msg: String) in
                DispatchQueue.main.async { [self] in
                    modelLoadStatusText = msg
                }
            }
            do {
                setStatusText("Loading Porcupine...")
                porcupine = try Porcupine(accessKey: ACCESS_KEY, keyword: .picovoice)

                setStatusText("Loading Cheetah...")
                let cheetahModelPath = Bundle(for: type(of: self)).path(forResource: "cheetah_params", ofType: "pv")!
                cheetah = try Cheetah(accessKey: ACCESS_KEY, modelPath: cheetahModelPath)

                setStatusText("Loading picoLLM...")
                picollm = try PicoLLM(accessKey: ACCESS_KEY, modelPath: selectedModelUrl!.path)
                dialog = try picollm!.getDialog()

                setStatusText("Loading Orca...")
                let orcaModelPath = Bundle(for: type(of: self)).path(forResource: "orca_params_female", ofType: "pv")!
                orca = try Orca(accessKey: ACCESS_KEY, modelPath: orcaModelPath)

                setStatusText("Loading Audio Player...")
                audioStream = try AudioPlayerStream(sampleRate: Double(self.orca!.sampleRate!))

                setStatusText("Loading Voice Processor...")
                VoiceProcessor.instance.addFrameListener(VoiceProcessorFrameListener(audioCallback))
                VoiceProcessor.instance.addErrorListener(VoiceProcessorErrorListener(errorCallback))
                startAudioRecording()

                DispatchQueue.main.async { [self] in
                    enginesLoaded = true
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

        if porcupine != nil {
            porcupine!.delete()
        }
        if cheetah != nil {
            cheetah!.delete()
        }
        if picollm != nil {
            picollm!.delete()
        }
        if orca != nil {
            orca!.delete()
        }
        porcupine = nil
        cheetah = nil
        picollm = nil
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
                    frameLength: Porcupine.frameLength,
                    sampleRate: Porcupine.sampleRate)
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

    private func streamCallback(completion: String) {
        DispatchQueue.main.async { [self] in
            if self.stopPhrases.contains(completion) || chatState != .GENERATE {
                return
            }

            completionQueue.async {
                self.completionArray.append(completion)
            }
            chatText[chatText.count - 1].append(text: completion)
        }
    }

    public func generate() {
        errorMessage = ""

        enableGenerateButton = false

        DispatchQueue.global(qos: .userInitiated).async { [self] in
            do {
                try dialog!.addHumanRequest(content: chatText[chatText.count - 1].msg)

                DispatchQueue.main.async { [self] in
                    chatText.append(Message(speaker: "picoLLM:", msg: ""))
                }

                let result = try picollm!.generate(
                    prompt: dialog!.prompt(),
                    completionTokenLimit: 128,
                    streamCallback: streamCallback)

                try dialog!.addLLMResponse(content: result.completion)

                DispatchQueue.main.async { [self] in
                    if result.endpoint == .interrupted {
                        statusText = "Listening..."
                        chatText.append(Message(speaker: "You:", msg: ""))
                        chatState = .STT

                        promptText = ""
                        enableGenerateButton = true
                    } else {
                        statusText = ViewModel.statusTextDefault
                        chatState = .WAKEWORD

                        promptText = ""
                        enableGenerateButton = true
                    }
                }
            } catch {
                DispatchQueue.main.async { [self] in
                    errorMessage = "\(error.localizedDescription)"
                }
            }
        }

        DispatchQueue.global(qos: .userInitiated).async { [self] in
            do {
                audioStream!.resetAudioPlayer()
                let orcaStream = try self.orca!.streamOpen()

                var warmup = true
                var warmupBuffer: [Int16] = []

                var itemsRemaining = true
                while chatState == .GENERATE || itemsRemaining {
                    completionQueue.sync {
                        itemsRemaining = !self.completionArray.isEmpty
                    }

                    if itemsRemaining {
                        var token = ""
                        completionQueue.sync {
                            token = completionArray[0]
                            completionArray.removeFirst()
                        }

                        let pcm = try orcaStream.synthesize(text: token)
                        if pcm != nil {
                            if warmup {
                                warmupBuffer.append(contentsOf: pcm!)
                                if warmupBuffer.count >= (1 * orca!.sampleRate!) {
                                    try audioStream!.playStreamPCM(pcm!)
                                    warmupBuffer.removeAll()
                                    warmup = false
                                }
                            } else {
                                try audioStream!.playStreamPCM(pcm!)
                            }
                        }
                    }
                }

                if !warmupBuffer.isEmpty {
                    try audioStream!.playStreamPCM(warmupBuffer)
                }

                let pcm = try orcaStream.flush()
                if pcm != nil {
                    try audioStream!.playStreamPCM(pcm!)
                }
                orcaStream.close()
            } catch {
                DispatchQueue.main.async { [self] in
                    errorMessage = "\(error.localizedDescription)"
                }
            }
        }
    }

    public func interrupt() {
        do {
            audioStream!.stopStreamPCM()
            try picollm?.interrupt()
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
            if chatState == .WAKEWORD {
                let keywordIndex = try self.porcupine!.process(pcm: frame)
                if keywordIndex == 0 {
                    DispatchQueue.main.async { [self] in
                        self.interrupt()
                        statusText = "Listening..."
                        chatText.append(Message(speaker: "You:", msg: ""))
                        chatState = .STT
                    }
                }
            } else if chatState == .GENERATE {
                let keywordIndex = try self.porcupine!.process(pcm: frame)
                if keywordIndex == 0 {
                    DispatchQueue.main.async { [self] in
                        self.interrupt()
                    }
                }
            } else if chatState == .STT {
                var (transcription, endpoint) = try self.cheetah!.process(frame)
                if endpoint {
                    transcription += "\(try self.cheetah!.flush())"
                }
                if !transcription.isEmpty {
                    DispatchQueue.main.async { [self] in
                        chatText[chatText.count - 1].append(text: transcription)
                    }
                }
                if endpoint {
                    DispatchQueue.main.async { [self] in
                        statusText = "Generating...\nSay `Picovoice` to interrupt"
                        chatState = .GENERATE
                        self.generate()
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
    var speaker: String
    var msg: String

    mutating func append(text: String) {
        self.msg.append(text)
    }
}
