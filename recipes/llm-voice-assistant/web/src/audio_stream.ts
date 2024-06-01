
class AudioStream {
  private readonly _sampleRate: number;
  private _audioContext: AudioContext;
  private readonly _audioGain: GainNode;

  private _audioBuffers: AudioBuffer[] = [];
  private _isPlaying = false;

  constructor(sampleRate: number) {
    this._sampleRate = sampleRate;
    this._audioContext = new (window.AudioContext ||
      // @ts-ignore
      window.webKitAudioContext)({ sampleRate: sampleRate });

    this._audioGain = this._audioContext.createGain();
    this._audioGain.gain.value = 1;
    this._audioGain.connect(this._audioContext.destination);
  }

  public stream(pcm: Int16Array): void {
    this._audioBuffers.push(this.createBuffer(pcm));
  }

  public play(): void {
    if (this._isPlaying) {
      return;
    }
    if (this._audioBuffers.length === 0) {
      return;
    }

    const streamSource = this._audioContext.createBufferSource();

    streamSource.buffer = this._audioBuffers.shift() ?? null;
    streamSource.connect(this._audioGain);

    streamSource.onended = (): void => {
      this._isPlaying = false;
      if (this._audioBuffers.length > 0) {
        this.play();
      }
    };

    streamSource.start();
    this._isPlaying = true;
  }

  public async waitPlayback(): Promise<void> {
    return new Promise<void>(resolve => {
      const interval = setInterval(() => {
        if (this._audioBuffers.length === 0 && !this._isPlaying) {
          clearInterval(interval);
          resolve();
        }
      }, 100);
    });
  }

  private createBuffer(pcm: Int16Array): AudioBuffer {
    const buffer = this._audioContext.createBuffer(
      1,
      pcm.length,
      this._sampleRate,
    );
    const source = new Float32Array(pcm.length);
    for (let i = 0; i < pcm.length; i++) {
      source[i] = pcm[i] < 0 ? pcm[i] / 32768 : pcm[i] / 32767;
    }
    buffer.copyToChannel(source, 0);
    return buffer;
  }
}

export {
  AudioStream
};
