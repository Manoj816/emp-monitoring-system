using EmpVerify.Agent.Core;
using EmpVerify.Agent.Core.Scheduling;

namespace EmpVerify.Agent.Core.Tests;

/// <summary>Mid-day activation and the one-more-attempt-after-a-miss scheduling.</summary>
public class SchedulerRetryTests
{
    private static readonly CheckPolicy OneADay =
        new(new TimeOnly(9, 0), new TimeOnly(17, 0), "UTC", 1, 120, false);

    private static DateTimeOffset At(int hour, int minute = 0) =>
        new(2026, 10, 28, hour, minute, 0, TimeSpan.Zero);

    [Fact]
    public void ActivatingMidDayPlansTheCheckInTheRestOfTheWindow()
    {
        var scheduler = new CheckScheduler(new Random(1));
        var now = At(14, 0);

        var plan = scheduler.PlanRemainingDay(OneADay, now, TimeSpan.FromMinutes(1));

        var only = Assert.Single(plan);
        Assert.True(only >= At(14, 1), "not before now + lead");
        Assert.True(only < At(17, 0), "inside the work window");
    }

    [Fact]
    public void ActivatingBeforeTheWindowPlansFromTheWindowStart()
    {
        var scheduler = new CheckScheduler(new Random(2));

        var plan = scheduler.PlanRemainingDay(OneADay, At(7, 30), TimeSpan.FromMinutes(1));

        var only = Assert.Single(plan);
        Assert.True(only >= At(9, 0) && only < At(17, 0));
    }

    [Fact]
    public void ActivatingAfterTheWindowPlansNothingForToday()
    {
        var scheduler = new CheckScheduler(new Random(3));

        Assert.Empty(scheduler.PlanRemainingDay(OneADay, At(17, 30), TimeSpan.FromMinutes(1)));
        Assert.Empty(scheduler.PlanRemainingDay(OneADay, At(16, 59, 30), TimeSpan.FromMinutes(1)));
    }

    [Fact]
    public void RetryAfterAMissIsARandomTimeLaterTodayInsideTheWindow()
    {
        var scheduler = new CheckScheduler(new Random(4));
        var now = At(11, 0);

        var retry = scheduler.RetryLaterToday(OneADay, now, TimeSpan.FromMinutes(5));

        Assert.NotNull(retry);
        Assert.True(retry >= At(11, 5), "at least the lead after the miss");
        Assert.True(retry < At(17, 0), "before the window ends");
    }

    [Fact]
    public void NoRetryWhenTheWindowIsAboutToEnd()
    {
        var scheduler = new CheckScheduler(new Random(5));

        Assert.Null(scheduler.RetryLaterToday(OneADay, At(16, 57), TimeSpan.FromMinutes(5)));
        Assert.Null(scheduler.RetryLaterToday(OneADay, At(18, 0), TimeSpan.FromMinutes(5)));
    }

    private static DateTimeOffset At(int hour, int minute, int second) =>
        new(2026, 10, 28, hour, minute, second, TimeSpan.Zero);
}
