using System.ComponentModel;
using System.Net.Http;
using System.Windows;
using System.Windows.Threading;
using EmpVerify.Agent.Core;
using EmpVerify.Agent.Core.Api;
using EmpVerify.Agent.Core.Scheduling;
using EmpVerify.Agent.Windows.Services;
using WinForms = System.Windows.Forms;

namespace EmpVerify.Agent.Windows;

/// <summary>
/// Agent window: register with an enrollment token, then run scheduled randomized
/// checks and a manual "Verify now". The app runs as a background tray agent, so
/// closing this window hides it to the tray rather than exiting; checks continue.
/// </summary>
public partial class MainWindow : Window
{
    private readonly AgentSettings _settings = AgentSettings.Load();
    private readonly DpapiCredentialStore _credentialStore = new();
    private readonly WindowsCameraService _camera = new();
    private readonly WpfPromptNotificationService _notifications = new();
    private readonly CheckScheduler _scheduler = new();
    private readonly DispatcherTimer _timer = new() { Interval = TimeSpan.FromSeconds(30) };

    private AgentApiClient? _api;
    private VerificationFlow? _flow;
    private CheckPolicy? _policy;
    private IReadOnlyList<DateTimeOffset> _plan = Array.Empty<DateTimeOffset>();
    private DateTimeOffset _lastHandled = DateTimeOffset.MinValue;
    private bool _checkRunning;
    private bool _exiting;
    private WinForms.NotifyIcon? _trayIcon;

    public MainWindow()
    {
        InitializeComponent();
        BackendUrlBox.Text = _settings.BackendUrl;
        _timer.Tick += async (_, _) => await OnScheduleTickAsync();
    }

    /// <summary>True when this device already has stored registration credentials.</summary>
    public bool IsRegistered => DpapiCredentialStore.CredentialsExist();

    public void AttachTray(WinForms.NotifyIcon trayIcon) => _trayIcon = trayIcon;

    /// <summary>Connects and starts scheduling. Called once at startup (shown or background).</summary>
    public async Task InitializeAsync() => await TryConnectAsync();

    /// <summary>Tray "Verify now": the click is the consent, so no extra prompt.</summary>
    public async Task VerifyNowFromTrayAsync() => await RunCheckAsync(CheckType.SessionStart, promptFirst: false);

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
            Log("No device registration found — enter an enrollment token to register.");
            return;
        }
        await ConnectAsync(credentials);
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
            _api = CreateApi();
            await _api.AuthenticateAsync(credentials, CancellationToken.None);
            _policy = await _api.GetPolicyAsync(CancellationToken.None);
            _flow = new VerificationFlow(_api, _camera, _notifications);

            // Agent is active: make sure it starts at login (no-op when MSIX-packaged).
            StartupManager.EnsureEnabled();

            _plan = _scheduler.PlanDay(_policy, DateTimeOffset.Now);
            _lastHandled = DateTimeOffset.Now;
            _timer.Start();

            VerifyNowButton.IsEnabled = true;
            SetStatus("Connected — scheduled checks active.");
            Log($"Connected. Policy: window {_policy.WorkWindowStart}-{_policy.WorkWindowEnd}, " +
                $"{_policy.MaxChecksPerDay} checks/day, {_policy.ResponseTimeoutSeconds}s response window.");
            var next = _scheduler.NextCheck(_plan, DateTimeOffset.Now);
            Log(next is null ? "No more checks planned today." : $"Next scheduled check: {next:HH:mm}.");
        }
        catch (Exception ex)
        {
            Log($"Connection failed: {ex.Message}");
            SetStatus("Connection failed.");
        }
    }

    private async Task OnScheduleTickAsync()
    {
        if (_flow is null || _policy is null || _checkRunning)
        {
            return;
        }
        var now = DateTimeOffset.Now;
        var due = _plan.Where(t => t > _lastHandled && t <= now).ToList();
        if (due.Count == 0)
        {
            return;
        }
        _lastHandled = now;
        await RunCheckAsync(CheckType.Periodic, promptFirst: true);
    }

    private async void VerifyNow_Click(object sender, RoutedEventArgs e)
    {
        await RunCheckAsync(CheckType.SessionStart, promptFirst: false);
    }

    private async Task RunCheckAsync(CheckType type, bool promptFirst)
    {
        if (_flow is null || _policy is null || _checkRunning)
        {
            return;
        }
        _checkRunning = true;
        await Dispatcher.InvokeAsync(() => VerifyNowButton.IsEnabled = false);
        try
        {
            var result = await _flow.RunCheckAsync(
                type, TimeSpan.FromSeconds(_policy.ResponseTimeoutSeconds), promptFirst,
                CancellationToken.None);
            var message = result.Outcome switch
            {
                FlowOutcome.Completed =>
                    $"{type} check: {result.Result!.Status}" +
                    (result.Result.SimilarityScore is { } s ? $" (similarity {s:F1})" : ""),
                FlowOutcome.NotAccepted => $"{type} check missed (no response within the window).",
                FlowOutcome.CameraBusy => $"{type} check deferred — camera in use by another app.",
                _ => $"{type} check failed — no camera available or access denied.",
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
            await Dispatcher.InvokeAsync(() => VerifyNowButton.IsEnabled = true);
        }
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
