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
    private int _checkRetriesLeft;
    private bool _exiting;
    private WinForms.NotifyIcon? _trayIcon;

    public MainWindow()
    {
        InitializeComponent();
        BackendUrlBox.Text = _settings.BackendUrl;
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
            Log("No device registration found — enter an enrollment token to register.");
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
                BackendUrlBox.Text = _settings.BackendUrl;
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
            ProvisioningReader.Clear();
            return credentials;
        }
        catch (Exception ex)
        {
            Log($"Auto-registration failed: {ex.Message}");
            return null;
        }
    }

    private async void Register_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            RegisterButton.IsEnabled = false;
            _settings.BackendUrl = BackendUrlBox.Text.Trim();
            _settings.Save();

            var api = CreateApi();
            var credentials = await api.RegisterAsync(
                EnrollmentTokenBox.Text.Trim(), "WINDOWS", Environment.MachineName,
                typeof(MainWindow).Assembly.GetName().Version?.ToString(), CancellationToken.None);
            await _credentialStore.SaveAsync(credentials, CancellationToken.None);
            EnrollmentTokenBox.Clear();
            Log($"Device registered: {credentials.DeviceId}");
            await ConnectAsync(credentials);
        }
        catch (Exception ex)
        {
            Log($"Registration failed: {ex.Message}");
        }
        finally
        {
            RegisterButton.IsEnabled = true;
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
        _timer.Start();
    }

    /// <summary>Assigned and active: load the policy, plan the day, and run the session-start scan.</summary>
    private async Task ActivateAsync()
    {
        try
        {
            _policy = await _api!.GetPolicyAsync(CancellationToken.None);
            _flow = new VerificationFlow(_api, _camera, _notifications, _browser);

            // Agent is active: make sure it starts at login (no-op when MSIX-packaged).
            StartupManager.EnsureEnabled();

            _plan = _scheduler.PlanDay(_policy, DateTimeOffset.Now);
            _lastHandled = DateTimeOffset.Now;
            _timer.Start();

            await SetCheckButtonsEnabled(true);
            DemoScheduleButton.IsEnabled = true;
            DemoClearButton.IsEnabled = true;
            SetStatus("Connected — scheduled checks active.");
            Log($"Connected. Policy: window {_policy.WorkWindowStart}-{_policy.WorkWindowEnd}, " +
                $"{_policy.MaxChecksPerDay} checks/day, {_policy.ResponseTimeoutSeconds}s response window.");
            if (_settings.DemoIntervalMinutes > 0)
            {
                Log($"Demo mode: a check every {_settings.DemoIntervalMinutes} min.");
            }
            var next = _scheduler.NextCheck(_plan, DateTimeOffset.Now);
            Log(next is null ? "No more checks planned today." : $"Next scheduled check: {next:HH:mm}.");
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
        Log($"Session-start scan not completed — it reopens at {retryAt:HH:mm}. Click 'Liveness check' to do it now.");
        SetStatus($"Face scan required — reopens at {retryAt:HH:mm}.");
    }

    /// <summary>A scheduled check that ended without a scan is offered again, a bounded number of times.</summary>
    private void ScheduleCheckRetry(CheckType type)
    {
        if (_checkRetriesLeft <= 0)
        {
            Log($"{type} check recorded as missed.");
            return;
        }
        _checkRetriesLeft--;
        var retryAt = DateTimeOffset.Now.AddMinutes(Math.Max(1, _settings.RetryMinutes));
        _checkRetryAt = retryAt;
        Log($"{type} check will be offered again at {retryAt:HH:mm}.");
    }

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

        // A missed session-start scan is required: reopen it when its retry time arrives.
        if (!_sessionStartDone && _sessionStartRetryAt is { } retryAt && now >= retryAt)
        {
            await RunSessionStartAsync();
            return;
        }

        // A missed scheduled check is offered again (bounded by MissedCheckRetries).
        if (_checkRetryAt is { } again && now >= again)
        {
            _checkRetryAt = null;
            await RunCheckAsync(CheckType.Periodic, scheduled: true);
            return;
        }

        // Demo: a fixed interval overrides the daily plan.
        if (_settings.DemoIntervalMinutes > 0)
        {
            if (now - _lastDemoCheck >= TimeSpan.FromMinutes(_settings.DemoIntervalMinutes))
            {
                _lastDemoCheck = now;
                _checkRetriesLeft = _settings.MissedCheckRetries;
                await RunCheckAsync(CheckType.Periodic, scheduled: true);
            }
            return;
        }

        // Demo: a one-shot check armed from the UI (or DemoCheckAt in settings).
        if (_demoScheduledAt is { } at && now >= at)
        {
            _demoScheduledAt = null;
            DemoStatusText.Text = "";
            _checkRetriesLeft = _settings.MissedCheckRetries;
            await RunCheckAsync(CheckType.Periodic, scheduled: true);
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
        await RunCheckAsync(CheckType.Periodic, scheduled: true);
    }

    private async void VerifyNow_Click(object sender, RoutedEventArgs e)
    {
        await RunCheckAsync(CheckType.Periodic);
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
        CheckType type, bool scheduled = false, bool forceLiveness = false)
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
                Log($"{type} check: opening the face scan...");
                result = await _flow.RunLivenessCheckAsync(type, window, CancellationToken.None);
            }
            else
            {
                Log($"{type} check: opening the photo capture prompt...");
                result = await _flow.RunBrowserCaptureCheckAsync(type, window, CancellationToken.None);
            }
            Log(Describe(type, result));
            if (result.HasVerdict)
            {
                SetStatus($"Last check: {result.Result!.Status} at {DateTime.Now:HH:mm}.");
                if (!_sessionStartDone
                    && (type == CheckType.SessionStart || result.Result.LivenessConfidence is not null))
                {
                    _sessionStartDone = true;
                    _sessionStartRetryAt = null;
                }
            }
            else if (scheduled)
            {
                ScheduleCheckRetry(type);
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
            $"{type} check: {result.Result!.Status}" +
            (result.Result.SimilarityScore is { } s ? $" (similarity {s:F1})" : "") +
            (result.Result.LivenessConfidence is { } c ? $" (liveness {c:F1})" : ""),
        FlowOutcome.Completed => $"{type} check expired on the server before a scan was done.",
        FlowOutcome.WindowClosed => $"{type} check: the scan window was closed before the scan finished.",
        FlowOutcome.Cancelled => $"{type} check: the scan was cancelled or could not run.",
        FlowOutcome.NotAccepted => $"{type} check missed (not completed within the response window).",
        _ => $"{type} check failed.",
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
