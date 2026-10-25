using EmpVerify.Agent.Core;
using EmpVerify.Agent.Core.Scheduling;

namespace EmpVerify.Agent.Core.Tests;

public class CheckSchedulerTests
{
    private static readonly CheckPolicy Policy = new(
        WorkWindowStart: new TimeOnly(9, 0),
        WorkWindowEnd: new TimeOnly(17, 0),
        Timezone: "UTC",
        MaxChecksPerDay: 4,
        ResponseTimeoutSeconds: 120,
        LivenessOnSessionStart: true);

    private static readonly DateTimeOffset Day = new(2026, 10, 26, 0, 0, 0, TimeSpan.Zero);

    [Fact]
    public void PlanDay_ProducesConfiguredNumberOfChecks()
    {
        var plan = new CheckScheduler(new Random(42)).PlanDay(Policy, Day);
        Assert.Equal(Policy.MaxChecksPerDay, plan.Count);
    }

    [Fact]
    public void PlanDay_AllChecksFallInsideWorkWindow()
    {
        var plan = new CheckScheduler(new Random(42)).PlanDay(Policy, Day);
        foreach (var t in plan)
        {
            Assert.True(TimeOnly.FromDateTime(t.DateTime) >= Policy.WorkWindowStart);
            Assert.True(TimeOnly.FromDateTime(t.DateTime) < Policy.WorkWindowEnd);
        }
    }

    [Fact]
    public void PlanDay_TimesAreSortedAndRandomized()
    {
        var scheduler = new CheckScheduler(new Random(42));
        var plan1 = scheduler.PlanDay(Policy, Day);
        var plan2 = new CheckScheduler(new Random(7)).PlanDay(Policy, Day);

        Assert.Equal(plan1.OrderBy(t => t), plan1);
        Assert.NotEqual(plan1, plan2);
    }

    [Fact]
    public void PlanDay_InvertedWindowYieldsNoChecks()
    {
        var inverted = Policy with { WorkWindowStart = new TimeOnly(18, 0), WorkWindowEnd = new TimeOnly(9, 0) };
        Assert.Empty(new CheckScheduler().PlanDay(inverted, Day));
    }

    [Fact]
    public void NextCheck_SkipsPastTimes()
    {
        var scheduler = new CheckScheduler(new Random(42));
        var plan = scheduler.PlanDay(Policy, Day);
        var afterSecond = plan[1].AddMinutes(1);

        var next = scheduler.NextCheck(plan, afterSecond);

        Assert.Equal(plan[2], next);
    }

    [Fact]
    public void NextCheck_ReturnsNullWhenDayIsDone()
    {
        var scheduler = new CheckScheduler(new Random(42));
        var plan = scheduler.PlanDay(Policy, Day);

        Assert.Null(scheduler.NextCheck(plan, plan[^1].AddMinutes(1)));
    }
}
