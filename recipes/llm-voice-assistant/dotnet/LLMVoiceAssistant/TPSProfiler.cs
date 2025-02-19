using System;
using System.Diagnostics;

public class TPSProfiler : Profiler
{
    private int _numTokens;
    private double _startSec;
    private Stopwatch _stopwatch;
    public string Name { get; private set; }

    public TPSProfiler(string name)
    {
        _numTokens = 0;
        _startSec = 0.0;
        _stopwatch = new Stopwatch();
        Name = name;
    }

    public void Tock()
    {
        if (!_stopwatch.IsRunning)
        {
            _stopwatch.Start();
            _startSec = _stopwatch.Elapsed.TotalSeconds;
        }
        else
        {
            _numTokens += 1;
        }
    }

    public double TPS()
    {
        double elapsedSec = _stopwatch.Elapsed.TotalSeconds - _startSec;
        double tps = elapsedSec > 0 ? _numTokens / elapsedSec : 0.0;

        _numTokens = 0;
        _startSec = 0.0;
        _stopwatch.Reset();

        return tps;
    }

    public string Stats()
    {
        return $"{Name} TPS: {TPS()}";
    }

    public void Reset()
    {
        _numTokens = 0;
        _startSec = 0.0;
        _stopwatch.Reset();
    }
}