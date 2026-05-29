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
import SwiftUI
import Combine

enum AppState {
    case idle
    case enrolling
    case testing
    case error
}

struct Speaker: Identifiable {
    let id = UUID()
    let name: String
    let profile: EagleProfile
    let color: Color
}

class ViewModel: ObservableObject {
    private let ACCESS_KEY = "${YOUR_ACCESS_KEY_HERE}"
    private let EAGLE_THRESHOLD: Float = 0.75
    private let MAX_BUFFERED_AUDIO_FRAMES = 96
    let MAX_SPEAKERS = 10

    let speakerPalette: [Color] = [
        Color(red: 55/255, green: 125/255, blue: 255/255),  // Blue
        Color(red: 16/255, green: 185/255, blue: 129/255),  // Emerald Green
        Color(red: 139/255, green: 92/255, blue: 246/255),  // Violet
        Color(red: 236/255, green: 72/255, blue: 153/255),  // Pink
        Color(red: 245/255, green: 158/255, blue: 11/255),  // Amber
        Color(red: 6/255, green: 182/255, blue: 212/255),   // Cyan
        Color(red: 239/255, green: 68/255, blue: 68/255),   // Red
        Color(red: 132/255, green: 204/255, blue: 22/255),  // Lime
        Color(red: 99/255, green: 102/255, blue: 241/255),  // Indigo
        Color(red: 244/255, green: 63/255, blue: 94/255)    // Rose
    ]

    @Published var appState: AppState = .idle
    @Published var statusText: String = "Ready to Enroll"
    @Published var errorMessage: String = ""
    @Published var enrollPercentage: Float = 0.0
    @Published var volumeLevel: Float = 0.0

    @Published var speakers: [Speaker] = []
    @Published var pendingSpeakerName: String = ""

    @Published var showTestResult: Bool = false
    @Published var detectedSpeaker: Speaker?

    private var porcupine: Porcupine?
    private var eagleProfiler: EagleProfiler?
    private var eagle: Eagle?

    private var enrollBuffer: [Int16] = []
    private var enrollMaxSamples: Int = 0
    private var enrollValidSamples: Int = 0
    private var testBuffer: [Int16] = []

    private let audioLock = NSLock()

    public func addSpeaker(name: String) {
        pendingSpeakerName = name.isEmpty ? "Speaker \(speakers.count + 1)" : name
        checkPermissionsAndStart(targetState: .enrolling)
    }

    public func clearAll() {
        speakers.removeAll()
        updateUIForState()
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
                    self.handleError("Microphone permission is required for this demo")
                }
            }
        }
    }

    private func performStartEnrollment() {
        do {
            try stopAudio()

            guard let keywordPath = getKeywordPath() else { return }

            porcupine = try Porcupine(
                accessKey: ACCESS_KEY,
                keywordPaths: [keywordPath],
                sensitivities: [0.5]
            )
            eagleProfiler = try EagleProfiler(
                accessKey: ACCESS_KEY,
                minEnrollmentChunks: 4,
                voiceThreshold: 0.0)

            enrollMaxSamples = EagleProfiler.frameLength * MAX_BUFFERED_AUDIO_FRAMES

            enrollBuffer = [Int16](repeating: 0, count: enrollMaxSamples)
            enrollValidSamples = 0

            DispatchQueue.main.async {
                self.appState = .enrolling
                self.updateUIForState()
            }

            VoiceProcessor.instance.addFrameListener(VoiceProcessorFrameListener(audioCallback))
            VoiceProcessor.instance.addErrorListener(VoiceProcessorErrorListener(errorCallback))
            try VoiceProcessor.instance.start(
                frameLength: Porcupine.frameLength,
                sampleRate: Porcupine.sampleRate
            )
        } catch {
            self.handleError("Engine init error: \(error.localizedDescription)")
        }
    }

    private func performStartTesting() {
        do {
            try stopAudio()

            guard let keywordPath = getKeywordPath() else { return }

            porcupine = try Porcupine(
                accessKey: ACCESS_KEY,
                keywordPaths: [keywordPath],
                sensitivities: [0.5])
            eagle = try Eagle(
                accessKey: ACCESS_KEY,
                voiceThreshold: 0.0)

            testBuffer = [Int16](repeating: 0, count: try eagle!.minProcessSamples())

            DispatchQueue.main.async {
                self.appState = .testing
                self.updateUIForState()
            }

            VoiceProcessor.instance.addFrameListener(VoiceProcessorFrameListener(audioCallback))
            VoiceProcessor.instance.addErrorListener(VoiceProcessorErrorListener(errorCallback))
            try VoiceProcessor.instance.start(
                frameLength: Porcupine.frameLength,
                sampleRate: Porcupine.sampleRate
            )
        } catch {
            self.handleError("Engine init error: \(error.localizedDescription)")
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
            let newProfile = try eagleProfiler!.export()
            let newColor = speakerPalette[speakers.count % speakerPalette.count]
            let newSpeaker = Speaker(name: pendingSpeakerName, profile: newProfile, color: newColor)

            DispatchQueue.main.async {
                self.speakers.append(newSpeaker)
                self.cancel()
            }
        } catch {
            self.handleError("Failed to export profile: \(error.localizedDescription)")
        }
    }

    private func showResult(speaker: Speaker) {
        DispatchQueue.main.async {
            self.detectedSpeaker = speaker

            withAnimation(.spring(response: 0.3, dampingFraction: 0.6)) {
                self.showTestResult = true
            }

            DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) {
                withAnimation {
                    self.showTestResult = false
                    self.updateUIForState()
                }
            }
        }
    }

    private func updateUIForState() {
        if showTestResult { return }

        switch appState {
        case .idle:
            statusText = speakers.isEmpty ? "Ready to Enroll" : ""
        case .enrolling:
            statusText = "Hello \(pendingSpeakerName)\n\nSay the wake word until\nthe circle is full"
            enrollPercentage = 0.0
        case .testing:
            statusText = "Listening for wake word..."
        case .error:
            break
        }
    }

    private func audioCallback(frame: [Int16]) {
        self.calculateVolume(frame: frame)

        audioLock.lock()
        defer { audioLock.unlock() }

        guard let porcupine = self.porcupine else { return }

        if appState == .enrolling {
            enrollBuffer.removeFirst(frame.count)
            enrollBuffer.append(contentsOf: frame)
            enrollValidSamples = min(enrollMaxSamples, enrollValidSamples + frame.count)

            do {
                let keywordIndex = try porcupine.process(pcm: frame)
                if keywordIndex == 0 {
                    let numEagleFrames = enrollValidSamples / EagleProfiler.frameLength
                    let startIndex = enrollMaxSamples - enrollValidSamples

                    var progress: Float = 0.0
                    for i in 0..<numEagleFrames {
                        let chunkStart = startIndex + (i * EagleProfiler.frameLength)
                        let chunkEnd = chunkStart + EagleProfiler.frameLength
                        let chunk = Array(enrollBuffer[chunkStart..<chunkEnd])

                        progress = try eagleProfiler!.enroll(pcm: chunk)
                    }
                    enrollValidSamples = 0

                    DispatchQueue.main.async {
                        if progress >= 100.0 {
                            withAnimation(.easeInOut(duration: 0.5)) {
                                self.enrollPercentage = progress
                            } completion: {
                                self.finishEnrollment()
                            }
                        } else {
                            withAnimation(.easeInOut(duration: 0.5)) {
                                self.enrollPercentage = progress
                            }
                        }
                    }
                }
            } catch {
                self.handleError("Audio processing error: \(error.localizedDescription)")
            }

        } else if appState == .testing {
            testBuffer.removeFirst(frame.count)
            testBuffer.append(contentsOf: frame)

            do {
                let keywordIndex = try porcupine.process(pcm: frame)
                if keywordIndex == 0 {
                    let profiles = speakers.map { $0.profile }
                    let scores = try eagle!.process(
                        pcm: testBuffer,
                        speakerProfiles: profiles)

                    if let scores = scores {
                        var bestScore: Float = 0.0
                        var bestIndex = -1
                        for i in 0..<scores.count where scores[i] > bestScore {
                            bestScore = scores[i]
                            bestIndex = i
                        }

                        if bestScore >= EAGLE_THRESHOLD && bestIndex != -1 {
                            showResult(speaker: speakers[bestIndex])
                        }
                    }
                }
            } catch {
                self.handleError("Audio processing error: \(error.localizedDescription)")
            }
        }
    }

    private func errorCallback(error: VoiceProcessorError) {
        self.handleError("Audio Error: \(error.localizedDescription)")
    }

    private func handleError(_ message: String) {
        DispatchQueue.main.async {
            try? self.stopAudio()
            self.errorMessage = message
            self.appState = .error
        }
    }

    private func calculateVolume(frame: [Int16]) {
        let MIN_DB: Float = -40.0
        let MAX_DB: Float = 0.0

        var sum: Float = 0.0
        for sample in frame {
            sum += pow(Float(sample), 2)
        }
        let rms = (sum / Float(frame.count)) / pow(Float(Int16.max), 2)
        let dbfs = 10 * log10(max(rms, 1e-9))
        let normalized = (dbfs - MIN_DB) / (MAX_DB - MIN_DB)
        let volume =  max(0.0, min(1.0, normalized))

        DispatchQueue.main.async {
            self.volumeLevel = volume
        }
    }

    private func stopAudio() throws {
        if VoiceProcessor.instance.isRecording {
            try VoiceProcessor.instance.stop()
        }

        VoiceProcessor.instance.clearErrorListeners()
        VoiceProcessor.instance.clearFrameListeners()

        audioLock.lock()
        defer { audioLock.unlock() }

        porcupine?.delete()
        porcupine = nil
        eagleProfiler?.delete()
        eagleProfiler = nil
        eagle?.delete()
        eagle = nil
    }

    private func getKeywordPath() -> String? {
        if let path = Bundle.main.path(forResource: "porcupine_model", ofType: "ppn") {
            return path
        }
        self.handleError("Could not find porcupine_model.ppn. Make sure setup.py was run.")
        return nil
    }
}
