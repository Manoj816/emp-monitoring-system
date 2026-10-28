using EmpVerify.Agent.Core.Abstractions;
using Microsoft.Web.WebView2.Core;

namespace EmpVerify.Agent.Windows.Services;

/// <summary>
/// Opens the check page in an agent-owned <see cref="CaptureWindow"/> and closes
/// it when the flow disposes the session — i.e. as soon as the check is done.
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

    public IDisposable Open(string url)
    {
        var window = new CaptureWindow(url);
        window.Show();
        window.Activate();
        return new Session(window);
    }

    private sealed class Session(CaptureWindow window) : IDisposable
    {
        private bool _closed;

        public void Dispose()
        {
            if (_closed) return;
            _closed = true;
            window.Dispatcher.InvokeAsync(() =>
            {
                if (window.IsLoaded) window.Close();
            });
        }
    }
}
