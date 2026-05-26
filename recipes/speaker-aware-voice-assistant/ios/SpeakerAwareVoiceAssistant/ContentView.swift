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
    init(hex: UInt, alpha: Double = 1) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xff) / 255,
            green: Double((hex >> 08) & 0xff) / 255,
            blue: Double((hex >> 00) & 0xff) / 255,
            opacity: alpha
        )
    }
}

struct ContentView: View {
    @StateObject var viewModel = ViewModel()

    @State private var showingAlert = false
    @State private var pendingSpeakerName = ""
    @State private var pendingSpeakerAdminRole = false

    let brandPrimary = Color(red: 55/255, green: 125/255, blue: 255/255) // #377dff
    let grayLight = Color(red: 224/255, green: 224/255, blue: 224/255)   // #E0E0E0
    let textDark = Color(red: 51/255, green: 51/255, blue: 51/255)       // #333333

    let speakerColours = [
        Color(hex: 0x377dff), // Blue
        Color(hex: 0x10B981), // Emerald Green
        Color(hex: 0x8B5CF6), // Violet
        Color(hex: 0xEC4899), // Pink
        Color(hex: 0xF59E0B), // Amber
        Color(hex: 0x06B6D4), // Cyan
        Color(hex: 0xEF4444), // Red
        Color(hex: 0x84CC16), // Lime
        Color(hex: 0x6366F1), // Indigo
        Color(hex: 0xF43F5E), // Rose
    ]

    var body: some View {
        VStack(spacing: 24) {

            if viewModel.appState == .idle {
                Text("Speaker Aware Voice Assistant")
                    .font(.system(size: 24, weight: .bold))
                    .foregroundColor(brandPrimary)
                    .padding(.bottom, 8)
            }

            if viewModel.showTestResult {
                VStack(spacing: 10) {
                    Text(String(format: "Hello"))
                        .font(.system(size: 20, weight: .bold))
                        .multilineTextAlignment(.center)
                    if (viewModel.testUserName != nil) {
                        Text(String(format: viewModel.testUserName!))
                            .font(.system(size: 20, weight: .bold))
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 4)
                            .background(speakerColours[viewModel.testUserIndex!])
                            .foregroundColor(.white)
                            .cornerRadius(8)
                    }
                    Text(String(format: "Say your command"))
                        .multilineTextAlignment(.center)
                }
            } else {
                Text(viewModel.statusText)
                    .font(.system(size: 18))
                    .multilineTextAlignment(.center)

            }

            if (viewModel.tooltipText != nil) {
                Text(viewModel.tooltipText!)
                    .font(.system(size: 12))
                    .multilineTextAlignment(.center)
                    .italic()
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

            if (viewModel.appState == .enrolling || viewModel.appState == .testing) && viewModel.testingState != .ORCA {
                VolumeMeterView(viewModel: viewModel)
                    .frame(height: 64)
                    .padding(.top, 24)
            }

            if viewModel.appState == .idle && !viewModel.showTestResult {
                let hasEnrolled = viewModel.speakerProfiles.count > 0
                let gridWidthMax = 2

                if hasEnrolled {
                    Grid {
                        ForEach(Array(stride(from: 0, to: viewModel.speakerProfiles.count, by: gridWidthMax)), id: \.self) { i in
                            GridRow {
                                let gridWidth = min(i + gridWidthMax, viewModel.speakerProfiles.count)
                                ForEach(i..<gridWidth, id: \.self) { j in
                                    Text("\(viewModel.speakerNames[j]) [\(viewModel.speakerRoles[j])]")
                                        .padding(.horizontal, 8)
                                        .padding(.vertical, 4)
                                        .background(speakerColours[j])
                                        .foregroundColor(.white)
                                        .cornerRadius(8)
                                }
                            }
                        }
                    }
                }

                HStack(spacing: 16) {
                    if hasEnrolled {
                        Button(action: {
                            viewModel.clear()
                        }) {
                            Text("Clear")
                                .padding(.horizontal, 16)
                                .padding(.vertical, 10)
                                .background(grayLight)
                                .foregroundColor(textDark)
                                .cornerRadius(4)
                        }
                    }

                    let defaultSpeakerName = "Speaker \(viewModel.speakerNames.count)"

                    Button(action: {
                        pendingSpeakerName = ""
                        pendingSpeakerAdminRole = false
                        showingAlert.toggle()
                    }) {
                        Text(hasEnrolled ? "+ Add" : "Start Enrollment")
                            .padding(.horizontal, 16)
                            .padding(.vertical, 10)
                            .background(hasEnrolled ? grayLight : brandPrimary)
                            .foregroundColor(hasEnrolled ? textDark : .white)
                            .cornerRadius(4)
                    }
                    .sheet(isPresented: $showingAlert) {
                        Text("Enroll New Speaker")
                            .font(.system(size: 20, weight: .bold))
                            .padding(.horizontal, 32)
                            .padding(.vertical, 10)
                        TextField("Enter your name", text: $pendingSpeakerName, prompt: Text(defaultSpeakerName))
                            .padding(.horizontal, 32)
                            .padding(.vertical, 10)
                            .textFieldStyle(.roundedBorder)
                        Toggle("Admin permissions:", isOn: $pendingSpeakerAdminRole)
                            .padding(.horizontal, 32)
                            .padding(.vertical, 10)
                        Button("OK", action: {
                            showingAlert.toggle()
                            viewModel.startEnrollment(
                                pendingSpeakerName: pendingSpeakerName.isEmpty ? defaultSpeakerName : pendingSpeakerName,
                                pendingSpeakerAdminRole: pendingSpeakerAdminRole)
                        })
                    }

                    if hasEnrolled {
                        Spacer()
                        Button(action: {
                            viewModel.startTesting()
                        }) {
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

            if viewModel.appState != .idle && !viewModel.showTestResult {
                Button(action: {
                    viewModel.cancel()
                }) {
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
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

#Preview {
    ContentView()
}
