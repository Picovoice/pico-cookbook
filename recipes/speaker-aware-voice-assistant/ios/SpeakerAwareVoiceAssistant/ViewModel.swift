//
//  Copyright 2026 Picovoice Inc.
//  You may not use this file except in compliance with the license. A copy of the license is located in the "LICENSE"
//  file accompanying this source.
//  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
//  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
//  specific language governing permissions and limitations under the License.
//

import Eagle
import Porcupine
import Orca
import Rhino
import ios_voice_processor

import Foundation
import Combine

enum AppState {
    case idle
    case enrolling
    case testing
}

enum TestingState {
    case PPN
    case RHN
    case ORCA
}

enum UserRole {
    case admin
    case user
}

class ViewModel: ObservableObject {
    private let ACCESS_KEY = "${YOUR_ACCESS_KEY_HERE}"
    
    private let EAGLE_THRESHOLD: Float = 0.50
    private let EAGLE_MIN_EMROLLMENT_CHUNKS = 6;
    private let MAX_SPEAKERS = 10;


    @Published var speakerProfiles: [EagleProfile] = []
    @Published var speakerNames: [String] = []
    @Published var speakerRoles: [UserRole] = []

    @Published var appState: AppState = .idle
    @Published var testingState: TestingState = .PPN

    @Published var pendingSpeakerName: String = ""
    @Published var pendingSpeakerAdminRole: Bool = false

    @Published var soundLevel: Float = 0.0

    private var porcupine: Porcupine?
    private var rhino: Rhino?
    private var orca: Orca?

    private var eagleProfiler: EagleProfiler?
    private var eagle: Eagle?

    private var audioStream: AudioPlayerStream?

    @Published var statusText: String = "Ready to Enroll"
    @Published var tooltipText: String?
    @Published var enrollPercentage: Float = 0.0

    @Published var showTestResult: Bool = false
    @Published var testUserName: String?
    @Published var testUserIndex: Int?

    private var enrollBuffer: [Int16] = []
    private var enrollMaxSamples: Int = 0
    private var enrollValidSamples: Int = 0
    private var eagleFrameLength: Int = 0

    private var testBuffer: [Int16] = []
    private var testMaxSamples: Int = 0

    private var inferenceBuffer: [Int16] = []

    init() {
        VoiceProcessor.instance.addFrameListener(VoiceProcessorFrameListener(audioCallback))
        VoiceProcessor.instance.addErrorListener(VoiceProcessorErrorListener(errorCallback))
    }

    public func showAlert() {
        self.pendingSpeakerName = "Speaker \(self.speakerNames.count)"
        self.pendingSpeakerAdminRole = false
    }

    public func clear() {
        self.speakerProfiles = []
        self.speakerNames = []
        self.speakerRoles = []
    }

    public func startEnrollment() {
        checkPermissionsAndStart(targetState: .enrolling)
    }

    public func startTesting() {
        checkPermissionsAndStart(targetState: .testing)
    }

    private func checkPermissionsAndStart(targetState: AppState) {
        if VoiceProcessor.hasRecordAudioPermission {
            if targetState == .enrolling {
                performStartEnrollment()
            } else {
                performStartTesting()
            }
        } else {
            VoiceProcessor.requestRecordAudioPermission { isGranted in
                if isGranted {
                    if targetState == .enrolling {
                        self.performStartEnrollment()
                    } else {
                        self.performStartTesting()
                    }
                } else {
                    DispatchQueue.main.async {
                        self.statusText = "Microphone permission is required for this demo"
                    }
                }
            }
        }
    }

    private func performStartEnrollment() {
        do {
            try stopAudio()

            eagleProfiler = try EagleProfiler(
                accessKey: ACCESS_KEY,
                minEnrollmentChunks: EAGLE_MIN_EMROLLMENT_CHUNKS,
                voiceThreshold: EAGLE_THRESHOLD)

            eagleFrameLength = Int(EagleProfiler.frameLength)
            enrollMaxSamples = eagleFrameLength * 64

            enrollBuffer = [Int16](repeating: 0, count: enrollMaxSamples)
            enrollValidSamples = 0

            DispatchQueue.main.async {
                self.appState = .enrolling
                self.updateUIForState()
            }

            try VoiceProcessor.instance.start(
                frameLength: Porcupine.frameLength,
                sampleRate: Porcupine.sampleRate
            )
        } catch {
            DispatchQueue.main.async {
                self.statusText = "Engine init error: \(error.localizedDescription)"
            }
        }
    }

    private func performStartTesting() {
        do {
            try stopAudio()

            guard let keywordPath = getKeywordPath() else { return }
            guard let contextPath = getContextPath() else { return }
            guard let orcaModelPath = getOrcaModelPath() else { return }

            porcupine = try Porcupine(accessKey: ACCESS_KEY, keywordPaths: [keywordPath], sensitivities: [0.5])
            rhino = try Rhino(accessKey: ACCESS_KEY, contextPath: contextPath)
            orca = try Orca(accessKey: ACCESS_KEY, modelPath: orcaModelPath)
            eagle = try Eagle(accessKey: ACCESS_KEY, voiceThreshold: 0.1)
            audioStream = try AudioPlayerStream(sampleRate: Double(self.orca!.sampleRate!))

            testMaxSamples = Int(try eagle!.minProcessSamples())
            testBuffer = [Int16](repeating: 0, count: testMaxSamples)

            DispatchQueue.main.async {
                self.appState = .testing
                self.updateUIForState()
            }

            try VoiceProcessor.instance.start(
                frameLength: Porcupine.frameLength,
                sampleRate: Porcupine.sampleRate
            )
        } catch {
            DispatchQueue.main.async {
                self.statusText = "Engine init error: \(error.localizedDescription)"
            }
        }
    }

    public func cancel() {
        do {
            try stopAudio()
        } catch {
            print(error)
        }
        DispatchQueue.main.async {
            self.appState = .idle
            self.updateUIForState()
        }
    }

    private func finishEnrollment() {
        do {
            let profile = try eagleProfiler!.export()

            DispatchQueue.main.async {
                self.speakerProfiles.append(profile)
                self.speakerNames.append(self.pendingSpeakerName)
                self.speakerRoles.append(self.pendingSpeakerAdminRole ? .admin : .user)

                self.cancel()
            }
        } catch {
            print("Failed to export profile: \(error)")
        }
    }

    private func showResult(name: String?, index: Int?) {
        DispatchQueue.main.async {
            self.testUserName = name
            self.testUserIndex = index
            self.showTestResult = true
            self.statusText = ""
        }
    }

    private func hideResult() {
        DispatchQueue.main.async {
            self.testingState = .PPN
            self.showTestResult = false
            self.updateUIForState()
        }
    }

    private func updateUIForState() {
        if showTestResult { return }

        switch appState {
        case .idle:
            statusText = speakerProfiles.count > 0 ? "Enrolled Profiles" : "Ready to Enroll"
            tooltipText = nil
        case .enrolling:
            statusText = "Hello \(pendingSpeakerName)\n\nSpeak these phrases until the circle is full:"
            tooltipText =
                    "\n\"The quick brown fox jumps over the lazy dog.\"" +
                    "\n\"I am recording my voice for speaker enrollment.\"" +
                    "\n\"This is my normal speaking voice in a quiet room.\"" +
                    "\n\"The assistant should recognize me when I speak.\"" +
                    "\n\"Voice recognition works best with clean and natural speech.\""
            enrollPercentage = 0.0
        case .testing:
            statusText = "Listening for wake word..."
            tooltipText = nil
        }
    }

    private func audioCallback(frame: [Int16]) {
        DispatchQueue.main.async {
            self.soundLevel = self.calculateRMS(frame: frame)
        }

        if appState == .enrolling {
            enrollBuffer.removeFirst(frame.count)
            enrollBuffer.append(contentsOf: frame)
            enrollValidSamples = min(enrollMaxSamples, enrollValidSamples + frame.count)

            do {
                let numEagleFrames = enrollValidSamples / eagleFrameLength
                let startIndex = enrollMaxSamples - enrollValidSamples

                var progress: Float = 0.0
                for i in 0..<numEagleFrames {
                    let chunkStart = startIndex + (i * eagleFrameLength)
                    let chunkEnd = chunkStart + eagleFrameLength
                    let chunk = Array(enrollBuffer[chunkStart..<chunkEnd])

                    let result = try eagleProfiler!.enroll(pcm: chunk)
                    progress = result
                }
                enrollValidSamples = 0

                DispatchQueue.main.async {
                    self.enrollPercentage = progress
                }

                if progress >= 100.0 {
                    DispatchQueue.main.sync {
                        self.appState = .idle
                    }
                    self.finishEnrollment()
                }
            } catch {
                print("Audio processing error: \(error)")
            }

        } else if appState == .testing && testingState == .PPN {
            testBuffer.removeFirst(frame.count)
            testBuffer.append(contentsOf: frame)

            do {
                let keywordIndex = try porcupine!.process(pcm: frame)
                if keywordIndex == 0 {
                    let scores = try eagle!.process(pcm: testBuffer, speakerProfiles: speakerProfiles)
                    if (scores != nil) {
                        let bestScore = scores!.max() ?? -1
                        let bestIndex = scores!.lastIndex(of: bestScore)

                        if (bestScore >= EAGLE_THRESHOLD) {
                            showResult(name: speakerNames[bestIndex!], index: bestIndex)
                        } else {
                            showResult(name: nil, index: nil)
                        }

                        inferenceBuffer = []
                        DispatchQueue.main.async {
                            self.testingState = .RHN
                            self.tooltipText = "Available commands:\n" +
                                            "\n\"do something that requires admin permission\"" +
                                            "\n\"do something just for me\"" +
                                            "\n\"do something anyone can do\""
                        }
                    }
                }
            } catch {
                print("Audio processing error: \(error)")
            }
        } else if appState == .testing && testingState == .RHN {
            inferenceBuffer.append(contentsOf: frame)
            do {
                let is_complete = try rhino!.process(pcm: frame)
                if (is_complete) {
                    let inference = try rhino!.getInference()
                    if (inference.isUnderstood) {
                        let scores = try eagle!.process(pcm: inferenceBuffer, speakerProfiles: speakerProfiles)

                        var bestScore: Float = 0.0
                        var bestIndex: Int = -1
                        if (scores != nil) {
                            bestScore = scores!.max() ?? bestScore
                            bestIndex = scores!.lastIndex(of: bestScore) ?? bestIndex
                        }

                        if (inference.intent == "adminOnly") {
                            if (bestScore >= EAGLE_THRESHOLD) {
                                let speakerRole = speakerRoles[bestIndex]

                                DispatchQueue.main.async {
                                    self.testUserName = self.speakerNames[bestIndex]
                                    self.testUserIndex = bestIndex
                                }

                                if (speakerRole == .admin) {
                                    synthesizeAndPlayback("Admin command approved.")
                                } else {
                                    synthesizeAndPlayback("Permission denied. This command requires an admin.")
                                }
                            } else {
                                synthesizeAndPlayback("Sorry, I could not verify your voice.")
                            }
                        } else if (inference.intent == "speakerPersonalized") {
                            let speakerName = speakerNames[bestIndex]

                            DispatchQueue.main.async {
                                self.testUserName = self.speakerNames[bestIndex]
                                self.testUserIndex = bestIndex
                            }

                            synthesizeAndPlayback("Hi \(speakerName). I will personalize this command for you.")
                        } else if (inference.intent == "generic") {
                            synthesizeAndPlayback("Okay. This command is available to everyone.")
                        }
                    } else {
                        synthesizeAndPlayback("Sorry, I did not understand that command.")
                    }
                }
            } catch {
                print("Audio processing error: \(error)")
            }
        }
    }

    private func errorCallback(error: VoiceProcessorError) {
        DispatchQueue.main.async {
            self.statusText = "Audio Error: \(error.localizedDescription)"
        }
    }

    private func synthesizeAndPlayback(_ text: String) {
        DispatchQueue.main.async {
            self.testingState = .ORCA
            self.tooltipText = nil
        }

        DispatchQueue.global(qos: .userInitiated).async { [self] in
            Task {
                do {
                    let audio = try orca!.synthesize(text: text)

                    try audioStream!.playStreamPCM(audio.pcm)

                    let duration = Int((audio.pcm.count / Int(orca!.sampleRate!)) * 1000)
                    try await Task.sleep(for: .milliseconds(duration))
                } catch {
                    print("Audio processing error: \(error)")
                }

                hideResult()
            }
        }
    }

    private func calculateRMS(frame: [Int16]) -> Float {
        let MIN_DB: Float = -40.0
        let MAX_DB: Float = 0.0

        var sum: Float = 0.0
        for sample in frame {
            sum += pow(Float(sample), 2)
        }
        let rms = (sum / Float(frame.count)) / pow(Float(Int16.max), 2)
        let db = 10 * log10(max(rms, 1e-9))
        let normalized = (db - MIN_DB) / (MAX_DB - MIN_DB)
        return max(0.0, min(1.0, normalized))
    }

    private func stopAudio() throws {
        if VoiceProcessor.instance.isRecording {
            try VoiceProcessor.instance.stop()
        }

        audioStream = nil

        porcupine?.delete()
        porcupine = nil
        rhino?.delete()
        rhino = nil
        orca?.delete()
        orca = nil
        eagleProfiler?.delete()
        eagleProfiler = nil
        eagle?.delete()
        eagle = nil
    }

    private func getKeywordPath() -> String? {
        if let path = Bundle.main.path(forResource: "keyword", ofType: "ppn") {
            return path
        }
        DispatchQueue.main.async {
            self.statusText = "Could not find keyword.ppn. Make sure setup.py ran."
        }
        return nil
    }

    private func getContextPath() -> String? {
        if let path = Bundle.main.path(forResource: "context", ofType: "rhn") {
            return path
        }
        DispatchQueue.main.async {
            self.statusText = "Could not find context.rhn. Make sure setup.py ran."
        }
        return nil
    }

    private func getOrcaModelPath() -> String? {
        if let path = Bundle.main.path(forResource: "orca_params_en_female", ofType: "pv") {
            return path
        }
        DispatchQueue.main.async {
            self.statusText = "Could not find orca_params_en_female.pv. Make sure setup.py ran."
        }
        return nil
    }

}
