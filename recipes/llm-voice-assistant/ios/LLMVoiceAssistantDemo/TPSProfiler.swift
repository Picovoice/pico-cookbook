//
//  Copyright 2025 Picovoice Inc.
//  You may not use this file except in compliance with the license. A copy of the license is located in the "LICENSE"
//  file accompanying this source.
//  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
//  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
//  specific language governing permissions and limitations under the License.
//

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
