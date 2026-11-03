namespace EmpVerify.Agent.Core.Tests;

/// <summary>
/// Guards the parts of the install/uninstall scripts that keep the agent running:
/// an administrator installs it, and it is meant to be on for as long as the PC
/// is, until an administrator uninstalls it.
/// </summary>
public class PackagingScriptTests
{
    private const string TaskName = "EmpVerify Agent";

    private static string ReadScript(string name)
    {
        // Walk up from the test output dir to the repo, then into packaging/.
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir is not null)
        {
            var candidate = Path.Combine(dir.FullName, "desktop-agent", "packaging", name);
            if (File.Exists(candidate))
            {
                return File.ReadAllText(candidate);
            }
            dir = dir.Parent;
        }
        throw new FileNotFoundException($"{name} not found by walking up from {AppContext.BaseDirectory}");
    }

    [Fact]
    public void InstallerRegistersTheStartupTask()
    {
        var script = ReadScript("Install-PerMachine.ps1");
        Assert.Contains("Register-ScheduledTask", script);
        Assert.Contains($"$TaskName = \"{TaskName}\"", script);
        Assert.Contains("New-ScheduledTaskTrigger -AtLogOn", script);
    }

    [Fact]
    public void InstallerKeepsTheMachineWideRunEntry()
    {
        // Belt and braces with the task: one of them being switched off must not
        // leave the agent dead at the next sign-in.
        var script = ReadScript("Install-PerMachine.ps1");
        Assert.Contains(@"HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Run", script);
    }

    [Fact]
    public void UninstallerRemovesTheStartupTask()
    {
        var script = ReadScript("Uninstall.ps1");
        Assert.Contains("Unregister-ScheduledTask", script);
        Assert.Contains(TaskName, script);
    }

    [Fact]
    public void UninstallerStopsTheAgentOnlyAfterRemovingWhatStartsIt()
    {
        // The other order is a race: the watchdog task starts the agent again
        // between the Stop-Process and the task being removed.
        var script = ReadScript("Uninstall.ps1");
        var removesTask = script.IndexOf("Unregister-ScheduledTask", StringComparison.Ordinal);
        var stopsAgent = script.IndexOf("Stop-Process", StringComparison.Ordinal);
        Assert.True(removesTask >= 0 && stopsAgent > removesTask,
            "Uninstall.ps1 must unregister the scheduled task before stopping the agent");
    }
}
