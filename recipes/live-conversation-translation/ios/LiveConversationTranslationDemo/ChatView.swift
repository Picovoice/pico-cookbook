//
//  Copyright 2026 Picovoice Inc.
//  You may not use this file except in compliance with the license. A copy of the license is located in the "LICENSE"
//  file accompanying this source.
//  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
//  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
//  specific language governing permissions and limitations under the License.
//

import SwiftUI

struct ChatView: View {
    @ObservedObject var viewModel: ViewModel

    var body: some View {
        ZStack {
            VStack(alignment: .center) {
                resultsBox

                Spacer()

                HStack(alignment: .center) {
                    Spacer()
                }
                .padding(.horizontal, 24)
            }
            .padding(.bottom, 32)
            .frame(minWidth: 0, maxWidth: .infinity, minHeight: 0, maxHeight: .infinity).background(Color.white)
        }
    }

    var resultsBox: some View {
        VStack {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading) {
                        ForEach(0..<viewModel.chatText.count, id: \.self) { i in
                            let transcript = viewModel.chatText[i].transcript
                            let translated = viewModel.chatText[i].translated
                            let direction = viewModel.chatText[i].direction
                            let dots = (i == viewModel.chatText.count - 1)

                            let alignment: Alignment = direction == .ltr ? .topLeading : .topTrailing
                            let sourceLang = direction == .ltr ?
                                viewModel.selectedSourceLanguage : viewModel.selectedTargetLanguage
                            let targetLang = direction != .ltr ?
                                viewModel.selectedSourceLanguage : viewModel.selectedTargetLanguage

                            VStack(spacing: 0) {
                                Text("\(sourceLang) -> \(targetLang)")
                                    .frame(maxWidth: .infinity, alignment: alignment)
                                    .padding(.horizontal, 16)
                                    .foregroundColor(.gray)
                                    .font(.system(size: 12))
                                VStack(spacing: 0) {
                                    Text(viewModel.withDots(transcript, dots: dots && translated == nil))
                                        .foregroundColor(translated == nil ? Constants.activeBlue : .gray)
                                        .frame(maxWidth: .infinity, alignment: alignment)
                                        .padding(.horizontal, 8)
                                        .padding(.vertical, 8)
                                    if translated != nil {
                                        Text(viewModel.withDots(translated!, dots: dots))
                                            .foregroundColor(Constants.activeBlue)
                                            .frame(maxWidth: .infinity, alignment: alignment)
                                            .padding(.horizontal, 8)
                                            .padding(.bottom, 8)
                                    }
                                }
                                .background(Constants.backgroundGrey)
                                .cornerRadius(6)
                                .overlay(
                                    RoundedRectangle(cornerRadius: 6)
                                        .stroke(Constants.secondaryGrey, lineWidth: 1)
                                )
                                .padding(.bottom, 8)
                            }
                        }
                    }
                    .padding(12)
                    .id(0)
                }.onChange(of: viewModel.chatText) { () in
                    proxy.scrollTo(0, anchor: .bottom)
                }
            }
            .frame(
                maxWidth: .infinity,
                maxHeight: .infinity,
                alignment: .topLeading
            )
            .font(.body)
        }
        .frame(
            maxWidth: .infinity,
            maxHeight: .infinity,
            alignment: .topLeading
        )
    }
}

#Preview {
    ChatView(viewModel: ViewModel())
}
