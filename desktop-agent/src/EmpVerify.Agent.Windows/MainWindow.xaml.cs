using System.ComponentModel;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Windows;
using System.Windows.Threading;
using EmpVerify.Agent.Core;
using EmpVerify.Agent.Core.Abstractions;
using EmpVerify.Agent.Core.Api;
using EmpVerify.Agent.Core.Scheduling;
using EmpVerify.Agent.Windows.Services;
using WinForms = System.Windows.Forms;

namespace EmpVerify.Agent.Windows;

/// <summary>
/// The agent's hidden host window — the agent is a background tray app and this
/// window is never shown. Registration is either device-first (tenant provisioning
/// token: the device self-registers as unassigned and waits for an admin to link
/// it to an employee) or per-employee (enrollment token). Once assigned, it runs
/// one randomized interactive check per working day inside the admin-configured
/// check window, plus any check an administrator sends from the portal ("send a
/// check now"). The only scan the agent opens by itself at start-up is the
/// enrollment scan of a system that still owes one. Every check opens the agent's
/// own browser window — nothing is captured silently; everything else (scheduling,
/// the check window, on-demand checks) belongs to the administrator, so the
/// employee is shown nothing to click and no activity to pattern-match.
/// </summary>
public partial class MainWindow : Window
{
    private readonly AgentSettings _settings = AgentSettings.Load();
    private readonly DpapiCredentialStore _credentialStore = new();
    private readonly WindowsCameraService _camera = new();
    private readonly WpfPromptNotificationService _notifications = new();
    // Agent-owned WebView2 window so checks close themselves; shell browser only if the runtime is missing.
    private readonly IBrowserLauncher _browser = WebViewCaptureLauncher.IsAvailable()
        ? new WebViewCaptureLauncher()
        : new WindowsBrowserLauncher();
    private readonly CheckScheduler _scheduler = new();
    private readonly WindowsWorkstationMonitor _workstation = new();
    private readonly DispatcherTimer _timer = new() { Interval = TimeSpan.FromSeconds(15) };

    private AgentApiClient? _api;
    private VerificationFlow? _flow;
    private CheckPolicy? _policy;
    private DeviceCredentials? _credentials;
    private IReadOnlyList<DateTimeOffset> _plan = Array.Empty<DateTimeOffset>();
    private DateTimeOffset _lastHandled = DateTimeOffset.MinValue;
    private DateTimeOffset _lastDemoCheck = DateTimeOffset.MinValue;
    private bool _awaitingAssignment;
    private bool _checkRunning;
    /// <summary>The start-up enrollment scan has been completed (or was not due) in this run.</summary>
    private bool _startupScanDone;
    private DateTimeOffset? _startupScanRetryAt;
    private int _startupScanAttempts;
    private DateTimeOffset? _checkRetryAt;
    private bool _checkRetryIsDemo;
    /// <summary>The day's check, held back because nobody was at an unlocked workstation.</summary>
    private DeferredCheck? _deferred;
    private int _checkRetriesLeft;
    private DeviceState? _deviceState;
    private DateTime _planDate = DateTime.MinValue;
    private DateTimeOffset _lastStateRefresh = DateTimeOffset.MinValue;
    /// <summary>The server is currently unreachable; keeps the wake-up burst to one log line.</summary>
    private bool _offline;
    private bool _exiting;

    /// <summary>How often an active agent re-reads its assignment (offboarding / reassignment).</summary>
    private static readonly TimeSpan StateRefreshInterval = TimeSpan.FromSeconds(60);

    /// <summary>A check planned at activation lands at least this far in the future.</summary>
    private static readonly TimeSpan PlanLead = TimeSpan.FromMinutes(1);
    /// <summary>A same-day retry after a miss is at least this far from the miss.</summary>
    private static readonly TimeSpan RetryLead = TimeSpan.FromMinutes(5);
    /// <summary>
    /// At most how long a held check waits after the employee is back at the machine.
    /// Short enough to land inside a real return to the desk, random enough that the
    /// scan is not simply "the moment I unlock".
    /// </summary>
    private static readonly TimeSpan ResumeJitter = TimeSpan.FromMinutes(3);
    /// <summary>How long an admin-sent check waits for somebody to be back at an unlocked workstation.</summary>
    private static readonly TimeSpan OnDemandHold = TimeSpan.FromMinutes(30);
    private WinForms.NotifyIcon? _trayIcon;

    public MainWindow()
    {
        InitializeComponent();
        _timer.Tick += async (_, _) => await OnScheduleTickAsync();
    }

    /// <summary>True when this device already has stored registration credentials.</summary>
    public bool IsRegistered => DpapiCredentialStore.CredentialsExist();

    public void AttachTray(WinForms.NotifyIcon trayIcon) => _trayIcon = trayIcon;

    /// <summary>Connects and starts scheduling. Called once at startup (shown or background).</summary>
    public async Task InitializeAsync()
    {
        // Come back at every sign-in whatever state this system is in — including
        // registered-but-not-yet-assigned, and "the server was unreachable just now".
        // Previously this waited until the system was assigned and active, so a PC
        // restarted before an administrator got to it never started the agent again.
        // No-op for the MSIX install, where the package and the installer own startup.
        StartupManager.EnsureEnabled();
        await TryConnectAsync();
    }

    /// <summary>Lets App close the window for real (bypass hide-to-tray) on Exit.</summary>
    public void PrepareExit() => _exiting = true;

    protected override void OnClosing(CancelEventArgs e)
    {
        // The window is never shown, but belt and braces: a close that is not the
        // dev Exit only hides it; the agent keeps verifying.
        if (!_exiting)
        {
            e.Cancel = true;
            Hide();
        }
        base.OnClosing(e);
    }

    // ---- registration ------------------------------------------------------------

    private async Task TryConnectAsync()
    {
        var credentials = await _credentialStore.LoadAsync(CancellationToken.None);
        var installId = ProvisioningReader.ReadInstallId();
        if (credentials is not null && installId is not null
            && !string.Equals(installId, _settings.RegisteredInstallId, StringComparison.Ordinal))
        {
            // The id changes only when an administrator asks for it (the installer's
            // -ResetIdentity, a re-image, a PC handed to someone else). A plain
            // upgrade leaves it alone and this system keeps its employee.
            Log("An administrator reset this system's registration — registering it again.");
            await _credentialStore.DeleteAsync(CancellationToken.None);
            credentials = null;
        }
        if (credentials is null)
        {
            // Self-register from a deploy-time token, if the installer/MDM supplied one.
            var provisioning = ProvisioningReader.TryRead();
            if (provisioning is not null)
            {
                credentials = await AutoRegisterAsync(provisioning);
            }
        }
        if (credentials is null)
        {
            // No stored identity and no deploy-time token: the installer did not
            // configure this machine. Nothing the employee can type fixes that. The
            // window stays closed, so the tray balloon is the only way this is seen.
            Log("This system is not set up for PresenSure (no provisioning token found). Contact your administrator.");
            SetStatus("Not set up — contact your administrator.");
            _trayIcon?.ShowBalloonTip(6000, "PresenSure Workforce",
                "This system is not set up for PresenSure. Contact your administrator.",
                WinForms.ToolTipIcon.Warning);
            return;
        }
        await ConnectAsync(credentials);
    }

    private async Task<DeviceCredentials?> AutoRegisterAsync(Provisioning provisioning)
    {
        try
        {
            if (!string.IsNullOrWhiteSpace(provisioning.BackendUrl))
            {
                _settings.BackendUrl = provisioning.BackendUrl!;
                _settings.Save();
            }
            var api = CreateApi();
            var version = typeof(MainWindow).Assembly.GetName().Version?.ToString();
            DeviceCredentials credentials;
            if (provisioning.Kind == ProvisioningKind.Provisioning)
            {
                Log("Provisioning token found — registering this system automatically...");
                credentials = await api.ProvisionAsync(
                    provisioning.Token, "WINDOWS", Environment.MachineName, version, CancellationToken.None);
                Log($"System registered: {credentials.DeviceId} (awaiting assignment to an employee).");
            }
            else
            {
                Log("Enrollment token found — registering this device automatically...");
                credentials = await api.RegisterAsync(
                    provisioning.Token, "WINDOWS", Environment.MachineName, version, CancellationToken.None);
                Log($"Device registered: {credentials.DeviceId}");
            }
            await _credentialStore.SaveAsync(credentials, CancellationToken.None);
            _settings.RegisteredInstallId = ProvisioningReader.ReadInstallId();
            _settings.Save();
            ProvisioningReader.Clear();
            return credentials;
        }
        catch (Exception ex)
        {
            Log($"Auto-registration failed: {ex.Message}");
            return null;
        }
    }

    // ---- connection / activation -------------------------------------------------

    private async Task ConnectAsync(DeviceCredentials credentials)
    {
        _credentials = credentials;
        _api = CreateApi();
        try
        {
            await _api.AuthenticateAsync(credentials, CancellationToken.None);
        }
        catch (HttpRequestException ex) when (ex.StatusCode is
            HttpStatusCode.Unauthorized or HttpStatusCode.Forbidden or HttpStatusCode.NotFound)
        {
            // The server no longer knows this device (revoked, or the data was reset):
            // drop the stale credential and register again — silently via a deploy-time
            // token when present, otherwise the manual form.
            await _credentialStore.DeleteAsync(CancellationToken.None);
            _credentials = null;
            Log("Device registration is no longer valid on the server — registering again.");
            SetStatus("Not registered.");
            await TryConnectAsync();
            return;
        }
        catch (Exception ex)
        {
            Log($"Connection failed: {ex.Message}");
            SetStatus("Connection failed.");
            return;
        }

        try
        {
            var state = await _api.GetDeviceStateAsync(CancellationToken.None);
            _deviceState = state;
            if (!state.Assigned)
            {
                EnterAwaitingAssignment();
                return;
            }
        }
        catch (Exception ex)
        {
            Log($"Could not read device state: {ex.Message}");
            SetStatus("Connection failed.");
            return;
        }

        await ActivateAsync(atStartup: true);
    }

    /// <summary>Registered but not yet linked to an employee: poll until an admin assigns it.</summary>
    private void EnterAwaitingAssignment()
    {
        _awaitingAssignment = true;
        SetStatus("Registered — waiting for an administrator to assign this system.");
        Log("Waiting for an administrator to assign this system to an employee...");
        _trayIcon?.ShowBalloonTip(4000, "PresenSure Workforce",
            "Registered. Waiting for an administrator to assign this system.", WinForms.ToolTipIcon.Info);
        _timer.Start();
    }

    /// <summary>
    /// Assigned and active: load the policy and plan the day. <paramref name="atStartup"/>
    /// is true only for the agent's own start; an assignment or reassignment that
    /// arrives while it is already running re-plans the day but opens nothing, because
    /// the moment an administrator clicks Assign is not necessarily a moment the
    /// employee is at the machine.
    /// </summary>
    private async Task ActivateAsync(bool atStartup = false)
    {
        try
        {
            _policy = await _api!.GetPolicyAsync(CancellationToken.None);
            _flow = new VerificationFlow(_api, _camera, _notifications, _browser, _workstation);

            // The day's check lands at a random time in what is left of the work window —
            // never at the moment of assignment, which may not be when the employee is there.
            var now = DateTimeOffset.Now;
            _plan = _scheduler.PlanRemainingDay(_policy, now, PlanLead);
            _planDate = DateTime.Today;
            _lastHandled = now;
            _lastStateRefresh = now;
            _checkRetriesLeft = _settings.MissedCheckRetries;
            _checkRetryAt = null;
            _timer.Start();

            SetStatus("Connected — scheduled checks active.");
            // Deliberately no schedule details (window, count, time): the day's check
            // is random so that nobody can plan around it.
            Log("Connected. Scheduled identity checks are active.");
            if (_settings.DemoIntervalMinutes > 0)
            {
                Log($"Demo mode: a check every {_settings.DemoIntervalMinutes} min.");
            }
            LogNextCheck();
        }
        catch (Exception ex)
        {
            Log($"Connection failed: {ex.Message}");
            SetStatus("Connection failed.");
            return;
        }

        // A system that still owes its enrollment scan is scanned now, so a freshly
        // installed (or re-enrolled) machine does not sit unverified until the day's
        // random check. An already enrolled system opens nothing at start-up: it is
        // checked once at a random time during the work day, like every other day.
        if (atStartup && StartupScan.IsDue(_policy, _deviceState, _startupScanDone))
        {
            await RunEnrollmentScanAsync();
        }
    }

    /// <summary>
    /// The enrollment scan this system still owes, run at start-up. If the employee
    /// closes the window, cancels, or lets it time out, it reopens after RetryMinutes —
    /// and keeps doing so until a scan completes. A closed window never silently
    /// satisfies the requirement. Once a scan has been done it is not asked again:
    /// the system is enrolled, and later runs of the agent start silently.
    /// </summary>
    private async Task RunEnrollmentScanAsync()
    {
        _startupScanRetryAt = null;
        _startupScanAttempts++;
        Log(_startupScanAttempts == 1
            ? "This system still needs its enrollment scan — opening it now."
            : $"Enrollment scan still required (attempt {_startupScanAttempts}).");
        await RunCheckAsync(CheckType.Enrollment);
        if (_startupScanDone)
        {
            return;
        }
        var retryAt = DateTimeOffset.Now.AddMinutes(Math.Max(1, _settings.RetryMinutes));
        _startupScanRetryAt = retryAt;
        Log("Enrollment scan not completed — it reopens shortly.");
        SetStatus("Enrollment scan required — it reopens shortly.");
    }

    /// <summary>
    /// A scheduled check that ended without a scan: the miss is already recorded on
    /// the server for the administrator; one more attempt is made at a random later
    /// time today (a fixed delay for demo-driven checks), a bounded number of times.
    /// </summary>
    private void ScheduleCheckRetry(CheckType type, bool demo)
    {
        if (_checkRetriesLeft <= 0)
        {
            Log($"{Label(type)} missed — recorded for the administrator; no further attempt today.");
            return;
        }
        var retryAt = demo
            ? DateTimeOffset.Now.AddMinutes(Math.Max(1, _settings.RetryMinutes))
            : _scheduler.RetryLaterToday(_policy!, DateTimeOffset.Now, RetryLead);
        if (retryAt is null)
        {
            Log($"{Label(type)} missed — recorded for the administrator; no time left in today's work window.");
            return;
        }
        _checkRetriesLeft--;
        _checkRetryAt = retryAt;
        _checkRetryIsDemo = demo;
        Log($"{Label(type)} missed — recorded for the administrator; it is offered once more later today.");
    }

    /// <summary>
    /// Tells the employee what to expect, without the time: the day's check is random
    /// precisely so that nobody can plan around it, so neither its time nor whether
    /// one is still due today is shown.
    /// </summary>
    private void LogNextCheck()
    {
        Log(_deviceState is { Enrolled: false }
            ? "The next check is the enrollment scan, matched against the photo on file. It opens at a random time during the work day."
            : "Identity checks open at a random time during the work day.");
    }

    /// <summary>
    /// Which check the day's scan is: the enrollment scan until the employee's first
    /// live scan has matched the uploaded ID/photo, a periodic liveness check after.
    /// </summary>
    private async Task<CheckType> ResolveScheduledCheckTypeAsync()
    {
        try
        {
            await EnsureAuthenticatedAsync(CancellationToken.None);
            _deviceState = await _api!.GetDeviceStateAsync(CancellationToken.None);
        }
        catch (Exception ex)
        {
            NoteNetworkFailure(ex, "Could not read enrollment state");
        }
        return _deviceState is { Enrolled: false } ? CheckType.Enrollment : CheckType.Periodic;
    }

    private static string Label(CheckType type) => type switch
    {
        CheckType.Enrollment => "Enrollment scan",
        CheckType.SessionStart => "Session-start scan",
        CheckType.Periodic => "Daily check",
        CheckType.Liveness => "Liveness check",
        CheckType.OnDemand => "Admin-requested check",
        _ => $"{type} check",
    };

    /// <summary>
    /// Forgets a locally-remembered "send a check now" after a check has been
    /// opened. The server clears its flag the moment any check opens; this keeps
    /// the agent's copy of the state from re-triggering on a value read before
    /// that moment. The next state poll is the fresh truth either way.
    /// </summary>
    private void ClearLocalCheckRequest()
    {
        if (_deviceState is { CheckRequested: true })
        {
            _deviceState = _deviceState with { CheckRequested = false };
        }
    }

    /// <summary>Refreshes the short-lived device token before a check so a long-running agent never 403s.</summary>
    private async Task EnsureAuthenticatedAsync(CancellationToken ct)
    {
        if (_api is not null && _credentials is not null)
        {
            await _api.AuthenticateAsync(_credentials, ct);
        }
    }

    // ---- the network after a wake-up ----------------------------------------------

    /// <summary>
    /// True for the failures a PC that has just started or woken up produces: DNS not
    /// up yet ("No such host is known"), no route, a refused connection, a timeout.
    /// An HTTP error status is not one of these — there the server did answer.
    /// </summary>
    private static bool IsNetworkFailure(Exception? ex)
    {
        for (var e = ex; e is not null; e = e.InnerException)
        {
            if (e is System.Net.Sockets.SocketException or TaskCanceledException)
            {
                return true;
            }
            if (e is HttpRequestException { StatusCode: null })
            {
                return true; // the request never got a response at all
            }
        }
        return false;
    }

    /// <summary>
    /// Reports a failed call. An unreachable server is logged once, as a plain
    /// sentence: a waking PC fails every call for a minute, and a burst of raw
    /// "No such host is known" lines reads like a broken agent when nothing is
    /// wrong. Any other failure is logged with its message as before. Returns true
    /// when the cause was the network being down.
    /// </summary>
    private bool NoteNetworkFailure(Exception ex, string what)
    {
        if (!IsNetworkFailure(ex))
        {
            Log($"{what}: {ex.Message}");
            return false;
        }
        if (!_offline)
        {
            _offline = true;
            Log("Waiting for the network — the PresenSure server cannot be reached yet. "
                + "This is normal for a minute after the PC starts or wakes up; checks resume by themselves.");
            SetStatus("Waiting for the network…");
        }
        return true;
    }

    /// <summary>A call got through: say so once, so the quiet period has a visible end.</summary>
    private void NetworkOk()
    {
        if (_offline)
        {
            _offline = false;
            Log("Back online — identity checks continue as normal.");
            SetStatus("Connected — scheduled checks active.");
        }
    }

    /// <summary>
    /// Whether the server can be reached right now (refreshing the device token on
    /// the way). A non-network error is left for the check itself to surface; only
    /// an unreachable server stops a scheduled check before it starts.
    /// </summary>
    private async Task<bool> ServerReachableAsync()
    {
        try
        {
            await EnsureAuthenticatedAsync(CancellationToken.None);
            NetworkOk();
            return true;
        }
        catch (Exception ex)
        {
            return !NoteNetworkFailure(ex, "Could not reach the server");
        }
    }

    /// <summary>
    /// The server was unreachable, so nothing was opened and nothing was recorded.
    /// That is not the employee missing a check: the same check is offered again in
    /// a few minutes and the day's retry budget is deliberately left untouched.
    /// </summary>
    private void RearmAfterNetworkFailure(bool demo)
    {
        _checkRetryAt = DateTimeOffset.Now.AddMinutes(Math.Max(1, _settings.RetryMinutes));
        _checkRetryIsDemo = demo;
    }

    // ---- scheduling ---------------------------------------------------------------

    private async Task OnScheduleTickAsync()
    {
        if (_awaitingAssignment)
        {
            try
            {
                await EnsureAuthenticatedAsync(CancellationToken.None);
                var state = await _api!.GetDeviceStateAsync(CancellationToken.None);
                _deviceState = state;
                if (state.Assigned)
                {
                    _awaitingAssignment = false;
                    Log("This system has been assigned to an employee — activating.");
                    await ActivateAsync();
                }
            }
            catch (Exception ex)
            {
                NoteNetworkFailure(ex, "Assignment check failed");
            }
            return;
        }

        if (_flow is null || _policy is null || _checkRunning)
        {
            return;
        }
        var now = DateTimeOffset.Now;

        // Assignment can change under a running agent: the employee is offboarded
        // (system deregistered -> back to waiting) or the system is handed to another
        // employee (re-plan; the enrollment scan runs first if they have none).
        if (now - _lastStateRefresh >= StateRefreshInterval)
        {
            _lastStateRefresh = now;
            if (await RefreshAssignmentAsync())
            {
                return;
            }
            await RefreshPolicyAsync();
        }

        // A new day: plan it afresh (the agent may run for weeks without a restart).
        if (DateTime.Today != _planDate)
        {
            _plan = _scheduler.PlanDay(_policy, now);
            _planDate = DateTime.Today;
            _lastHandled = now;
            _checkRetriesLeft = _settings.MissedCheckRetries;
            _checkRetryAt = null;
            LogNextCheck();
        }

        // A missed enrollment scan is required: reopen it when its retry time arrives.
        if (!_startupScanDone && _startupScanRetryAt is { } retryAt && now >= retryAt)
        {
            await RunEnrollmentScanAsync();
            return;
        }

        // A check held back because nobody was at an unlocked workstation. Nothing
        // else starts while one is being held: the employee owes one scan, not two.
        if (_deferred is not null)
        {
            await HandleDeferredAsync(now);
            return;
        }

        // An administrator sent a check from the portal ("send a check now"). It
        // runs ahead of anything the day still has planned; the server clears the
        // request the moment any check opens, so it cannot fire twice.
        if (_deviceState is { CheckRequested: true })
        {
            _deviceState = _deviceState with { CheckRequested = false };
            await RunAdminRequestedCheckAsync();
            return;
        }

        // The one more attempt after a missed check.
        if (_checkRetryAt is { } again && now >= again)
        {
            _checkRetryAt = null;
            await RunScheduledCheckAsync(demo: _checkRetryIsDemo);
            return;
        }

        // Demo: a fixed interval overrides the daily plan.
        if (_settings.DemoIntervalMinutes > 0)
        {
            if (now - _lastDemoCheck >= TimeSpan.FromMinutes(_settings.DemoIntervalMinutes))
            {
                _lastDemoCheck = now;
                _checkRetriesLeft = _settings.MissedCheckRetries;
                await RunScheduledCheckAsync(demo: true);
            }
            return;
        }

        // Normal: the randomized daily plan (one check/day by policy).
        var due = _plan.Where(t => t > _lastHandled && t <= now).ToList();
        if (due.Count == 0)
        {
            return;
        }
        _lastHandled = now;
        _checkRetriesLeft = _settings.MissedCheckRetries;

        // The slot is only now being reached because the machine slept through it.
        // Inside the window the check simply runs late; past it, a scan would be
        // outside the hours the employee is checked in, so record what happened.
        if (LateSlot.TooLateToShow(now, WindowEnd(now)))
        {
            await RecordSleptThroughAsync(due[0], now);
            return;
        }
        await RunScheduledCheckAsync(demo: false);
    }

    /// <summary>
    /// Runs the day's scheduled check — unless the workstation cannot show it right
    /// now (the screen is locked, or the machine is shutting down). Opening a scan
    /// window behind a lock screen would only time out unseen, so instead the miss
    /// is recorded with that exact reason for the administrator and offered again
    /// later (when the employee unlocks, the retry fires and they do the scan).
    /// </summary>
    private async Task RunScheduledCheckAsync(bool demo)
    {
        // A PC that has just woken up often has no network for a minute. Nothing can
        // be opened, scanned or recorded then, so re-arm instead of spending the
        // day's attempt on a check that cannot reach the server.
        if (!await ServerReachableAsync())
        {
            RearmAfterNetworkFailure(demo);
            return;
        }
        var type = await ResolveScheduledCheckTypeAsync();
        if (TryDefer(type, demo))
        {
            return;
        }
        await RunCheckAsync(type, scheduled: true, demo: demo);
    }

    /// <summary>
    /// A check an administrator sent from the portal. It opens now — or, with
    /// nobody at an unlocked workstation, waits up to <see cref="OnDemandHold"/>
    /// for them to come back before the miss is recorded. The agent never retries
    /// it by itself: the administrator sees the result (or the miss) in the portal
    /// and can simply send another.
    /// </summary>
    private async Task RunAdminRequestedCheckAsync()
    {
        if (!await ServerReachableAsync())
        {
            return; // nothing opened: the request stays on the server and the next poll re-delivers it
        }
        // An un-enrolled system still owes the enrollment scan first; the admin's
        // request is answered by whichever check opens.
        var type = await ResolveScheduledCheckTypeAsync() == CheckType.Enrollment
            ? CheckType.Enrollment
            : CheckType.OnDemand;
        Log("An administrator requested a verification check on this system.");
        if (TryDefer(type, demo: false, dueBy: DateTimeOffset.Now + OnDemandHold))
        {
            return;
        }
        await RunCheckAsync(type, scheduled: false);
    }

    /// <summary>
    /// Re-reads the policy while active, so a change made in the portal reaches
    /// running agents within a minute: a new check window re-plans a day whose
    /// check has not fired yet (a day already checked stays checked), and taking
    /// the employee off the daily-check roster arrives as maxChecksPerDay = 0,
    /// which drops any check still scheduled for today. Admin-sent checks bypass
    /// the plan, so they keep working either way.
    /// </summary>
    private async Task RefreshPolicyAsync()
    {
        CheckPolicy fresh;
        try
        {
            fresh = await _api!.GetPolicyAsync(CancellationToken.None);
        }
        catch (Exception ex)
        {
            NoteNetworkFailure(ex, "Could not refresh the check policy");
            return;
        }
        NetworkOk();
        var current = _policy;
        _policy = fresh; // other knobs (timeout, capture-on-timeout) apply silently
        if (current is null
            || (fresh.WorkWindowStart == current.WorkWindowStart
                && fresh.WorkWindowEnd == current.WorkWindowEnd
                && fresh.MaxChecksPerDay == current.MaxChecksPerDay))
        {
            return;
        }
        var fired = _plan.Where(t => t <= _lastHandled).ToList();
        var remaining = fresh.MaxChecksPerDay - fired.Count;
        if (remaining <= 0)
        {
            _plan = fired; // drop anything still scheduled under the old policy
            Log(fresh.MaxChecksPerDay == 0
                ? "An administrator took this employee off the daily-check roster — no scheduled check today; admin-sent checks still run."
                : "An administrator changed the check window — today's check already ran; the new window applies from tomorrow.");
            return;
        }
        _plan = fired.Concat(_scheduler.PlanRemainingDay(fresh, DateTimeOffset.Now, PlanLead).Take(remaining)).ToList();
        Log("An administrator changed the check schedule — re-planning today's check.");
        LogNextCheck();
    }

    /// <summary>
    /// The day's check, come due with nobody at an unlocked workstation to show it
    /// to. Nothing is recorded while it waits — a locked screen is somebody away
    /// from their desk, not a refusal to be checked.
    /// </summary>
    private sealed class DeferredCheck
    {
        public required CheckType Type { get; init; }
        public required bool Demo { get; init; }
        /// <summary>When it came due.</summary>
        public required DateTimeOffset Since { get; init; }
        /// <summary>End of the work window it belongs to; after this it is the day's miss.</summary>
        public required DateTimeOffset DueBy { get; init; }
        /// <summary>When it is set to open, once the employee is back; null while waiting.</summary>
        public DateTimeOffset? ArmedAt { get; set; }
        /// <summary>How many times the workstation came back into use while it waited.</summary>
        public int Returns { get; set; }
    }

    /// <summary>
    /// Nobody is at an unlocked workstation, so the check is held rather than shown
    /// to an empty chair and counted against the employee. Returns true when it was
    /// held; false to run the check now.
    /// </summary>
    private bool TryDefer(CheckType type, bool demo, DateTimeOffset? dueBy = null)
    {
        if (_workstation.Current == WorkstationState.Active)
        {
            return false;
        }
        var now = DateTimeOffset.Now;
        _deferred = new DeferredCheck { Type = type, Demo = demo, Since = now, DueBy = dueBy ?? WindowEnd(now) };
        Log($"{Label(type)} came due while {StateDescription(_workstation.Current)} — holding it until the "
            + "workstation is back in use. Nothing is recorded as missed yet.");
        SetStatus("Check waiting for the workstation to be back in use.");
        return true;
    }

    /// <summary>
    /// Moves a held check along: opens it shortly after the employee is back, or
    /// records the day's miss if the work window closes first. Nothing else starts
    /// while a check is being held.
    /// </summary>
    private async Task HandleDeferredAsync(DateTimeOffset now)
    {
        var held = _deferred!;
        switch (DeferredCheckPolicy.Next(now, held.DueBy, _workstation.Current, held.ArmedAt))
        {
            case DeferredCheckAction.Arm:
                var wait = TimeSpan.FromTicks((long)(Random.Shared.NextDouble() * ResumeJitter.Ticks));
                held.ArmedAt = now + wait < held.DueBy ? now + wait : held.DueBy;
                held.Returns++;
                break;

            case DeferredCheckAction.Disarm:
                held.ArmedAt = null; // away again before the scan: keep holding it
                break;

            case DeferredCheckAction.Run:
                _deferred = null;
                Log($"{Label(held.Type)}: the workstation is back in use — opening it now.");
                // An admin-sent check is never retried by the agent: the admin sees
                // the miss in the portal and can simply send another.
                await RunCheckAsync(held.Type, scheduled: held.Type != CheckType.OnDemand, demo: held.Demo);
                break;

            case DeferredCheckAction.RecordMiss:
                _deferred = null;
                await RecordHeldMissAsync(held, now);
                break;
        }
    }

    /// <summary>
    /// The work window closed on a check nobody was ever there to answer. This is
    /// the one thing a held check is ever recorded as, and it says what was actually
    /// observed rather than guessing why.
    /// </summary>
    private async Task RecordHeldMissAsync(DeferredCheck held, DateTimeOffset now)
    {
        if (now.Date != held.Since.Date)
        {
            // The machine was away past midnight. Recording it now would date
            // yesterday's miss today; the server's end-of-day sweep describes that
            // day from what it knows (when the system was last online).
            Log($"{Label(held.Type)} was still held when the day ended — the server records that day.");
            return;
        }
        var note = HeldMissNote(held);
        try
        {
            await EnsureAuthenticatedAsync(CancellationToken.None);
            var check = await _api!.OpenCheckAsync(held.Type, CancellationToken.None);
            await _api.CancelCheckAsync(check.CheckId, "unattended", CancellationToken.None, note);
            ClearLocalCheckRequest(); // opening the check settled any pending admin request
            NetworkOk();
            Log($"{Label(held.Type)} missed: {note}");
            SetStatus($"Last check: missed (nobody at the workstation) at {DateTime.Now:HH:mm}.");
        }
        catch (Exception ex)
        {
            // Nothing reached the server, so nothing was recorded: never claim it was.
            NoteNetworkFailure(ex, $"{Label(held.Type)}: could not record the missed check");
        }
    }

    /// <summary>
    /// Today's check was planned for a moment the machine slept through, and by the
    /// time the agent could act the work window had closed. The employee was not
    /// asked anything and nothing was shown to an empty desk: this says exactly
    /// that, instead of the day quietly becoming "no check ran, machine was off".
    /// </summary>
    private async Task RecordSleptThroughAsync(DateTimeOffset due, DateTimeOffset now)
    {
        var windowEnd = WindowEnd(now);
        var note = $"The check was planned for {due:HH:mm}, but the agent was not running at that moment and "
            + $"only got to it at {now:HH:mm}, after the work window closed at {windowEnd:HH:mm}.";
        note += _workstation.HasSleptSince(WindowStart(now))
            ? " The machine slept at some point after the window opened, which is what it missed the check in."
            : " The machine did not report sleeping, so something else stopped the agent from running then.";
        try
        {
            await EnsureAuthenticatedAsync(CancellationToken.None);
            var type = await ResolveScheduledCheckTypeAsync();
            var check = await _api!.OpenCheckAsync(type, CancellationToken.None);
            await _api.CancelCheckAsync(check.CheckId, "machine_away", CancellationToken.None, note);
            ClearLocalCheckRequest(); // opening the check settled any pending admin request
            NetworkOk();
            Log($"{Label(type)} missed: {note}");
            SetStatus($"Last check: missed (the machine was away) at {DateTime.Now:HH:mm}.");
        }
        catch (Exception ex)
        {
            // Nothing reached the server, so nothing was recorded: never claim it was.
            NoteNetworkFailure(ex, "Could not record the check the machine slept through");
        }
    }

    /// <summary>What the agent saw while it held the check, for the administrator.</summary>
    private string HeldMissNote(DeferredCheck held)
    {
        var note = held.Type == CheckType.OnDemand
            ? $"An administrator asked for this check at {held.Since:HH:mm}. It was held for the employee "
                + $"to be at an unlocked workstation, and the wait ended at {held.DueBy:HH:mm}."
            : $"It came due at {held.Since:HH:mm} and was held back for the employee to be at an "
                + $"unlocked workstation, but the work window closed at {held.DueBy:HH:mm} first.";
        note += held.Returns == 0
            ? " The workstation was not back in use at any point in between."
            : $" It came back into use {held.Returns} time(s), but was away again before the scan could be shown.";
        if (_workstation.HasSleptSince(held.Since))
        {
            note += " The machine also slept during that time.";
        }
        return note;
    }

    /// <summary>Start of today's work window, in local time.</summary>
    private DateTimeOffset WindowStart(DateTimeOffset now) =>
        new(now.Date + _policy!.WorkWindowStart.ToTimeSpan(), now.Offset);

    /// <summary>End of today's work window, in local time.</summary>
    private DateTimeOffset WindowEnd(DateTimeOffset now) =>
        new(now.Date + _policy!.WorkWindowEnd.ToTimeSpan(), now.Offset);

    private static string StateDescription(WorkstationState state) => state switch
    {
        WorkstationState.Locked => "the screen was locked",
        WorkstationState.ShuttingDown => "the machine was shutting down",
        _ => "the workstation was unavailable",
    };

    /// <summary>
    /// Re-reads the device state while active. Returns true when the assignment
    /// changed and the agent was deactivated or re-planned, so the caller's tick
    /// should stop here.
    /// </summary>
    private async Task<bool> RefreshAssignmentAsync()
    {
        DeviceState state;
        try
        {
            await EnsureAuthenticatedAsync(CancellationToken.None);
            state = await _api!.GetDeviceStateAsync(CancellationToken.None);
        }
        catch (Exception ex)
        {
            NoteNetworkFailure(ex, "Could not refresh device state");
            return false;
        }
        NetworkOk();

        var previous = _deviceState;
        _deviceState = state;
        if (!state.Assigned)
        {
            Deactivate();
            Log("This system was deregistered from its employee — checks stop until an administrator assigns it again.");
            EnterAwaitingAssignment();
            return true;
        }
        if (previous?.EmployeeId is not null && state.EmployeeId != previous.EmployeeId)
        {
            Log("This system was assigned to a different employee — re-planning today's check.");
            Deactivate();
            await ActivateAsync();
            return true;
        }
        if (previous is { Enrolled: true } && !state.Enrolled)
        {
            // An administrator replaced the ID/photo or asked for the first scan again.
            // Nothing pops up now: the next check (the day's random time, or Verify now)
            // runs as the enrollment scan.
            Log("An administrator requested a new enrollment scan — the next check will be the enrollment scan.");
            LogNextCheck();
        }
        return false;
    }

    /// <summary>Stops scheduled checks; the connection and credentials are kept.</summary>
    private void Deactivate()
    {
        _flow = null;
        _plan = Array.Empty<DateTimeOffset>();
        _planDate = DateTime.MinValue;
        _checkRetryAt = null;
        _startupScanRetryAt = null;
        _deferred = null;
    }

    // ---- checks -------------------------------------------------------------------

    /// <summary>
    /// One interactive check in the agent's own browser window. By default this is
    /// the AWS Face Liveness scan (a live person, then compared to the enrolled
    /// reference); the photo capture is the opt-in fallback. The token is refreshed
    /// first so the agent never 403s mid-session. A check that ends without a scan
    /// (window closed, cancelled, timed out) is logged with why, and a scheduled
    /// one is offered again; any completed scan also settles a start-up enrollment
    /// scan that is still outstanding.
    /// </summary>
    private async Task<FlowResult?> RunCheckAsync(
        CheckType type, bool scheduled = false, bool forceLiveness = false, bool demo = false)
    {
        if (_flow is null || _policy is null || _checkRunning)
        {
            return null;
        }
        _checkRunning = true;
        FlowResult? result = null;
        try
        {
            await EnsureAuthenticatedAsync(CancellationToken.None);
            var window = TimeSpan.FromSeconds(_policy.ResponseTimeoutSeconds);
            if (forceLiveness || _settings.UseLivenessForChecks)
            {
                Log(type == CheckType.Enrollment
                    ? "Enrollment scan: opening the face scan — matched against the photo on file."
                    : $"{Label(type)}: opening the face scan...");
                result = await _flow.RunLivenessCheckAsync(
                    type, window, CancellationToken.None, captureOnTimeout: _policy.CaptureOnTimeout);
            }
            else
            {
                Log($"{Label(type)}: opening the photo capture prompt...");
                result = await _flow.RunBrowserCaptureCheckAsync(
                    type, window, CancellationToken.None, captureOnTimeout: _policy.CaptureOnTimeout);
            }
            Log(Describe(type, result));
            // A technical failure (scan did not complete, no reference enrolled) is a
            // verdict for the record, but not a completed scan: it is retried like a miss.
            var technicalFailure = result.Result?.Status == CheckStatus.VerificationError;
            if (result.HasVerdict && !technicalFailure)
            {
                SetStatus($"Last check: {result.Result!.Status} at {DateTime.Now:HH:mm}.");
                if (type == CheckType.Enrollment && result.Result.Status == CheckStatus.Match)
                {
                    Log("Enrollment complete: this scan is now the second reference for daily checks.");
                }
                // Any completed face scan settles the one the agent opened at start-up:
                // the server has the result, so it is not asked for twice.
                if (!_startupScanDone
                    && (type == CheckType.Enrollment || result.Result.LivenessConfidence is not null))
                {
                    _startupScanDone = true;
                    _startupScanRetryAt = null;
                }
            }
            else if (scheduled)
            {
                ScheduleCheckRetry(type, demo);
            }
        }
        catch (Exception ex)
        {
            // A scheduled check that died because the server was unreachable was
            // never recorded either way, so offer it again shortly rather than
            // letting the day's check vanish with a one-line error.
            if (NoteNetworkFailure(ex, $"{Label(type)} error") && scheduled)
            {
                RearmAfterNetworkFailure(demo);
            }
        }
        finally
        {
            _checkRunning = false;
            // Opening the check cleared the server's "send a check now" flag, but
            // ResolveScheduledCheckTypeAsync re-reads the device state BEFORE the
            // open, so our local copy can still say "requested" here. Left alone,
            // that stale flag opened a second scan the moment this one finished.
            ClearLocalCheckRequest();
        }
        return result;
    }

    private static string Describe(CheckType type, FlowResult result) => result.Outcome switch
    {
        FlowOutcome.Completed when result.HasVerdict =>
            $"{Label(type)}: {result.Result!.Status}" +
            (result.Result.SimilarityScore is { } s ? $" (similarity {s:F1})" : "") +
            (result.Result.LivenessConfidence is { } c ? $" (liveness {c:F1})" : ""),
        FlowOutcome.Completed => $"{Label(type)} expired on the server before a scan was done.",
        FlowOutcome.WindowClosed => $"{Label(type)}: the scan window was closed before the scan finished.",
        FlowOutcome.Cancelled => $"{Label(type)}: the scan was cancelled or could not run.",
        FlowOutcome.NotAccepted => $"{Label(type)} not completed within the response window.",
        _ => $"{Label(type)} failed.",
    };

    private AgentApiClient CreateApi() =>
        new(new HttpClient { BaseAddress = new Uri(_settings.BackendUrl) });

    private void SetStatus(string status)
    {
        if (_trayIcon is not null)
        {
            // NotifyIcon.Text is capped at 63 characters.
            var text = $"PresenSure — {status}";
            _trayIcon.Text = text.Length > 63 ? text[..63] : text;
        }
    }

    private static readonly string LogPath = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "EmpVerify", "agent.log");

    /// <summary>
    /// The activity line. The agent has no visible window — scheduling belongs to
    /// the administrator, and an on-screen activity list would hand the employee
    /// the pattern the random check exists to deny them — so lines go to a local
    /// file for support instead of a screen. Best-effort: a log line is never
    /// worth an exception in the scheduler.
    /// </summary>
    private void Log(string message)
    {
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(LogPath)!);
            var file = new FileInfo(LogPath);
            if (file.Exists && file.Length > 1_000_000)
            {
                // One rotation keeps the file bounded on a machine that runs for months.
                File.Copy(LogPath, LogPath + ".old", overwrite: true);
                File.Delete(LogPath);
            }
            File.AppendAllText(LogPath, $"{DateTime.Now:yyyy-MM-dd HH:mm:ss}  {message}{Environment.NewLine}");
        }
        catch
        {
            // Logging must never cost the agent its checks.
        }
    }
}
