namespace EmpVerify.Agent.Core.Scheduling;

/// <summary>
/// The moment planned for today's check has passed and the agent is only getting
/// to it now, because the machine slept or hibernated through it.
///
/// Inside the work window that is fine — the check simply runs late, which is the
/// point of planning a day rather than firing a timer. Once the window has closed
/// it is not: opening a face scan at 16:10 for a window that ended at 15:00 puts
/// a camera in front of somebody outside the hours they are checked in, and a scan
/// nobody is required to answer is worth nothing anyway. It is recorded as the
/// day's miss instead, with what the agent observed.
/// </summary>
public static class LateSlot
{
    /// <summary>
    /// How far past the window a check may still be shown. Covers the scheduler's
    /// own tick latency and a slot the day's plan put in the window's last seconds,
    /// so an ordinary check is never turned into a miss by a few lost ticks.
    /// </summary>
    public static readonly TimeSpan Grace = TimeSpan.FromMinutes(1);

    /// <summary>True when the work window has closed and the check must not be opened.</summary>
    public static bool TooLateToShow(DateTimeOffset now, DateTimeOffset windowEnd) => now > windowEnd + Grace;
}
