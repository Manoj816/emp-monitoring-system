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

    /// <summary>
    /// Plans the rest of today starting from <paramref name="now"/> (plus a minimum
    /// lead), for an agent that activates mid-day: the check lands at a random time in
    /// what is left of the work window rather than at the moment of assignment, and
    /// is never skipped just because the day's original slot has already passed.
    /// </summary>
    public IReadOnlyList<DateTimeOffset> PlanRemainingDay(
        CheckPolicy policy, DateTimeOffset now, TimeSpan minimumLead)
    {
        if (policy.MaxChecksPerDay <= 0)
            return Array.Empty<DateTimeOffset>();

        var windowStart = new DateTimeOffset(now.Date + policy.WorkWindowStart.ToTimeSpan(), now.Offset);
        var windowEnd = new DateTimeOffset(now.Date + policy.WorkWindowEnd.ToTimeSpan(), now.Offset);
        var start = now + minimumLead > windowStart ? now + minimumLead : windowStart;
        if (windowEnd <= start)
            return Array.Empty<DateTimeOffset>();

        var slot = (windowEnd - start) / policy.MaxChecksPerDay;
        var times = new List<DateTimeOffset>(policy.MaxChecksPerDay);
        for (var i = 0; i < policy.MaxChecksPerDay; i++)
        {
            var jitter = TimeSpan.FromTicks((long)(_random.NextDouble() * slot.Ticks));
            times.Add(start + slot * i + jitter);
        }
        return times;
    }

    /// <summary>
    /// One more attempt after a missed check: a random time later today, at least
    /// <paramref name="minimumLead"/> from now. Null when the work window is (nearly)
    /// over, in which case the miss stands.
    /// </summary>
    public DateTimeOffset? RetryLaterToday(CheckPolicy policy, DateTimeOffset now, TimeSpan minimumLead)
    {
        var windowEnd = new DateTimeOffset(now.Date + policy.WorkWindowEnd.ToTimeSpan(), now.Offset);
        var earliest = now + minimumLead;
        if (earliest >= windowEnd)
            return null;
        var span = windowEnd - earliest;
        return earliest + TimeSpan.FromTicks((long)(_random.NextDouble() * span.Ticks));
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
