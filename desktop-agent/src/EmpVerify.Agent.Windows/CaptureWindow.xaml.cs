using System.IO;
using System.Windows;
using System.Windows.Input;
using Microsoft.Web.WebView2.Core;

namespace EmpVerify.Agent.Windows;

/// <summary>
/// Hosts the interactive check page (capture or Face Liveness) in an embedded
/// WebView2 so the agent owns the window: it opens it for a check and closes it
/// the moment the server has a verdict. The check is required, so the window is
/// borderless, full-screen and always on top, with no close button and Alt+F4
/// swallowed — the employee completes the scan, or the response window runs out
/// and the miss is recorded. Only the agent (and an OS shutdown) can close it.
/// </summary>
public partial class CaptureWindow : Window
{
    private static readonly string UserDataFolder = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "EmpVerify", "WebView2");

    private readonly string _url;

    public CaptureWindow(string url)
    {
        InitializeComponent();
        _url = url;
        Loaded += async (_, _) => await InitializeAsync();
    }

    protected override void OnPreviewKeyDown(System.Windows.Input.KeyEventArgs e)
    {
        // No skipping: Alt+F4 would close the window around the scan.
        if (e.Key == Key.System && e.SystemKey == Key.F4)
        {
            e.Handled = true;
            return;
        }
        base.OnPreviewKeyDown(e);
    }

    private async Task InitializeAsync()
    {
        try
        {
            // Explicit user-data folder: the install location (Program Files / MSIX) is read-only.
            var environment = await CoreWebView2Environment.CreateAsync(userDataFolder: UserDataFolder);
            await Browser.EnsureCoreWebView2Async(environment);

            var core = Browser.CoreWebView2;
            core.Settings.AreDefaultContextMenusEnabled = false;
            core.Settings.AreDevToolsEnabled = false;
            // The page is our own; clicking Capture is the consent, so don't add a second prompt.
            core.PermissionRequested += (_, e) =>
            {
                if (e.PermissionKind is CoreWebView2PermissionKind.Camera or CoreWebView2PermissionKind.Microphone)
                {
                    e.State = CoreWebView2PermissionState.Allow;
                }
            };
            core.Navigate(_url);
        }
        catch (Exception ex)
        {
            // The WebView2 runtime was removed or cannot start on this PC (an
            // unhandled exception here would take the whole agent down). Closing
            // the window lets the flow record the check as missed with a reason;
            // the installer puts the runtime back on the next install/repair.
            System.Diagnostics.Trace.TraceError("Check window could not start WebView2: {0}", ex);
            Close();
        }
    }
}
