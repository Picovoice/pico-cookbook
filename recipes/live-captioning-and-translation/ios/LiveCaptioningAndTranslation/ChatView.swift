//
//  Copyright 2026 Picovoice Inc.
//  You may not use this file except in compliance with the license. A copy of the license is located in the "LICENSE"
//  file accompanying this source.
//  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
//  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
//  specific language governing permissions and limitations under the License.
//

import SwiftUI

let DOTS = [
    ".  ",
    ".. ",
    "...",
    " ..",
    "  .",
    "   "
]

struct ChatView: View {
    @ObservedObject var viewModel: ViewModel
    let startDate = Date()

    var body: some View {
        ZStack {
            VStack(alignment: .center) {
                resultsBox
            }
            .padding(.bottom, 32)
            .frame(minWidth: 0, maxWidth: .infinity, minHeight: 0, maxHeight: .infinity).background(Color.white)
        }
    }

    @ViewBuilder
    func TextBox(text: String, color: Color) -> some View {
        Text(text)
            .foregroundColor(color)
            .frame(maxWidth: .infinity, alignment: .topLeading)
    }

    @ViewBuilder
    func AnimatedDots() -> some View {
        TimelineView(.periodic(from: startDate, by: 0.1)) { context in
            let index = Int64(context.date.timeIntervalSince1970 * 10) % Int64(DOTS.count)
            TextBox(text: DOTS[Int(index)], color: Constants.activeBlue)
        }
    }

    var resultsBox: some View {
        VStack {
            ScrollViewReader { proxy in
                ScrollView {
                    VStack(alignment: .leading) {
                        ForEach(0..<viewModel.chatTranslations.count, id: \.self) { i in
                            let transcript = viewModel.chatTranscript(index: i)
                            let translated = viewModel.chatTranslations[i]

                            VStack(spacing: 0) {
                                TextBox(text: transcript, color: .gray)
                                TextBox(text: translated, color: Constants.activeBlue)
                            }
                            .padding(8)
                            .frame(maxWidth: .infinity, alignment: .topLeading)
                            .background(Constants.backgroundGrey)
                            .cornerRadius(3.0)
                            .padding(.bottom, 8)
                        }

                        let chatText = viewModel.chatTranscript(
                            index: viewModel.chatTranslations.count)

                        if !viewModel.finalized {
                            VStack(spacing: 0) {
                                if chatText.isEmpty {
                                    AnimatedDots()
                                } else {
                                    TextBox(text: chatText, color: Constants.activeBlue)
                                }
                            }
                            .padding(8)
                            .frame(maxWidth: .infinity, alignment: .topLeading)
                            .background(Constants.backgroundGrey)
                            .cornerRadius(3.0)
                            .padding(.bottom, 8)
                        }
                    }
                    .padding(12)
                    .id(0)
                }.onChange(of: viewModel.chatText) { () in
                    proxy.scrollTo(0, anchor: .bottom)
                }.onChange(of: viewModel.chatBoundaries) { () in
                    proxy.scrollTo(0, anchor: .bottom)
                }.onChange(of: viewModel.chatTranslations) { () in
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
