//
//  Copyright 2026 Picovoice Inc.
//  You may not use this file except in compliance with the license. A copy of the license is located in the "LICENSE"
//  file accompanying this source.
//  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
//  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
//  specific language governing permissions and limitations under the License.
//

import Koala
import Orca
import Porcupine
import Rhino
import ios_voice_processor

import Combine
import Foundation

enum Steps {
    case ORCA,
         PORCUPINE,
         RHINO
}

enum RecipeSteps {
    case STANDBY,
         PROMPT_USER,
         RECORD_USER
}

enum RecipeStates {
    case STANDBY,
         TASK_LOCATION_PROMPT,
         TASK_LOCATION_REPORT,
         TASK_PICK_PROMPT,
         TASK_PICK_REPORT,
         COMPLETE_PROMPT
}

enum ViewState {
    case loading, main
}

enum ListenState {
    case idle, listening
}

struct PickTask {
    let locationName: String
    let checkDigit: String
    let itemName: String
    let quantity: Int
}

let TASKS: [PickTask] = [
    PickTask(locationName: "bin bravo", checkDigit: "four two", itemName: "blue widgets", quantity: 3),
    PickTask(locationName: "bin delta", checkDigit: "five seven", itemName: "battery packs", quantity: 5),
    PickTask(locationName: "zone one", checkDigit: "one nine", itemName: "safety gloves", quantity: 1)
]

struct CardData: Hashable {
    let title: String
    let hasAlternate: Bool
    var isActive: Bool
    var value: String
}

class ViewModel: ObservableObject {

    private let ACCESS_KEY = "${YOUR_ACCESS_KEY_HERE}"

    private let KEYWORD_MODEL = "voice_picking_ios.ppn"

    private let CONTEXT_MODEL = "voice_picking_ios.rhn"

    private let TTS_MODEL = "orca_params_en_female.pv"

    private let NS_MODEL = "koala_params.pv"

    @Published var viewState: ViewState = .loading
    @Published var listenState: ListenState = .idle
    @Published var statusText: String = ""
    @Published var errorText: String?
    @Published var enginesLoaded: Bool = false
    @Published var soundLevel: Float = 0.0
    @Published var activeCard: CardData?
    // @Published var cardIDs: [String] = []
    @Published var cardData: [String: CardData] = [:]
    // @published var cardTitles: [String] = []
    // @Published var cardValues: [String] = [:]

    private var koala: Koala?
    private var porcupine: Porcupine?
    private var orca: Orca?
    private var rhino: Rhino?

    private var audioPlayerStream: AudioPlayerStream?
    private var basicRecorder: BasicRecorder?
    private var noiseSuppressionRecorder: AINoiseSuppressionRecorder?

    private var orcaStep: OrcaStep?
    private var porcupineStep: PorcupineStep?
    private var rhinoStep: RhinoStep?
    private var workflow: Workflow?

    init() {
        for (i, task) in TASKS.enumerated() {
            cardData["location-\(i)"] = CardData(
                title: "LOCATION",
                hasAlternate: false,
                isActive: false,
                value: "-")
            cardData["pick-\(i)"] = CardData(
                title: "PICK",
                hasAlternate: true,
                isActive: false,
                value: "-")
        }

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

    func setActiveCard(cardId: String?, isAlternate: Bool) async {
        await MainActor.run {
            // TODO: impl alternate logic too (might not even need to be here)
            if let cardIdStr = cardId {
                activeCard = cardData[cardIdStr]
            } else {
                activeCard = nil
            }
        }
    }

    func setCardValue(cardId: String, value: String) async {
        // TODO: impl this
        await MainActor.run {
            cardData[cardId]!.value = value
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


        audioPlayerStream = try AudioPlayerStream(
            sampleRate: Double(orca!.sampleRate!))
        basicRecorder = BasicRecorder(
            frameLength: Koala.frameLength,
            sampleRate: Koala.sampleRate,
            frameCallback: computeSoundLevel)
        noiseSuppressionRecorder = AINoiseSuppressionRecorder(
            recorder: basicRecorder!,
            koala: koala!)

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
                    // TODO: does this make sense?
                    cardData.removeAll()
                }

                if try await workflow!.run() {
                    try await Task.sleep(for: .seconds(1))
                }

                await setStatusText(text: "Ready to Start")
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
    var shouldCancel: Bool = false

    init(
        viewModel: ViewModel,
        orcaStep: OrcaStep,
        porcupineStep: PorcupineStep,
        rhinoStep: RhinoStep
    ) {
        states = [
            .STANDBY: StandbyState(
                viewModel: viewModel,
                porcupineStep: porcupineStep,
            ),
            .TASK_LOCATION_PROMPT: TaskLocationPromptState(
                viewModel: viewModel,
                orcaStep: orcaStep,
            ),
            .TASK_LOCATION_REPORT: TaskLocationReportState(
                viewModel: viewModel,
                rhinoStep: rhinoStep,
            ),
            .TASK_PICK_PROMPT: TaskPickPromptState(
                viewModel: viewModel,
                orcaStep: orcaStep,
            ),
            .TASK_PICK_REPORT: TaskPickReportState(
                viewModel: viewModel,
                rhinoStep: rhinoStep,
            ),
            .COMPLETE_PROMPT: CompletePromptState(
                viewModel: viewModel,
                orcaStep: orcaStep,
            ),
        ]
    }

    func run() async throws -> Bool {
        var currentState: RecipeStates? = .STANDBY
        var currentArgs: [String: Any] = [:]

        shouldCancel = false
        while currentState != nil {
            let state = states[currentState!]
            let transition = try await state!.run(args: currentArgs)
            currentState = transition.nextState
            currentArgs = transition.nextStateArgs
        }

        return !shouldCancel
    }

    func cancel() {
        shouldCancel = true
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

    init(
        viewModel: ViewModel,
        porcupineStep: PorcupineStep,
    ) {
        self.viewModel = viewModel
        self.porcupineStep = porcupineStep
    }

    func run(args: [String: Any]) async throws -> Transition {
        await viewModel.setStatusText(text: "Listening for wake word...")
        await viewModel.setListenState(state: .listening)

        if try await porcupineStep.run() {
            await viewModel.setListenState(state: .idle)
            return Transition(nextState: .TASK_LOCATION_PROMPT, nextStateArgs: [:])
        } else {
            await viewModel.setListenState(state: .idle)
            return Transition(nextState: nil, nextStateArgs: [:])
        }
    }

    func cancel() {
        porcupineStep.cancel()
    }
}

class TaskLocationPromptState: State {
    let viewModel: ViewModel
    let orcaStep: OrcaStep

    init(
        viewModel: ViewModel,
        orcaStep: OrcaStep,
    ) {
        self.viewModel = viewModel
        self.orcaStep = orcaStep
    }

    func run(args: [String: Any]) async throws -> Transition {
        let tasks = args["tasks"]! as! [PickTask]
        let taskIndex = args["taskIndex"]! as! Int
        let task = tasks[taskIndex]

        await viewModel.setActiveCard(cardId: "location-\(taskIndex)", isAlternate: false)

        let defaultPrompt = "Go to \(task.locationName). Confirm location. Check digits are \(task.checkDigit)."
        var promptList = [ defaultPrompt ]
        if let prompt = args["prompt"] {
            if prompt is String {
                promptList = [ prompt as! String ]
            } else if prompt is [String] {
                promptList = prompt as! [String]
            }
        }

        for prompt in promptList {
            await viewModel.setStatusText(text: prompt)

            if !(try await orcaStep.run(prompt: prompt)) {
                return Transition(nextState: nil, nextStateArgs: [:])
            }
        }

        return Transition(nextState: .TASK_LOCATION_REPORT, nextStateArgs: [
            "tasks": args["tasks"]!,
            "taskIndex": args["taskIndex"]!,
        ])
    }

    func cancel() {
        orcaStep.cancel()
    }
}

class TaskLocationReportState: State {
    let viewModel: ViewModel
    let rhinoStep: RhinoStep

    init(viewModel: ViewModel, rhinoStep: RhinoStep) {
        self.viewModel = viewModel
        self.rhinoStep = rhinoStep
    }

    func run(args: [String: Any]) async throws -> Transition {
        let tasks = args["tasks"]! as! [PickTask]
        let taskIndex = args["taskIndex"]! as! Int
        let task = tasks[taskIndex]
        let cardId = "location-\(taskIndex)"
        
        await viewModel.setCardValue(cardId: cardId, value: "...")
        
        await viewModel.setStatusText(text: "Listening for location confirmation...")
        if let inference: Inference = try await rhinoStep.run() {
            if (
                inference.isUnderstood
                && (inference.intent == "confirmLocation")
                && (inference.slots["checkDigit"]! == task.checkDigit)
            ) {
                await viewModel.setCardValue(cardId: cardId, value: inference.slots["checkDigit"]!)
                await viewModel.setActiveCard(cardId: nil, isAlternate: false)
                
                return Transition(nextState: .TASK_PICK_PROMPT, nextStateArgs: [
                    "tasks": tasks,
                    "taskIndex": taskIndex,
                ])
            } else {
                var failurePrompt: [String] = []
                if (inference.isUnderstood && inference.intent == "confirmLocation") {
                    let spokenDigits = inference.slots["checkDigit"]!
                    failurePrompt.append("Location check digit \(spokenDigits) does not match. Retrying...")
                } else {
                    failurePrompt.append("Failed to capture location confirmation. Retrying...")
                }
                
                failurePrompt.append("Please confirm location for \(task.locationName). Check digits are \(task.checkDigit).")
                
                return Transition(nextState: .TASK_LOCATION_PROMPT, nextStateArgs: [
                    "tasks": tasks,
                    "taskIndex": taskIndex,
                    "prompt": failurePrompt,
                ])
            }
        }
    
        return Transition(nextState: nil, nextStateArgs: [:])
    }
    
    func cancel() {
        rhinoStep.cancel()
    }
}

class TaskPickPromptState: State {
    let viewModel: ViewModel
    let orcaStep: OrcaStep

    init(viewModel: ViewModel, orcaStep: OrcaStep) {
        self.viewModel = viewModel
        self.orcaStep = orcaStep
    }

    func run(args: [String: Any]) async throws -> Transition {
        let tasks = args["tasks"]! as! [PickTask]
        let taskIndex = args["taskIndex"]! as! Int
        let task = tasks[taskIndex]

        await viewModel.setActiveCard(cardId: "pick-\(taskIndex)", isAlternate: false)

        let defaultPrompt = "Pick \(task.quantity) \(task.itemName)."
        var promptList = [ defaultPrompt ]
        if let prompt = args["prompt"] {
            if prompt is String {
                promptList = [ prompt as! String ]
            } else if prompt is [String] {
                promptList = prompt as! [String]
            }
        }

        for prompt in promptList {
            await viewModel.setStatusText(text: prompt)

            if !(try await orcaStep.run(prompt: prompt)) {
                return Transition(nextState: nil, nextStateArgs: [:])
            }
        }

        return Transition(nextState: .TASK_PICK_REPORT, nextStateArgs: [
            "tasks": tasks,
            "taskIndex": taskIndex,
        ])
    }

    func cancel() {
        orcaStep.cancel()
    }
}

class TaskPickReportState: State {
    let viewModel: ViewModel
    let rhinoStep: RhinoStep

    init(viewModel: ViewModel, rhinoStep: RhinoStep) {
        self.viewModel = viewModel
        self.rhinoStep = rhinoStep
    }

    let VALID_INTENTS = [
        "confirmPickedQuantity",
        "reportShortPick",
        "reportDamagedItem",
        "reportLocationEmpty",
        "exitWorkflow",
    ]

    func run(args: [String: Any]) async throws -> Transition {
        let tasks = args["tasks"]! as! [PickTask]
        let taskIndex = args["taskIndex"]! as! Int
        let task = tasks[taskIndex]
        let cardId = "pick-\(taskIndex)"
        
        await viewModel.setCardValue(cardId: cardId, value: "...")
        await viewModel.setStatusText(text: "Listening for pick result")
        
        if let inference: Inference = try await rhinoStep.run() {
            if inference.isUnderstood && VALID_INTENTS.contains(inference.intent) {
                if inference.intent == "exitWorkflow" {
                    await viewModel.setStatusText(text: "Ending picking workflow.")
                    await viewModel.setCardValue(cardId: cardId, value: "...")

                    return Transition(nextState: .COMPLETE_PROMPT, nextStateArgs: [
                        "prompt": "Picking workflow ended."
                    ])
                }

                let nextTaskIndex = taskIndex + 1

                if nextTaskIndex >= tasks.count {
                    if inference.intent == "confirmPickedQuantity" {
                        let quantity = inference.slots["quantity"]!
                        await viewModel.setStatusText(text: "Recorded picked \(quantity)")
                        await viewModel.setCardValue(cardId: cardId, value: "pick \(quantity)")
                    } else if inference.intent == "reportShortPick" {
                        let quantity = inference.slots["quantity"]!
                        await viewModel.setStatusText(text: "Recorded short pick \(quantity)")
                        await viewModel.setCardValue(cardId: cardId, value: "short pick \(quantity)")
                    } else if inference.intent == "reportDamagedItem" {
                        await viewModel.setStatusText(text: "Recorded damaged item.")
                        await viewModel.setCardValue(cardId: cardId, value: "damaged item")
                    } else {
                        await viewModel.setStatusText(text: "Recorded empty location.")
                        await viewModel.setCardValue(cardId: cardId, value: "empty location")
                    }

                    return Transition(nextState: .COMPLETE_PROMPT, nextStateArgs: [:])
                }

                let nextTask = tasks[nextTaskIndex]

                var nextPrompt: String
                if inference.intent == "confirmPickedQuantity" {
                    let quantity = inference.slots["quantity"]!
                    await viewModel.setStatusText(text: "Recorded picked \(quantity)")
                    await viewModel.setCardValue(cardId: cardId, value: "pick \(quantity)")
                    nextPrompt = "Go to \(nextTask.locationName). " +
                                 "Confirm location. " +
                                 "Check digits are \(nextTask.checkDigit)."
                } else if inference.intent == "reportShortPick" {
                    let quantity = inference.slots["quantity"]!
                    await viewModel.setStatusText(text: "Recorded short picked \(quantity)")
                    await viewModel.setCardValue(cardId: cardId, value: "short pick \(quantity)")
                    nextPrompt = "Short pick recorded. " +
                                 "Proceed to \(nextTask.locationName). " +
                                 "Confirm location. " +
                                 "Check digits are \(nextTask.checkDigit)."
                } else if inference.intent == "reportDamagedItem" {
                    await viewModel.setStatusText(text: "Recorded damaged item.")
                    await viewModel.setCardValue(cardId: cardId, value: "damaged item")
                    nextPrompt = "Damaged item recorded. Set it aside. " +
                                 "Then proceed to \(nextTask.locationName). " +
                                 "Confirm location. " +
                                 "Check digits are \(nextTask.checkDigit)."
                } else {
                    await viewModel.setStatusText(text: "Recorded empty location.")
                    await viewModel.setCardValue(cardId: cardId, value: "empty location")
                    nextPrompt = "Empty location recorded. Proceed to \(nextTask.locationName). " +
                                 "Confirm location. Check digits are \(nextTask.checkDigit)."
                }

                return Transition(nextState: .TASK_LOCATION_PROMPT, nextStateArgs: [
                    "tasks": tasks,
                    "taskIndex": nextTaskIndex,
                    "prompt": nextPrompt
                ])
            }

            let failurePrompt = [
                "Failed to capture pick result. Retrying...",
                "Please report the result for picking \(task.quantity) \(task.itemName).",
            ]
            return Transition(nextState: .TASK_PICK_PROMPT, nextStateArgs: [
                "tasks": tasks,
                "taskIndex": taskIndex,
                "prompt": failurePrompt
            ])
        }

        return Transition(nextState: nil, nextStateArgs: [:])
    }
    
    func cancel() {
        rhinoStep.cancel()
    }
}

class CompletePromptState: State {
    let viewModel: ViewModel
    let orcaStep: OrcaStep

    init(
        viewModel: ViewModel,
        orcaStep: OrcaStep,
    ) {
        self.viewModel = viewModel
        self.orcaStep = orcaStep
    }

    func run(args: [String: Any]) async throws -> Transition {
        var prompt = "Picking workflow complete."
        if let argPrompt = args["prompt"] {
            if argPrompt is String {
                prompt = argPrompt as! String
            }
        }

        await viewModel.setStatusText(text: prompt)
        let _ = try await orcaStep.run(prompt: prompt)

        return Transition(nextState: nil, nextStateArgs: [:])
    }

    func cancel() {
        orcaStep.cancel()
    }
}

/*
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
}*/
