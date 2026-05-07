//
//  Copyright 2026 Picovoice Inc.
//  You may not use this file except in compliance with the license. A copy of the license is located in the "LICENSE"
//  file accompanying this source.
//  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
//  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
//  specific language governing permissions and limitations under the License.
//

import SwiftUI

struct MainView: View {
    @ObservedObject var viewModel: ViewModel

    var body: some View {
        VStack(spacing: 20) {
            
            // Text Displays
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    
                    Text("Memo:")
                        .font(.headline)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    
                    Text(viewModel.memoText)
                        .padding()
                        .frame(maxWidth: .infinity, minHeight: 150, alignment: .topLeading)
                        .background(Color(red: 0.93, green: 0.93, blue: 0.93))
                        .cornerRadius(10)
                        .overlay(
                            RoundedRectangle(cornerRadius: 10)
                                .stroke(Color.gray.opacity(0.5), lineWidth: 1)
                        )
                    
                    if !viewModel.modifiedTitle.isEmpty {
                        Text(viewModel.modifiedTitle)
                            .font(.headline)
                            .padding(.top, 10)
                        
                        Text(viewModel.modifiedText)
                            .padding()
                            .frame(maxWidth: .infinity, minHeight: 150, alignment: .topLeading)
                            .foregroundColor(.blue)
                            .background(Color(red: 0.93, green: 0.93, blue: 0.93))
                            .cornerRadius(10)
                            .overlay(
                                RoundedRectangle(cornerRadius: 10)
                                    .stroke(Color.gray.opacity(0.5), lineWidth: 1)
                            )
                    }

                    if !viewModel.tooltipText.isEmpty {
                        Text(viewModel.tooltipText)
                            .font(.subheadline)
                            .foregroundColor(.gray)
                            .italic()
                            .padding(.top, 10)
                    }
                }
            }
            .padding()
            
            // Status area
            VStack {
                Text(viewModel.statusText)
                    .font(.title3)
                    .padding(.bottom, 10)
                
                if viewModel.uiState == .wakeWord ||
                    viewModel.uiState == .voiceCommand ||
                    viewModel.uiState == .startRecording {
                    VolumeMeterView(volume: viewModel.volumeLevel)
                } else if viewModel.uiState == .loadingModel ||
                            viewModel.uiState == .readRecording ||
                            viewModel.uiState == .summarizeRecording ||
                            viewModel.uiState == .rewriteRecording {
                    ProgressView()
                        .progressViewStyle(CircularProgressViewStyle(tint: .blue))
                        .scaleEffect(1.5)
                }
            }
            .frame(height: 150)
            .frame(maxWidth: .infinity)
        }
        .frame(maxHeight: .infinity, alignment: .top)
    }
}
