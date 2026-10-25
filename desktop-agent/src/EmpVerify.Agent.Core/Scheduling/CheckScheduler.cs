namespace EmpVerify.Agent.Core.Scheduling;

/// <summary>
/// Pure scheduling logic: picks randomized check times inside the policy's work
/// window. Deliberately free of timers and platform APIs so it is unit-testable
/// and reusable on any platform; the host app owns the actual waiting.
/// </summary>
public sealed class CheckScheduler
{
    private readonly Random _random;

    public CheckScheduler(Random? random = null)
    {
        _random = random ?? Random.Shared;
    }

    /// <summary>
    /// Generates up to <see cref="CheckPolicy.MaxChecksPerDay"/> randomized times
    /// within the work window for the given date, sorted ascending. The window is
    /// split into equal slots with one random time per slot, so checks are spread
    /// across the day but individually unpredictable.
    /// </summary>
    public IReadOnlyList<DateTimeOffset> PlanDay(CheckPolicy policy, DateTimeOffset date)
    {
        if (policy.MaxChecksPerDay <= 0)
            return Array.Empty<DateTimeOffset>();

        var windowStart = date.Date + policy.WorkWindowStart.ToTimeSpan();
        var windowEnd = date.Date + policy.WorkWindowEnd.ToTimeSpan();
        if (windowEnd <= windowStart)
            return Array.Empty<DateTimeOffset>();

        var window = windowEnd - windowStart;
        var slot = window / policy.MaxChecksPerDay;

        var times = new List<DateTimeOffset>(policy.MaxChecksPerDay);
        for (var i = 0; i < policy.MaxChecksPerDay; i++)
        {
            var jitter = TimeSpan.FromTicks((long)(_random.NextDouble() * slot.Ticks));
            times.Add(new DateTimeOffset(windowStart + slot * i + jitter, date.Offset));
        }
        return times;
    }

    /// <summary>The next planned check strictly after <paramref name="now"/>, or null if the day is done.</summary>
    public DateTimeOffset? NextCheck(IReadOnlyList<DateTimeOffset> plan, DateTimeOffset now)
    {
        foreach (var t in plan)
        {
            if (t > now)
                return t;
        }
        return null;
    }
}
