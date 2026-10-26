namespace EmpVerify.Agent.Core.Resilience;

/// <summary>
/// Exponential backoff with full jitter for transient network failures, so a
/// brief connectivity blip during a check does not lose the attempt. Pure and
/// clock-injectable so it is unit-testable without real waiting.
/// </summary>
public sealed class RetryPolicy
{
    public int MaxAttempts { get; }
    public TimeSpan BaseDelay { get; }
    public TimeSpan MaxDelay { get; }

    public RetryPolicy(int maxAttempts = 3, TimeSpan? baseDelay = null, TimeSpan? maxDelay = null)
    {
        if (maxAttempts < 1) throw new ArgumentOutOfRangeException(nameof(maxAttempts));
        MaxAttempts = maxAttempts;
        BaseDelay = baseDelay ?? TimeSpan.FromSeconds(1);
        MaxDelay = maxDelay ?? TimeSpan.FromSeconds(15);
    }

    /// <summary>
    /// Backoff for a 1-based attempt number: min(MaxDelay, BaseDelay * 2^(attempt-1))
    /// scaled by <paramref name="jitter"/> in [0,1] (full jitter).
    /// </summary>
    public TimeSpan DelayFor(int attempt, double jitter)
    {
        var exponent = Math.Min(attempt - 1, 16); // guard against overflow
        var raw = BaseDelay.TotalMilliseconds * Math.Pow(2, exponent);
        var capped = Math.Min(raw, MaxDelay.TotalMilliseconds);
        return TimeSpan.FromMilliseconds(capped * Math.Clamp(jitter, 0.0, 1.0));
    }

    /// <summary>
    /// Runs <paramref name="action"/>, retrying while <paramref name="isTransient"/> is
    /// true, up to MaxAttempts. Non-transient exceptions propagate immediately. The
    /// delay function is injectable so tests run instantly.
    /// </summary>
    public async Task<T> ExecuteAsync<T>(
        Func<CancellationToken, Task<T>> action,
        Func<Exception, bool> isTransient,
        Func<TimeSpan, CancellationToken, Task> delay,
        CancellationToken ct,
        Func<double>? jitterSource = null)
    {
        jitterSource ??= () => Random.Shared.NextDouble();
        for (var attempt = 1; ; attempt++)
        {
            ct.ThrowIfCancellationRequested();
            try
            {
                return await action(ct);
            }
            catch (Exception ex) when (isTransient(ex) && attempt < MaxAttempts)
            {
                // Transient and attempts remain: back off and retry. A non-transient
                // exception, or a transient one on the final attempt, propagates.
                await delay(DelayFor(attempt, jitterSource()), ct);
            }
        }
    }
}
