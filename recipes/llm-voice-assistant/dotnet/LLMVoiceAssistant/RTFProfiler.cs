using System.Diagnostics;

class RTFProfiler : Profiler
{
    int _sampleRate;
    Stopwatch _stopwatch = new Stopwatch();
    double _computeTime = 0;
    double _audioTime = 0;
    public string Name { get; private set; }

    public RTFProfiler(string name)
    {
        Name = name;
    }

    public void Init(int sampleRate)
    {
        _sampleRate = sampleRate;
    }

    public void Tick()
    {
        _stopwatch.Restart();
    }

    public void Tock(short[]? pcm)
    {
        _stopwatch.Stop();
        _computeTime += _stopwatch.Elapsed.TotalSeconds; // Using seconds for timing
        _audioTime += pcm == null ? 0 : (double)pcm.Length / _sampleRate; // Audio time in seconds
    }

    public double RTF()
    {
        double rtf = 0;
        if (_audioTime > 0)
            rtf = _computeTime / _audioTime; // Ratio of compute time to audio time
        _computeTime = 0;
        _audioTime = 0;
        return rtf;
    }

    public string Stats()
    {
        return $"{Name} RTF: {RTF():F3}"; // Format to 3 decimal places
    }

    public void Reset()
    {
        _computeTime = 0;
        _audioTime = 0;
    }
}