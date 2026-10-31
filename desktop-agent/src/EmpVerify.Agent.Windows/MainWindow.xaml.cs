using System.ComponentModel;
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
/// Agent window. Registration is either device-first (tenant provisioning token:
/// the device self-registers as unassigned and waits for an admin to link it to an
/// employee) or per-employee (enrollment token, manual or provisioned). Once
/// assigned, it runs a full face scan at session start and one randomized
/// interactive check per working day, plus on-demand/demo checks. Every check
/// opens the agent's own browser window — nothing is captured silently. Closing
/// the window hides to the tray; checks keep running.
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
    private DateTimeOffset? _demoScheduledAt;
    private bool _awaitingAssignment;
    private bool _checkRunning;
    private bool _sessionStartDone;
    private DateTimeOffset? _sessionStartRetryAt;
    private int _sessionStartAttempts;
    private DateTimeOffset? _checkRetryAt;
    private bool _checkRetryIsDemo;
    private int _checkRetriesLeft;
    private DeviceState? _deviceState;
    private DateTime _planDate = DateTime.MinValue;
    private DateTimeOffset _lastStateRefresh = DateTimeOffset.MinValue;
    private bool _exiting;

    /// <summary>How often an active agent re-reads its assignment (offboarding / reassignment).</summary>
    private static readonly TimeSpan StateRefreshInterval = TimeSpan.FromSeconds(60);

    /// <summary>A check planned at activation lands at least this far in the future.</summary>
    private static readonly TimeSpan PlanLead = TimeSpan.FromMinutes(1);
    /// <summary>A same-day retry after a miss is at least this far from the miss.</summary>
    private static readonly TimeSpan RetryLead = TimeSpan.FromMinutes(5);
    private WinForms.NotifyIcon? _trayIcon;

    public MainWindow()
    {
        InitializeComponent();
        if (!string.IsNullOrWhiteSpace(_settings.DemoCheckAt))
        {
            DemoTimeBox.Text = _settings.DemoCheckAt;
        }
        _timer.Tick += async (_, _) => await OnScheduleTickAsync();
    }

    /// <summary>True when this device already has stored registration credentials.</summary>
    public bool IsRegistered => DpapiCredentialStore.CredentialsExist();

    public void AttachTray(WinForms.NotifyIcon trayIcon) => _trayIcon = trayIcon;

    /// <summary>Connects and starts scheduling. Called once at startup (shown or background).</summary>
    public async Task InitializeAsync() => await TryConnectAsync();

    /// <summary>Tray "Verify now": the click is the consent.</summary>
    public async Task VerifyNowFromTrayAsync() => await RunCheckAsync(CheckType.Periodic);

    /// <summary>Lets App close the window for real (bypass hide-to-tray) on Exit.</summary>
    public void PrepareExit() => _exiting = true;

    protected override void OnClosing(CancelEventArgs e)
    {
        // Closing the window hides the agent to the tray; it keeps verifying.
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
            // The installer has run since this system registered. To the administrator
            // a reinstall is a new system: it comes back as Unassigned and is assigned
            // and enrolled again, so the stored identity is dropped here.
            Log("The agent was reinstalled — registering this system again.");
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
            // configure this machine. Nothing the employee can type fixes that.
            Log("This system is not set up for EmpVerify (no provisioning token found). Contact your administrator.");
            SetStatus("Not set up — contact your administrator.");
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

        await ActivateAsync();
    }

    /// <summary>Registered but not yet linked to an employee: poll until an admin assigns it.</summary>
    private void EnterAwaitingAssignment()
    {
        _awaitingAssignment = true;
        SetStatus("Registered — waiting for an administrator to assign this system.");
        Log("Waiting for an administrator to assign this system to an employee...");
        _trayIcon?.ShowBalloonTip(4000, "EmpVerify Agent",
            "Registered. Waiting for an administrator to assign this system.", WinForms.ToolTipIcon.Info);
        _timer.Start();
    }

    /// <summary>Assigned and active: load the policy, plan the day, and run the session-start scan.</summary>
    private async Task ActivateAsync()
    {
        try
        {
            _policy = await _api!.GetPolicyAsync(CancellationToken.None);
            _flow = new VerificationFlow(_api, _camera, _notifications, _browser, _workstation);

            // Agent is active: make sure it starts at login (no-op when MSIX-packaged).
            StartupManager.EnsureEnabled();

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

            await SetCheckButtonsEnabled(true);
            DemoScheduleButton.IsEnabled = true;
            DemoClearButton.IsEnabled = true;
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

        // First check of the session ("opening the laptop"): a full face scan. It is
        // required, so it keeps reopening until a scan completes (see RunSessionStartAsync).
        if (_policy is { LivenessOnSessionStart: true } && !_sessionStartDone)
        {
            await RunSessionStartAsync();
        }
    }

    /// <summary>
    /// The required session-start scan. If the employee closes the window, cancels,
    /// or lets it time out, it reopens after RetryMinutes — and keeps doing so until
    /// a scan completes. A closed window never silently satisfies the requirement.
    /// </summary>
    private async Task RunSessionStartAsync()
    {
        _sessionStartRetryAt = null;
        _sessionStartAttempts++;
        Log(_sessionStartAttempts == 1
            ? "Session start: full face scan required."
            : $"Session start: full face scan still required (attempt {_sessionStartAttempts}).");
        await RunCheckAsync(CheckType.SessionStart);
        if (_sessionStartDone)
        {
            return;
        }
        var retryAt = DateTimeOffset.Now.AddMinutes(Math.Max(1, _settings.RetryMinutes));
        _sessionStartRetryAt = retryAt;
        Log("Session-start scan not completed — it reopens shortly. Click 'Liveness check' to do it now.");
        SetStatus("Face scan required — it reopens shortly.");
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
            Log($"Could not read enrollment state: {ex.Message}");
        }
        return _deviceState is { Enrolled: false } ? CheckType.Enrollment : CheckType.Periodic;
    }

    private static string Label(CheckType type) => type switch
    {
        CheckType.Enrollment => "Enrollment scan",
        CheckType.SessionStart => "Session-start scan",
        CheckType.Periodic => "Daily check",
        CheckType.Liveness => "Liveness check",
        _ => $"{type} check",
    };

    /// <summary>Refreshes the short-lived device token before a check so a long-running agent never 403s.</summary>
    private async Task EnsureAuthenticatedAsync(CancellationToken ct)
    {
        if (_api is not null && _credentials is not null)
        {
            await _api.AuthenticateAsync(_credentials, ct);
        }
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
                Log($"Assignment check failed: {ex.Message}");
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

        // A missed session-start scan is required: reopen it when its retry time arrives.
        if (!_sessionStartDone && _sessionStartRetryAt is { } retryAt && now >= retryAt)
        {
            await RunSessionStartAsync();
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

        // Demo: a one-shot check armed from the UI (or DemoCheckAt in settings).
        if (_demoScheduledAt is { } at && now >= at)
        {
            _demoScheduledAt = null;
            DemoStatusText.Text = "";
            _checkRetriesLeft = _settings.MissedCheckRetries;
            await RunScheduledCheckAsync(demo: true);
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
        var type = await ResolveScheduledCheckTypeAsync();
        if (await TryRecordUnavailableMissAsync(type, demo))
        {
            return;
        }
        await RunCheckAsync(type, scheduled: true, demo: demo);
    }

    /// <summary>
    /// If the workstation is locked or shutting down, records the scheduled check as
    /// a miss with that reason (open-then-cancel, so it shows under Missed with the
    /// state explained) and schedules the retry, without opening a doomed scan
    /// window. Returns true when it handled the miss; false to run the check normally.
    /// </summary>
    private async Task<bool> TryRecordUnavailableMissAsync(CheckType type, bool demo)
    {
        string? reason = _workstation.Current switch
        {
            WorkstationState.Locked => "locked",
            WorkstationState.ShuttingDown => "shutdown",
            _ => null,
        };
        if (reason is null)
        {
            return false;
        }
        try
        {
            await EnsureAuthenticatedAsync(CancellationToken.None);
            var check = await _api!.OpenCheckAsync(type, CancellationToken.None);
            await _api.CancelCheckAsync(check.CheckId, reason, CancellationToken.None);
            Log($"{Label(type)} not shown — {StateDescription(_workstation.Current)}. Recorded for the administrator.");
            SetStatus($"Last check: missed ({reason}) at {DateTime.Now:HH:mm}.");
        }
        catch (Exception ex)
        {
            Log($"{Label(type)}: could not record the '{reason}' state: {ex.Message}");
        }
        ScheduleCheckRetry(type, demo);
        return true;
    }

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
            Log($"Could not refresh device state: {ex.Message}");
            return false;
        }

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
        _demoScheduledAt = null;
        _sessionStartRetryAt = null;
        _ = SetCheckButtonsEnabled(false);
        DemoScheduleButton.IsEnabled = false;
        DemoClearButton.IsEnabled = false;
    }

    private async void VerifyNow_Click(object sender, RoutedEventArgs e)
    {
        await RunCheckAsync(await ResolveScheduledCheckTypeAsync());
    }

    private void DemoSchedule_Click(object sender, RoutedEventArgs e)
    {
        if (!TimeOnly.TryParse(DemoTimeBox.Text.Trim(), out var at))
        {
            DemoStatusText.Text = "Enter a time as HH:mm (e.g. 14:30).";
            return;
        }
        var now = DateTimeOffset.Now;
        var target = new DateTimeOffset(now.Year, now.Month, now.Day, at.Hour, at.Minute, 0, now.Offset);
        if (target <= now)
        {
            target = target.AddDays(1); // a past time today means the same time tomorrow
        }
        _demoScheduledAt = target;
        _settings.DemoCheckAt = DemoTimeBox.Text.Trim();
        _settings.Save();
        DemoStatusText.Text = $"Check scheduled for {target:ddd HH:mm}.";
        Log($"Demo: check scheduled for {target:ddd HH:mm}.");
    }

    private void DemoClear_Click(object sender, RoutedEventArgs e)
    {
        _demoScheduledAt = null;
        DemoStatusText.Text = "Demo check cleared.";
        Log("Demo: scheduled check cleared.");
    }

    // ---- checks -------------------------------------------------------------------

    /// <summary>"Liveness check" button: an on-demand face scan; the click is the consent.</summary>
    private async void Liveness_Click(object sender, RoutedEventArgs e)
    {
        await RunCheckAsync(CheckType.Liveness, forceLiveness: true);
    }

    /// <summary>
    /// One interactive check in the agent's own browser window. By default this is
    /// the AWS Face Liveness scan (a live person, then compared to the enrolled
    /// reference); the photo capture is the opt-in fallback. The token is refreshed
    /// first so the agent never 403s mid-session. A check that ends without a scan
    /// (window closed, cancelled, timed out) is logged with why, and a scheduled
    /// one is offered again; any completed scan also satisfies the session-start
    /// requirement.
    /// </summary>
    private async Task<FlowResult?> RunCheckAsync(
        CheckType type, bool scheduled = false, bool forceLiveness = false, bool demo = false)
    {
        if (_flow is null || _policy is null || _checkRunning)
        {
            return null;
        }
        _checkRunning = true;
        await SetCheckButtonsEnabled(false);
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
                result = await _flow.RunLivenessCheckAsync(type, window, CancellationToken.None);
            }
            else
            {
                Log($"{Label(type)}: opening the photo capture prompt...");
                result = await _flow.RunBrowserCaptureCheckAsync(type, window, CancellationToken.None);
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
                if (!_sessionStartDone
                    && (type == CheckType.SessionStart || result.Result.LivenessConfidence is not null))
                {
                    _sessionStartDone = true;
                    _sessionStartRetryAt = null;
                }
            }
            else if (scheduled)
            {
                ScheduleCheckRetry(type, demo);
            }
        }
        catch (Exception ex)
        {
            Log($"{type} check error: {ex.Message}");
        }
        finally
        {
            _checkRunning = false;
            await SetCheckButtonsEnabled(true);
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

    private async Task SetCheckButtonsEnabled(bool enabled)
    {
        await Dispatcher.InvokeAsync(() =>
        {
            VerifyNowButton.IsEnabled = enabled;
            LivenessButton.IsEnabled = enabled;
        });
    }

    private AgentApiClient CreateApi() =>
        new(new HttpClient { BaseAddress = new Uri(_settings.BackendUrl) });

    private void SetStatus(string status)
    {
        StatusText.Text = status;
        if (_trayIcon is not null)
        {
            // NotifyIcon.Text is capped at 63 characters.
            var text = $"EmpVerify — {status}";
            _trayIcon.Text = text.Length > 63 ? text[..63] : text;
        }
    }

    private void Log(string message)
    {
        ActivityList.Items.Insert(0, $"{DateTime.Now:HH:mm:ss}  {message}");
    }
}
