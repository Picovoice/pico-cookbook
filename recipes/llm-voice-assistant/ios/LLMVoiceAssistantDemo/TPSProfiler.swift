import Foundation

class TPSProfiler {
    private var numTokens: Int
    private var startSec: UInt64
    private var endSec: UInt64

    init() {
        self.numTokens = 0
        self.startSec = 0
        self.endSec = 0
    }

    func tock() {
        if self.startSec == 0 {
            self.startSec = DispatchTime.now().uptimeNanoseconds
        } else {
            self.endSec = DispatchTime.now().uptimeNanoseconds
            self.numTokens += 1
        }
    }

    func tps() -> Double {
        let elapsedSec = Double(self.endSec - self.startSec) / 1e9
        let tps = elapsedSec > 0 ? Double(self.numTokens) / elapsedSec : 0.0

        self.numTokens = 0
        self.startSec = 0
        self.endSec = 0

        return tps
    }
}
