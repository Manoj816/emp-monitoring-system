using System.Net.Http;
using System.Windows;
using System.Windows.Threading;
using EmpVerify.Agent.Core;
using EmpVerify.Agent.Core.Api;
using EmpVerify.Agent.Core.Scheduling;
using EmpVerify.Agent.Windows.Services;

namespace EmpVerify.Agent.Windows;

/// <summary>
/// Phase 1 agent shell: register once with an enrollment token, then run
/// scheduled randomized checks (visible prompt + capture) and a manual
/// "Verify now". Tray icon + background start come with the MSIX packaging work.
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

    public MainWindow()
    {
        InitializeComponent();
        BackendUrlBox.Text = _settings.BackendUrl;
        Loaded += async (_, _) => await TryConnectAsync();
        _timer.Tick += async (_, _) => await OnScheduleTickAsync();
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

            _plan = _scheduler.PlanDay(_policy, DateTimeOffset.Now);
            _lastHandled = DateTimeOffset.Now;
            _timer.Start();

            VerifyNowButton.IsEnabled = true;
            StatusText.Text = "Connected — scheduled checks active.";
            Log($"Connected. Policy: window {_policy.WorkWindowStart}-{_policy.WorkWindowEnd}, " +
                $"{_policy.MaxChecksPerDay} checks/day, {_policy.ResponseTimeoutSeconds}s response window.");
            var next = _scheduler.NextCheck(_plan, DateTimeOffset.Now);
            Log(next is null ? "No more checks planned today." : $"Next scheduled check: {next:HH:mm}.");
        }
        catch (Exception ex)
        {
            Log($"Connection failed: {ex.Message}");
            StatusText.Text = "Connection failed.";
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
        // Employee-initiated: the click is the consent, no extra prompt needed.
        await RunCheckAsync(CheckType.SessionStart, promptFirst: false);
    }

    private async Task RunCheckAsync(CheckType type, bool promptFirst)
    {
        if (_flow is null || _policy is null || _checkRunning)
        {
            return;
        }
        _checkRunning = true;
        VerifyNowButton.IsEnabled = false;
        try
        {
            var result = await _flow.RunCheckAsync(
                type, TimeSpan.FromSeconds(_policy.ResponseTimeoutSeconds), promptFirst,
                CancellationToken.None);
            Log(result.Outcome switch
            {
                FlowOutcome.Completed =>
                    $"{type} check: {result.Result!.Status}" +
                    (result.Result.SimilarityScore is { } s ? $" (similarity {s:F1})" : ""),
                FlowOutcome.NotAccepted => $"{type} check missed (no response within the window).",
                FlowOutcome.CameraBusy => $"{type} check deferred — camera in use by another app.",
                _ => $"{type} check failed — no camera available or access denied.",
            });
        }
        catch (Exception ex)
        {
            Log($"{type} check error: {ex.Message}");
        }
        finally
        {
            _checkRunning = false;
            VerifyNowButton.IsEnabled = true;
        }
    }

    private AgentApiClient CreateApi() =>
        new(new HttpClient { BaseAddress = new Uri(_settings.BackendUrl) });

    private void Log(string message)
    {
        ActivityList.Items.Insert(0, $"{DateTime.Now:HH:mm:ss}  {message}");
    }
}
