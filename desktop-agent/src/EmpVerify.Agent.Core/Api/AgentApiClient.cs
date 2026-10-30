using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace EmpVerify.Agent.Core.Api;

/// <summary>
/// Typed client for the agent endpoints defined in docs/openapi/agent-api.yaml.
/// The server is the sole authority on verification outcomes.
/// </summary>
public sealed class AgentApiClient : IAgentApi
{
    private readonly HttpClient _http;

    // The backend serializes enums as SCREAMING_SNAKE_CASE strings (e.g. "NO_FACE").
    // Map them onto the PascalCase CheckStatus members; keep Web defaults (camelCase props).
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web)
    {
        Converters = { new JsonStringEnumConverter(JsonNamingPolicy.SnakeCaseUpper) }
    };

    public AgentApiClient(HttpClient http)
    {
        _http = http;
    }

    private sealed record RegisterRequest(
        string EnrollmentToken, string Platform, string? Hostname, string? AgentVersion, string? MachineId);
    private sealed record RegisterResponse(string DeviceId, string DeviceSecret);
    private sealed record AuthRequest(string DeviceId, string DeviceSecret, string? AgentVersion, string? MachineId);

    /// <summary>The build's version, reported on every sign-in so the fleet view tracks upgrades.</summary>
    private static readonly string? AgentVersion =
        typeof(AgentApiClient).Assembly.GetName().Version?.ToString();

    /// <summary>
    /// Stable id of this installation, supplied by the platform head. Sent with every
    /// registration and sign-in so the server re-uses the same device row after a
    /// reinstall instead of listing the machine twice.
    /// </summary>
    public static string? MachineId { get; set; }
    private sealed record AuthResponse(string AccessToken, long ExpiresInSeconds);
    private sealed record PolicyResponse(
        string WorkWindowStart, string WorkWindowEnd, string Timezone,
        int MaxChecksPerDay, int ResponseTimeoutSeconds, bool LivenessOnSessionStart);

    public async Task<DeviceCredentials> RegisterAsync(
        string enrollmentToken, string platform, string? hostname, string? agentVersion, CancellationToken ct)
    {
        var response = await _http.PostAsJsonAsync(
            "/api/v1/devices/register",
            new RegisterRequest(enrollmentToken, platform, hostname, agentVersion, MachineId), ct);
        response.EnsureSuccessStatusCode();
        var body = (await response.Content.ReadFromJsonAsync<RegisterResponse>(ct))!;
        return new DeviceCredentials(body.DeviceId, body.DeviceSecret);
    }

    private sealed record ProvisionRequest(
        string ProvisioningToken, string Platform, string? Hostname, string? AgentVersion, string? MachineId);
    private sealed record MeResponse(string DeviceId, string Status, bool Assigned, bool Enrolled, string? EmployeeId);

    public async Task<DeviceCredentials> ProvisionAsync(
        string provisioningToken, string platform, string? hostname, string? agentVersion, CancellationToken ct)
    {
        var response = await _http.PostAsJsonAsync(
            "/api/v1/devices/provision",
            new ProvisionRequest(provisioningToken, platform, hostname, agentVersion, MachineId), ct);
        response.EnsureSuccessStatusCode();
        var body = (await response.Content.ReadFromJsonAsync<RegisterResponse>(ct))!;
        return new DeviceCredentials(body.DeviceId, body.DeviceSecret);
    }

    public async Task<DeviceState> GetDeviceStateAsync(CancellationToken ct)
    {
        var body = (await _http.GetFromJsonAsync<MeResponse>("/api/v1/devices/me", ct))!;
        return new DeviceState(body.Status, body.Assigned, body.Enrolled, body.EmployeeId);
    }

    public async Task<string> AuthenticateAsync(DeviceCredentials credentials, CancellationToken ct)
    {
        var response = await _http.PostAsJsonAsync(
            "/api/v1/devices/auth",
            new AuthRequest(credentials.DeviceId, credentials.DeviceSecret, AgentVersion, MachineId), ct);
        response.EnsureSuccessStatusCode();
        var body = (await response.Content.ReadFromJsonAsync<AuthResponse>(ct))!;
        _http.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", body.AccessToken);
        return body.AccessToken;
    }

    public async Task<CheckPolicy> GetPolicyAsync(CancellationToken ct)
    {
        var body = (await _http.GetFromJsonAsync<PolicyResponse>("/api/v1/policy", ct))!;
        return new CheckPolicy(
            TimeOnly.Parse(body.WorkWindowStart),
            TimeOnly.Parse(body.WorkWindowEnd),
            body.Timezone,
            body.MaxChecksPerDay,
            body.ResponseTimeoutSeconds,
            body.LivenessOnSessionStart);
    }

    public async Task<OpenCheck> OpenCheckAsync(CheckType type, CancellationToken ct)
    {
        var checkType = type switch
        {
            CheckType.Enrollment => "ENROLLMENT",
            CheckType.SessionStart => "SESSION_START",
            CheckType.Periodic => "PERIODIC",
            CheckType.Liveness => "LIVENESS",
            CheckType.Escalation => "ESCALATION",
            _ => throw new ArgumentOutOfRangeException(nameof(type)),
        };
        var response = await _http.PostAsync($"/api/v1/checks?checkType={checkType}", content: null, ct);
        response.EnsureSuccessStatusCode();
        return (await response.Content.ReadFromJsonAsync<OpenCheck>(ct))!;
    }

    public async Task<CheckResult> UploadCaptureAsync(
        OpenCheck check, CapturedFrame frame, CancellationToken ct)
    {
        using var form = new MultipartFormDataContent();
        form.Add(new StringContent(check.Nonce), "nonce");
        form.Add(new StringContent(frame.CameraDeviceName), "cameraDeviceName");
        var image = new ByteArrayContent(frame.Jpeg);
        image.Headers.ContentType = new MediaTypeHeaderValue("image/jpeg");
        form.Add(image, "image", "capture.jpg");

        var response = await _http.PostAsync($"/api/v1/checks/{check.CheckId}/capture", form, ct);
        response.EnsureSuccessStatusCode();
        return (await response.Content.ReadFromJsonAsync<CheckResult>(JsonOptions, ct))!;
    }

    public async Task<CheckResult> GetResultAsync(string checkId, CancellationToken ct)
    {
        return (await _http.GetFromJsonAsync<CheckResult>($"/api/v1/checks/{checkId}/result", JsonOptions, ct))!;
    }

    public async Task CancelCheckAsync(string checkId, string reason, CancellationToken ct)
    {
        var response = await _http.PostAsync(
            $"/api/v1/checks/{checkId}/cancel?reason={Uri.EscapeDataString(reason)}", content: null, ct);
        response.EnsureSuccessStatusCode();
    }

    private sealed record LivenessSessionDto(string LivenessUrl, DateTimeOffset ExpiresAt);

    public async Task<LivenessSession> CreateLivenessSessionAsync(string checkId, CancellationToken ct)
    {
        var response = await _http.PostAsync($"/api/v1/checks/{checkId}/liveness-session", content: null, ct);
        response.EnsureSuccessStatusCode();
        var body = (await response.Content.ReadFromJsonAsync<LivenessSessionDto>(ct))!;
        return new LivenessSession(body.LivenessUrl, body.ExpiresAt);
    }

    private sealed record CaptureSessionDto(string CaptureUrl, DateTimeOffset ExpiresAt);

    public async Task<CaptureSession> CreateCaptureSessionAsync(string checkId, CancellationToken ct)
    {
        var response = await _http.PostAsync($"/api/v1/checks/{checkId}/capture-session", content: null, ct);
        response.EnsureSuccessStatusCode();
        var body = (await response.Content.ReadFromJsonAsync<CaptureSessionDto>(ct))!;
        return new CaptureSession(body.CaptureUrl, body.ExpiresAt);
    }
}
