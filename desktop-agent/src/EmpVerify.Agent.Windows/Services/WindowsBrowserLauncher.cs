using System.Diagnostics;
using EmpVerify.Agent.Core.Abstractions;

namespace EmpVerify.Agent.Windows.Services;

/// <summary>
/// Fallback launcher: opens the URL in the default browser. A shell-launched tab
/// cannot be closed or observed by us (browsers only let scripts close windows
/// they opened), so the session is a no-op whose Closed task never completes.
/// Used only when WebView2 is unavailable.
/// </summary>
public sealed class WindowsBrowserLauncher : IBrowserLauncher
{
    public IBrowserSession Open(string url)
    {
        Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });
        return NoOp.Instance;
    }

    private sealed class NoOp : IBrowserSession
    {
        public static readonly NoOp Instance = new();
        private static readonly Task Never = new TaskCompletionSource().Task;
        public Task Closed => Never;
        public void Dispose() { }
    }
}
