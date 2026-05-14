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
import AVFoundation

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
    @Published var selectAudioFile: URL?
    @Published var speaking: Bool = false
    var selectedAudioPCM: [Int16] = []

    static let statusTextDefault = ""
    @Published var statusText = statusTextDefault

    @Published var chatText: [Message] = []
    var translatedIndex: Int = 0

    @Published var errorMessage = ""

    @Published var soundLevel: Float = 0.0

    private let semaphore = DispatchSemaphore(value: 1)

    deinit {
        unloadEngines()
    }

    public func selectedSourceLanguageChange() {
        selectedTargetLanguage = "invalid"
        if chatState != .SELECTING {
            unloadEngines()
        }
    }

    public func selectedTargetLanguageChange() {
        if chatState != .SELECTING {
            unloadEngines()
        }
    }

    public func startDemo(url: URL? = nil) {
        chatState = .LOADING
        selectAudioFile = url
        selectedAudioPCM = []
        loadEngines()
    }

    public func stopDemo() {
        chatState = .SELECTING
        unloadEngines()
    }

    public func loadEngines() {
        errorMessage = ""
        statusText = ""

        let sourceLanguage = selectedSourceLanguage
        let targetLanguage = selectedTargetLanguage

        do {
            if selectAudioFile != nil {
                selectedAudioPCM = try loadPCM(url: selectAudioFile!)

                if selectedAudioPCM.isEmpty {
                    throw NSError(domain: "Failed to load \(selectAudioFile!.absoluteString)", code: 1)
                }
            }
        } catch {
            errorMessage = "\(error.localizedDescription)"
            return
        }

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

                translatedIndex = 0

                if selectAudioFile != nil {
                    setStatusText("Loading Audio Player...")
                    audioStream = try AudioPlayerStream(sampleRate: Double(Cheetah.sampleRate))
                } else {
                    setStatusText("Loading Voice Processor...")
                    VoiceProcessor.instance.addFrameListener(VoiceProcessorFrameListener(audioCallback))
                    VoiceProcessor.instance.addErrorListener(VoiceProcessorErrorListener(errorCallback))
                    startAudioRecording()
                }

                setStatusText(ViewModel.statusTextDefault)

                if selectAudioFile != nil {
                    speakFile()
                } else {
                    DispatchQueue.main.async { [self] in
                        chatState = .LISTENING
                        chatText.removeAll()
                        chatText.append(Message(transcript: ""))
                    }
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
        audioStream = nil
        stopAudioRecording()
        VoiceProcessor.instance.clearFrameListeners()
        VoiceProcessor.instance.clearErrorListeners()

        DispatchQueue.global(qos: .userInitiated).async { [self] in
            if cheetah != nil {
                cheetah!.delete()
                cheetah = nil
            }
            if zebra != nil {
                zebra!.delete()
                zebra = nil
            }
        }

        errorMessage = ""
        chatText.removeAll()

        chatState = .SELECTING
    }

    private func loadPCM(url: URL) throws -> [Int16] {
        let file = try AVAudioFile(forReading: selectAudioFile!)
        let inputFormat = file.processingFormat
        let frameCount = UInt32(file.length)

        guard let outputFormat = AVAudioFormat(
            commonFormat: .pcmFormatInt16,
            sampleRate: Double(Cheetah.sampleRate),
            channels: 1,
            interleaved: true
        ) else {
            return []
        }

        guard let inputBuffer = AVAudioPCMBuffer(pcmFormat: inputFormat, frameCapacity: frameCount) else {
            return []
        }

        guard let outputBuffer = AVAudioPCMBuffer(pcmFormat: outputFormat, frameCapacity: frameCount) else {
            return []
        }

        try file.read(into: inputBuffer)

        guard let converter = AVAudioConverter(from: inputFormat, to: outputFormat) else {
            return []
        }

        try converter.convert(to: outputBuffer, from: inputBuffer)

        guard let channelData = outputBuffer.int16ChannelData else {
            return []
        }

        let unsafeBuffer = UnsafeBufferPointer(start: channelData[0], count: Int(frameCount))

        return Array(unsafeBuffer)
    }

    private func speakFile() {
        Task.detached(priority: .background) { [self] in
            do {
                await MainActor.run {
                    chatState = .LISTENING
                    chatText.removeAll()
                    chatText.append(Message(transcript: ""))
                    speaking = true
                }

                try audioStream!.playStreamPCM(selectedAudioPCM)
                let clock = ContinuousClock()
                let start = clock.now

                for index in stride(
                    from: 0,
                    to: selectedAudioPCM.count - Int(Cheetah.frameLength) + 1,
                    by: Int(Cheetah.frameLength)
                ) {
                    let quit = await MainActor.run {
                        return chatState != .LISTENING
                    }

                    if quit {
                        return
                    }

                    let indexEnd = index + Int(Cheetah.frameLength)
                    let duration = (clock.now - start).milliseconds()
                    let expected = index * 1000 / Int(Cheetah.sampleRate)
                    if expected > duration {
                        try await Task.sleep(for: .milliseconds(expected - duration))
                    }

                    audioCallback(frame: Array(selectedAudioPCM[index..<indexEnd]))
                }

                await MainActor.run {
                    _ = chatText.popLast()
                    speaking = false
                }
            } catch {
                await MainActor.run {
                    errorMessage = "\(error.localizedDescription)"
                }
            }
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

    private func appendChatText(text: String) {
        let R0 = /^(.*[.!?])(\s.*)?$/

        if !text.isEmpty {
            if let result = try? R0.wholeMatch(in: text) {
                DispatchQueue.main.async { [self] in
                    if chatText.count > 0 {
                        chatText[chatText.count - 1].appendTranscript(text: String(result.1))
                    }
                }

                flushChatText()

                DispatchQueue.main.async { [self] in
                    if chatText.count > 0 {
                        chatText[chatText.count - 1].appendTranscript(text: String(result.2 ?? ""))
                    }
                }

                return
            }

            DispatchQueue.main.async { [self] in
                if chatText.count > 0 {
                    chatText[chatText.count - 1].appendTranscript(text: text)
                }
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
                let index = translatedIndex
                translatedIndex += 1

                semaphore.wait()
                let translation = try self.zebra!.translate(text: chatText[index].transcript)
                semaphore.signal()

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
        computeSoundLevel(frame: frame)

        do {
            if chatState == .LISTENING && self.cheetah != nil {
                let partialTranscript = try self.cheetah!.process(frame)
                appendChatText(text: partialTranscript.0)

                if partialTranscript.1 && self.cheetah != nil {
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

struct Message: Equatable {
    var transcript: String
    var translated: String?

    mutating func appendTranscript(text: String) {
        if self.transcript.isEmpty {
            self.transcript.append(String(text
                .trimmingPrefix(while: \.isWhitespace)))
        } else {
            self.transcript.append(text)
        }
    }

    mutating func appendTranslated(text: String) {
        if self.translated != nil {
            self.translated!.append(text)
        } else {
            self.translated = text
        }
    }
}

extension Duration {
    func milliseconds() -> Int {
        let (seconds, attoseconds) = self.components
        return Int(seconds * 1000) + Int(attoseconds / Int64(1e15))
    }
}
