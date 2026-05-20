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

enum Steps {
    case CHEETAH,
         ORCA,
         PORCUPINE,
         RHINO
}

enum CardType: CaseIterable {
    case UNIT_ID,
         OIL_CONDITION,
         TIRE_CONDITION,
         SERVICE_STATUS,
         NOTES
}

enum RecipeSteps {
    case STANDBY,
         PROMPT_USER,
         RECORD_USER,
         TRANSCRIBE_USER
}

enum RecipeStates {
    case STANDBY,
         IDENTIFY_UNIT_PROMPT,
         IDENTIFY_UNIT_REPORT,
         CHECK_OIL_PROMPT,
         CHECK_OIL_REPORT,
         CHECK_TIRE_PROMPT,
         CHECK_TIRE_REPORT,
         CHECK_SERVICE_STATUS_PROMPT,
         CHECK_SERVICE_STATUS_REPORT,
         FINAL_NOTE_PROMPT,
         FINAL_NOTE_REPORT,
         REPORT_COMPILATION
}

enum ViewState {
    case loading, main
}

enum ListenState {
    case idle, listening
}

class ViewModel: ObservableObject {

    private let ACCESS_KEY = "${YOUR_ACCESS_KEY_HERE}"

    private let KEYWORD_MODEL = "keyword.ppn"

    private let CONTEXT_MODEL = "context.rhn"

    private let STT_MODEL = "cheetah_params.pv"

    private let TTS_MODEL = "orca_params_en_female.pv"

    private let NS_MODEL = "koala_params.pv"

    let cardTitles: [CardType: String] = [
        .UNIT_ID: "UNIT ID",
        .OIL_CONDITION: "OIL CONDITION",
        .TIRE_CONDITION: "TIRE CONDITION",
        .SERVICE_STATUS: "SERVICE STATUS",
        .NOTES: "NOTES"
    ]
    
    @Published var viewState: ViewState = .loading
    @Published var listenState: ListenState = .idle
    @Published var statusText: String = ""
    @Published var errorText: String?
    @Published var enginesLoaded: Bool = false
    @Published var soundLevel: Float = 0.0
    @Published var activeCard: CardType?
    @Published var cardValues: [CardType: String] = [:]

    private var koala: Koala?
    private var porcupine: Porcupine?
    private var orca: Orca?
    private var rhino: Rhino?
    private var cheetah: Cheetah?

    private var audioPlayerStream: AudioPlayerStream?
    private var basicRecorder: BasicRecorder?
    private var noiseSuppressionRecorder: AINoiseSuppressionRecorder?
    
    private var cheetahStep: CheetahStep?
    private var orcaStep: OrcaStep?
    private var porcupineStep: PorcupineStep?
    private var rhinoStep: RhinoStep?
    private var workflow: Workflow?
    
    init() {
        Task.detached(priority: .background) { [self] in
            do {
                try await preloadDemo()
            } catch {
                await setErrorText(text: error.localizedDescription)
            }
        }
    }
    
    func setViewState(state: ViewState) async {
        await MainActor.run {
            viewState = state
        }
    }
    
    func setListenState(state: ListenState) async {
        await MainActor.run {
            listenState = state
        }
    }

    func setStatusText(text: String) async {
        await MainActor.run {
            statusText = text
        }
    }

    func setErrorText(text: String) async {
        await MainActor.run {
            errorText = text
        }
    }
    
    func setActiveCard(card: CardType?) async {
        await MainActor.run {
            activeCard = card
        }
    }
    
    func setCardValue(card: CardType, value: String) async {
        await MainActor.run {
            cardValues[card] = value
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

        audioPlayerStream = try AudioPlayerStream(
            sampleRate: Double(orca!.sampleRate!))
        basicRecorder = BasicRecorder(
            frameLength: Koala.frameLength,
            sampleRate: Koala.sampleRate,
            frameCallback: computeSoundLevel)
        noiseSuppressionRecorder = AINoiseSuppressionRecorder(
            recorder: basicRecorder!,
            koala: koala!)

        cheetahStep = CheetahStep(
            recorder: noiseSuppressionRecorder!,
            cheetah: cheetah!)
        orcaStep = OrcaStep(
            speaker: audioPlayerStream!,
            orca: orca!)
        porcupineStep = PorcupineStep(
            recorder: noiseSuppressionRecorder!,
            porcupine: porcupine!)
        rhinoStep = RhinoStep(
            recorder: noiseSuppressionRecorder!,
            rhino: rhino!)
        workflow = Workflow(
            viewModel: self,
            cheetahStep: cheetahStep!,
            orcaStep: orcaStep!,
            porcupineStep: porcupineStep!,
            rhinoStep: rhinoStep!)

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
                    listenState = .idle
                    statusText = ""
                    activeCard = nil
                    cardValues.removeAll()
                }
                
                try await workflow!.run()

                await setViewState(state: .loading)
            } catch {
                await setErrorText(text: error.localizedDescription)
            }
        }
    }
    
    public func stopDemo() {
        workflow!.cancel()
    }

    func computeSoundLevel(frame: [Int16]) async {
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

        await MainActor.run {
            soundLevel = soundLevel * 0.5 + Float(clamped) * 0.5
        }
    }
}

class Workflow {
    let states: [RecipeStates: State]
    
    init(
        viewModel: ViewModel,
        cheetahStep: CheetahStep,
        orcaStep: OrcaStep,
        porcupineStep: PorcupineStep,
        rhinoStep: RhinoStep
    ) {
        states = [
            .STANDBY: StandbyState(
                viewModel: viewModel,
                porcupineStep: porcupineStep,
                nextState: .IDENTIFY_UNIT_PROMPT),
            .IDENTIFY_UNIT_PROMPT: PromptState(
                viewModel: viewModel,
                orcaStep: orcaStep,
                defaultPrompt: "What is the unit ID?",
                nextState: .IDENTIFY_UNIT_REPORT,
                cardType: .UNIT_ID
            ),
            .IDENTIFY_UNIT_REPORT: ReportState(
                viewModel: viewModel,
                rhinoStep: rhinoStep,
                listeningPrompt: "Listening for unit ID",
                expectedIntent: "identifyUnit",
                cardType: .UNIT_ID,
                successLogGen: {inf in inf.slots["unitId"]!},
                successNextState: .CHECK_OIL_PROMPT,
                failurePromptGen: {inf in "Failed to capture unit ID. Retrying..."},
                failureNextState: .IDENTIFY_UNIT_PROMPT),
            .CHECK_OIL_PROMPT: PromptState(
                viewModel: viewModel,
                orcaStep: orcaStep,
                defaultPrompt: "What's the oil level?",
                nextState: .CHECK_OIL_REPORT,
                cardType: .OIL_CONDITION),
            .CHECK_OIL_REPORT: ReportState(
                viewModel: viewModel,
                rhinoStep: rhinoStep,
                listeningPrompt: "Listening for oil status",
                expectedIntent: "reportOilCondition",
                cardType: .OIL_CONDITION,
                successLogGen: {inf in inf.slots["fluidCondition"]!},
                successNextState: .CHECK_TIRE_PROMPT,
                failurePromptGen: {inf in "Failed to capture oil condition. Retrying..."},
                failureNextState: .CHECK_OIL_PROMPT),
            .CHECK_TIRE_PROMPT: PromptState(
                viewModel: viewModel,
                orcaStep: orcaStep,
                defaultPrompt: "What's the tire condition?",
                nextState: .CHECK_TIRE_REPORT,
                cardType: .TIRE_CONDITION),
            .CHECK_TIRE_REPORT: ReportState(
                viewModel: viewModel,
                rhinoStep: rhinoStep,
                listeningPrompt: "Listening for tire condition",
                expectedIntent: "reportTireCondition",
                cardType: .TIRE_CONDITION,
                successLogGen: {inf in inf.slots["tireCondition"]!},
                successNextState: .CHECK_SERVICE_STATUS_PROMPT,
                failurePromptGen: {inf in "Failed to capture tire condition. Retrying..."},
                failureNextState: .CHECK_TIRE_PROMPT),
            .CHECK_SERVICE_STATUS_PROMPT: PromptState(
                viewModel: viewModel,
                orcaStep: orcaStep,
                defaultPrompt: "What's the vehicle status?",
                nextState: .CHECK_SERVICE_STATUS_REPORT,
                cardType: .SERVICE_STATUS),
            .CHECK_SERVICE_STATUS_REPORT: ReportState(
                viewModel: viewModel,
                rhinoStep: rhinoStep,
                listeningPrompt: "Listening for vehicle status",
                expectedIntent: "reportServiceStatus",
                cardType: .SERVICE_STATUS,
                successLogGen: {inf in inf.slots["serviceStatus"]!},
                successNextState: .FINAL_NOTE_PROMPT,
                failurePromptGen: {inf in "Failed to capture vehicle status. Retrying..."},
                failureNextState: .CHECK_SERVICE_STATUS_PROMPT),
            .FINAL_NOTE_PROMPT: PromptState(
                viewModel: viewModel,
                orcaStep: orcaStep,
                defaultPrompt: "Please provide final notes.",
                nextState: .FINAL_NOTE_REPORT,
                cardType: .NOTES),
            .FINAL_NOTE_REPORT: DictationState(
                viewModel: viewModel,
                cheetahStep: cheetahStep,
                listeningPrompt: "Listening for notes...",
                nextState: .REPORT_COMPILATION,
                cardType: .NOTES),
            .REPORT_COMPILATION: PromptState(
                viewModel: viewModel,
                orcaStep: orcaStep,
                defaultPrompt: "Field report recorded.",
                nextState: nil,
                cardType: nil)
        ]
    }
    
    func run() async throws {
        var currentState: RecipeStates? = .STANDBY
        var currentArgs: [String: Any] = [:]

        while currentState != nil {
            let state = states[currentState!]
            let transition = try await state!.run(args: currentArgs)
            currentState = transition.nextState
            currentArgs = transition.nextStateArgs
        }
    }
    
    func cancel() {
        for state in states.values {
            state.cancel()
        }
    }
}

protocol Recorder {
    func start() throws
    func stop() throws
    func read(frameLength: UInt32) async throws -> [Int16]
}

class BasicRecorder: Recorder {
    let frameLength: UInt32
    let sampleRate: UInt32
    var stream: AsyncStream<Int16>?
    var continuation: AsyncStream<Int16>.Continuation?
    var listener: VoiceProcessorFrameListener?
    let frameCallback: (([Int16]) async -> Void)?

    init(
            frameLength: UInt32,
            sampleRate: UInt32,
            frameCallback: (([Int16]) async -> Void)? = nil
    ) {
        self.frameLength = frameLength
        self.sampleRate = sampleRate
        self.frameCallback = frameCallback
        self.listener = VoiceProcessorFrameListener(audioCallback)
        
        VoiceProcessor.instance.addFrameListener(listener!)
    }

    deinit {
        VoiceProcessor.instance.removeFrameListener(listener!)
    }

    func start() throws {
        try VoiceProcessor.instance.start(
            frameLength: frameLength,
            sampleRate: sampleRate)
        
        let (stream, continuation) = AsyncStream.makeStream(of: Int16.self)
        self.stream = stream
        self.continuation = continuation
    }

    func stop() throws {
        try VoiceProcessor.instance.stop()
        self.stream = nil
        self.continuation = nil
    }

    func read(frameLength: UInt32) async throws -> [Int16] {
        var index = 0
        var frame = [Int16](repeating: 0, count: Int(frameLength))
        for await sample in stream!.prefix(Int(frameLength)) {
            frame[index] = sample
            index += 1
        }
        return frame
    }

    private func audioCallback(frame: [Int16]) {
        if frameCallback != nil {
            Task.detached(priority: .background) {[self] in
                await frameCallback!(frame)
            }
        }
        
        let cont = continuation
        if cont != nil {
            for sample in frame {
                cont!.yield(sample)
            }
        }
    }
}

class AINoiseSuppressionRecorder: Recorder {
    let recorder: Recorder
    let koala: Koala
    
    init(recorder: Recorder, koala: Koala) {
        self.recorder = recorder
        self.koala = koala
    }
    
    func start() throws {
        try recorder.start()
    }
    
    func stop() throws {
        try recorder.stop()
    }
    
    func read(frameLength: UInt32) async throws -> [Int16] {
        var buffer: [Int16] = []
        
        while buffer.count < frameLength {
            let rawFrame = try await recorder.read(frameLength: Koala.frameLength)
            let nsFrame = try koala.process(rawFrame)
            buffer.append(contentsOf: nsFrame)
        }
        
        return buffer
    }
}

class CheetahStep {
    let recorder: Recorder
    let cheetah: Cheetah
    var shouldCancel: Bool = false
    
    init(recorder: Recorder, cheetah: Cheetah) {
        self.recorder = recorder
        self.cheetah = cheetah
    }
    
    func run() async throws -> String? {
        var result = ""
        
        try recorder.start()
        
        var isEndpoint = false
        shouldCancel = false
        while !isEndpoint && !shouldCancel {
            let frame = try await recorder.read(frameLength: Cheetah.frameLength)
            let (transcript, endpoint) = try cheetah.process(frame)
            result += transcript
            
            if endpoint {
                let flush = try cheetah.flush()
                result += flush
                isEndpoint = true
            }
        }
        
        try recorder.stop()
        
        if shouldCancel {
            return nil
        } else {
            return result
        }
    }
    
    func cancel() {
        shouldCancel = true
    }
}

class OrcaStep {
    let speaker: AudioPlayerStream
    let orca: Orca
    var shouldCancel: Bool = false
    
    init(speaker: AudioPlayerStream, orca: Orca) {
        self.speaker = speaker
        self.orca = orca
    }
    
    func run(prompt: String) async throws -> Bool {
        shouldCancel = false
        let audio = try orca.synthesize(text: prompt)
        if shouldCancel {
            return false
        }

        speaker.resetAudioPlayer()
        try speaker.playStreamPCM(audio.pcm)
    
        while speaker.isPlaying && !shouldCancel {
            try await Task.sleep(for: .milliseconds(100))
        }

        speaker.stopStreamPCM()

        return !shouldCancel
    }
    
    func cancel() {
        shouldCancel = true
    }
}

class PorcupineStep {
    let recorder: Recorder
    let porcupine: Porcupine
    var shouldCancel: Bool = false
    
    init(recorder: Recorder, porcupine: Porcupine) {
        self.recorder = recorder
        self.porcupine = porcupine
    }
    
    func run() async throws -> Bool {
        try recorder.start()
        
        var isDetected = false
        shouldCancel = false
        while !isDetected && !shouldCancel {
            let frame = try await recorder.read(frameLength: Porcupine.frameLength)
            isDetected = try porcupine.process(pcm: frame) == 0
        }
        
        try recorder.stop()
        
        return !shouldCancel
    }
    
    func cancel() {
        shouldCancel = true
    }
}

class RhinoStep {
    let recorder: Recorder
    let rhino: Rhino
    var shouldCancel: Bool = false
    
    init(recorder: Recorder, rhino: Rhino) {
        self.recorder = recorder
        self.rhino = rhino
    }
    
    func run() async throws -> Inference? {
        try recorder.start()
        try rhino.reset()
        
        var isFinalized = false
        shouldCancel = false
        while !isFinalized && !shouldCancel {
            let frame = try await recorder.read(frameLength: Rhino.frameLength)
            isFinalized = try rhino.process(pcm: frame)
        }
        
        try recorder.stop()
        
        if shouldCancel {
            return nil
        } else {
            return try rhino.getInference()
        }
    }
    
    func cancel() {
        shouldCancel = true
    }
}

struct Transition {
    let nextState: RecipeStates?
    let nextStateArgs: [String: Any]
}

protocol State {
    func run(args: [String: Any]) async throws -> Transition
    func cancel()
}

class StandbyState: State {
    let viewModel: ViewModel
    let porcupineStep: PorcupineStep
    let nextState: RecipeStates
    
    init(
        viewModel: ViewModel,
        porcupineStep: PorcupineStep,
        nextState: RecipeStates
    ) {
        self.viewModel = viewModel
        self.porcupineStep = porcupineStep
        self.nextState = nextState
    }
    
    func run(args: [String: Any]) async throws -> Transition {
        await viewModel.setStatusText(text: "Listening for wake word...")
        await viewModel.setListenState(state: .listening)
        if try await porcupineStep.run() {
            await viewModel.setListenState(state: .idle)
            return Transition(nextState: self.nextState, nextStateArgs: [:])
        } else {
            await viewModel.setListenState(state: .idle)
            return Transition(nextState: nil, nextStateArgs: [:])
        }
    }
    
    func cancel() {
        porcupineStep.cancel()
    }
}

class PromptState: State {
    let viewModel: ViewModel
    let orcaStep: OrcaStep
    let defaultPrompt: String
    let nextState: RecipeStates?
    let cardType: CardType?
    
    init(
        viewModel: ViewModel,
        orcaStep: OrcaStep,
        defaultPrompt: String,
        nextState: RecipeStates?,
        cardType: CardType?
    ) {
        self.viewModel = viewModel
        self.orcaStep = orcaStep
        self.defaultPrompt = defaultPrompt
        self.nextState = nextState
        self.cardType = cardType
    }
    
    func run(args: [String: Any]) async throws -> Transition {
        let prompt: String = if args["prompt"] != nil {
            if args["prompt"]! is String {
                args["prompt"] as! String
            } else {
                defaultPrompt
            }
        } else {
            defaultPrompt
        }
        
        await viewModel.setActiveCard(card: self.cardType)
        await viewModel.setStatusText(text: prompt)
        await viewModel.setListenState(state: .idle)
        if try await orcaStep.run(prompt: prompt) {
            return Transition(nextState: self.nextState, nextStateArgs: [:])
        } else {
            return Transition(nextState: nil, nextStateArgs: [:])
        }
    }
    
    func cancel() {
        orcaStep.cancel()
    }
}

class ReportState: State {
    let viewModel: ViewModel
    let rhinoStep: RhinoStep
    let listeningPrompt: String
    let expectedIntent: String
    let cardType: CardType
    let successLogGen: (Inference) -> String
    let successNextState: RecipeStates
    let failurePromptGen: (Inference) -> String
    let failureNextState: RecipeStates
    
    init(
        viewModel: ViewModel,
        rhinoStep: RhinoStep,
        listeningPrompt: String,
        expectedIntent: String,
        cardType: CardType,
        successLogGen: @escaping (Inference) -> String,
        successNextState: RecipeStates,
        failurePromptGen: @escaping (Inference) -> String,
        failureNextState: RecipeStates
    ) {
        self.viewModel = viewModel
        self.rhinoStep = rhinoStep
        self.listeningPrompt = listeningPrompt
        self.expectedIntent = expectedIntent
        self.cardType = cardType
        self.successLogGen = successLogGen
        self.successNextState = successNextState
        self.failurePromptGen = failurePromptGen
        self.failureNextState = failureNextState
    }
    
    func run(args: [String: Any]) async throws -> Transition {
        await viewModel.setStatusText(text: listeningPrompt)
        await viewModel.setListenState(state: .listening)
        let inference = try await rhinoStep.run()
        await viewModel.setListenState(state: .listening)
        if inference == nil {
            return Transition(nextState: nil, nextStateArgs: [:])
        } else if inference!.isUnderstood && inference!.intent == expectedIntent {
            await viewModel.setCardValue(card: cardType, value: successLogGen(inference!))
            await viewModel.setActiveCard(card: nil)
            
            return Transition(nextState: self.successNextState, nextStateArgs: [:])
        } else {
            return Transition(nextState: self.failureNextState, nextStateArgs: [
                "prompt": failurePromptGen(inference!)
            ])
        }
    }
    
    func cancel() {
        rhinoStep.cancel()
    }
}

class DictationState: State {
    let viewModel: ViewModel
    let cheetahStep: CheetahStep
    let listeningPrompt: String
    let nextState: RecipeStates
    let cardType: CardType
    
    init(
        viewModel: ViewModel,
        cheetahStep: CheetahStep,
        listeningPrompt: String,
        nextState: RecipeStates,
        cardType: CardType
    ) {
        self.viewModel = viewModel
        self.cheetahStep = cheetahStep
        self.listeningPrompt = listeningPrompt
        self.nextState = nextState
        self.cardType = cardType
    }
    
    func run(args: [String : Any]) async throws -> Transition {
        await viewModel.setStatusText(text: listeningPrompt)
        await viewModel.setListenState(state: .listening)
        let transcript = try await cheetahStep.run()
        if transcript != nil {
            await viewModel.setCardValue(card: cardType, value: transcript!)
            await viewModel.setActiveCard(card: nil)
            await viewModel.setListenState(state: .listening)
            return Transition(nextState: self.nextState, nextStateArgs: [:])
        } else {
            return Transition(nextState: nil, nextStateArgs: [:])
        }
    }
    
    func cancel() {
        cheetahStep.cancel()
    }
}
