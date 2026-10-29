using System.Windows;
using WinForms = System.Windows.Forms;

namespace EmpVerify.Agent.Windows;

/// <summary>
/// Application entry point. Runs as a background tray agent: a NotifyIcon is the
/// always-visible status indicator, scheduled checks run whether or not the window
/// is open, and closing the window hides to tray rather than exiting. If the device
/// is already registered the app starts minimized (as at login); otherwise it shows
/// the window so the employee can register.
/// </summary>
public partial class App : System.Windows.Application
{
    private WinForms.NotifyIcon? _trayIcon;
    private MainWindow? _window;
    private Mutex? _instanceLock;

    protected override async void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

        // One agent per user session: the login startup task, the installer's
        // run-at-logon entry and a manual launch must not run side by side.
        _instanceLock = new Mutex(initiallyOwned: true, @"Local\EmpVerify.Agent", out var firstInstance);
        if (!firstInstance)
        {
            Shutdown();
            return;
        }

        _window = new MainWindow();

        _trayIcon = new WinForms.NotifyIcon
        {
            Icon = System.Drawing.SystemIcons.Application,
            Visible = true,
            Text = "EmpVerify Agent",
        };
        var menu = new WinForms.ContextMenuStrip();
        menu.Items.Add("Open", null, (_, _) => ShowWindow());
        menu.Items.Add("Verify now", null, async (_, _) => await _window!.VerifyNowFromTrayAsync());
        menu.Items.Add(new WinForms.ToolStripSeparator());
        menu.Items.Add("Exit", null, (_, _) => ExitApp());
        _trayIcon.ContextMenuStrip = menu;
        _trayIcon.DoubleClick += (_, _) => ShowWindow();

        _window.AttachTray(_trayIcon);

        if (_window.IsRegistered)
        {
            _trayIcon.ShowBalloonTip(3000, "EmpVerify Agent",
                "Running in the background. Identity checks are active.", WinForms.ToolTipIcon.Info);
        }
        else
        {
            ShowWindow();
        }

        await _window.InitializeAsync();
    }

    private void ShowWindow()
    {
        if (_window is null) return;
        _window.Show();
        _window.WindowState = WindowState.Normal;
        _window.Activate();
    }

    private void ExitApp()
    {
        _window?.PrepareExit();
        if (_trayIcon is not null)
        {
            _trayIcon.Visible = false;
            _trayIcon.Dispose();
            _trayIcon = null;
        }
        Shutdown();
    }

    protected override void OnExit(ExitEventArgs e)
    {
        _trayIcon?.Dispose();
        _instanceLock?.Dispose();
        base.OnExit(e);
    }
}
