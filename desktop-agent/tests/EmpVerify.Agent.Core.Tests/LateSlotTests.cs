using EmpVerify.Agent.Core.Scheduling;

namespace EmpVerify.Agent.Core.Tests;

/// <summary>
/// A machine that slept through the moment planned for the day's check wakes up
/// with the slot still due. Inside the work window it is simply run late; past
/// it, the employee must not get a face scan outside the hours they are checked
/// in, so it is recorded as the day's miss instead.
/// </summary>
public class LateSlotTests
{
    private static readonly DateTimeOffset WindowEnd = new(2026, 11, 3, 15, 0, 0, TimeSpan.Zero);

    [Fact]
    public void InsideTheWindowTheCheckIsStillShown()
    {
        // Slept through 13:30, woke at 14:10: there is still a working day left.
        Assert.False(LateSlot.TooLateToShow(WindowEnd.AddMinutes(-50), WindowEnd));
    }

    [Fact]
    public void WokenAfterTheWindowClosedItIsNotShown()
    {
        // The case from the field: planned 13:30, machine asleep until 16:10.
        Assert.True(LateSlot.TooLateToShow(WindowEnd.AddMinutes(70), WindowEnd));
    }

    [Fact]
    public void TheWindowsLastSecondsAreNotTurnedIntoAMissByTickLatency()
    {
        // The day's plan can put the check seconds before the window ends and the
        // scheduler only ticks every 15s. Those lost seconds must not cost the
        // employee a missed check.
        Assert.False(LateSlot.TooLateToShow(WindowEnd.AddSeconds(20), WindowEnd));
        Assert.False(LateSlot.TooLateToShow(WindowEnd.AddSeconds(59), WindowEnd));
    }

    [Fact]
    public void TheGraceDoesNotStretchToAnotherScanLongAfterHours()
    {
        Assert.True(LateSlot.TooLateToShow(WindowEnd + LateSlot.Grace + TimeSpan.FromSeconds(1), WindowEnd));
        Assert.True(LateSlot.TooLateToShow(WindowEnd.AddHours(7), WindowEnd));
    }

    [Fact]
    public void ExactlyAtTheWindowEndIsStillInTime()
    {
        Assert.False(LateSlot.TooLateToShow(WindowEnd, WindowEnd));
    }
}
