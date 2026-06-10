/*
    Copyright 2026 Picovoice Inc.

    You may not use this file except in compliance with the license. A copy of the license is
    located in the "LICENSE" file accompanying this source.

    Unless required by applicable law or agreed to in writing, software distributed under the
    License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
    express or implied. See the License for the specific language governing permissions and
    limitations under the License.
*/

package ai.picovoice.retailassociate;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

public class PvSpeaker {
    private final AudioTrack audioTrack;
    private int writtenSamples;

    public PvSpeaker(int sampleRate) {
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();

        AudioFormat audioFormat = new AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build();

        audioTrack = new AudioTrack(
                audioAttributes,
                audioFormat,
                AudioTrack.getMinBufferSize(
                        sampleRate,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT),
                AudioTrack.MODE_STREAM,
                0);

        writtenSamples = 0;
    }

    public void play(short[] pcm, Predicate isRunning) {
        audioTrack.play();

        int samplesWritten = 0;
        while ((samplesWritten < pcm.length) && isRunning.call()) {
            samplesWritten += audioTrack.write(
                    pcm,
                    samplesWritten,
                    pcm.length - samplesWritten,
                    AudioTrack.WRITE_NON_BLOCKING);
            this.writtenSamples = samplesWritten;
        }
    }

    public void stop() {
        audioTrack.stop();
        writtenSamples = 0;
    }

    public boolean isPlaying() {
        return audioTrack.getPlaybackHeadPosition() < this.writtenSamples;
    }

    public void delete() {
        this.stop();
        audioTrack.release();
    }
}