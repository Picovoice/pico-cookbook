//
//  Copyright 2026 Picovoice Inc.
//  You may not use this file except in compliance with the license. A copy of the license is located in the "LICENSE"
//  file accompanying this source.
//  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
//  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
//  specific language governing permissions and limitations under the License.
//

import SwiftUI

struct ContentView: View {
    @StateObject var viewModel: ViewModel = ViewModel()

    var body: some View {
        ZStack {
            if viewModel.errorText != nil {
                Text(viewModel.errorText!)
                    .font(.title)
                    .foregroundStyle(.red)
            } else if viewModel.viewState == .loading {
                LoadingView(viewModel: viewModel)
            } else if viewModel.viewState == .main {
                MainView(viewModel: viewModel)
            }
        }.padding()
            .animation(.easeInOut, value: viewModel.viewState)
    }
}
