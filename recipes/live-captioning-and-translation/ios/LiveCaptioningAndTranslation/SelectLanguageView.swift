//
//  Copyright 2026 Picovoice Inc.
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
            Text("Live Captioning And Translation")

            Spacer()

            if viewModel.chatState != ChatState.SELECTING {
                ChatView(viewModel: viewModel)
            }

            Spacer()

            if !viewModel.errorMessage.isEmpty {
                Text(viewModel.errorMessage)
                    .padding()
                    .foregroundColor(Color.white)
                    .frame(maxWidth: .infinity)
                    .background(Constants.dangerRed)
                    .font(.body)
                    .opacity(viewModel.errorMessage.isEmpty ? 0 : 1)
                    .cornerRadius(10)
            } else {
                Text(viewModel.statusText)
            }

            HStack(alignment: .center, spacing: 0) {
                Spacer()
                Picker("Source Language", selection: $viewModel.selectedSourceLanguage,
                       content: {
                    if $viewModel.selectedSourceLanguage.wrappedValue == "invalid" {
                        Text("Select Language").tag("invalid")
                    }
                    ForEach(Array(LANGUAGE_PAIRS.keys).sorted(), id: \.self) { key in
                        Text(LANGUAGE_DISPLAY[key] ?? "").tag(key)
                    }
                })
                .onChange(of: $viewModel.selectedSourceLanguage.wrappedValue) { () in
                    viewModel.selectedSourceLanguageChange()
                }
                .disabled(viewModel.chatState == .LOADING)
                .containerRelativeFrame(.horizontal, alignment: .center) { length, _ in
                    return length / 3
                }
                Image(systemName: "arrow.right")
                .containerRelativeFrame(.horizontal, alignment: .center) { length, _ in
                    return length / 4
                }
                Picker("Target Language", selection: $viewModel.selectedTargetLanguage,
                       content: {
                    if $viewModel.selectedTargetLanguage.wrappedValue == "invalid" {
                        Text("Select Language").tag("invalid")
                    }
                    if $viewModel.selectedSourceLanguage.wrappedValue != "invalid" {
                        let selectedSourceLanguage = $viewModel.selectedSourceLanguage.wrappedValue
                        ForEach(0..<LANGUAGE_PAIRS[selectedSourceLanguage]!.count, id: \.self) { i in
                            let lang = LANGUAGE_PAIRS[selectedSourceLanguage]![i]
                            Text(LANGUAGE_DISPLAY[lang] ?? "").tag(lang)
                        }
                    }
                })
                .onChange(of: $viewModel.selectedTargetLanguage.wrappedValue) { () in
                    viewModel.selectedTargetLanguageChange()
                }
                .disabled(viewModel.chatState == .LOADING ||
                          $viewModel.selectedSourceLanguage.wrappedValue == "invalid")
                .containerRelativeFrame(.horizontal, alignment: .center) { length, _ in
                    return length / 3
                }
                Spacer()
            }
            .frame(maxWidth: .infinity, minHeight: 64)
        }.frame(minWidth: 0, maxWidth: .infinity, minHeight: 0, maxHeight: .infinity).background(Color.white)
    }
}

#Preview {
    SelectLanguageView(viewModel: ViewModel())
}
