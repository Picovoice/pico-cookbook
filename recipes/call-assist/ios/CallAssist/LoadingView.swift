//
//  Copyright 2026 Picovoice Inc.
//  You may not use this file except in compliance with the license. A copy of the license is located in the "LICENSE"
//  file accompanying this source.
//  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
//  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
//  specific language governing permissions and limitations under the License.
//

import SwiftUI

struct LoadingView: View {
    @ObservedObject var viewModel: ViewModel
    
    var body: some View {
        VStack {
            Text("Call Assist")
                .foregroundStyle(.blue)
                .font(.largeTitle)
                .padding(16)
                .bold()
            Text(viewModel.statusText)
                .foregroundStyle(.gray)
                .padding(16)
            Button(action: {
                withAnimation(.easeInOut) {
                    viewModel.startDemo()
                }
            }) {
                Text("Start Demo")
                    .padding(.vertical, 8)
                    .padding(8)
                    .foregroundStyle(.white)
                    .background(viewModel.enginesLoaded ? .blue : .gray)
                    .clipShape(
                        RoundedRectangle(
                            cornerRadius: 8))
            }.padding(16)
                .disabled(!viewModel.enginesLoaded)
        }
        .padding()
    }
}
