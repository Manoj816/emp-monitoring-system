using EmpVerify.Agent.Core;
using EmpVerify.Agent.Core.Abstractions;
using EmpVerify.Agent.Core.Api;

namespace EmpVerify.Agent.Core.Tests;

public class VerificationFlowTests
{
    private sealed class FakeCamera : ICameraService
    {
        public bool Busy;
        public CapturedFrame? Frame = new([1, 2, 3], "Integrated Camera");
        public Task<CapturedFrame?> CaptureFrameAsync(CancellationToken ct) => Task.FromResult(Frame);
        public Task<bool> IsCameraBusyAsync(CancellationToken ct) => Task.FromResult(Busy);
        public Task<IReadOnlyList<string>> ListCameraDevicesAsync(CancellationToken ct) =>
            Task.FromResult<IReadOnlyList<string>>(new[] { "Integrated Camera" });
    }

    private sealed class FakeNotifications : INotificationService
    {
        public bool Accept = true;
        public int Prompts;
        public Task<bool> PromptForCheckAsync(TimeSpan window, CancellationToken ct)
        {
            Prompts++;
            return Task.FromResult(Accept);
        }
    }

    private sealed class FakeApi : IAgentApi
    {
        public int Uploads;
        public CheckResult Result = new("c1", CheckStatus.Match, false, 95.0, null, DateTimeOffset.UtcNow);

        // Liveness: GetResultAsync returns Pending until PendingPolls reaches zero.
        public int PendingPolls;
        public CheckResult LivenessResult = new("c1", CheckStatus.Match, false, 96.0, 99.0, DateTimeOffset.UtcNow);

        public Task<OpenCheck> OpenCheckAsync(CheckType type, CancellationToken ct) =>
            Task.FromResult(new OpenCheck("c1", "nonce", DateTimeOffset.UtcNow.AddMinutes(2)));

        public Task<CheckResult> UploadCaptureAsync(OpenCheck check, CapturedFrame frame, CancellationToken ct)
        {
            Uploads++;
            return Task.FromResult(Result);
        }

        public Task<LivenessSession> CreateLivenessSessionAsync(string checkId, CancellationToken ct) =>
            Task.FromResult(new LivenessSession("http://localhost:5174/?checkId=c1", DateTimeOffset.UtcNow.AddMinutes(2)));

        public Task<CheckResult> GetResultAsync(string checkId, CancellationToken ct)
        {
            if (PendingPolls > 0)
            {
                PendingPolls--;
                return Task.FromResult(new CheckResult(checkId, CheckStatus.Pending, false, null, null, null));
            }
            return Task.FromResult(LivenessResult);
        }

        public Task<DeviceCredentials> RegisterAsync(string t, string p, string? h, string? v, CancellationToken ct) =>
            throw new NotImplementedException();
        public Task<string> AuthenticateAsync(DeviceCredentials c, CancellationToken ct) =>
            throw new NotImplementedException();
        public Task<CheckPolicy> GetPolicyAsync(CancellationToken ct) => throw new NotImplementedException();
    }

    private sealed class FakeBrowser : EmpVerify.Agent.Core.Abstractions.IBrowserLauncher
    {
        public int Opens;
        public string? LastUrl;
        public void Open(string url) { Opens++; LastUrl = url; }
    }

    private static readonly TimeSpan Window = TimeSpan.FromSeconds(120);

    [Fact]
    public async Task AcceptedPromptCapturesAndUploads()
    {
        var api = new FakeApi();
        var notifications = new FakeNotifications { Accept = true };
        var flow = new VerificationFlow(api, new FakeCamera(), notifications);

        var result = await flow.RunCheckAsync(CheckType.Periodic, Window, promptFirst: true, CancellationToken.None);

        Assert.Equal(FlowOutcome.Completed, result.Outcome);
        Assert.Equal(CheckStatus.Match, result.Result!.Status);
        Assert.Equal(1, api.Uploads);
        Assert.Equal(1, notifications.Prompts);
    }

    [Fact]
    public async Task DeclinedPromptDoesNotCaptureOrUpload()
    {
        var api = new FakeApi();
        var flow = new VerificationFlow(api, new FakeCamera(), new FakeNotifications { Accept = false });

        var result = await flow.RunCheckAsync(CheckType.Periodic, Window, promptFirst: true, CancellationToken.None);

        Assert.Equal(FlowOutcome.NotAccepted, result.Outcome);
        Assert.Equal(0, api.Uploads);
    }

    [Fact]
    public async Task BusyCameraDefersWithoutUploading()
    {
        var api = new FakeApi();
        var flow = new VerificationFlow(api, new FakeCamera { Busy = true }, new FakeNotifications());

        var result = await flow.RunCheckAsync(CheckType.Periodic, Window, promptFirst: true, CancellationToken.None);

        Assert.Equal(FlowOutcome.CameraBusy, result.Outcome);
        Assert.Equal(0, api.Uploads);
    }

    [Fact]
    public async Task NoCameraFrameReportsUnavailable()
    {
        var api = new FakeApi();
        var flow = new VerificationFlow(api, new FakeCamera { Frame = null }, new FakeNotifications());

        var result = await flow.RunCheckAsync(CheckType.Periodic, Window, promptFirst: true, CancellationToken.None);

        Assert.Equal(FlowOutcome.CameraUnavailable, result.Outcome);
        Assert.Equal(0, api.Uploads);
    }

    [Fact]
    public async Task EmployeeInitiatedCheckSkipsThePrompt()
    {
        var notifications = new FakeNotifications();
        var flow = new VerificationFlow(new FakeApi(), new FakeCamera(), notifications);

        var result = await flow.RunCheckAsync(
            CheckType.SessionStart, Window, promptFirst: false, CancellationToken.None);

        Assert.Equal(FlowOutcome.Completed, result.Outcome);
        Assert.Equal(0, notifications.Prompts);
    }

    [Fact]
    public async Task LivenessLaunchesBrowserAndReturnsServerVerdict()
    {
        var api = new FakeApi { PendingPolls = 2 }; // resolves on the third poll
        var browser = new FakeBrowser();
        var flow = new VerificationFlow(api, new FakeCamera(), new FakeNotifications(), browser);

        var result = await flow.RunLivenessCheckAsync(
            Window, CancellationToken.None, poll: (_, _) => Task.CompletedTask);

        Assert.Equal(FlowOutcome.Completed, result.Outcome);
        Assert.Equal(CheckStatus.Match, result.Result!.Status);
        Assert.Equal(1, browser.Opens);
        Assert.Contains("checkId=c1", browser.LastUrl);
    }

    [Fact]
    public async Task LivenessTimesOutWhenNeverCompleted()
    {
        var api = new FakeApi { PendingPolls = int.MaxValue }; // always Pending
        var flow = new VerificationFlow(api, new FakeCamera(), new FakeNotifications(), new FakeBrowser());

        // Zero window => the poll loop never runs, so it reports not-completed.
        var result = await flow.RunLivenessCheckAsync(
            TimeSpan.Zero, CancellationToken.None, poll: (_, _) => Task.CompletedTask);

        Assert.Equal(FlowOutcome.NotAccepted, result.Outcome);
    }

    [Fact]
    public async Task LivenessWithoutBrowserThrows()
    {
        var flow = new VerificationFlow(new FakeApi(), new FakeCamera(), new FakeNotifications());
        await Assert.ThrowsAsync<InvalidOperationException>(() =>
            flow.RunLivenessCheckAsync(Window, CancellationToken.None, poll: (_, _) => Task.CompletedTask));
    }
}
