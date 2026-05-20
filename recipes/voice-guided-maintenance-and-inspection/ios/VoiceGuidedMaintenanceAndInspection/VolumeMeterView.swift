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
    @ObservedObject var viewModel: ViewModel
    var barScale: [Float] = [4.0, 9.0, 6.0]

    var body: some View {
        HStack(spacing: 10) {
            ForEach(0..<3) { index in
                let soundLevel = viewModel.soundLevel * barScale[index]
                RoundedRectangle(cornerRadius: 5, style: .continuous)
                    .fill(.blue)
                    .frame(
                        width: 10,
                        height: 10 + 10 * CGFloat(soundLevel))
            }
        }
        .frame(width: 50, height: 70)
        .padding(20)
        .animation(.easeInOut(duration: 0.05), value: viewModel.soundLevel)
    }
}
