package ai.picovoice.llmvoiceassistant;

public class TPSProfiler {
    private int numTokens;
    private long startSec;
    private long endSec;

    public TPSProfiler() {
        this.numTokens = 0;
        this.startSec = 0;
        this.endSec = 0;
    }

    public void tock() {
        if (this.startSec == 0) {
            this.startSec = System.nanoTime();
        } else {
            this.endSec = System.nanoTime();
            this.numTokens += 1;
        }
    }

    public double tps() {
        double tps = this.numTokens / ((this.endSec - this.startSec) / 1e9);
        this.numTokens = 0;
        this.startSec = 0;
        this.endSec = 0;
        return tps;
    }
}
