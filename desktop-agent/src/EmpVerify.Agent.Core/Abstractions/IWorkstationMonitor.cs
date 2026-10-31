namespace EmpVerify.Agent.Core.Abstractions;

/// <summary>
/// What the workstation was doing when a check could not be completed, so a missed
/// check tells the administrator why in plain terms — the screen was locked, the
/// machine slept, it was shutting down — instead of a bare "timed out".
/// </summary>
public enum WorkstationState
{
    /// <summary>Unlocked and in use: a check not done here is a real non-response.</summary>
    Active,
    /// <summary>The session is locked (Win+L, the lock screen, or an idle lock).</summary>
    Locked,
    /// <summary>The machine is shutting down or the user is signing out.</summary>
    ShuttingDown,
}

/// <summary>
/// Reports the current interaction state of the machine. Platform-neutral so the
/// verification flow can label a missed check without a dependency on Windows APIs;
/// the Windows head implements it from session and power events.
/// </summary>
public interface IWorkstationMonitor
{
    /// <summary>The state right now.</summary>
    WorkstationState Current { get; }

    /// <summary>True if the machine suspended (slept or hibernated) at any point since the given instant.</summary>
    bool HasSleptSince(DateTimeOffset since);
}
