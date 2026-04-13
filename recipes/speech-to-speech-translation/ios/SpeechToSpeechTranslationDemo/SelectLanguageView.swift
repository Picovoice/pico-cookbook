//
//  Copyright 2024 Picovoice Inc.
//  You may not use this file except in compliance with the license. A copy of the license is located in the "LICENSE"
//  file accompanying this source.
//  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
//  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
//  specific language governing permissions and limitations under the License.
//

import SwiftUI

struct SelectLanguageView: View {
    @ObservedObject var viewModel: ViewModel

    @State var showSidebar = false

    var body: some View {
        VStack(alignment: .center) {
            Text("Speech To Speech Translation Demo")

            Spacer()
            
            List {
                Picker("Source Language", selection: $viewModel.selectedSourceLanguage,
                       content: {
                    Text("en").tag("en")
                    Text("fr").tag("fr")
                    Text("de").tag("de")
                    Text("es").tag("es")
                })
                Picker("Target Language", selection: $viewModel.selectedTargetLanguage,
                       content: {
                    Text("en").tag("en")
                    Text("fr").tag("fr")
                    Text("de").tag("de")
                    Text("es").tag("es")
                })
            }
            
            Button(action: viewModel.startDemo) {
                Text("Start")
                    .background(Constants.btnColor(viewModel.enableLoadModelButton))
                    .foregroundColor(.white)
                    .padding(.horizontal, 35.0)
                    .padding(.vertical, 20.0)
            }.background(
                Capsule().fill(Constants.btnColor(viewModel.enableLoadModelButton))
            )
            .padding(12)
            .disabled(!viewModel.enableLoadModelButton)

            Spacer()
        }.frame(minWidth: 0, maxWidth: .infinity, minHeight: 0, maxHeight: .infinity).background(Color.white)
    }
}

#Preview {
    SelectLanguageView(viewModel: ViewModel())
}
