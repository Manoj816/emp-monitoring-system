using EmpVerify.Agent.Core.Resilience;

namespace EmpVerify.Agent.Core.Tests;

public class RetryPolicyTests
{
    private sealed class TransientException : Exception { }
    private sealed class FatalException : Exception { }

    private static bool IsTransient(Exception e) => e is TransientException;

    // Delay stub that records calls and never actually waits.
    private static Func<TimeSpan, CancellationToken, Task> NoWait(List<TimeSpan> recorded) =>
        (d, _) => { recorded.Add(d); return Task.CompletedTask; };

    [Fact]
    public async Task SucceedsWithoutRetryWhenActionSucceeds()
    {
        var delays = new List<TimeSpan>();
        var policy = new RetryPolicy(maxAttempts: 3);
        var calls = 0;

        var result = await policy.ExecuteAsync<int>(
            _ => { calls++; return Task.FromResult(42); },
            IsTransient, NoWait(delays), CancellationToken.None);

        Assert.Equal(42, result);
        Assert.Equal(1, calls);
        Assert.Empty(delays);
    }

    [Fact]
    public async Task RetriesTransientThenSucceeds()
    {
        var delays = new List<TimeSpan>();
        var policy = new RetryPolicy(maxAttempts: 3);
        var calls = 0;

        var result = await policy.ExecuteAsync<string>(
            _ =>
            {
                calls++;
                if (calls < 3) throw new TransientException();
                return Task.FromResult("ok");
            },
            IsTransient, NoWait(delays), CancellationToken.None, jitterSource: () => 1.0);

        Assert.Equal("ok", result);
        Assert.Equal(3, calls);
        Assert.Equal(2, delays.Count); // two backoffs before the third success
    }

    [Fact]
    public async Task NonTransientThrowsImmediatelyWithoutRetry()
    {
        var delays = new List<TimeSpan>();
        var policy = new RetryPolicy(maxAttempts: 5);
        var calls = 0;

        await Assert.ThrowsAsync<FatalException>(() => policy.ExecuteAsync<int>(
            _ => { calls++; throw new FatalException(); },
            IsTransient, NoWait(delays), CancellationToken.None));

        Assert.Equal(1, calls);
        Assert.Empty(delays);
    }

    [Fact]
    public async Task GivesUpAfterMaxAttemptsOnPersistentTransient()
    {
        var delays = new List<TimeSpan>();
        var policy = new RetryPolicy(maxAttempts: 3);
        var calls = 0;

        await Assert.ThrowsAsync<TransientException>(() => policy.ExecuteAsync<int>(
            _ => { calls++; throw new TransientException(); },
            IsTransient, NoWait(delays), CancellationToken.None));

        Assert.Equal(3, calls);        // tried exactly MaxAttempts times
        Assert.Equal(2, delays.Count); // backed off between attempts only
    }

    [Fact]
    public void BackoffGrowsExponentiallyAndCaps()
    {
        var policy = new RetryPolicy(
            maxAttempts: 10,
            baseDelay: TimeSpan.FromSeconds(1),
            maxDelay: TimeSpan.FromSeconds(10));

        // Full jitter = 1.0 gives the upper bound: 1s, 2s, 4s, 8s, then capped at 10s.
        Assert.Equal(1000, policy.DelayFor(1, 1.0).TotalMilliseconds);
        Assert.Equal(2000, policy.DelayFor(2, 1.0).TotalMilliseconds);
        Assert.Equal(4000, policy.DelayFor(3, 1.0).TotalMilliseconds);
        Assert.Equal(8000, policy.DelayFor(4, 1.0).TotalMilliseconds);
        Assert.Equal(10000, policy.DelayFor(5, 1.0).TotalMilliseconds); // capped
        Assert.Equal(10000, policy.DelayFor(9, 1.0).TotalMilliseconds); // stays capped
    }

    [Fact]
    public void JitterScalesTheDelay()
    {
        var policy = new RetryPolicy(baseDelay: TimeSpan.FromSeconds(4));
        Assert.Equal(0, policy.DelayFor(1, 0.0).TotalMilliseconds);
        Assert.Equal(2000, policy.DelayFor(1, 0.5).TotalMilliseconds);
    }
}
