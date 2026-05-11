//
//  Copyright 2026 Picovoice Inc.
//  You may not use this file except in compliance with the license. A copy of the license is located in the "LICENSE"
//  file accompanying this source.
//  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
//  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
//  specific language governing permissions and limitations under the License.
//

import Cheetah
import Orca
import PicoLLM
import ios_voice_processor

import CryptoKit
import Foundation

enum ViewState {
  case loading, main
}

enum ListenState {
    case idle, question
}

enum ControlState {
    case idle, loading, prompt, completion, interrupting
}

struct Chunk: Codable {
    let start: Int
    let end: Int
    var embedding: [Float] = []
}

struct TextElement {
    var content: String = ""
    var withDots: Bool = true
    var isBlue: Bool = false
}

let DOTS = [
    " .  ",
    " .. ",
    " ...",
    "  ..",
    "   .",
    "    "
]

class ViewModel: ObservableObject {
    @Published var viewState: ViewState = .loading
    @Published var listenState: ListenState = .idle
    @Published var controlState: ControlState = .idle
    @Published var statusText: String = ""
    @Published var enginesLoaded: Bool = false
    @Published var textHistory: [TextElement] = [TextElement]()
    @Published var useEmbeddingCache: Bool = true
    @Published var soundLevel: Float = 0.0

    @Published var dotIndex = 0
    private var timer: Timer?

    var documentURL: URL?
    var documentContent: String?
    var documentChunks: [Chunk] = [Chunk]()

    private let ACCESS_KEY = "${YOUR_ACCESS_KEY_HERE}"

    private let COMPLETION_TOKEN_LIMIT: Int32 = 128
    private let CHUNK_SIZE = 300
    private let CHUNK_OVERLAP = 200
    private let TOPK = 4
    private let STOP_PHRASES = [
        "</s>",             // Llama-2, Mistral, and Mixtral
        "<end_of_turn>",    // Gemma
        "<|endoftext|>",    // Phi-2
        "<|eot_id|>",       // Llama-3
        "<|end|>", "<|user|>", "<|assistant|>" // Phi-3
    ]

    private var cheetah: Cheetah?
    private var orca: Orca?
    private var picollm: PicoLLM?
    private var embedder: PicoLLM?

    private var audioStream: AudioPlayerStream?

    private var currentTranscript: String = ""
    private var isGenerating: Bool = false
    private var isSpeaking: Bool = false
    private var isInterrupting: Bool = false

    func setStatusText(text: String) {
        DispatchQueue.main.async { [self] in
            viewState = .loading
            statusText = text
        }
    }

    func setViewState(state: ViewState) {
        DispatchQueue.main.async { [self] in
            viewState = state
        }
    }

    func setListenState(state: ListenState) {
        DispatchQueue.main.async { [self] in
            listenState = state
        }
    }

    func setControlState(state: ControlState) {
        DispatchQueue.main.async { [self] in
            if state != .completion || controlState == .prompt {
                controlState = state
            }
        }
    }

    func sendText(text: String) {
        DispatchQueue.main.async { [self] in
            if textHistory.isEmpty || !textHistory.last!.withDots {
                textHistory.append(TextElement())
            }

            textHistory[textHistory.count - 1].content += text
        }
    }

    func blueText() {
        DispatchQueue.main.async { [self] in
            textHistory[textHistory.count - 1].isBlue = true
        }
    }

    func flushText() {
        DispatchQueue.main.async { [self] in
            textHistory[textHistory.count - 1].content += "\n"
            textHistory[textHistory.count - 1].withDots = false
        }
    }

    func withDots(item: TextElement) -> String {
        if item.content.isEmpty {
            return ""
        } else if item.withDots {
            return item.content + DOTS[dotIndex]
        } else {
            return item.content
        }
    }

    init() {
        timer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
            self!.dotIndex = (self!.dotIndex + 1) % DOTS.count
        }

        loadEngines()
    }

    func loadEngines() {
        unloadEngines()

        DispatchQueue.global(qos: .userInitiated).async { [self] in
            do {
                setStatusText(text: "Loading Cheetah...")
                guard let cheetahModelPath = Bundle(for: type(of: self))
                    .path(forResource: "cheetah_params", ofType: "pv") else {
                        throw NSError(domain: "cheetah_params not found", code: 0)
                    }
                cheetah = try Cheetah(accessKey: ACCESS_KEY, modelPath: cheetahModelPath, device: "cpu:1")

                setStatusText(text: "Loading Orca...")
                guard let orcaModelPath = Bundle(for: type(of: self))
                    .path(forResource: "orca_params_en_female", ofType: "pv") else {
                        throw NSError(domain: "orca_params_female not found", code: 0)
                    }
                orca = try Orca(accessKey: ACCESS_KEY, modelPath: orcaModelPath, device: "cpu:1")

                setStatusText(text: "Loading picoLLM (chat)...")
                guard let picollmModelPath = Bundle(for: type(of: self))
                    .path(forResource: "picollm_model", ofType: "pllm") else {
                        throw NSError(domain: "picollm_model not found", code: 0)
                    }
                picollm = try PicoLLM(accessKey: ACCESS_KEY, modelPath: picollmModelPath, device: "cpu:2")

                setStatusText(text: "Loading picoLLM (embedding)...")
                guard let embeddingModelPath = Bundle(for: type(of: self))
                    .path(forResource: "picollm_embedding_model", ofType: "pllm") else {
                        throw NSError(domain: "picollm_embedding_model not found", code: 0)
                    }
                embedder = try PicoLLM(accessKey: ACCESS_KEY, modelPath: embeddingModelPath, device: "cpu:2")

                setStatusText(text: "Loading Audio Player...")
                audioStream = try AudioPlayerStream(sampleRate: Double(self.orca!.sampleRate!))

                setStatusText(text: "Loading Voice Processor...")
                VoiceProcessor.instance.addFrameListener(VoiceProcessorFrameListener(audioCallback))

                startRecording()

                DispatchQueue.main.async { [self] in
                    setStatusText(text: "Load a document to begin.")
                    enginesLoaded = true
                }
            } catch {
                DispatchQueue.main.async { [self] in
                    setStatusText(text: error.localizedDescription)
                    unloadEngines()
                }
            }
        }
    }

    func unloadEngines() {
        enginesLoaded = false

        stopRecording()

        audioStream = nil
        VoiceProcessor.instance.clearFrameListeners()
        VoiceProcessor.instance.clearErrorListeners()

        if cheetah != nil {
            cheetah!.delete()
            cheetah = nil
        }

        if orca != nil {
            orca!.delete()
            orca = nil
        }

        if picollm != nil {
            picollm!.delete()
            picollm = nil
        }

        if embedder != nil {
            embedder!.delete()
            embedder = nil
        }
    }

    func startRecording() {
        DispatchQueue.main.async { [self] in
            do {
                try VoiceProcessor.instance.start(
                    frameLength: Cheetah.frameLength,
                    sampleRate: Cheetah.sampleRate)
            } catch {
                setStatusText(text: error.localizedDescription)
            }
        }
    }

    func stopRecording() {
        do {
            try VoiceProcessor.instance.stop()
        } catch {
            DispatchQueue.main.async { [self] in
                setStatusText(text: error.localizedDescription)
            }
        }
    }

    func loadDocument(url: URL) {
        do {
            documentURL = url
            documentContent = try String(contentsOf: documentURL!, encoding: .utf8)
                .replacing("\r\n", with: "\n")
                .replacing(/\n\n+/, with: "\n\n")
            documentChunks.removeAll()

            let cachePath = getDocumentCachePath()

            do {
                let cacheText = try String(contentsOf: cachePath!, encoding: .utf8)
                documentChunks = try JSONDecoder().decode([Chunk].self, from: Data(cacheText.utf8))
            } catch {}
        } catch {
            setStatusText(text: error.localizedDescription)
        }
    }

    func hasEmbeddings() -> Bool {
        return !documentChunks.isEmpty
    }

    func resetEmbeddings() {
        documentChunks.removeAll()
    }

    func getDocumentCachePath() -> URL? {
        let data = Data(documentURL!.absoluteString.utf8)
        let hashed = SHA256.hash(data: data)
        let hex = hashed.compactMap { String(format: "%02x", $0) }.joined()
        let file = "\(hex)-\(CHUNK_SIZE)-\(CHUNK_OVERLAP).json"
        let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first
        return folder?.appendingPathComponent(file).absoluteURL
    }

    func computeEmbeddings() throws {
        chunkDocument()
        embedDocument()

        let cachePath = getDocumentCachePath()
        let cached = try JSONEncoder().encode(documentChunks)
        try cached.write(to: cachePath!)
    }

    func startDemo() {
        DispatchQueue.global(qos: .userInitiated).async { [self] in
            setControlState(state: .loading)

            DispatchQueue.main.async { [self] in
                textHistory.removeAll()
            }

            if documentContent == nil {
                return
            }

            do {
                if documentChunks.isEmpty {
                    try computeEmbeddings()
                }

                setStatusText(text: "Starting Demo...")

                _ = try cheetah!.flush()
                setViewState(state: .main)
                startListening()
            } catch {
                setStatusText(text: error.localizedDescription)
            }
        }
    }

    func startListening() {
        DispatchQueue.main.async {[self] in
            if viewState == .main {
                sendText(text: "[USER] ")
                setListenState(state: .question)
            }
        }
    }

    func chunkDocument() {
        let content = documentContent!

        var start = 0
        while start < content.count {
            setStatusText(text: "Splitting Document into Chunks (\(start * 100 / content.count)%)...")

            var end = min(start + CHUNK_SIZE, content.count)
            if end < content.count {
                let startIndex = content.index(content.startIndex, offsetBy: start)
                let endIndex = content.index(content.startIndex, offsetBy: end)
                let paragraphBreakRange = content.range(of: "\n\n", options: .backwards, range: startIndex..<endIndex)
                if paragraphBreakRange != nil {
                    let paragraphBreak = content.distance(from: content.startIndex, to: paragraphBreakRange!.lowerBound)
                    if paragraphBreak > start + CHUNK_SIZE / 2 {
                        end = paragraphBreak
                    }
                }
            }

            if end - start > 0 {
                documentChunks.append(Chunk(start: start, end: end))
            }

            if end >= content.count {
                break
            }

            start = max(start + CHUNK_SIZE - CHUNK_OVERLAP, end - CHUNK_OVERLAP)
        }
    }

    func getChunkText(index: Int) -> String {
        let startIndex = documentContent!.index(documentContent!.startIndex, offsetBy: documentChunks[index].start)
        let endIndex = documentContent!.index(documentContent!.startIndex, offsetBy: documentChunks[index].end)
        let chunk = String(documentContent![startIndex..<endIndex])
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return chunk
    }

    func embedDocument() {
        do {
            for index in documentChunks.indices {
                setStatusText(text: "Computing chunk embedding (\(index)/\(documentChunks.count))...")
                let chunk = getChunkText(index: index)
                let embedding = try computeEmbedding(prompt: chunk)
                documentChunks[index].embedding = embedding
            }
        } catch {
            setStatusText(text: error.localizedDescription)
        }
    }

    func stopDemo() {
        skipResponse()
        setStatusText(text: "Load a document to begin.")
        setListenState(state: .idle)
        setViewState(state: .loading)
        setControlState(state: .idle)
    }

    func audioCallback(frame: [Int16]) {
        computeSoundLevel(frame: frame)

        if listenState == .question {
            do {
                let (transcript, endpoint) = try cheetah!.process(frame)
                currentTranscript += transcript
                sendText(text: transcript)

                if endpoint {
                    let flush = try cheetah!.flush()
                    currentTranscript += flush
                    sendText(text: flush)
                    flushText()

                    let question = currentTranscript
                    currentTranscript = ""

                    if !question.isEmpty {
                        setListenState(state: .idle)
                        processQuestion(question: question)
                    }
                }
            } catch {
                setStatusText(text: error.localizedDescription)
            }
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

    private func computeEmbedding(prompt: String) throws -> [Float] {
        let embedding = try embedder!.generateEmbeddings(prompt: prompt)
        return normalizeVector(vector: embedding)
    }

    private func normalizeVector(vector: [Float]) -> [Float] {
        var sum: Float = 0
        for x in vector {
            sum += x * x
        }
        let norm = pow(sum, 0.5)

        var normalizedVector = [Float](repeating: 0.0, count: vector.count)
        for i in vector.indices {
            normalizedVector[i] = vector[i] / norm
        }

        return normalizedVector
    }

    private func dotProduct(a: [Float], b: [Float]) -> Float {
        var sum: Float = 0
        for i in 0..<a.count {
            sum += a[i] * b[i]
        }
        return sum
    }

    func processQuestion(question: String) {
        DispatchQueue.global(qos: .userInitiated).async { [self] in
            if viewState != .main {
                return
            }

            do {
                let retrievedChunks = try retrieveChunks(question: question)
                let prompt = try buildPrompt(question: question, retrievedChunks: retrievedChunks)

                let stream = try orca!.streamOpen()
                audioStream!.resetAudioPlayer()

                sendText(text: "[ASSISTANT] ")
                blueText()

                isGenerating = true
                setControlState(state: .prompt)
                let completion = try picollm!.generate(
                    prompt: prompt,
                    completionTokenLimit: COMPLETION_TOKEN_LIMIT,
                    stopPhrases: STOP_PHRASES,
                    streamCallback: {[self] token in
                        setControlState(state: .completion)
                        if !isInterrupting {
                            var isStopPhrase = false
                            for phrase in STOP_PHRASES where token.contains(phrase) {
                                isStopPhrase = true
                            }

                            if !isStopPhrase {
                                do {
                                    sendText(text: token)
                                    let pcm = try stream.synthesize(text: token)
                                    if pcm != nil {
                                        try audioStream!.playStreamPCM(pcm!)
                                    }
                                } catch {
                                    setStatusText(text: error.localizedDescription)
                                }
                            }
                        }
                    })
                isGenerating = false
                isInterrupting = false
                isSpeaking = true
                setControlState(state: .idle)
                flushText()

                let pcm = try stream.flush()
                stream.close()

                if completion.endpoint == .interrupted {
                    audioStream!.stopStreamPCM()
                    startListening()
                } else {
                    if pcm != nil {
                        try audioStream!.playStreamPCM(pcm!)
                    }

                    audioStream!.flushStreamPCM({[self] in
                        if viewState == .main {
                            isSpeaking = false
                            startListening()
                        }
                    })
                }
            } catch {
                setStatusText(text: error.localizedDescription)
            }
        }
    }

    func retrieveChunks(question: String) throws -> [String] {
        let embedding = try computeEmbedding(prompt: question)

        var scores = [Float](repeating: 0.0, count: documentChunks.count)
        for i in documentChunks.indices {
            scores[i] = dotProduct(a: embedding, b: documentChunks[i].embedding)
        }

        var topks = [Float](repeating: -Float.infinity, count: TOPK)
        var indices = [Int](repeating: 0, count: TOPK)

        for i in scores.indices {
            var element = scores[i]
            var indice = i

            if element > topks[TOPK - 1] {
                for j in 0..<TOPK where element > topks[j] {
                    let prev_topk = topks[j]
                    topks[j] = element
                    element = prev_topk

                    let prev_topk_indice = indices[j]
                    indices[j] = indice
                    indice = prev_topk_indice
                }
            }
        }

        var retrievedChunks = [String]()
        for i in indices.indices {
            retrievedChunks.append(getChunkText(index: indices[i]))
        }

        return retrievedChunks
    }

    func buildPrompt(question: String, retrievedChunks: [String]) throws -> String {
        var context = ""
        for index in retrievedChunks.indices {
            context += "[Excerpt \(index + 1)]\n\(retrievedChunks[index])\n\n"
        }

        let dialog = try picollm!.getDialog(
            system: "You are a document question-answering assistant. " +
                    "Answer only using the provided document excerpts. " +
                    "If the answer is not in the excerpts, say that you do not know from the provided document. " +
                    "Do not give legal advice. " +
                    "Keep the answer concise. " +
                    "Do not use Markdown formatting. " +
                    "Do not use bullet points. " +
                    "Use plain text only."
        )

        try dialog.addHumanRequest(
            content: "Document excerpts:\n\n\(context)Question:\n\(question)"
        )

        return try dialog.prompt()
    }

    func skipResponse() {
        do {
            if isGenerating {
                if !isInterrupting {
                    isInterrupting = true
                    try picollm!.interrupt()
                    setControlState(state: .interrupting)
                }
            } else if isSpeaking {
                audioStream!.stopStreamPCM()
                startListening()
            }
        } catch {
            setStatusText(text: error.localizedDescription)
        }
    }
}
