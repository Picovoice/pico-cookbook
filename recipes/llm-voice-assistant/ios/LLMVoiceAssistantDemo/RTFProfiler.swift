import Foundation

class RTFProfiler {
    private let sampleRate: Int32
    private var computeSec: Double
    private var audioSec: Double
    private var tickSec: Double

    init(sampleRate: Int32) {
        self.sampleRate = sampleRate
        self.computeSec = 0.0
        self.audioSec = 0.0
        self.tickSec = 0.0
    }

    func tick() {
        self.tickSec = Double(DispatchTime.now().uptimeNanoseconds) / 1e9
    }

    func tock(pcm: [Int16]?) {
        let nowSec = Double(DispatchTime.now().uptimeNanoseconds) / 1e9
        self.computeSec += nowSec - self.tickSec

        if let pcm = pcm, !pcm.isEmpty {
            self.audioSec += Double(pcm.count) / Double(self.sampleRate)
        }
    }

    func rtf() -> Double {
        let rtf = audioSec > 0.0 ? computeSec / audioSec : 0.0
        self.computeSec = 0.0
        self.audioSec = 0.0
        return rtf
    }
}
