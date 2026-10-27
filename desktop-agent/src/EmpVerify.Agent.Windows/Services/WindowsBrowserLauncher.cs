using System.Diagnostics;
using EmpVerify.Agent.Core.Abstractions;

namespace EmpVerify.Agent.Windows.Services;

/// <summary>Opens the liveness URL in the default browser (Chrome/Edge).</summary>
public sealed class WindowsBrowserLauncher : IBrowserLauncher
{
    public void Open(string url)
    {
        Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });
    }
}
