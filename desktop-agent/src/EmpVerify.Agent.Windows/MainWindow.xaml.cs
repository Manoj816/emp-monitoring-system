using System.ComponentModel;
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
/// Agent window: registers (manually or from a deploy-time provisioning token),
/// then runs one randomized interactive check per day plus on-demand/demo checks.
/// Every check opens a browser capture prompt — nothing is captured silently.
/// Closing the window hides to the tray; checks keep running.
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
    private bool _checkRunning;
    private bool _sessionStartDone;
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

    private async Task TryConnectAsync()
    {
        var credentials = await _credentialStore.LoadAsync(CancellationToken.None);
        if (credentials is null)
        {
            // Auto-register from a deploy-time provisioning token, if one was supplied.
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
            Log("Provisioning token found — registering this device automatically...");
            var api = CreateApi();
            var credentials = await api.RegisterAsync(
                provisioning.EnrollmentToken, "WINDOWS", Environment.MachineName,
                typeof(MainWindow).Assembly.GetName().Version?.ToString(), CancellationToken.None);
            await _credentialStore.SaveAsync(credentials, CancellationToken.None);
            ProvisioningReader.Clear();
            Log($"Device auto-registered: {credentials.DeviceId}");
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

    private async Task ConnectAsync(DeviceCredentials credentials)
    {
        try
        {
            _credentials = credentials;
            _api = CreateApi();
            await _api.AuthenticateAsync(credentials, CancellationToken.None);
            _policy = await _api.GetPolicyAsync(CancellationToken.None);
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

        // First check of the session ("opening the laptop"): a full face scan, once per launch.
        if (_policy is { LivenessOnSessionStart: true } && !_sessionStartDone)
        {
            _sessionStartDone = true;
            Log("Session start: full face scan required.");
            await RunCheckAsync(CheckType.SessionStart);
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

    private async Task OnScheduleTickAsync()
    {
        if (_flow is null || _policy is null || _checkRunning)
        {
            return;
        }
        var now = DateTimeOffset.Now;

        // Demo: a fixed interval overrides the daily plan.
        if (_settings.DemoIntervalMinutes > 0)
        {
            if (now - _lastDemoCheck >= TimeSpan.FromMinutes(_settings.DemoIntervalMinutes))
            {
                _lastDemoCheck = now;
                await RunCheckAsync(CheckType.Periodic);
            }
            return;
        }

        // Demo: a one-shot check armed from the UI (or DemoCheckAt in settings).
        if (_demoScheduledAt is { } at && now >= at)
        {
            _demoScheduledAt = null;
            DemoStatusText.Text = "";
            await RunCheckAsync(CheckType.Periodic);
            return;
        }

        // Normal: the randomized daily plan (one check/day by policy).
        var due = _plan.Where(t => t > _lastHandled && t <= now).ToList();
        if (due.Count == 0)
        {
            return;
        }
        _lastHandled = now;
        await RunCheckAsync(CheckType.Periodic);
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

    private async void Liveness_Click(object sender, RoutedEventArgs e)
    {
        if (_flow is null || _policy is null || _checkRunning)
        {
            return;
        }
        _checkRunning = true;
        await SetCheckButtonsEnabled(false);
        try
        {
            await EnsureAuthenticatedAsync(CancellationToken.None);
            Log("Liveness check: opening the browser challenge...");
            var result = await _flow.RunLivenessCheckAsync(
                TimeSpan.FromSeconds(_policy.ResponseTimeoutSeconds), CancellationToken.None);
            if (result.Outcome == FlowOutcome.Completed)
            {
                Log($"Liveness check: {result.Result!.Status}" +
                    (result.Result.LivenessConfidence is { } c ? $" (liveness {c:F1})" : ""));
                SetStatus($"Last liveness: {result.Result.Status} at {DateTime.Now:HH:mm}.");
            }
            else
            {
                Log("Liveness check not completed within the window.");
            }
        }
        catch (Exception ex)
        {
            Log($"Liveness check error: {ex.Message}");
        }
        finally
        {
            _checkRunning = false;
            await SetCheckButtonsEnabled(true);
        }
    }

    /// <summary>
    /// One interactive check: opens the browser capture prompt, the employee takes
    /// the photo there (the click is the consent), and the server returns the
    /// verdict. No silent capture; the token is refreshed first so the agent never
    /// 403s mid-session.
    /// </summary>
    private async Task RunCheckAsync(CheckType type)
    {
        if (_flow is null || _policy is null || _checkRunning)
        {
            return;
        }
        _checkRunning = true;
        await SetCheckButtonsEnabled(false);
        try
        {
            await EnsureAuthenticatedAsync(CancellationToken.None);
            var window = TimeSpan.FromSeconds(_policy.ResponseTimeoutSeconds);
            FlowResult result;
            if (_settings.UseLivenessForChecks)
            {
                // Face Liveness: the guided full-face scan proves a live person, then the
                // frame it captures is compared to the enrolled reference.
                Log($"{type} check: opening the face scan...");
                result = await _flow.RunLivenessCheckAsync(window, CancellationToken.None);
            }
            else
            {
                Log($"{type} check: opening the photo capture prompt...");
                result = await _flow.RunBrowserCaptureCheckAsync(type, window, CancellationToken.None);
            }
            var message = result.Outcome switch
            {
                FlowOutcome.Completed =>
                    $"{type} check: {result.Result!.Status}" +
                    (result.Result.SimilarityScore is { } s ? $" (similarity {s:F1})" : "") +
                    (result.Result.LivenessConfidence is { } c ? $" (liveness {c:F1})" : ""),
                FlowOutcome.NotAccepted => $"{type} check missed (not completed within the window).",
                _ => $"{type} check failed.",
            };
            Log(message);
            if (result.Outcome == FlowOutcome.Completed)
            {
                SetStatus($"Last check: {result.Result!.Status} at {DateTime.Now:HH:mm}.");
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
    }

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
