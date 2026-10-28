using EmpVerify.Agent.Core.Abstractions;
using Microsoft.Web.WebView2.Core;

namespace EmpVerify.Agent.Windows.Services;

/// <summary>
/// Opens the check page in an agent-owned <see cref="CaptureWindow"/>. The flow
/// disposes the session to close it once the check is over, and is told at once
/// (via <see cref="IBrowserSession.Closed"/>) if the employee closes it first.
/// </summary>
public sealed class WebViewCaptureLauncher : IBrowserLauncher
{
    /// <summary>True when the WebView2 (Edge) runtime is present on this machine.</summary>
    public static bool IsAvailable()
    {
        try
        {
            return !string.IsNullOrEmpty(CoreWebView2Environment.GetAvailableBrowserVersionString());
        }
        catch
        {
            return false;
        }
    }

    public IBrowserSession Open(string url)
    {
        var window = new CaptureWindow(url);
        var session = new Session(window);
        window.Show();
        window.Activate();
        return session;
    }

    private sealed class Session : IBrowserSession
    {
        private readonly CaptureWindow _window;
        private readonly TaskCompletionSource _closed = new(TaskCreationOptions.RunContinuationsAsynchronously);
        private bool _disposed;

        public Session(CaptureWindow window)
        {
            _window = window;
            // Fires for any close. After Dispose it is our own close and the flow has
            // already finished, so only an employee's early close is ever observed.
            window.Closed += (_, _) => _closed.TrySetResult();
        }

        public Task Closed => _closed.Task;

        public void Dispose()
        {
            if (_disposed) return;
            _disposed = true;
            _window.Dispatcher.InvokeAsync(() =>
            {
                if (_window.IsLoaded) _window.Close();
            });
        }
    }
}
