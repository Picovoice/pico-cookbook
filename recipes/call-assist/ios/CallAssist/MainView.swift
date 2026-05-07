//
//  Copyright 2026 Picovoice Inc.
//  You may not use this file except in compliance with the license. A copy of the license is located in the "LICENSE"
//  file accompanying this source.
//  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
//  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
//  specific language governing permissions and limitations under the License.
//

import SwiftUI

let ACTIONS = [
    "Greet",
    "Connect Call",
    "Decline Call",
    "Ask for Details",
    "Ask to Text",
    "Ask to Email",
    "Ask to Call Back",
    "Block Caller"
]

extension Color {
    static let lightGray = Color(red: 0.8, green: 0.8, blue: 0.8)
    static let offWhite = Color(red: 0.93, green: 0.93, blue: 0.93)
}

extension Font {
    static let twenty = Font.system(size: 20)
    static let fifteen = Font.system(size: 15)
}

struct MainView: View {
    @ObservedObject var viewModel: ViewModel

    var body: some View {
        VStack {
            ScrollView {
                VStack {
                    ForEach(
                        Array(viewModel.callerTextHistory.enumerated()), id: \.offset) { index, item in
                        Text(viewModel.withDots(item: item))
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .foregroundStyle((index % 2 == 1) ? .green : .gray)
                            .monospacedDigit()
                            .font(.twenty)
                    }
                }.frame(minHeight: 300, alignment: .top)
            }
            .frame(maxWidth: .infinity, maxHeight: 300)
            .padding(8)
            .defaultScrollAnchor(.bottom)
            .background(Color.offWhite)
            .cornerRadius(10)
            .overlay(
                RoundedRectangle(cornerRadius: 10)
                    .stroke(Color.lightGray, lineWidth: 1)
            )

            Text(viewModel.withDots(item: viewModel.aiTextHistory))
                .frame(maxWidth: .infinity, alignment: .leading)
                .foregroundStyle(.blue)
                .monospacedDigit()
                .font(.twenty)
                .padding([.top, .bottom], 5)

            if viewModel.listenState == .command {
                Text("Say one of the following commands")
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .foregroundStyle(.gray)
                    .font(.fifteen)

                ForEach(ACTIONS, id: \.self) {item in
                    Text("- \(item)")
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .foregroundStyle(.gray)
                        .font(.fifteen)
                        .padding(.leading, 10)
                        .padding(.top, -10)
                }

                Text(viewModel.withDots(item: viewModel.userTextHistory))
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .foregroundStyle(.blue)
                    .monospacedDigit()
                    .font(.twenty)
                    .padding([.top, .bottom], 5)
            }

            Spacer()

            VolumeMeterView(viewModel: viewModel)
        }.frame(maxHeight: .infinity, alignment: .top)
            .padding()
    }
}
