//
//  Copyright 2026 Picovoice Inc.
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
    public var isStopped = false

    init(sampleRate: Double) throws {
        let audioSession = AVAudioSession.sharedInstance()
        try audioSession.setCategory(.playAndRecord, options: [.mixWithOthers, .allowBluetooth])
        try audioSession.setActive(true)
        RecipeDiagnostics.log("player session configured")
        RecipeDiagnostics.logRoute()

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

    func playStreamPCM(_ pcmData: [Int16]) throws {
        if isStopped {
            RecipeDiagnostics.log("player ignored audio: stopped")
            return
        }
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
        RecipeDiagnostics.log("player enqueued samples=\(pcmData.count) buffers=\(pcmBuffers.count) " +
                              "engineRunning=\(engine.isRunning) isPlaying=\(isPlaying)")

        if !engine.isRunning {
            try engine.start()
        }
        if !isPlaying {
            playNextPCMBuffer()
        }
    }

    private func playNextPCMBuffer() {
        if isStopped {
            RecipeDiagnostics.log("player ignored audio: stopped")
            return
        }
        guard let pcmData = pcmBuffers.first else {
            isPlaying = false
            RecipeDiagnostics.log("player buffer queue empty (not an output-completion signal)")
            return
        }
        pcmBuffers.removeFirst()

        let bufferID = String(UUID().uuidString.prefix(8))
        RecipeDiagnostics.log("player scheduling buffer=\(bufferID) samples=\(pcmData.frameLength)")
        playerNode.scheduleBuffer(pcmData) { [weak self] in
            RecipeDiagnostics.log("player buffer consumed id=\(bufferID)")
            self?.playNextPCMBuffer()
        }

        playerNode.play()
        isPlaying = true
        RecipeDiagnostics.log("player play requested buffer=\(bufferID)")
    }

    func resetAudioPlayer() {
        RecipeDiagnostics.log("player reset")
        isStopped = false
        isPlaying = false
    }

    func stopStreamPCM() {
        RecipeDiagnostics.log("player stop")
        isStopped = true
        pcmBuffers.removeAll()
        playerNode.stop()
    }
}
