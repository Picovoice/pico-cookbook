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
    @State private var loadingFile: Bool = false

    var body: some View {
        VStack(alignment: .center) {
            Text("Live Captioning And Translation")

            Spacer()

            if viewModel.chatState == ChatState.LISTENING {
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

            if viewModel.chatState != ChatState.LISTENING {
                VStack {
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

                    let buttonDisabled = viewModel.selectedSourceLanguage == "invalid" ||
                        viewModel.selectedTargetLanguage == "invalid" ||
                        viewModel.chatState != .SELECTING ||
                        loadingFile

                    HStack {
                        Button(
                            action: {
                                viewModel.startDemo()
                            },
                            label: {
                                Text("Mic Demo")
                                    .padding(.vertical, 8)
                                    .padding(8)
                                    .foregroundStyle(.white)
                                    .background(buttonDisabled ? .gray : .blue)
                                    .clipShape(
                                        RoundedRectangle(
                                            cornerRadius: 8))
                            }
                        ).padding(8)
                            .disabled(buttonDisabled)

                        Button(
                            action: {
                                loadingFile = true
                            },
                            label: {
                                Text("File Demo")
                                    .padding(.vertical, 8)
                                    .padding(8)
                                    .foregroundStyle(.white)
                                    .background(buttonDisabled ? .gray : .blue)
                                    .clipShape(
                                        RoundedRectangle(
                                            cornerRadius: 8))
                            }
                        ).padding(16)
                            .disabled(buttonDisabled)
                            .fileImporter(
                                isPresented: $loadingFile,
                                allowedContentTypes: [.item]
                            ) { result in
                                switch result {
                                case .success(let url):
                                    guard url.startAccessingSecurityScopedResource() else { return }
                                    viewModel.startDemo(url: url)
                                    url.stopAccessingSecurityScopedResource()
                                case .failure(let error):
                                    viewModel.errorMessage = error.localizedDescription
                                }
                            }
                    }
                }
            } else {
                ZStack {
                    HStack {
                        Button(action: {
                            viewModel.stopDemo()
                        }, label: {
                            Image(systemName: "arrowshape.left.fill")
                                .font(.title3)
                        }).padding(20)
                            .padding(.leading, 10)

                        Spacer()
                    }

                    if viewModel.selectAudioFile == nil {
                        VolumeMeterView(viewModel: viewModel)
                    } else {
                        HStack {
                            if viewModel.speaking {
                                ProgressView()
                                    .controlSize(.extraLarge)
                            }
                        }.frame(width: 50, height: 70)
                            .padding(20)
                    }
                }.frame(maxWidth: .infinity)
            }
        }.frame(minWidth: 0, maxWidth: .infinity, minHeight: 0, maxHeight: .infinity).background(Color.white)
    }
}
