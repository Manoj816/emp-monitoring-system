namespace EmpVerify.Agent.Core.Api;

/// <summary>
/// Agent-facing backend operations (see docs/openapi/agent-api.yaml). Extracted
/// as an interface so flows are unit-testable without a live HTTP backend.
/// </summary>
public interface IAgentApi
{
    Task<DeviceCredentials> RegisterAsync(
        string enrollmentToken, string platform, string? hostname, string? agentVersion, CancellationToken ct);

    /// <summary>Device-first enrollment with the tenant provisioning token; the device starts unassigned.</summary>
    Task<DeviceCredentials> ProvisionAsync(
        string provisioningToken, string platform, string? hostname, string? agentVersion, CancellationToken ct);

    /// <summary>The device's own status; works while unassigned so the agent can wait for assignment.</summary>
    Task<DeviceState> GetDeviceStateAsync(CancellationToken ct);

    Task<string> AuthenticateAsync(DeviceCredentials credentials, CancellationToken ct);

    Task<CheckPolicy> GetPolicyAsync(CancellationToken ct);

    Task<OpenCheck> OpenCheckAsync(CheckType type, CancellationToken ct);

    Task<CheckResult> UploadCaptureAsync(OpenCheck check, CapturedFrame frame, CancellationToken ct);

    Task<LivenessSession> CreateLivenessSessionAsync(string checkId, CancellationToken ct);

    Task<CaptureSession> CreateCaptureSessionAsync(string checkId, CancellationToken ct);

    Task<CheckResult> GetResultAsync(string checkId, CancellationToken ct);
}
