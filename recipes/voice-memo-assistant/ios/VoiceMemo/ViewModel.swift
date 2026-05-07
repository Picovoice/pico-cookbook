//
//  Copyright 2026 Picovoice Inc.
//  You may not use this file except in compliance with the license. A copy of the license is located in the "LICENSE"
//  file accompanying this source.
//  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
//  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
//  specific language governing permissions and limitations under the License.
//

import Foundation
import Porcupine
import Rhino
import Cheetah
import PicoLLM
import Orca
import ios_voice_processor

enum UIState {
    case initState
    case loadingModel
    case wakeWord
    case voiceCommand
    case startRecording
    case readRecording
    case summarizeRecording
    case rewriteRecording
}

class ViewModel: ObservableObject {
    @Published var statusText: String = ""
    @Published var tooltipText: String = ""
    @Published var enginesLoaded: Bool = false
    @Published var uiState: UIState = .initState
    @Published var volumeLevel: Float = 0.0

    @Published var memoText: String = ""
    @Published var modifiedText: String = ""
    @Published var modifiedTitle: String = ""

    private let ACCESS_KEY = "${YOUR_ACCESS_KEY_HERE}"
    private let STOP_PHRASE = "<|eot_id|>"
    private let NO_MEMO_ERROR_PHRASE = "You need to record a memo first."

    private var porcupine: Porcupine?
    private var rhino: Rhino?
    private var cheetah: Cheetah?
    private var picollm: PicoLLM?
    private var orca: Orca?

    private var audioStream: AudioPlayerStream?

    init() {
        loadEngines()
    }

    func setStatusText(text: String) {
        DispatchQueue.main.async { self.statusText = text }
    }

    func updateUIState(_ state: UIState) {
        DispatchQueue.main.async {
            self.uiState = state
            switch state {
            case .wakeWord:
                self.statusText = "Listening for wake word..."
                self.tooltipText = ""
            case .voiceCommand:
                self.statusText = "Listening for voice command..."
                self.tooltipText = self.memoText.isEmpty ?
                    "Say 'start memo'" :
                    "Say one of the following commands:\n- 'start memo'\n" +
                    "- 'read memo'\n- 'summarize memo'\n- 'rewrite memo'"
            case .startRecording:
                self.statusText = "Listening..."
                self.tooltipText = "Say 'stop recording' to end memo"
                self.modifiedText = ""
                self.modifiedTitle = ""
            case .readRecording:
                self.statusText = "Speaking..."
                self.tooltipText = ""
            case .summarizeRecording:
                self.statusText = "Summarizing..."
                self.tooltipText = ""
                if !self.memoText.isEmpty {
                    self.modifiedTitle = "Summarized:"
                    self.modifiedText = ""
                }
            case .rewriteRecording:
                self.statusText = "Rewriting..."
                self.tooltipText = ""
                if !self.memoText.isEmpty {
                    self.modifiedTitle = "Rewritten:"
                    self.modifiedText = ""
                }
            default:
                break
            }
        }
    }

    func loadEngines() {
        unloadEngines()
        DispatchQueue.global(qos: .userInitiated).async { [self] in
            do {
                setStatusText(text: "Loading Porcupine...")
                guard let ppnPath = Bundle.main.path(
                    forResource: "porcupine_model",
                    ofType: "ppn")
                    else { throw NSError(domain: "ppn not found", code: 0) }
                porcupine = try Porcupine(accessKey: ACCESS_KEY, keywordPath: ppnPath)

                setStatusText(text: "Loading Rhino...")
                guard let rhnPath = Bundle.main.path(
                    forResource: "rhino_model",
                    ofType: "rhn")
                    else { throw NSError(domain: "rhn not found", code: 0) }
                rhino = try Rhino(
                    accessKey: ACCESS_KEY,
                    contextPath: rhnPath,
                    sensitivity: 0.0,
                    endpointDurationSec: 0.5,
                    requireEndpoint: false)

                setStatusText(text: "Loading Cheetah...")
                guard let cheetahModelPath = Bundle.main.path(
                    forResource: "cheetah_params",
                    ofType: "pv")
                    else { throw NSError(domain: "chetah_params not found", code: 0) }
                cheetah = try Cheetah(
                    accessKey: ACCESS_KEY,
                    modelPath: cheetahModelPath,
                    enableAutomaticPunctuation: true,
                    enableTextNormalization: true)

                setStatusText(text: "Loading picoLLM...")
                guard let pllmPath = Bundle.main.path(
                    forResource: "picollm_model",
                    ofType: "pllm")
                    else { throw NSError(domain: "pllm not found", code: 0) }
                picollm = try PicoLLM(accessKey: ACCESS_KEY, modelPath: pllmPath, device: "cpu:2")

                setStatusText(text: "Loading Orca...")
                guard let orcaModelPath = Bundle.main.path(
                    forResource: "orca_params_en_female",
                    ofType: "pv")
                    else { throw NSError(domain: "orca_params not found", code: 0) }
                orca = try Orca(accessKey: ACCESS_KEY, modelPath: orcaModelPath)

                audioStream = try AudioPlayerStream(sampleRate: Double(orca!.sampleRate!))

                VoiceProcessor.instance.addFrameListener(VoiceProcessorFrameListener(audioCallback))
                try VoiceProcessor.instance.start(frameLength: Cheetah.frameLength, sampleRate: Cheetah.sampleRate)

                DispatchQueue.main.async {
                    self.enginesLoaded = true
                    self.updateUIState(.wakeWord)
                }
            } catch {
                setStatusText(text: error.localizedDescription)
                unloadEngines()
            }
        }
    }

    func audioCallback(frame: [Int16]) {
        calculateVolume(frame: frame)

        do {
            if uiState == .wakeWord {
                let keywordIndex = try porcupine!.process(pcm: frame)
                if keywordIndex == 0 {
                    interrupt()
                    updateUIState(.voiceCommand)
                }
            } else if uiState == .voiceCommand {
                let isFinalized = try rhino!.process(pcm: frame)
                if isFinalized {
                    let inference = try rhino!.getInference()
                    if inference.isUnderstood {
                        switch inference.intent {
                        case "startMemo":
                            DispatchQueue.main.async { self.memoText = "" }
                            updateUIState(.startRecording)
                        case "readMemo":
                            updateUIState(.readRecording)
                            synthesizeAndPlayback(
                                text: !modifiedText.isEmpty ?
                                    modifiedText :
                                    (!memoText.isEmpty ? memoText : NO_MEMO_ERROR_PHRASE))
                        case "summarizeMemo":
                            updateUIState(.summarizeRecording)
                            processLLM(task: "Summarize")
                        case "rewriteMemo":
                            updateUIState(.rewriteRecording)
                            processLLM(task: "Rewrite")
                        default:
                            break
                        }
                    } else {
                        synthesizeAndPlayback(text: "Sorry, I didn't understand that. Please try again.")
                        updateUIState(.wakeWord)
                    }
                }
            } else if uiState == .startRecording {
                let (transcript, endpoint) = try cheetah!.process(frame)
                DispatchQueue.main.async { self.memoText += transcript }

                if endpoint {
                    let flush = try cheetah!.flush()
                    DispatchQueue.main.async { self.memoText += flush + " " }
                }

                if memoText.lowercased().range(
                        of: "stop recording. *$|stop recording *$",
                        options: .regularExpression) != nil {
                    DispatchQueue.main.async {
                        self.memoText = self.memoText.replacingOccurrences(
                            of: "stop recording.",
                            with: "",
                            options: [.caseInsensitive, .regularExpression]).trimmingCharacters(in: .whitespaces)
                    }
                    updateUIState(.wakeWord)
                }
            }
        } catch {
            print(error.localizedDescription)
        }
    }

    func processLLM(task: String) {
        guard !memoText.isEmpty else {
            synthesizeAndPlayback(text: NO_MEMO_ERROR_PHRASE)
            updateUIState(.wakeWord)
            return
        }

        DispatchQueue.global(qos: .userInitiated).async { [self] in
            do {
                let dialog = try picollm!.getDialog()
                var promptBody = ""

                if task == "Summarize" {
                    promptBody = "Summarize the memo below. Return only the summary.\n" +
                        "Rules:\n- Do not say \"Here is the summarized memo\".\n" +
                        "- Do not add any prefix, label, intro, explanation, or quotes.\n" +
                        "- Keep the important details.\n" +
                        "- Use one short sentence.\n\n" +
                        "Memo:\n\(memoText)\n\n" +
                        "Summarized memo:\n"
                } else {
                    promptBody = "Rewrite the memo below. Return only the rewritten memo.\n" +
                        "Rules:\n" +
                        "- Do not say \"Here is the rewritten memo\".\n" +
                        "- Do not add any prefix, label, intro, explanation, or quotes.\n" +
                        "- Fix grammar, punctuation, and repeated words.\n" +
                        "- Preserve original meaning.\n\n" +
                        "Memo:\n\(memoText)\n\n" +
                        "Rewritten memo:\n"
                }

                try dialog.addHumanRequest(content: promptBody)
                let prompt = try dialog.prompt()

                let completion = try picollm!.generate(
                        prompt: prompt,
                        completionTokenLimit: 100,
                        stopPhrases: [STOP_PHRASE]) { token in
                    DispatchQueue.main.async {
                        self.modifiedText += token
                    }
                }
                DispatchQueue.main.async {
                    self.modifiedText = completion.completion.replacingOccurrences(of: self.STOP_PHRASE, with: "")
                }
                updateUIState(.wakeWord)
            } catch {
                print(error)
            }
        }
    }

    private func calculateVolume(frame: [Int16]) {
        let minDb: Float = -40.0
        let maxDb: Float = 0.0
        var sum: Float = 0.0
        for sample in frame {
            sum += pow(Float(sample), 2)
        }
        let rms = (sum / Float(frame.count)) / pow(Float(Int16.max), 2)
        let dbfs = 10 * log10(max(rms, 1e-9))
        let normalized = (dbfs - minDb) / (maxDb - minDb)
        let volume = max(0.0, min(1.0, normalized))

        DispatchQueue.main.async {
            self.volumeLevel = volume
        }
    }

    func synthesizeAndPlayback(text: String) {
        DispatchQueue.global(qos: .userInitiated).async { [self] in
            do {
                let audio = try orca!.synthesize(text: text)
                try audioStream!.playStreamPCM(audio.pcm)
                updateUIState(.wakeWord)
            } catch {
                print(error)
            }
        }
    }

    func interrupt() {
        do {
            try picollm?.interrupt()
            audioStream?.stopStreamPCM()
        } catch {
            print(error)
        }
    }

    func unloadEngines() {
        enginesLoaded = false
        do { try VoiceProcessor.instance.stop() } catch {}
        VoiceProcessor.instance.clearFrameListeners()
        audioStream = nil
        porcupine?.delete()
        rhino?.delete()
        cheetah?.delete()
        picollm?.delete()
        orca?.delete()
    }
}
