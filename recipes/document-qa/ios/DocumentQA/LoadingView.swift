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
    @State private var loadingFile: Bool = false
    @State private var loadingEmbeddings: Bool = false
    
    var body: some View {
        VStack {
            Text("Document QA")
                .foregroundStyle(.blue)
                .font(.largeTitle)
                .padding(16)
                .bold()
            Text(viewModel.statusText)
                .foregroundStyle(.gray)
                .padding(16)
            Button(
                action: {
                    loadingFile = true
                },
                label: {
                    Text("Load Document")
                        .padding(.vertical, 8)
                        .padding(8)
                        .foregroundStyle(.white)
                       .background(viewModel.enginesLoaded ? .blue : .gray)
                        .clipShape(
                            RoundedRectangle(
                                cornerRadius: 8))
                }
            ).padding(16)
                .disabled(!viewModel.enginesLoaded)
                .fileImporter(
                    isPresented: $loadingFile,
                    allowedContentTypes: [.item]
                ) { result in
                    switch result {
                    case .success(let url) :
                        viewModel.loadDocument(url: url)
                        if viewModel.hasEmbeddings() {
                            loadingEmbeddings = true
                        } else {
                            viewModel.startDemo()
                        }
                    case .failure(let error):
                        viewModel.setStatusText(text: error.localizedDescription)
                    }
                }.alert(
                    "Use Cached Embeddings",
                    isPresented: $loadingEmbeddings) {
                        Button("Yes", action: {
                            viewModel.startDemo()
                        })
                        Button("No", action: {
                            viewModel.resetEmbeddings()
                            viewModel.startDemo()
                        })
                    } message: {
                        Text("Use Cached Embeddings")
                    }
        }.padding()
    }
}
