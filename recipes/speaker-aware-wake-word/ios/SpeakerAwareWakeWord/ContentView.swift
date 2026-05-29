//
//  Copyright 2026 Picovoice Inc.
//  You may not use this file except in compliance with the license. A copy of the license is located in the "LICENSE"
//  file accompanying this source.
//  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
//  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
//  specific language governing permissions and limitations under the License.
//

import SwiftUI

struct ContentView: View {
    @StateObject var viewModel = ViewModel()
    @State private var showingAddSpeakerAlert = false
    @State private var newSpeakerName = ""

    let brandPrimary = Color(red: 55/255, green: 125/255, blue: 255/255)
    let grayLight = Color(red: 224/255, green: 224/255, blue: 224/255)
    let textDark = Color(red: 51/255, green: 51/255, blue: 51/255)

    let columns = [
        GridItem(.adaptive(minimum: 100), spacing: 8)
    ]

    var body: some View {
        ZStack {
            Color.white.ignoresSafeArea()

            VStack(spacing: 24) {

                if viewModel.appState == .idle || viewModel.appState == .error {
                    Text("Speaker Aware Wake Word")
                        .font(.system(size: 24, weight: .bold))
                        .foregroundColor(brandPrimary)
                        .padding(.bottom, 8)
                }

                if viewModel.appState == .error {
                    Text(viewModel.errorMessage)
                        .foregroundColor(.white)
                        .padding()
                        .background(Color.red)
                        .cornerRadius(10)
                        .padding(.horizontal)
                        .multilineTextAlignment(.leading)
                } else {
                    if viewModel.showTestResult, let speaker = viewModel.detectedSpeaker {
                        VStack(spacing: 10) {
                            Text("Hello ")
                                .font(.system(size: 40, weight: .bold))
                                .foregroundColor(textDark)

                            Text(" \(speaker.name) ")
                                .font(.system(size: 40, weight: .bold))
                                .foregroundColor(.white)
                                .lineLimit(1)
                                .minimumScaleFactor(0.5)
                                .padding(.horizontal, 16)
                                .padding(.vertical, 8)
                                .background(speaker.color)
                                .clipShape(Capsule())
                        }
                        .padding(.horizontal, 32)
                        .padding(.top, 24)
                        .padding(.bottom, 32)
                        .background(Color.white)
                        .cornerRadius(32)
                        .overlay(
                            RoundedRectangle(cornerRadius: 32)
                                .stroke(grayLight, lineWidth: 1)
                        )
                        .shadow(color: Color.black.opacity(0.15), radius: 6, x: 0, y: 4)
                        .transition(.scale(scale: 0.8).combined(with: .opacity))
                    } else {
                        if !viewModel.statusText.isEmpty {
                            Text(viewModel.statusText)
                                .font(.system(size: 18))
                                .multilineTextAlignment(.center)
                                .foregroundColor(textDark)
                        }

                        if viewModel.appState == .idle && !viewModel.speakers.isEmpty {
                            VStack(spacing: 16) {
                                Text("ENROLLED PROFILES")
                                    .font(.system(size: 14, weight: .bold))
                                    .foregroundColor(Color.gray)

                                LazyVGrid(columns: columns, alignment: .leading, spacing: 16) {
                                    ForEach(viewModel.speakers) { speaker in
                                        Text(speaker.name)
                                            .font(.system(size: 16, weight: .bold))
                                            .foregroundColor(.white)
                                            .lineLimit(1)
                                            .minimumScaleFactor(0.5)
                                            .padding(.horizontal, 16)
                                            .frame(maxWidth: .infinity)
                                            .frame(height: 38)
                                            .background(speaker.color)
                                            .clipShape(Capsule())
                                    }

                                    if viewModel.speakers.count < viewModel.MAX_SPEAKERS {
                                        Button(action: {showingAddSpeakerAlert = true}, label: {
                                            Text("+ Add")
                                                .font(.system(size: 16, weight: .bold))
                                                .foregroundColor(brandPrimary)
                                                .padding(.horizontal, 16)
                                                .frame(height: 38)
                                                .overlay(
                                                    Capsule().stroke(brandPrimary, lineWidth: 2)
                                                )
                                        })
                                    }
                                }
                                .padding(.horizontal, 16)
                            }
                            .padding(.bottom, 8)
                        }

                        if viewModel.appState == .enrolling {
                            ZStack {
                                Circle()
                                    .stroke(grayLight, lineWidth: 12)
                                    .frame(width: 150, height: 150)

                                Circle()
                                    .trim(from: 0.0, to: CGFloat(viewModel.enrollPercentage / 100.0))
                                    .stroke(brandPrimary, style: StrokeStyle(lineWidth: 12, lineCap: .round))
                                    .frame(width: 150, height: 150)
                                    .rotationEffect(Angle(degrees: -90))
                                    .animation(.linear, value: viewModel.enrollPercentage)
                            }
                            .padding(.top, 24)
                        }

                        if viewModel.appState == .enrolling || viewModel.appState == .testing {
                            VolumeMeterView(volume: viewModel.volumeLevel)
                                .frame(height: 64)
                                .padding(.top, 24)
                        }

                        if viewModel.appState == .idle {
                            HStack(spacing: 16) {
                                if viewModel.speakers.isEmpty {
                                    Button(action: {showingAddSpeakerAlert = true}, label: {
                                            Text("Start Enrollment")
                                                .padding(.horizontal, 16)
                                                .padding(.vertical, 10)
                                                .background(brandPrimary)
                                                .foregroundColor(.white)
                                                .cornerRadius(4)
                                    })
                                } else {
                                    Button(action: viewModel.clearAll) {
                                        Text("Clear Profiles")
                                            .padding(.horizontal, 16)
                                            .padding(.vertical, 10)
                                            .background(grayLight)
                                            .foregroundColor(textDark)
                                            .cornerRadius(4)
                                    }

                                    Button(action: viewModel.startTesting) {
                                        Text("Start Testing")
                                            .padding(.horizontal, 16)
                                            .padding(.vertical, 10)
                                            .background(brandPrimary)
                                            .foregroundColor(.white)
                                            .cornerRadius(4)
                                    }
                                }
                            }
                            .padding(.top, 32)
                        }

                        if viewModel.appState != .idle && viewModel.appState != .error {
                            Button(action: viewModel.cancel) {
                                Text(viewModel.appState == .testing ? "Stop Testing" : "Cancel")
                                    .padding(.horizontal, 16)
                                    .padding(.vertical, 10)
                                    .background(grayLight)
                                    .foregroundColor(textDark)
                                    .cornerRadius(4)
                            }
                            .padding(.top, 16)
                        }
                    }
                }
            }
            .padding(24)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .preferredColorScheme(.light)
        .alert("Speaker Name", isPresented: $showingAddSpeakerAlert) {
            TextField("Speaker \(viewModel.speakers.count + 1)", text: $newSpeakerName)
                .autocapitalization(.words)
                .autocorrectionDisabled()
            Button("Enroll") {
                viewModel.addSpeaker(name: newSpeakerName)
                newSpeakerName = ""
            }
            Button("Cancel", role: .cancel) {
                newSpeakerName = ""
            }
        }
    }
}

#Preview {
    ContentView()
}
