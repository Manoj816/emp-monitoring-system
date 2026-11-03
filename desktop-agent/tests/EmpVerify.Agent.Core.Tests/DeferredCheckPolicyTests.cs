using EmpVerify.Agent.Core.Abstractions;
using EmpVerify.Agent.Core.Scheduling;

namespace EmpVerify.Agent.Core.Tests;

/// <summary>
/// A tea break must not cost an employee a missed check. A check that comes due
/// with nobody at the machine waits for them and is only recorded as missed when
/// the work window closes without them ever coming back.
/// </summary>
public class DeferredCheckPolicyTests
{
    private static readonly DateTimeOffset Due = new(2026, 11, 2, 15, 10, 0, TimeSpan.Zero);
    private static readonly DateTimeOffset WindowEnd = new(2026, 11, 2, 17, 0, 0, TimeSpan.Zero);

    [Fact]
    public void LockedScreenKeepsWaitingInsteadOfBeingAMiss()
    {
        var action = DeferredCheckPolicy.Next(
            Due.AddMinutes(10), WindowEnd, WorkstationState.Locked, armedAt: null);
        Assert.Equal(DeferredCheckAction.Wait, action);
    }

    [Fact]
    public void EmployeeBackAtTheMachineArmsTheScan()
    {
        var action = DeferredCheckPolicy.Next(
            Due.AddMinutes(10), WindowEnd, WorkstationState.Active, armedAt: null);
        Assert.Equal(DeferredCheckAction.Arm, action);
    }

    [Fact]
    public void TheScanIsNotOpenedAtTheInstantTheyReturn()
    {
        // Armed for 15:22; at 15:20 there is still nothing to do, so the scan does
        // not simply land the moment the screen unlocks.
        var action = DeferredCheckPolicy.Next(
            Due.AddMinutes(10), WindowEnd, WorkstationState.Active, armedAt: Due.AddMinutes(12));
        Assert.Equal(DeferredCheckAction.Wait, action);
    }

    [Fact]
    public void TheArmedMomentOpensTheScan()
    {
        var action = DeferredCheckPolicy.Next(
            Due.AddMinutes(12), WindowEnd, WorkstationState.Active, armedAt: Due.AddMinutes(12));
        Assert.Equal(DeferredCheckAction.Run, action);
    }

    [Fact]
    public void AwayAgainBeforeTheScanHoldsItOnceMore()
    {
        var action = DeferredCheckPolicy.Next(
            Due.AddMinutes(11), WindowEnd, WorkstationState.Locked, armedAt: Due.AddMinutes(12));
        Assert.Equal(DeferredCheckAction.Disarm, action);
    }

    [Fact]
    public void ShuttingDownWaitsLikeALockedScreen()
    {
        var action = DeferredCheckPolicy.Next(
            Due.AddMinutes(10), WindowEnd, WorkstationState.ShuttingDown, armedAt: null);
        Assert.Equal(DeferredCheckAction.Wait, action);
    }

    [Fact]
    public void TheWorkWindowClosingIsWhatMakesItAMiss()
    {
        var action = DeferredCheckPolicy.Next(
            WindowEnd, WindowEnd, WorkstationState.Locked, armedAt: null);
        Assert.Equal(DeferredCheckAction.RecordMiss, action);
    }

    [Fact]
    public void TheWindowClosingBeatsAnArmedScan()
    {
        // Armed just before the window closed but never shown: still the day's miss,
        // never a scan opened after hours.
        var action = DeferredCheckPolicy.Next(
            WindowEnd.AddMinutes(1), WindowEnd, WorkstationState.Active, armedAt: WindowEnd.AddMinutes(2));
        Assert.Equal(DeferredCheckAction.RecordMiss, action);
    }

    [Fact]
    public void ATeaBreakEndsWithTheScanDoneNotAMiss()
    {
        // The whole point, as a sequence: due at 15:10 while locked, back at 15:25,
        // armed for 15:27, scanned at 15:27. Nothing is recorded as missed anywhere.
        DateTimeOffset? armed = null;
        Assert.Equal(DeferredCheckAction.Wait,
            DeferredCheckPolicy.Next(Due, WindowEnd, WorkstationState.Locked, armed));
        Assert.Equal(DeferredCheckAction.Wait,
            DeferredCheckPolicy.Next(Due.AddMinutes(14), WindowEnd, WorkstationState.Locked, armed));

        Assert.Equal(DeferredCheckAction.Arm,
            DeferredCheckPolicy.Next(Due.AddMinutes(15), WindowEnd, WorkstationState.Active, armed));
        armed = Due.AddMinutes(17);

        Assert.Equal(DeferredCheckAction.Wait,
            DeferredCheckPolicy.Next(Due.AddMinutes(16), WindowEnd, WorkstationState.Active, armed));
        Assert.Equal(DeferredCheckAction.Run,
            DeferredCheckPolicy.Next(Due.AddMinutes(17), WindowEnd, WorkstationState.Active, armed));
    }
}
