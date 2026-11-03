using EmpVerify.Agent.Core.Abstractions;

namespace EmpVerify.Agent.Core.Scheduling;

/// <summary>What to do with a check that is being held back.</summary>
public enum DeferredCheckAction
{
    /// <summary>Nothing to do this tick.</summary>
    Wait,

    /// <summary>The employee is back: pick the moment to ask (the host adds a short random delay).</summary>
    Arm,

    /// <summary>They went away again before the scan was shown: hold it once more.</summary>
    Disarm,

    /// <summary>The chosen moment has arrived: open the scan now.</summary>
    Run,

    /// <summary>The work window closed and it was never shown: this is the day's miss.</summary>
    RecordMiss,
}

/// <summary>
/// A check that came due when there was nobody at an unlocked workstation to show
/// it to.
///
/// A locked screen is somebody away from their desk — a tea break, a meeting, lunch
/// — not a refusal to be checked, so nothing is recorded while the check waits. It
/// is opened as soon as the employee is back, and only becomes the day's miss if
/// the work window closes before that. The scan is not opened at the exact instant
/// the screen unlocks: the host arms it a short random time later, so "it scans me
/// whenever I come back from tea" is not a pattern anybody can plan around.
/// </summary>
public static class DeferredCheckPolicy
{
    /// <param name="now">Current local time.</param>
    /// <param name="dueBy">End of the work window the check belongs to.</param>
    /// <param name="workstation">What the machine is doing right now.</param>
    /// <param name="armedAt">When the held check is set to open; null while it is still waiting.</param>
    public static DeferredCheckAction Next(
        DateTimeOffset now, DateTimeOffset dueBy, WorkstationState workstation, DateTimeOffset? armedAt)
    {
        // The window closes on a check nobody was ever there to answer: that is the
        // miss, and it is the only thing a held check is ever recorded as.
        if (now >= dueBy)
        {
            return DeferredCheckAction.RecordMiss;
        }
        if (workstation != WorkstationState.Active)
        {
            return armedAt is null ? DeferredCheckAction.Wait : DeferredCheckAction.Disarm;
        }
        if (armedAt is null)
        {
            return DeferredCheckAction.Arm;
        }
        return now >= armedAt.Value ? DeferredCheckAction.Run : DeferredCheckAction.Wait;
    }
}
