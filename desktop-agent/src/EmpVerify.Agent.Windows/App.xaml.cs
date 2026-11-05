using System.Windows;
using WinForms = System.Windows.Forms;

namespace EmpVerify.Agent.Windows;

/// <summary>
/// Application entry point. Runs as a background tray agent: a NotifyIcon is the
/// always-visible status indicator, scheduled checks run whether or not the window
/// is open, and closing the window hides to tray rather than exiting. The window is
/// never shown automatically — installing or signing in starts it silently in the
/// background; the employee opens it from the tray icon if they want to.
///
/// An installed (packaged) agent runs for as long as the PC is on: it starts at
/// every sign-in, the tray menu has no Exit, and the installer's scheduled task
/// starts it again if the process goes away. Stopping it is an administrator
/// action — uninstalling.
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

        // Identify this installation to the server so a reinstall re-uses its device row.
        EmpVerify.Agent.Core.Api.AgentApiClient.MachineId = MachineIdentity.Id;

        _window = new MainWindow();

        _trayIcon = new WinForms.NotifyIcon
        {
            Icon = LoadTrayIcon() ?? System.Drawing.SystemIcons.Application,
            Visible = true,
            Text = "EmpVerify Agent",
        };
        var menu = new WinForms.ContextMenuStrip();
        menu.Items.Add("Open", null, (_, _) => ShowWindow());
        menu.Items.Add("Verify now", null, async (_, _) => await _window!.VerifyNowFromTrayAsync());
        // An installed agent has no Exit: an administrator installed it, and only an
        // administrator stops it (by uninstalling). The dev exe keeps Exit so a
        // developer can close the thing they just launched.
        if (!Services.StartupManager.IsPackaged())
        {
            menu.Items.Add(new WinForms.ToolStripSeparator());
            menu.Items.Add("Exit", null, (_, _) => ExitApp());
        }
        _trayIcon.ContextMenuStrip = menu;
        _trayIcon.DoubleClick += (_, _) => ShowWindow();

        _window.AttachTray(_trayIcon);

        // The agent never opens its window by itself — not on a fresh install either.
        // The installer provisions the system, so there is nothing for the employee to
        // fill in: it registers silently in the background and reports through the tray
        // icon. The window is theirs to open from the tray when they want it.
        _trayIcon.ShowBalloonTip(3000, "EmpVerify Agent",
            _window.IsRegistered
                ? "Running in the background. Identity checks are active."
                : "Running in the background. Setting this system up…",
            WinForms.ToolTipIcon.Info);

        await _window.InitializeAsync();
    }

    /// <summary>
    /// The EV mark for the tray, at the size this machine's tray actually asks for
    /// (SmallIconSize follows the display scaling, so a 150% screen gets the 24px
    /// frame rather than a blurred-up 16px one).
    /// </summary>
    /// <returns>null if the resource cannot be read, so a missing or broken icon
    /// costs the agent its branding and not its start-up.</returns>
    private static System.Drawing.Icon? LoadTrayIcon()
    {
        try
        {
            var resource = GetResourceStream(new Uri("pack://application:,,,/Assets/EmpVerify.ico"));
            if (resource is null) return null;
            using var stream = resource.Stream;
            return new System.Drawing.Icon(stream, WinForms.SystemInformation.SmallIconSize);
        }
        catch (Exception)
        {
            return null;
        }
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
