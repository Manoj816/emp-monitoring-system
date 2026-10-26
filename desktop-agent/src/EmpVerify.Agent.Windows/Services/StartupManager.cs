using Microsoft.Win32;

namespace EmpVerify.Agent.Windows.Services;

/// <summary>
/// Configures the agent to start at user login.
///
/// - Packaged (MSIX): declared by the windows.startupTask extension in
///   AppxManifest.xml; this helper is a no-op, so we never fight the package.
/// - Unpackaged (dev exe): a per-user HKCU\...\Run entry. Reversible, current-user
///   scope (no admin), removed on offboarding/uninstall.
/// </summary>
public static class StartupManager
{
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string ValueName = "EmpVerifyAgent";

    private static bool IsPackaged()
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
