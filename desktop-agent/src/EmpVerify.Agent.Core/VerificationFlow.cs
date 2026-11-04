using EmpVerify.Agent.Core.Abstractions;
using EmpVerify.Agent.Core.Api;

namespace EmpVerify.Agent.Core;

public enum FlowOutcome
{
    /// <summary>Check uploaded; see <see cref="FlowResult.Result"/> for the server's verdict.</summary>
    Completed,
    /// <summary>Employee declined or did not respond within the window (recorded as a missed check).</summary>
    NotAccepted,
    /// <summary>Employee closed the check window before the scan finished (recorded as missed; the agent may retry).</summary>
    WindowClosed,
    /// <summary>Employee cancelled inside the page, or the scan could not run; the server ended the check as EXPIRED.</summary>
    Cancelled,
    /// <summary>Another app holds the camera; the check is deferred, not failed.</summary>
    CameraBusy,
    /// <summary>No camera, or camera permission denied in OS privacy settings.</summary>
    CameraUnavailable,
}

public sealed record FlowResult(FlowOutcome Outcome, CheckResult? Result)
{
    /// <summary>True when the server reached a verdict (anything other than a missed/cancelled check).</summary>
    public bool HasVerdict => Outcome == FlowOutcome.Completed && Result is { Status: not CheckStatus.Expired };
}

/// <summary>
/// One verification check, end to end: visible prompt -> capture -> upload ->
/// server verdict. Platform-neutral; the heads supply camera, prompt and window
/// implementations. The server is the sole authority on the outcome.
/// </summary>
public sealed class VerificationFlow
{
    private readonly IAgentApi _api;
    private readonly ICameraService _camera;
    private readonly INotificationService _notifications;
    private readonly IBrowserLauncher? _browser;
    private readonly IWorkstationMonitor? _workstation;

    public VerificationFlow(
        IAgentApi api, ICameraService camera, INotificationService notifications,
        IBrowserLauncher? browser = null, IWorkstationMonitor? workstation = null)
    {
        _api = api;
        _camera = camera;
        _notifications = notifications;
        _browser = browser;
        _workstation = workstation;
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
    /// Interactive capture check: opens a check, launches the capture page in the
    /// agent's window, and waits for the server's verdict. The employee takes the
    /// photo in the page (the click is the consent) — the agent never captures
    /// silently and never computes the result.
    /// </summary>
    public async Task<FlowResult> RunBrowserCaptureCheckAsync(
        CheckType type, TimeSpan responseWindow, CancellationToken ct,
        Func<TimeSpan, CancellationToken, Task>? poll = null, bool captureOnTimeout = false)
    {
        if (_browser is null)
        {
            throw new InvalidOperationException("Interactive capture requires a browser launcher.");
        }

        var check = await _api.OpenCheckAsync(type, ct);
        var session = await _api.CreateCaptureSessionAsync(check.CheckId, ct);
        // Disposed on every exit path, so the window closes as soon as the check is over.
        var url = captureOnTimeout ? WithTimeoutPhotoNotice(session.CaptureUrl) : session.CaptureUrl;
        using var window = _browser.Open(url);
        return await AwaitVerdictAsync(
            check.CheckId, window, responseWindow, ct, poll ?? Task.Delay, captureOnTimeout);
    }

    /// <summary>How long the result stays on screen before the check window closes.</summary>
    public static readonly TimeSpan ResultDisplayTime = TimeSpan.FromSeconds(2);

    /// <summary>
    /// Interactive liveness check: opens a check of the given type (session start,
    /// periodic, on-demand), launches the hosted Face Liveness challenge in the
    /// agent's window, and waits for the server's verdict. The agent never
    /// computes the result.
    /// </summary>
    public async Task<FlowResult> RunLivenessCheckAsync(
        CheckType type, TimeSpan responseWindow, CancellationToken ct,
        Func<TimeSpan, CancellationToken, Task>? poll = null, bool captureOnTimeout = false)
    {
        if (_browser is null)
        {
            throw new InvalidOperationException("Liveness requires a browser launcher.");
        }

        var check = await _api.OpenCheckAsync(type, ct);
        var session = await _api.CreateLivenessSessionAsync(check.CheckId, ct);
        // The page tells the employee a photo will be taken if nobody answers. The
        // notice travels with the window it applies to, so it cannot be shown on a
        // check that will not take one.
        var url = captureOnTimeout ? WithTimeoutPhotoNotice(session.LivenessUrl) : session.LivenessUrl;
        using var window = _browser.Open(url);
        return await AwaitVerdictAsync(
            check.CheckId, window, responseWindow, ct, poll ?? Task.Delay, captureOnTimeout);
    }

    /// <summary>
    /// Polls the check until the server has an outcome, the employee closes the
    /// window, or the response window elapses. Every way a check can end without
    /// a scan is reported to the server with its reason, so an administrator sees
    /// "missed: window closed" instead of a check that never finishes.
    /// </summary>
    /// <summary>Query flag the check page reads to show the "a photo will be taken" line.</summary>
    public const string TimeoutPhotoParam = "photoOnTimeout=1";

    private static string WithTimeoutPhotoNotice(string url) =>
        url.Contains(TimeoutPhotoParam, StringComparison.Ordinal)
            ? url
            : url + (url.Contains('?') ? "&" : "?") + TimeoutPhotoParam;

    private async Task<FlowResult> AwaitVerdictAsync(
        string checkId, IBrowserSession window, TimeSpan responseWindow,
        CancellationToken ct, Func<TimeSpan, CancellationToken, Task> poll,
        bool captureOnTimeout = false)
    {
        var openedAt = DateTimeOffset.UtcNow;
        var deadline = openedAt + responseWindow;
        while (DateTimeOffset.UtcNow < deadline)
        {
            // The verdict is checked before the window state: a scan that finished
            // just before the employee closed the window still counts.
            var result = await _api.GetResultAsync(checkId, ct);
            if (result.Status == CheckStatus.Expired)
            {
                return new FlowResult(FlowOutcome.Cancelled, result);
            }
            if (result.Status != CheckStatus.Pending)
            {
                if (!window.Closed.IsCompleted)
                {
                    await poll(ResultDisplayTime, ct); // let the employee read the result first
                }
                return new FlowResult(FlowOutcome.Completed, result);
            }
            if (window.Closed.IsCompleted)
            {
                await TryCancelAsync(checkId, MissReason(windowClosed: true, openedAt), ct);
                return new FlowResult(FlowOutcome.WindowClosed, null);
            }
            await poll(TimeSpan.FromSeconds(2), ct);
        }
        if (captureOnTimeout && MissReason(windowClosed: false, openedAt) == "timeout")
        {
            // Only a plain timeout: the screen was open and nobody answered, which is
            // the one case a photo can say something about. A locked or sleeping
            // machine is reported as what it was, with no camera touched.
            await ReportUnansweredAsync(checkId, window, ct, poll);
            return new FlowResult(FlowOutcome.NotAccepted, null);
        }
        await TryCancelAsync(checkId, MissReason(windowClosed: false, openedAt), ct);
        return new FlowResult(FlowOutcome.NotAccepted, null);
    }

    /// <summary>How long to let the check window hand the camera back before using it.</summary>
    public static readonly TimeSpan CameraReleaseTime = TimeSpan.FromSeconds(2);

    /// <summary>
    /// Ends an unanswered check with the frame taken as its window closed. The check
    /// page owns the camera until its window is gone, and closing it is asynchronous,
    /// so the window goes first and the camera is given a moment to come back. A
    /// camera that is still busy, missing or blocked simply yields no frame, and the
    /// miss is recorded exactly as it would have been without the photo.
    /// </summary>
    private async Task ReportUnansweredAsync(
        string checkId, IBrowserSession window, CancellationToken ct,
        Func<TimeSpan, CancellationToken, Task> poll)
    {
        CapturedFrame? frame = null;
        try
        {
            window.Dispose();
            await poll(CameraReleaseTime, ct);
            if (!await _camera.IsCameraBusyAsync(ct))
            {
                frame = await _camera.CaptureFrameAsync(ct);
            }
        }
        catch
        {
            // The photo is a nice-to-have on a check that is already missed; never
            // let it be the reason the miss itself goes unrecorded.
            frame = null;
        }

        try
        {
            await _api.ReportUnansweredAsync(checkId, frame, ct);
        }
        catch
        {
            // ignored: the server's own sweep expires a check the agent could not end
        }
    }

    /// <summary>
    /// Why a check that was open since <paramref name="openedAt"/> ended without a
    /// scan, in terms the administrator understands. The workstation state takes
    /// precedence over the window event: a window that "closed" because the machine
    /// was shutting down is reported as a shutdown, not an employee dismissal.
    /// </summary>
    private string MissReason(bool windowClosed, DateTimeOffset openedAt)
    {
        if (_workstation is not null)
        {
            if (_workstation.Current == WorkstationState.ShuttingDown)
            {
                return "shutdown";
            }
            if (_workstation.HasSleptSince(openedAt))
            {
                return "asleep";
            }
            if (_workstation.Current == WorkstationState.Locked)
            {
                return "locked";
            }
        }
        return windowClosed ? "closed" : "timeout";
    }

    private async Task TryCancelAsync(string checkId, string reason, CancellationToken ct)
    {
        try
        {
            await _api.CancelCheckAsync(checkId, reason, ct);
        }
        catch
        {
            // Best effort: the server's own expiry sweep ends the check anyway.
        }
    }
}
