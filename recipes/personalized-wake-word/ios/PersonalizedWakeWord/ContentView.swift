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

    let brandPrimary = Color(red: 55/255, green: 125/255, blue: 255/255)
    let grayLight = Color(red: 224/255, green: 224/255, blue: 224/255)
    let textDark = Color(red: 51/255, green: 51/255, blue: 51/255)

    var body: some View {
        ZStack {
            Color.white.ignoresSafeArea()

            VStack(spacing: 24) {

                if viewModel.appState == .idle || viewModel.appState == .error {
                    Text("Personalized Wake Word")
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
                    if viewModel.showTestResult {
                        VStack(spacing: 10) {
                            Image(systemName: viewModel.isTestVerified ? "checkmark.circle.fill" : "xmark.circle.fill")
                                .resizable()
                                .frame(width: 120, height: 120)
                                .foregroundColor(viewModel.isTestVerified ? .green : .red)
                                .transition(.scale)

                            Text(String(format: "Wake word detected\nUser score: %.2f", viewModel.testScore))
                                .font(.system(size: 20, weight: .bold))
                                .multilineTextAlignment(.center)
                                .foregroundColor(viewModel.isTestVerified ? .green : .red)
                        }
                    } else {
                        Text(viewModel.statusText)
                            .font(.system(size: 18))
                            .multilineTextAlignment(.center)
                            .foregroundColor(textDark)
                    }

                    if viewModel.appState == .enrolling && !viewModel.showTestResult {
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

                    if (viewModel.appState == .enrolling || viewModel.appState == .testing)
                            && !viewModel.showTestResult {
                        VolumeMeterView(volume: viewModel.volumeLevel)
                            .frame(height: 64)
                            .padding(.top, 24)
                    }

                    if viewModel.appState == .idle && !viewModel.showTestResult {
                        HStack(spacing: 16) {
                            Button(action: viewModel.startEnrollment()) {
                                Text(viewModel.hasEnrolled ? "Re-Enroll" : "Start Enrollment")
                                    .padding(.horizontal, 16)
                                    .padding(.vertical, 10)
                                    .background(viewModel.hasEnrolled ? grayLight : brandPrimary)
                                    .foregroundColor(viewModel.hasEnrolled ? textDark : .white)
                                    .cornerRadius(4)
                            }

                            if viewModel.hasEnrolled {
                                Button(action: viewModel.startTesting()) {
                                    Text("Start Testing")
                                        .padding(.horizontal, 16)
                                        .padding(.vertical, 10)
                                        .background(brandPrimary)
                                        .foregroundColor(.white)
                                        .cornerRadius(4)
                                }
                            }
                        }
                        .padding(.top, 48)
                    }

                    if viewModel.appState != .idle && viewModel.appState != .error && !viewModel.showTestResult {
                        Button(action: viewModel.cancel()) {
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
            .padding(24)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .preferredColorScheme(.light)
    }
}

#Preview {
    ContentView()
}
