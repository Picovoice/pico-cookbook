package ai.picovoice.llmvoiceassistant;

public class RTFProfiler {
    private final int sampleRate;
    private double computeSec;
    private double audioSec;
    private double tickSec;

    public RTFProfiler(int sampleRate) {
        this.sampleRate = sampleRate;
        this.computeSec = 0.0;
        this.audioSec = 0.0;
        this.tickSec = 0.0;
    }

    public void tick() {
        this.tickSec = System.nanoTime() / 1e9;
    }

    public void tock(short[] pcm) {
        this.computeSec += (System.nanoTime() / 1e9) - this.tickSec;
        if (pcm != null && pcm.length > 0) {
            this.audioSec += pcm.length / (double) this.sampleRate;
        }
    }

    public double rtf() {
        double rtf = this.computeSec / this.audioSec;
        this.computeSec = 0.0;
        this.audioSec = 0.0;
        return rtf;
    }
}
