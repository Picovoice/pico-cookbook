//
//  Copyright 2026 Picovoice Inc.
//  You may not use this file except in compliance with the license. A copy of the license is located in the "LICENSE"
//  file accompanying this source.
//  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
//  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
//  specific language governing permissions and limitations under the License.
//

import SwiftUI

struct VolumeMeterView: View {
    var volume: Float

    var body: some View {
        HStack(spacing: 12) {
            Capsule()
                .fill(Color.blue)
                .frame(width: 8, height: 8)
                .scaleEffect(y: CGFloat(1.0 + (volume * 4.0)))
                .animation(.linear(duration: 0.05), value: volume)

            Capsule()
                .fill(Color.blue)
                .frame(width: 8, height: 8)
                .scaleEffect(y: CGFloat(1.0 + (volume * 9.0)))
                .animation(.linear(duration: 0.05), value: volume)

            Capsule()
                .fill(Color.blue)
                .frame(width: 8, height: 8)
                .scaleEffect(y: CGFloat(1.0 + (volume * 6.0)))
                .animation(.linear(duration: 0.05), value: volume)
        }
        .frame(height: 64)
    }
}
