//
//  Copyright 2024 Picovoice Inc.
//  You may not use this file except in compliance with the license. A copy of the license is located in the "LICENSE"
//  file accompanying this source.
//  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
//  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
//  specific language governing permissions and limitations under the License.
//

import Foundation
import AVFoundation

class AudioPlayerStream {
    private let engine = AVAudioEngine()
    private let playerNode = AVAudioPlayerNode()
    private let mixerNode = AVAudioMixerNode()

    private var pcmBuffers = [AVAudioPCMBuffer]()
    public var isPlaying = false

    init(sampleRate: Double) throws {
        let audioSession = AVAudioSession.sharedInstance()
        try audioSession.setCategory(.playback, mode: .default)
        try audioSession.setActive(true)

        let format = AVAudioFormat(
            commonFormat: .pcmFormatFloat32,
            sampleRate: sampleRate,
            channels: AVAudioChannelCount(1),
            interleaved: false)

        engine.attach(mixerNode)
        engine.connect(mixerNode, to: engine.outputNode, format: format)

        engine.attach(playerNode)
        engine.connect(playerNode, to: mixerNode, format: format)

        try engine.start()
    }

    func playStreamPCM(_ pcmData: [Int16], completion: @escaping (Bool) -> Void) {
        let audioBuffer = AVAudioPCMBuffer(
            pcmFormat: playerNode.outputFormat(forBus: 0), frameCapacity: AVAudioFrameCount(pcmData.count))!

        audioBuffer.frameLength = audioBuffer.frameCapacity
        let buf = audioBuffer.floatChannelData![0]
        for (index, sample) in pcmData.enumerated() {
            var convertedSample = Float32(sample) / Float32(Int16.max)
            if convertedSample > 1 {
                convertedSample = 1
            }
            if convertedSample < -1 {
                convertedSample = -1
            }
            buf[index] = convertedSample
        }

        pcmBuffers.append(audioBuffer)
        if !isPlaying {
            playNextPCMBuffer(completion: completion)
        } else {
            completion(true)
        }
    }

    private func playNextPCMBuffer(completion: @escaping (Bool) -> Void) {
        guard let pcmData = pcmBuffers.first else {
            isPlaying = false
            completion(false)
            return
        }
        pcmBuffers.removeFirst()

        playerNode.scheduleBuffer(pcmData) { [weak self] in
            self?.playNextPCMBuffer(completion: completion)
        }

        playerNode.play()
        isPlaying = true
        completion(true)
    }

    func stopStreamPCM() {
        playerNode.stop()
        engine.stop()
    }
}
