using System.Diagnostics;
using EmpVerify.Agent.Core.Abstractions;

namespace EmpVerify.Agent.Windows.Services;

/// <summary>
/// Fallback launcher: opens the URL in the default browser. A shell-launched tab
/// cannot be closed by us (browsers only let scripts close windows they opened),
/// so the returned session is a no-op. Used only when WebView2 is unavailable.
/// </summary>
public sealed class WindowsBrowserLauncher : IBrowserLauncher
{
    public IDisposable Open(string url)
    {
        Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });
        return NoOp.Instance;
    }

    private sealed class NoOp : IDisposable
    {
        public static readonly NoOp Instance = new();
        public void Dispose() { }
    }
}
