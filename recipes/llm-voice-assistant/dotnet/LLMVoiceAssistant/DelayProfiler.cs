using System.Diagnostics;

class DelayProfiler : Profiler
{
    readonly Stopwatch _stopwatch = new Stopwatch();
    public DelayProfiler() { }

    public void Tick()
    {
        _stopwatch.Restart();
    }

    public void Tock()
    {
        _stopwatch.Stop();
    }

    public string Stats()
    {
        return $"Delay: {_stopwatch.ElapsedMilliseconds:F2}ms";
    }
}