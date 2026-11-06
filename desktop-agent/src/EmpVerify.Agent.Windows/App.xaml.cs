using System.Windows;
using WinForms = System.Windows.Forms;

namespace EmpVerify.Agent.Windows;

/// <summary>
/// Application entry point. Runs as a pure background tray agent: a NotifyIcon is
/// the only thing the employee ever sees of it — there is no window to open, not
/// even from the tray, because scheduling belongs to the administrator and an
/// on-screen activity list would hand the employee the pattern the random check
/// exists to deny them. The only windows the agent ever opens are the check
/// windows themselves, which a scan cannot happen without.
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
            Text = "PresenSure Workforce",
        };
        // No "Open", no "Verify now": there is nothing for the employee to operate.
        // Checks are scheduled and sent by the administrator; the tray icon exists
        // so the agent is visibly present, never covert. An installed agent has no
        // Exit either — an administrator installed it, and only an administrator
        // stops it (by uninstalling). The dev exe keeps Exit so a developer can
        // close the thing they just launched.
        if (!Services.StartupManager.IsPackaged())
        {
            var menu = new WinForms.ContextMenuStrip();
            menu.Items.Add("Exit", null, (_, _) => ExitApp());
            _trayIcon.ContextMenuStrip = menu;
        }

        _window.AttachTray(_trayIcon);

        // The agent never opens a window of its own. The installer provisions the
        // system, so there is nothing for the employee to fill in: it registers
        // silently in the background and reports through the tray icon.
        _trayIcon.ShowBalloonTip(3000, "PresenSure Workforce",
            _window.IsRegistered
                ? "Running in the background. Identity checks are active."
                : "Running in the background. Setting this system up…",
            WinForms.ToolTipIcon.Info);

        await _window.InitializeAsync();
    }

    /// <summary>
    /// The PW mark for the tray, at the size this machine's tray actually asks for
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
