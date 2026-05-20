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

struct CardView: View {
    let title: String
    let content: String?
    let isActive: Bool
    
    var body: some View {
        VStack {
            HStack {
                Text(title)
                    .padding(.bottom, 8)
                    .foregroundStyle(isActive ? .blue : .gray)
                Spacer()
            }
            HStack {
                Text(content != nil ? content! : (isActive ? "..." : "-"))
                    .foregroundStyle(isActive ? .blue : .gray)
                Spacer()
            }
        }.padding(14)
            .background(Color.offWhite)
            .cornerRadius(10)
            .overlay(
                RoundedRectangle(cornerRadius: 10)
                    .stroke(
                        isActive ? .blue : .lightGray,
                        lineWidth: 1)
            )
    }
}

struct MainView: View {
    @ObservedObject var viewModel: ViewModel
    
    var body: some View {
        VStack {
            ScrollView {
                ForEach(CardType.allCases, id: \.self) {card in
                    CardView(
                        title: viewModel.cardTitles[card]!,
                        content: viewModel.cardValues[card],
                        isActive: viewModel.activeCard == card)
                }
            }

            Spacer()
            
            Text(viewModel.statusText)
            
            if viewModel.listenState == .listening {
                VolumeMeterView(viewModel: viewModel)
            } else {
                HStack {
                    ProgressView()
                        .controlSize(.extraLarge)
                }.frame(width: 50, height: 70)
                    .padding(20)
            }
            
            Button(
                action: {
                    viewModel.stopDemo()
                },
                label: {
                    Text("Cancel Report")
                        .padding(.vertical, 8)
                        .padding(8)
                        .foregroundStyle(.black)
                        .background(.gray)
                        .clipShape(
                            RoundedRectangle(
                                cornerRadius: 8))
                }
            )
        }
    }
}
