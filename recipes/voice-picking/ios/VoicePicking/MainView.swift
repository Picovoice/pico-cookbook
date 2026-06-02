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
    static let darkGray = Color(red: 0.5, green: 0.5, blue: 0.5)
    static let lightGray = Color(red: 0.8, green: 0.8, blue: 0.8)
    static let offWhite = Color(red: 0.93, green: 0.93, blue: 0.93)
}

let DOTS = [
    ".  ",
    ".. ",
    "...",
    " ..",
    "  .",
    "   "
]

struct CardView: View {
    let title: String
    let content: String?
    let isActive: Bool
    let startDate = Date()
    let hasAlternate: Bool
    let isAlternateActive: Bool

    var body: some View {
        HStack {
            VStack {
                HStack {
                    Text(title)
                        .foregroundStyle((isActive || content != nil) ? .blue : .darkGray)
                        .bold()
                    Spacer()
                }
                HStack {
                    if content == nil && isActive {
                        TimelineView(.periodic(from: startDate, by: 0.1)) { context in
                            let index = Int64(context.date.timeIntervalSince1970 * 7) % Int64(DOTS.count)
                            Text(DOTS[Int(index)])
                                .foregroundStyle(isActive ? .blue : .darkGray)
                        }
                    } else {
                        Text(content != nil ? content! : "-")
                            .foregroundStyle(isActive ? .blue : .darkGray)
                            .bold()
                    }

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

            if hasAlternate {
                VStack {
                    HStack {
                        Text("or STOP PICKING")
                            .frame(maxWidth: .infinity, alignment: .center)
                            .multilineTextAlignment(.center)
                            .foregroundStyle(isAlternateActive ? .blue : .darkGray)
                            .bold()
                        Spacer()
                    }
                }.padding(6)
            }

        }.padding(8)
    }
}

struct MainView: View {
    @ObservedObject var viewModel: ViewModel

    var body: some View {
        VStack {
            ScrollViewReader { proxy in
                ScrollView {
                    ForEach(Array(viewModel.cardData.values).sorted { (a, b) in a.order < b.order },
                            id: \.order) { cardData in
                        CardView(
                            title: cardData.title,
                            content: cardData.value,
                            isActive: cardData.isActive,
                            hasAlternate: cardData.hasAlternate,
                            isAlternateActive: cardData.isAlternateActive)
                    }.onChange(of: viewModel.cardData, {
                        if viewModel.activeCard != nil {
                            proxy.scrollTo(
                                viewModel.activeCard!,
                                anchor: .bottom)
                        }
                    })
                }
            }

            Spacer()

            Text(viewModel.statusText)
                .foregroundStyle(.gray)
                .padding(8)

            if viewModel.listenState == .listening {
                VolumeMeterView(viewModel: viewModel)
            } else {
                HStack {
                    ProgressView()
                        .controlSize(.extraLarge)
                }.frame(width: 50, height: 70)
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
                        .background(Color.lightGray)
                        .clipShape(
                            RoundedRectangle(
                                cornerRadius: 8))
                }
            )
        }
    }
}
