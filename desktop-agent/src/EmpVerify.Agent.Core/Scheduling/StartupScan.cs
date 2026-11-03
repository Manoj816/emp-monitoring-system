namespace EmpVerify.Agent.Core.Scheduling;

/// <summary>
/// Whether the agent opens a scan the moment it starts.
///
/// There is one reason it does: the system still owes its enrollment scan — a
/// fresh install, a system just handed to an employee, or a re-enrollment an
/// administrator asked for. An enrolled system starts silently. Signing in or
/// restarting a PC is not by itself a reason to scan somebody; that system is
/// checked once at a random time during the work day like every other day.
/// </summary>
public static class StartupScan
{
    /// <param name="policy">The server's check policy; null before the agent has loaded one.</param>
    /// <param name="state">The device's server-side state; null while it is unknown.</param>
    /// <param name="alreadyDone">A scan has already been completed in this run of the agent.</param>
    public static bool IsDue(CheckPolicy? policy, DeviceState? state, bool alreadyDone)
    {
        if (alreadyDone || policy is null || state is null)
        {
            return false;
        }
        // The policy can switch the start-up scan off for the whole fleet.
        if (!policy.LivenessOnSessionStart)
        {
            return false;
        }
        return state.Assigned && !state.Enrolled;
    }
}
