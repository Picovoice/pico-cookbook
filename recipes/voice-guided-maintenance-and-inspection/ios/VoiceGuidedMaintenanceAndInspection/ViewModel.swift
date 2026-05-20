//
//  Copyright 2026 Picovoice Inc.
//  You may not use this file except in compliance with the license. A copy of the license is located in the "LICENSE"
//  file accompanying this source.
//  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
//  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
//  specific language governing permissions and limitations under the License.
//

import Cheetah
import Koala
import Orca
import Porcupine
import Rhino
import ios_voice_processor

import Combine
import Foundation

enum CardType {
    case UNIT_ID, OIL_CONDITION, TIRE_CONDITION, SERVICE_STATUS, NOTES
}

protocol WorkflowListener {
    func onInitProgress(status: String)
    func onStatusChanged(status: String)
    func onCardActive(cardType: CardType)
    func onCardUpdated(cardType: CardType, value: String, isFinal: Bool)
    func onWorkflowComplete();
    func onVolumeFrame(frame: [Int16])
}

struct CardItem {
    let title: String
    var value: String?
}

enum ViewState {
    case loading, main
}

class ViewModel: ObservableObject {

    private let ACCESS_KEY = "${YOUR_ACCESS_KEY_HERE}"

    private let KEYWORD_MODEL = "keyword.ppn"

    private let CONTEXT_MODEL = "context.rhn"

    private let STT_MODEL = "cheetah_params.pv"

    private let TTS_MODEL = "orca_params_en_female.pv"

    private let NS_MODEL = "koala_params.pv"

    @Published var viewState: ViewState = .loading
    @Published var statusText: String = ""
    @Published var errorText: String?
    @Published var enginesLoaded: Bool = false
    @Published var soundLevel: Float = 0.0
    @Published var cardViews: [CardItem] = []

    private var koala: Koala?
    private var porcupine: Porcupine?
    private var orca: Orca?
    private var rhino: Rhino?
    private var cheetah: Cheetah?

    init() {
        Task.detached(priority: .background) { [self] in
            do {
                try await preloadDemo()
            } catch {
                await setErrorText(text: error.localizedDescription)
            }
        }
    }

    private func setStatusText(text: String) async {
        await MainActor.run {
            statusText = text
        }
    }

    private func setErrorText(text: String) async {
        await MainActor.run {
            errorText = text
        }
    }
    
    private func preloadDemo() async throws {
        await setStatusText(text: "Loading Koala Noise Suppression...")
        koala = try Koala(accessKey: ACCESS_KEY)
        
        await setStatusText(text: "Loading Porcupine Wake Word...")
        porcupine = try Porcupine(accessKey: ACCESS_KEY, keywordPath: KEYWORD_MODEL)

        await setStatusText(text: "Loading Orca Text-to-Speech...")
        orca = try Orca(accessKey: ACCESS_KEY, modelPath: TTS_MODEL)

        await setStatusText(text: "Loading Rhino Speech-to-Intent...")
        rhino = try Rhino(accessKey: ACCESS_KEY, contextPath: CONTEXT_MODEL)

        await setStatusText(text: "Loading Cheetah Speech-to-Text...")
        cheetah = try Cheetah(accessKey: ACCESS_KEY, modelPath: STT_MODEL)

        VoiceProcessor.instance.addFrameListener(VoiceProcessorFrameListener(rawAudioCallback))
        
        await setStatusText(text: "Ready to Start")
        await MainActor.run {
            enginesLoaded = true
        }
    }
    
    public func startDemo() {
        Task.detached(priority: .background) { [self] in
            do {
                await MainActor.run {
                    viewState = .main
                }
                
                try VoiceProcessor.instance.start(
                    frameLength: Koala.frameLength,
                    sampleRate: Koala.sampleRate)
            } catch {
                await MainActor.run {
                    viewState = .loading
                }
                
                await setErrorText(text: error.localizedDescription)
            }
        }
    }
    
    public func stopDemo() {
        Task.detached(priority: .background) { [self] in
            do {
                await MainActor.run {
                    viewState = .loading
                }
                
                try VoiceProcessor.instance.stop()
            } catch {
                await setErrorText(text: error.localizedDescription)
            }
        }
    }

    private func rawAudioCallback(frame: [Int16]) {
        do {
            computeSoundLevel(frame: frame)
            if koala != nil {
                let processedFrame = try koala!.process(frame)
                nsAudioCallback(frame: processedFrame)
            }
        } catch {
            Task {
                await setErrorText(text: error.localizedDescription)
            }
        }
    }

    private func nsAudioCallback(frame: [Int16]) {
        
    }
    
    func computeSoundLevel(frame: [Int16]) {
            let MIN_DB: Double = -40
            let MAX_DB: Double = 0

            var sum: Double = 0
            for sample in frame {
                sum += pow(Double(sample), 2)
            }

            let rms = (sum / Double(frame.count)) / pow(Double(Int16.max), 2)
            let db: Double = 10 * log10(max(rms, 1e-9))
            let normalized = (db - MIN_DB) / (MAX_DB - MIN_DB)
            let clamped = max(0.0, min(1.0, normalized))

            DispatchQueue.main.async { [self] in
                soundLevel = soundLevel * 0.5 + Float(clamped) * 0.5
            }
        }
}
