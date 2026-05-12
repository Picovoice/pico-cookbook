//
//  Copyright 2026 Picovoice Inc.
//  You may not use this file except in compliance with the license. A copy of the license is located in the "LICENSE"
//  file accompanying this source.
//  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
//  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
//  specific language governing permissions and limitations under the License.
//

import SwiftUI

extension Color {
    static let lightGray = Color(red: 0.8, green: 0.8, blue: 0.8)
    static let offWhite = Color(red: 0.93, green: 0.93, blue: 0.93)
}

extension Font {
    static let twenty = Font.system(size: 20)
    static let fourteen = Font.system(size: 14)
}

struct MainView: View {
    @ObservedObject var viewModel: ViewModel

    var body: some View {
        VStack {
            VStack {
                VStack {
                    GeometryReader { geometry in
                        ScrollView {
                            VStack {
                                ForEach(Array(viewModel.textHistory.enumerated()), id: \.offset) { _, item in
                                    Text(viewModel.withDots(item: item))
                                        .frame(maxWidth: .infinity, alignment: .leading)
                                        .foregroundStyle(item.isBlue ? .blue : .gray)
                                        .monospacedDigit()
                                        .font(.twenty)
                                }
                            }.frame(minHeight: geometry.size.height, alignment: .top)
                        }.defaultScrollAnchor(.bottomLeading)
                    }
                }.frame(maxHeight: .infinity)

                Spacer()

                HStack {
                    Button(action: {
                        viewModel.stopDemo()
                    }, label: {
                        Image(systemName: "arrowshape.left.fill")
                            .font(.twenty)

                    }).disabled(viewModel.controlState == .prompt)

                    Spacer()

                    Button(action: {
                        viewModel.skipResponse()
                    }, label: {
                        Image(systemName: "forward.end.fill")
                            .font(.twenty)
                    }).disabled(
                        viewModel.listenState == .question ||
                        viewModel.controlState == .prompt ||
                        viewModel.controlState == .interrupting)
                }
            }.frame(alignment: .top)
                .padding(8)
                .background(Color.offWhite)
                .cornerRadius(10)
                .overlay(
                    RoundedRectangle(cornerRadius: 10)
                        .stroke(Color.lightGray, lineWidth: 1)
                )

            Text(viewModel.tooltipText())
                .foregroundStyle(.gray)
                .padding(.top, 16)

            if viewModel.listenState == .question {
                VolumeMeterView(viewModel: viewModel)
            } else {
                HStack {
                    ProgressView()
                        .controlSize(.extraLarge)
                }.frame(width: 50, height: 70)
                    .padding(20)
            }
        }.padding()
    }
}
