using EmpVerify.Agent.Core.Abstractions;
using EmpVerify.Agent.Core.Api;

namespace EmpVerify.Agent.Core;

public enum FlowOutcome
{
    /// <summary>Check uploaded; see <see cref="FlowResult.Result"/> for the server's verdict.</summary>
    Completed,
    /// <summary>Employee declined or did not respond within the window (recorded as a missed check).</summary>
    NotAccepted,
    /// <summary>Another app holds the camera; the check is deferred, not failed.</summary>
    CameraBusy,
    /// <summary>No camera, or camera permission denied in OS privacy settings.</summary>
    CameraUnavailable,
}

public sealed record FlowResult(FlowOutcome Outcome, CheckResult? Result);

/// <summary>
/// One passive verification check, end to end: visible prompt -> capture ->
/// upload -> server verdict. Platform-neutral; the heads supply camera and
/// prompt implementations. The server is the sole authority on the outcome.
/// </summary>
public sealed class VerificationFlow
{
    private readonly IAgentApi _api;
    private readonly ICameraService _camera;
    private readonly INotificationService _notifications;
    private readonly IBrowserLauncher? _browser;

    public VerificationFlow(
        IAgentApi api, ICameraService camera, INotificationService notifications,
        IBrowserLauncher? browser = null)
    {
        _api = api;
        _camera = camera;
        _notifications = notifications;
        _browser = browser;
    }

    /// <param name="promptFirst">
    /// False only for employee-initiated checks (e.g. a "Verify now" button),
    /// where the click itself is the consent. Scheduled checks always prompt.
    /// </param>
    public async Task<FlowResult> RunCheckAsync(
        CheckType type, TimeSpan responseWindow, bool promptFirst, CancellationToken ct)
    {
        if (promptFirst && !await _notifications.PromptForCheckAsync(responseWindow, ct))
        {
            return new FlowResult(FlowOutcome.NotAccepted, null);
        }

        if (await _camera.IsCameraBusyAsync(ct))
        {
            return new FlowResult(FlowOutcome.CameraBusy, null);
        }

        var frame = await _camera.CaptureFrameAsync(ct);
        if (frame is null)
        {
            return new FlowResult(FlowOutcome.CameraUnavailable, null);
        }

        var check = await _api.OpenCheckAsync(type, ct);
        var result = await _api.UploadCaptureAsync(check, frame, ct);
        return new FlowResult(FlowOutcome.Completed, result);
    }

    /// <summary>
    /// Interactive capture check: opens a check, launches the browser capture
    /// page, and polls for the server's verdict until it resolves or the window
    /// elapses. The employee takes the photo in the browser (the click is the
    /// consent) — the agent never captures silently and never computes the result.
    /// </summary>
    public async Task<FlowResult> RunBrowserCaptureCheckAsync(
        CheckType type, TimeSpan responseWindow, CancellationToken ct,
        Func<TimeSpan, CancellationToken, Task>? poll = null)
    {
        if (_browser is null)
        {
            throw new InvalidOperationException("Interactive capture requires a browser launcher.");
        }
        poll ??= Task.Delay;

        var check = await _api.OpenCheckAsync(type, ct);
        var session = await _api.CreateCaptureSessionAsync(check.CheckId, ct);
        // Disposed on every exit path, so the window closes as soon as the check is over.
        using var window = _browser.Open(session.CaptureUrl);

        var deadline = DateTimeOffset.UtcNow + responseWindow;
        while (DateTimeOffset.UtcNow < deadline)
        {
            var result = await _api.GetResultAsync(check.CheckId, ct);
            if (result.Status != CheckStatus.Pending)
            {
                await poll(ResultDisplayTime, ct); // let the employee read the result first
                return new FlowResult(FlowOutcome.Completed, result);
            }
            await poll(TimeSpan.FromSeconds(2), ct);
        }
        return new FlowResult(FlowOutcome.NotAccepted, null);
    }

    /// <summary>How long the result stays on screen before the check window closes.</summary>
    public static readonly TimeSpan ResultDisplayTime = TimeSpan.FromSeconds(2);

    /// <summary>
    /// Interactive liveness check: opens a liveness check, launches the hosted
    /// challenge in the browser, and polls for the server's verdict until it
    /// resolves or the window elapses. The agent never computes the result.
    /// </summary>
    public async Task<FlowResult> RunLivenessCheckAsync(
        TimeSpan responseWindow, CancellationToken ct,
        Func<TimeSpan, CancellationToken, Task>? poll = null)
    {
        if (_browser is null)
        {
            throw new InvalidOperationException("Liveness requires a browser launcher.");
        }
        poll ??= Task.Delay;

        var check = await _api.OpenCheckAsync(CheckType.Liveness, ct);
        var session = await _api.CreateLivenessSessionAsync(check.CheckId, ct);
        using var window = _browser.Open(session.LivenessUrl);

        var deadline = DateTimeOffset.UtcNow + responseWindow;
        while (DateTimeOffset.UtcNow < deadline)
        {
            var result = await _api.GetResultAsync(check.CheckId, ct);
            if (result.Status != CheckStatus.Pending)
            {
                await poll(ResultDisplayTime, ct);
                return new FlowResult(FlowOutcome.Completed, result);
            }
            await poll(TimeSpan.FromSeconds(2), ct);
        }
        // Not completed in the browser within the window — recorded as not accepted.
        return new FlowResult(FlowOutcome.NotAccepted, null);
    }
}
