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

    [Fact]
    public void InstallerDoesNotAskForARepetitionTaskSchedulerRejects()
    {
        // [TimeSpan]::MaxValue serialises to P99999999DT23H59M59S, which Task
        // Scheduler refuses outright. An omitted duration is the task XML's own
        // way of saying "repeat for as long as the PC is on".
        var script = ReadScript("Install-PerMachine.ps1");
        Assert.DoesNotContain("-RepetitionDuration ([TimeSpan]::MaxValue)", script);
    }

    [Fact]
    public void InstallerCanActuallyReachItsFallbackTriggers()
    {
        // Register-ScheduledTask reports a rejected trigger as a non-terminating
        // error, so without -ErrorAction Stop every fallback below it is dead code.
        var script = ReadScript("Install-PerMachine.ps1");
        var register = script.IndexOf("Register-ScheduledTask -TaskName", StringComparison.Ordinal);
        Assert.True(register >= 0, "Install-PerMachine.ps1 must register the task");
        var call = script.Substring(register, Math.Min(400, script.Length - register));
        Assert.Contains("-ErrorAction Stop", call);
    }

    [Fact]
    public void InstallerReportsTheTaskItActuallyRegistered()
    {
        // It used to announce success unconditionally, which is how a watchdog
        // that was never registered went unnoticed on a real install.
        var script = ReadScript("Install-PerMachine.ps1");
        var readsBack = script.IndexOf("Get-ScheduledTask -TaskName $TaskName", StringComparison.Ordinal);
        var announces = script.IndexOf("scheduled task '$TaskName' registered", StringComparison.Ordinal);
        Assert.True(readsBack >= 0 && announces > readsBack,
            "Install-PerMachine.ps1 must read the task back before saying it registered one");
    }

    [Fact]
    public void UpgradingKeepsTheSystemAssignedToItsEmployee()
    {
        // A new install id makes the agent drop its identity and come back as
        // Unassigned. Minting one on every run meant every update cost an
        // administrator a re-assignment of every PC in the fleet.
        var script = ReadScript("Install-PerMachine.ps1");
        var mint = script.IndexOf("Set-ItemProperty -Path $regPath -Name \"InstallId\"", StringComparison.Ordinal);
        Assert.True(mint >= 0, "the installer must still be able to write an install id");

        // Reachable only when there is no id yet, or an administrator asked for one.
        Assert.Contains("$registersAfresh = $ResetIdentity -or -not $knownInstallId", script);
        var guard = script.IndexOf("if ($registersAfresh)", StringComparison.Ordinal);
        Assert.True(guard >= 0 && guard < mint,
            "writing the install id must be guarded by -ResetIdentity or its absence");
    }

    [Fact]
    public void StartingAMachineOverIsStillPossibleButDeliberate()
    {
        var script = ReadScript("Install-PerMachine.ps1");
        Assert.Contains("[switch]$ResetIdentity", script);
    }

    [Fact]
    public void LauncherExplainsItselfInsteadOfVanishing()
    {
        // Double-clicked inside the .zip, Windows extracts this file on its own
        // into a temp folder; the package and the .ps1 are left behind.
        var cmd = ReadScript("Install-EmpVerify.cmd");
        Assert.Contains(@"if not exist ""%~dp0Install-PerMachine.ps1""", cmd);
        Assert.Contains("cannot run from inside the zip", cmd);
        // Unconditional, so the window never closes before it can be read.
        Assert.EndsWith("pause", cmd.TrimEnd());
    }
}
