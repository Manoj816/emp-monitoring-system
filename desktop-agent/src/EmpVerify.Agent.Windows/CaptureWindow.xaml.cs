using System.IO;
using System.Windows;
using Microsoft.Web.WebView2.Core;

namespace EmpVerify.Agent.Windows;

/// <summary>
/// Hosts the interactive check page (capture or Face Liveness) in an embedded
/// WebView2 so the agent owns the window: it opens it for a check and closes it
/// the moment the server has a verdict. A shell-launched browser tab can't be
/// closed by us, which is why the page isn't opened in the default browser.
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

    private async Task InitializeAsync()
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
}
