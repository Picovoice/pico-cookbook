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
import ios_voice_processor

import Foundation
import Combine

enum AppState {
    case idle
    case enrolling
    case testing
}

class ViewModel: ObservableObject {
    private let ACCESS_KEY = "${YOUR_ACCESS_KEY_HERE}"
    private let EAGLE_THRESHOLD: Float = 0.75

    @Published var appState: AppState = .idle
    @Published var statusText: String = "Ready to Enroll"
    @Published var enrollPercentage: Float = 0.0
    @Published var hasEnrolled: Bool = false
    @Published var soundLevel: Float = 0.0

    @Published var showTestResult: Bool = false
    @Published var isTestVerified: Bool = false
    @Published var testScore: Float = 0.0

    private var porcupine: Porcupine?
    private var eagleProfiler: EagleProfiler?
    private var eagle: Eagle?
    private var speakerProfile: EagleProfile?

    private var enrollBuffer: [Int16] = []
    private var enrollMaxSamples: Int = 0
    private var enrollValidSamples: Int = 0
    private var eagleFrameLength: Int = 0

    private var testBuffer: [Int16] = []
    private var testMaxSamples: Int = 0

    init() {
        VoiceProcessor.instance.addFrameListener(VoiceProcessorFrameCallback(audioCallback))
        VoiceProcessor.instance.addErrorListener(VoiceProcessorErrorCallback(errorCallback))
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
        guard checkArgs() else { return }

        do {
            try stopAudio()

            guard let keywordPath = getKeywordPath() else { return }

            porcupine = try Porcupine(accessKey: ACCESS_KEY, keywordPaths: [keywordPath], sensitivities: [0.5])
            eagleProfiler = try EagleProfiler(accessKey: ACCESS_KEY)

            eagleFrameLength = Int(try eagleProfiler!.frameLength)
            enrollMaxSamples = eagleFrameLength * 64

            enrollBuffer = [Int16](repeating: 0, count: enrollMaxSamples)
            enrollValidSamples = 0

            DispatchQueue.main.async {
                self.appState = .enrolling
                self.updateUIForState()
            }

            try VoiceProcessor.instance.start(
                frameLength: porcupine!.frameLength,
                sampleRate: porcupine!.sampleRate
            )
        } catch {
            DispatchQueue.main.async {
                self.statusText = "Engine init error: \(error.localizedDescription)"
            }
        }
    }

    private func performStartTesting() {
        guard checkArgs() else { return }

        do {
            try stopAudio()

            guard let keywordPath = getKeywordPath() else { return }

            porcupine = try Porcupine(accessKey: ACCESS_KEY, keywordPaths: [keywordPath], sensitivities: [0.5])
            eagle = try Eagle(accessKey: ACCESS_KEY)

            testMaxSamples = Int(try eagle!.frameLength)
            testBuffer = [Int16](repeating: 0, count: testMaxSamples)

            DispatchQueue.main.async {
                self.appState = .testing
                self.updateUIForState()
            }

            try VoiceProcessor.instance.start(
                frameLength: porcupine!.frameLength,
                sampleRate: porcupine!.sampleRate
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
            speakerProfile = try eagleProfiler!.export()
            DispatchQueue.main.async {
                self.hasEnrolled = true
                self.cancel()
            }
        } catch {
            print("Failed to export profile: \(error)")
        }
    }

    private func showResult(isVerified: Bool, score: Float) {
        DispatchQueue.main.async {
            self.isTestVerified = isVerified
            self.testScore = score
            self.showTestResult = true
            self.statusText = ""

            DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) {
                self.showTestResult = false
                self.updateUIForState()
            }
        }
    }

    private func updateUIForState() {
        if showTestResult { return }

        switch appState {
        case .idle:
            statusText = hasEnrolled ? "Ready to Test or Re-Enroll" : "Ready to Enroll"
        case .enrolling:
            statusText = "Say the wake word until\nthe circle is full"
            enrollPercentage = 0.0
        case .testing:
            statusText = "Listening for wake word..."
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
                let keywordIndex = try porcupine!.process(pcm: frame)
                if keywordIndex == 0 {
                    let numEagleFrames = enrollValidSamples / eagleFrameLength
                    let startIndex = enrollMaxSamples - enrollValidSamples

                    var progress: Float = 0.0
                    for i in 0..<numEagleFrames {
                        let chunkStart = startIndex + (i * eagleFrameLength)
                        let chunkEnd = chunkStart + eagleFrameLength
                        let chunk = Array(enrollBuffer[chunkStart..<chunkEnd])

                        let result = try eagleProfiler!.enroll(pcm: chunk)
                        progress = result.percentage
                    }
                    enrollValidSamples = 0

                    DispatchQueue.main.async {
                        self.enrollPercentage = progress
                        if progress >= 100.0 {
                            self.finishEnrollment()
                        }
                    }
                }
            } catch {
                print("Audio processing error: \(error)")
            }

        } else if appState == .testing {
            testBuffer.removeFirst(frame.count)
            testBuffer.append(contentsOf: frame)

            do {
                let keywordIndex = try porcupine!.process(pcm: frame)
                if keywordIndex == 0 {
                    guard let activeProfile = speakerProfile else { return }
                    let scores = try eagle!.process(pcm: testBuffer, profiles: [activeProfile])

                    if let score = scores.first {
                        let isVerified = score >= EAGLE_THRESHOLD
                        showResult(isVerified: isVerified, score: score)
                    } else {
                        showResult(isVerified: false, score: 0.0)
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

        porcupine?.delete()
        porcupine = nil
        eagleProfiler?.delete()
        eagleProfiler = nil
        eagle?.delete()
        eagle = nil
    }

    private func checkArgs() -> Bool {
        if ACCESS_KEY == "${YOUR_ACCESS_KEY_HERE}" {
            DispatchQueue.main.async {
                self.statusText = "Please set your Picovoice AccessKey in ViewModel.swift"
            }
            return false
        }
        return true
    }

    private func getKeywordPath() -> String? {
        if let path = Bundle.main.path(forResource: "porcupine_model", ofType: "ppn") {
            return path
        }
        DispatchQueue.main.async {
            self.statusText = "Could not find porcupine_model.ppn. Make sure setup.py ran."
        }
        return nil
    }
}