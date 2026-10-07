//
//  Copyright 2026 Picovoice Inc.
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
import AVFoundation

enum ChatState {
    case SELECTING
    case LOADING
    case DETECTING
    case LISTENING
    case TRANSLATING
    case ERROR
}

let LANGUAGE_DISPLAY: [String: String] = [
    "automatic": "Automatic",
    "de": "German",
    "en": "English",
    "es": "Spanish",
    "fr": "French",
    "it": "Italian"
]

let LANGUAGE_PAIRS: [String: [String]] = [
  "automatic": [
    "de",
    "en",
    "es",
    "fr",
    "it"
  ],
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

let DOTS = [
    " .  ",
    " .. ",
    " ...",
    "  ..",
    "   .",
    "    "
]

let BAT_THRESHOLD: Float32 = 0.75

class ViewModel: ObservableObject {

    private let ACCESS_KEY = "${YOUR_ACCESS_KEY_HERE}"

    private var bat: Bat?
    private var cheetah: Cheetah?
    private var zebra: Zebra?
    private var orca: Orca?

    private var audioStream: AudioPlayerStream?

    private var pcmBuffer: [Int16] = []
    private let diagnostics = RecipeDiagnostics()
    private var audioObservers: [NSObjectProtocol] = []

    @Published var dotIndex = 0
    private var timer: Timer?

    @Published var chatState: ChatState = .SELECTING {
        didSet {
            RecipeDiagnostics.log("state \(oldValue) -> \(chatState)")
        }
    }

    @Published var selectedSourceLanguage: String = "automatic"
    @Published var selectedTargetLanguage: String = "invalid"

    @Published var isPaused = false

    static let statusTextDefault = ""
    @Published var statusText = statusTextDefault

    @Published var promptText = ""
    @Published var enableGenerateButton = true

    @Published var chatText: [Message] = []

    @Published var errorMessage = ""

    deinit {
        timer?.invalidate()
        for observer in audioObservers {
            NotificationCenter.default.removeObserver(observer)
        }
        unloadEngines()
    }

    func withDots(_ content: String, dots: Bool) -> String {
        if dots && !isPaused {
            return content + DOTS[dotIndex]
        } else {
            return content
        }
    }

    init() {
        RecipeDiagnostics.log("session start endpointSeconds=1.0 punctuation=true normalization=true")
        RecipeDiagnostics.log("Cheetah=\(Cheetah.version) frameLength=\(Cheetah.frameLength) " +
                              "sampleRate=\(Cheetah.sampleRate)")
        let center = NotificationCenter.default
        for name in [AVAudioSession.routeChangeNotification, AVAudioSession.interruptionNotification] {
            audioObservers.append(center.addObserver(
                forName: name,
                object: AVAudioSession.sharedInstance(),
                queue: nil
            ) { notification in
                let reason = notification.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt
                let interruption = notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt
                RecipeDiagnostics.log("audio notification=\(notification.name.rawValue) " +
                                      "routeReason=\(reason.map(String.init) ?? "none") " +
                                      "interruption=\(interruption.map(String.init) ?? "none")")
                RecipeDiagnostics.logRoute()
            })
        }
        timer = Timer.scheduledTimer(withTimeInterval: 0.4, repeats: true) { [weak self] _ in
            guard let self = self else { return }
            self.dotIndex = (self.dotIndex + 1) % DOTS.count
            self.diagnostics.report(state: self.chatState, paused: self.isPaused)
        }
    }

    public func selectedSourceLanguageChange() {
        if chatState != .DETECTING {
            selectedTargetLanguage = "invalid"
        } else {
            startDemo()
        }
    }

    public func selectedTargetLanguageChange() {
        if chatState != .SELECTING {
            unloadEngines()
        }
        if selectedTargetLanguage != "invalid" {
            startDemo()
        }
    }

    public func startDemo() {
        RecipeDiagnostics.log("start source=\(selectedSourceLanguage) target=\(selectedTargetLanguage)")
        chatState = .LOADING
        if selectedSourceLanguage == "automatic" {
            loadBat()
        } else {
            loadEngines()
        }
    }

    public func loadEngines() {
        errorMessage = ""
        statusText = ""

        let sourceLanguage = selectedSourceLanguage
        let targetLanguage = selectedTargetLanguage

        DispatchQueue.global(qos: .userInitiated).async { [self] in
            let setStatusText = {(_ msg: String) in
                RecipeDiagnostics.log("load stage=\(msg)")
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

                setStatusText("Loading Orca \(targetLanguage)...")
                let orcaModelPath = Bundle(for: type(of: self))
                    .path(forResource: "orca_params_\(targetLanguage)_male", ofType: "pv")!
                orca = try Orca(accessKey: ACCESS_KEY, modelPath: orcaModelPath)

                setStatusText("Loading Audio Player...")
                audioStream = try AudioPlayerStream(sampleRate: Double(self.orca!.sampleRate!))

                setStatusText("Loading Voice Processor...")
                if bat != nil {
                    RecipeDiagnostics.log("Bat handoff recording=\(VoiceProcessor.instance.isRecording) " +
                                          "bufferedSamples=\(pcmBuffer.count)")
                    bat!.delete()
                } else {
                    VoiceProcessor.instance.addFrameListener(VoiceProcessorFrameListener(audioCallback))
                    VoiceProcessor.instance.addErrorListener(VoiceProcessorErrorListener(errorCallback))
                    startAudioRecording()
                }
                DispatchQueue.main.async { [self] in
                    isPaused = false
                }

                setStatusText(ViewModel.statusTextDefault)
                DispatchQueue.main.async { [self] in
                    chatState = .LISTENING
                    chatText.removeAll()
                    chatText.append(Message(transcript: ""))
                }
            } catch {
                RecipeDiagnostics.logError(error, operation: #function)
                DispatchQueue.main.async { [self] in
                    unloadEngines()
                    errorMessage = "\(error.localizedDescription)"
                }
            }
        }
    }

    public func loadBat() {
        errorMessage = ""
        statusText = ""

        DispatchQueue.global(qos: .userInitiated).async { [self] in
            let setStatusText = {(_ msg: String) in
                RecipeDiagnostics.log("load stage=\(msg)")
                DispatchQueue.main.async { [self] in
                    statusText = msg
                }
            }
            do {
                setStatusText("Loading Bat...")
                bat = try Bat(accessKey: ACCESS_KEY)

                setStatusText("Loading Voice Processor...")
                VoiceProcessor.instance.addFrameListener(VoiceProcessorFrameListener(audioCallback))
                VoiceProcessor.instance.addErrorListener(VoiceProcessorErrorListener(errorCallback))
                startAudioRecording()

                setStatusText("Start speaking in source language")
                DispatchQueue.main.async { [self] in
                    chatState = .DETECTING
                    chatText.removeAll()
                    chatText.append(Message(transcript: ""))
                }
            } catch {
                RecipeDiagnostics.logError(error, operation: #function)
                DispatchQueue.main.async { [self] in
                    unloadEngines()
                    errorMessage = "\(error.localizedDescription)"
                }
            }
        }
    }

    public func unloadEngines() {
        RecipeDiagnostics.log("unload engines")
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

        chatState = .SELECTING
    }

    public func pauseDemo() {
        RecipeDiagnostics.log("pause toggle currentlyPaused=\(isPaused)")
        if isPaused {
            VoiceProcessor.instance.addFrameListener(VoiceProcessorFrameListener(audioCallback))
            isPaused = false
        } else {
            VoiceProcessor.instance.clearFrameListeners()
            isPaused = true
        }
    }

    private func startAudioRecording() {
        DispatchQueue.main.sync {
            do {
                try VoiceProcessor.instance.start(
                    frameLength: Cheetah.frameLength,
                    sampleRate: Cheetah.sampleRate)
                RecipeDiagnostics.log("recording started active=\(VoiceProcessor.instance.isRecording)")
                RecipeDiagnostics.logRoute()
            } catch {
                RecipeDiagnostics.logError(error, operation: #function)
                errorMessage = "\(error.localizedDescription)"
            }
        }
    }

    private func stopAudioRecording() {
        do {
            try VoiceProcessor.instance.stop()
            RecipeDiagnostics.log("recording stopped")
        } catch {
            RecipeDiagnostics.logError(error, operation: #function)
            DispatchQueue.main.async { [self] in
                errorMessage = "\(error.localizedDescription)"
            }
        }
    }

    private func appendChatText(text: String, translated: Bool) {
        DispatchQueue.main.async { [self] in
            if chatText.count > 0 {
                if translated {
                    chatText[chatText.count - 1].appendTranslated(text: text)
                } else {
                    chatText[chatText.count - 1].appendTranscript(text: text)
                }
            }
        }
    }

    private func translateAndSpeak() {
        let turn = String(UUID().uuidString.prefix(8))
        RecipeDiagnostics.log("turn=\(turn) translation requested messages=\(chatText.count)")
        DispatchQueue.main.async { [self] in
            chatState = .TRANSLATING
        }

        DispatchQueue.global(qos: .userInitiated).async { [self] in
            Task {
                do {
                    let translationStart = ProcessInfo.processInfo.systemUptime
                    RecipeDiagnostics.log("turn=\(turn) translation begin " +
                                          "inputChars=\(chatText.last?.transcript.count ?? 0)")
                    let translation = try self.zebra!.translate(text: chatText[chatText.count - 1].transcript)
                    RecipeDiagnostics.log("turn=\(turn) translation end chars=\(translation.count) " +
                                          "seconds=\(ProcessInfo.processInfo.systemUptime - translationStart)")

                    let synthesisStart = ProcessInfo.processInfo.systemUptime
                    RecipeDiagnostics.log("turn=\(turn) synthesis begin")
                    let audio = try orca!.synthesize(text: translation)
                    RecipeDiagnostics.log("turn=\(turn) synthesis end samples=\(audio.pcm.count) " +
                                          "words=\(audio.wordArray.count) " +
                                          "seconds=\(ProcessInfo.processInfo.systemUptime - synthesisStart)")

                    RecipeDiagnostics.log("turn=\(turn) playback submitted " +
                                          "audioSeconds=\(Double(audio.pcm.count) / Double(orca!.sampleRate!)) " +
                                          "lastWordEnd=\(audio.wordArray.last?.endSec ?? -1)")
                    try audioStream!.playStreamPCM(audio.pcm)

                    var currentTime: Float = 0.0
                    for (index, word) in audio.wordArray.enumerated() {
                        let duration = Int((word.startSec - currentTime) * 1000)
                        try await Task.sleep(for: .milliseconds(duration))
                        currentTime = word.startSec

                        appendChatText(text: word.word, translated: true)

                        if index + 1 < audio.wordArray.count && !audio.wordArray[index + 1].word.first!.isPunctuation {
                            appendChatText(text: " ", translated: true)
                        }
                    }

                    let duration = Int((audio.wordArray.last!.endSec - currentTime) * 1000)
                    try await Task.sleep(for: .milliseconds(duration))

                    RecipeDiagnostics.log("turn=\(turn) word timing finished; requesting listening")
                    DispatchQueue.main.async { [self] in
                        chatState = .LISTENING
                        chatText.append(Message(transcript: ""))
                    }
                } catch {
                    RecipeDiagnostics.logError(error, operation: #function)
                    DispatchQueue.main.async { [self] in
                        errorMessage = "\(error.localizedDescription)"
                    }
                }
            }
        }
    }

    private func audioCallback(frame: [Int16]) {
        diagnostics.record(frame: frame, state: chatState)
        do {
            if chatState == .LISTENING {
                pcmBuffer.append(contentsOf: frame)

                var isFlushed = false
                while pcmBuffer.count >= Cheetah.frameLength {
                    let processStart = ProcessInfo.processInfo.systemUptime
                    diagnostics.processing(startedAt: processStart)
                    defer { diagnostics.processing(startedAt: nil) }
                    let partialTranscript = try self.cheetah!.process(Array(pcmBuffer[0..<Int(Cheetah.frameLength)]))
                    diagnostics.processed(seconds: ProcessInfo.processInfo.systemUptime - processStart,
                                          characters: partialTranscript.0.count)
                    pcmBuffer.removeFirst(Int(Cheetah.frameLength))
                    appendChatText(text: partialTranscript.0, translated: false)

                    if partialTranscript.1 {
                        RecipeDiagnostics.log("endpoint received bufferedSamples=\(pcmBuffer.count) " +
                                              "partialChars=\(partialTranscript.0.count)")
                        let flushStart = ProcessInfo.processInfo.systemUptime
                        let finalTranscript = try self.cheetah!.flush()
                        RecipeDiagnostics.log("flush complete chars=\(finalTranscript.count) " +
                                              "seconds=\(ProcessInfo.processInfo.systemUptime - flushStart)")
                        appendChatText(text: finalTranscript, translated: false)
                        appendChatText(text: " ", translated: false)

                        if chatText.count > 0 && !chatText[chatText.count - 1].transcript.isEmpty {
                            isFlushed = true
                        }
                        RecipeDiagnostics.log("endpoint decision translate=\(isFlushed) " +
                                              "uiChars=\(chatText.last?.transcript.count ?? 0)")
                    }
                }
                if isFlushed {
                    translateAndSpeak()
                }
            } else if chatState == .DETECTING {
                pcmBuffer.append(contentsOf: frame)

                var foundLanguage = BatLanguages.UNKNOWN
                if pcmBuffer.count >= Bat.frameLength {
                    let bufferStart = pcmBuffer.count - Int(Bat.frameLength)
                    let bufferEnd = pcmBuffer.count

                    let scores = try bat!.process(Array(pcmBuffer[bufferStart..<bufferEnd]))
                    if scores != nil {
                        for (identified, confidence) in scores! where confidence >= BAT_THRESHOLD {
                            foundLanguage = identified
                        }
                    }
                }

                if foundLanguage != BatLanguages.UNKNOWN {
                    let foundLanguageString = foundLanguage.toString()
                    RecipeDiagnostics.log("Bat detected=\(foundLanguageString) bufferedSamples=\(pcmBuffer.count)")
                    if LANGUAGE_PAIRS.keys.contains(foundLanguageString) &&
                        LANGUAGE_PAIRS[foundLanguageString]!.contains(selectedTargetLanguage) {

                        DispatchQueue.main.async { [self] in
                            selectedSourceLanguage = foundLanguageString
                        }
                    } else {
                        DispatchQueue.main.async { [self] in
                            statusText = "Cannot translate from \(foundLanguageString) to \(selectedTargetLanguage)"
                        }
                    }
                }
            }
        } catch {
            RecipeDiagnostics.logError(error, operation: #function)
            DispatchQueue.main.async { [self] in
                errorMessage = "\(error.localizedDescription)"
            }
        }
    }

    private func errorCallback(error: VoiceProcessorError) {
        RecipeDiagnostics.logError(error, operation: "microphone callback")
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

class RecipeDiagnostics {
    private let lock = NSLock()
    private var lastReport = ProcessInfo.processInfo.systemUptime
    private var lastFrame: TimeInterval?
    private var processingStart: TimeInterval?
    private var frames = 0
    private var ignoredFrames = 0
    private var processedFrames = 0
    private var characters = 0
    private var maxProcessSeconds: TimeInterval = 0
    private var squareSum: Double = 0
    private var samples = 0

    static func log(_ message: String) {
        NSLog("%@", "[S2S] t=\(ProcessInfo.processInfo.systemUptime) \(message)")
    }

    static func logError(_ error: Error, operation: String) {
        log("error operation=\(operation) type=\(type(of: error)) code=\((error as NSError).code)")
    }

    static func logRoute() {
        let session = AVAudioSession.sharedInstance()
        log("route inputs=\(session.currentRoute.inputs.map { $0.portType.rawValue }) " +
            "outputs=\(session.currentRoute.outputs.map { $0.portType.rawValue }) " +
            "category=\(session.category.rawValue) options=\(session.categoryOptions.rawValue) " +
            "sampleRate=\(session.sampleRate)")
    }

    func record(frame: [Int16], state: ChatState) {
        let sum = frame.reduce(0.0) { $0 + pow(Double($1) / Double(Int16.max), 2) }
        lock.lock()
        defer { lock.unlock() }
        frames += 1
        if state != .LISTENING && state != .DETECTING {
            ignoredFrames += 1
        }
        lastFrame = ProcessInfo.processInfo.systemUptime
        squareSum += sum
        samples += frame.count
    }

    func processing(startedAt: TimeInterval?) {
        lock.lock()
        processingStart = startedAt
        lock.unlock()
    }

    func processed(seconds: TimeInterval, characters: Int) {
        lock.lock()
        defer { lock.unlock() }
        processedFrames += 1
        self.characters += characters
        maxProcessSeconds = max(maxProcessSeconds, seconds)
    }

    func report(state: ChatState, paused: Bool) {
        let now = ProcessInfo.processInfo.systemUptime
        lock.lock()
        guard now - lastReport >= 2 else {
            lock.unlock()
            return
        }
        let processingAge = processingStart.map { String(format: "%.2f", now - $0) } ?? "none"
        let age = lastFrame.map { String(format: "%.2f", now - $0) } ?? "none"
        let level = samples > 0 ? String(format: "%.1f", 10 * log10(max(squareSum / Double(samples), 1e-12))) : "none"
        let summary = "audio state=\(state) paused=\(paused) recording=\(VoiceProcessor.instance.isRecording) " +
            "windowSeconds=\(String(format: "%.2f", now - lastReport)) frames=\(frames) " +
            "ignored=\(ignoredFrames) lastFrameAge=\(age) rmsDBFS=\(level) " +
            "cheetahFrames=\(processedFrames) partialChars=\(characters) processingAge=\(processingAge) " +
            "maxProcessMs=\(String(format: "%.2f", maxProcessSeconds * 1000))"
        lastReport = now
        frames = 0
        ignoredFrames = 0
        processedFrames = 0
        characters = 0
        maxProcessSeconds = 0
        squareSum = 0
        samples = 0
        lock.unlock()
        Self.log(summary)
    }
}
