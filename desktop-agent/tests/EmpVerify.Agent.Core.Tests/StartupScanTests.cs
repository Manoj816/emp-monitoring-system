using EmpVerify.Agent.Core;
using EmpVerify.Agent.Core.Scheduling;

namespace EmpVerify.Agent.Core.Tests;

/// <summary>
/// The agent must not scan an employee just because they signed in or restarted
/// the PC. The only scan it opens by itself at start-up is the enrollment scan a
/// system still owes.
/// </summary>
public class StartupScanTests
{
    private static readonly CheckPolicy Policy = new(
        new TimeOnly(9, 0), new TimeOnly(17, 0), "UTC",
        MaxChecksPerDay: 1, ResponseTimeoutSeconds: 120, LivenessOnSessionStart: true);

    [Fact]
    public void EnrolledSystemIsNotScannedAtStartup()
    {
        var state = new DeviceState("ACTIVE", Assigned: true, Enrolled: true);
        Assert.False(StartupScan.IsDue(Policy, state, alreadyDone: false));
    }

    [Fact]
    public void SystemThatStillOwesItsEnrollmentScanIsScannedAtStartup()
    {
        var state = new DeviceState("ACTIVE", Assigned: true, Enrolled: false);
        Assert.True(StartupScan.IsDue(Policy, state, alreadyDone: false));
    }

    [Fact]
    public void UnassignedSystemIsNotScanned()
    {
        var state = new DeviceState("PENDING", Assigned: false, Enrolled: false);
        Assert.False(StartupScan.IsDue(Policy, state, alreadyDone: false));
    }

    [Fact]
    public void PolicyCanSwitchTheStartupScanOff()
    {
        var policy = Policy with { LivenessOnSessionStart = false };
        var state = new DeviceState("ACTIVE", Assigned: true, Enrolled: false);
        Assert.False(StartupScan.IsDue(policy, state, alreadyDone: false));
    }

    [Fact]
    public void ScanAlreadyCompletedInThisRunIsNotAskedAgain()
    {
        var state = new DeviceState("ACTIVE", Assigned: true, Enrolled: false);
        Assert.False(StartupScan.IsDue(Policy, state, alreadyDone: true));
    }

    [Fact]
    public void UnknownPolicyOrStateScansNobody()
    {
        var state = new DeviceState("ACTIVE", Assigned: true, Enrolled: false);
        Assert.False(StartupScan.IsDue(null, state, alreadyDone: false));
        Assert.False(StartupScan.IsDue(Policy, null, alreadyDone: false));
    }
}
