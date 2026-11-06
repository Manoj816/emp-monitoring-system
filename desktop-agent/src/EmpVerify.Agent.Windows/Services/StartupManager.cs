using Microsoft.Win32;

namespace EmpVerify.Agent.Windows.Services;

/// <summary>
/// Configures the agent to start at user login.
///
/// - Packaged (MSIX): declared by the windows.startupTask extension in
///   AppxManifest.xml, plus the machine-wide entry and scheduled task the
///   installer adds; this helper is a no-op, so we never fight the package.
/// - Unpackaged (dev exe): a per-user HKCU\...\Run entry. Reversible, current-user
///   scope (no admin), removed on offboarding/uninstall — and not written at all
///   on a machine that has the installed agent, see EnsureEnabled.
/// </summary>
public static class StartupManager
{
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string ValueName = "EmpVerifyAgent";

    /// <summary>
    /// True when the agent runs from the MSIX package — i.e. an administrator
    /// installed it. The dev exe run straight from the build output is not packaged.
    /// </summary>
    /// <summary>
    /// The version this agent reports: the installed package's version when packaged,
    /// else the assembly version marked "(dev build)".
    /// </summary>
    public static string ReportedVersion()
    {
        try
        {
            var v = global::Windows.ApplicationModel.Package.Current.Id.Version;
            return $"{v.Major}.{v.Minor}.{v.Build}.{v.Revision}";
        }
        catch
        {
            var assembly = typeof(StartupManager).Assembly.GetName().Version?.ToString() ?? "unknown";
            return $"{assembly} (dev build)";
        }
    }

    public static bool IsPackaged()
    {
        try
        {
            // Fully qualified: our own namespace is EmpVerify.Agent.Windows, which
            // would otherwise shadow the global Windows.* WinRT namespace.
            return global::Windows.ApplicationModel.Package.Current is not null;
        }
        catch
        {
            return false; // Package.Current throws when running unpackaged.
        }
    }

    public static void EnsureEnabled()
    {
        if (IsPackaged()) return; // manifest startupTask handles it

        // This machine has the installed agent, which owns start-up: a dev exe must
        // not line up beside it. Both would be started at sign-in, one would lose the
        // single-instance race, and — because the packaged agent keeps its credentials
        // in the package's redirected LocalAppData and the dev exe in the real one —
        // whichever won with no credentials would register the system all over again,
        // putting it back to Unassigned. A stale entry from an earlier run goes too.
        if (ManagedInstallPresent())
        {
            Disable();
            return;
        }
        try
        {
            var exe = Environment.ProcessPath;
            if (string.IsNullOrEmpty(exe)) return;
            using var key = Registry.CurrentUser.CreateSubKey(RunKey);
            key?.SetValue(ValueName, $"\"{exe}\"");
        }
        catch
        {
            // Non-fatal: the agent still runs, it just won't auto-start on this dev box.
        }
    }

    /// <summary>
    /// True when the installer has set its machine-wide run-at-sign-in entry, i.e.
    /// an administrator installed the agent on this PC.
    /// </summary>
    private static bool ManagedInstallPresent()
    {
        try
        {
            using var key = Registry.LocalMachine.OpenSubKey(RunKey);
            return key?.GetValue(ValueName) is string value && !string.IsNullOrWhiteSpace(value);
        }
        catch
        {
            return false; // registry unreadable: treat it as an ordinary dev box
        }
    }

    public static void Disable()
    {
        if (IsPackaged()) return;
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunKey, writable: true);
            key?.DeleteValue(ValueName, throwOnMissingValue: false);
        }
        catch
        {
            // ignore
        }
    }
}
