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

struct MainView: View {
    @ObservedObject var viewModel: ViewModel

    var body: some View {
        VStack {
            Text("Main Page")
                .foregroundStyle(.blue)
                .font(.largeTitle)
                .padding(16)
                .bold()
            ForEach(
                Array(viewModel.callerTextHistory.enumerated()), id: \.offset)
            { index, item in
                Text(item.content)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .foregroundStyle((index % 2 == 1) ? .green : .gray)
                    .monospacedDigit()
            }
            
            if viewModel.textMode == .caller {
                Text(viewModel.renderedText)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .foregroundStyle((viewModel.callerTextHistory.count % 2 == 1) ? .green : .gray)
                    .monospacedDigit()
            }
            
            if viewModel.textMode == .ai {
                Text(viewModel.renderedText)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .foregroundStyle(.blue)
                    .monospacedDigit()
            }
            
            if viewModel.textMode == .user {
                Text(viewModel.aiTextHistory)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .foregroundStyle(.blue)
                    .monospacedDigit()
            }
            
            if viewModel.textMode == .user {
                Text("Say one of the following commands")
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .foregroundStyle(.gray)
                    .font(.footnote)
                
                ForEach(ACTIONS, id: \.self) {item in
                    Text("- \(item)")
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .foregroundStyle(.gray)
                        .font(.footnote)
                        .padding(.leading, 10)
                        .padding(.top, -12)
                }
                
                Text(viewModel.renderedText)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .foregroundStyle(.blue)
                    .monospacedDigit()
            }
        }
        .padding()
    }
}
