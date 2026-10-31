using EmpVerify.Agent.Core.Abstractions;
using Microsoft.Win32;

namespace EmpVerify.Agent.Windows.Services;

/// <summary>
/// Tracks the workstation's interaction state from Windows session and power
/// events, so a check that could not run can be recorded with the real reason
/// (the screen was locked, the machine slept, it was shutting down). The agent is
/// a background tray app that keeps running while the session is locked, so these
/// events are the only way to know the machine was unattended when a check came due.
/// </summary>
public sealed class WindowsWorkstationMonitor : IWorkstationMonitor, IDisposable
{
    private volatile bool _locked;
    private volatile bool _shuttingDown;
    // UTC ticks of the most recent suspend; read with Interlocked for cross-thread safety
    // (SystemEvents handlers run off the UI thread).
    private long _lastSuspendUtcTicks;

    public WindowsWorkstationMonitor()
    {
        SystemEvents.SessionSwitch += OnSessionSwitch;
        SystemEvents.PowerModeChanged += OnPowerModeChanged;
        SystemEvents.SessionEnding += OnSessionEnding;
    }

    public WorkstationState Current =>
        _shuttingDown ? WorkstationState.ShuttingDown
        : _locked ? WorkstationState.Locked
        : WorkstationState.Active;

    public bool HasSleptSince(DateTimeOffset since)
    {
        long ticks = Interlocked.Read(ref _lastSuspendUtcTicks);
        return ticks > 0 && ticks >= since.UtcDateTime.Ticks;
    }

    private void OnSessionSwitch(object sender, SessionSwitchEventArgs e)
    {
        switch (e.Reason)
        {
            case SessionSwitchReason.SessionLock:
            case SessionSwitchReason.ConsoleDisconnect:
            case SessionSwitchReason.RemoteDisconnect:
                _locked = true;
                break;
            case SessionSwitchReason.SessionUnlock:
            case SessionSwitchReason.ConsoleConnect:
            case SessionSwitchReason.RemoteConnect:
            case SessionSwitchReason.SessionLogon:
                _locked = false;
                break;
        }
    }

    private void OnPowerModeChanged(object sender, PowerModeChangedEventArgs e)
    {
        if (e.Mode == PowerModes.Suspend)
        {
            Interlocked.Exchange(ref _lastSuspendUtcTicks, DateTime.UtcNow.Ticks);
        }
    }

    private void OnSessionEnding(object sender, SessionEndingEventArgs e) => _shuttingDown = true;

    public void Dispose()
    {
        SystemEvents.SessionSwitch -= OnSessionSwitch;
        SystemEvents.PowerModeChanged -= OnPowerModeChanged;
        SystemEvents.SessionEnding -= OnSessionEnding;
    }
}
