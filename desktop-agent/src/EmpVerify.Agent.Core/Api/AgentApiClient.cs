using System.Net.Http.Headers;
using System.Net.Http.Json;

namespace EmpVerify.Agent.Core.Api;

/// <summary>
/// Typed client for the agent endpoints defined in docs/openapi/agent-api.yaml.
/// The server is the sole authority on verification outcomes.
/// </summary>
public sealed class AgentApiClient : IAgentApi
{
    private readonly HttpClient _http;

    public AgentApiClient(HttpClient http)
    {
        _http = http;
    }

    private sealed record RegisterRequest(string EnrollmentToken, string Platform, string? Hostname, string? AgentVersion);
    private sealed record RegisterResponse(string DeviceId, string DeviceSecret);
    private sealed record AuthRequest(string DeviceId, string DeviceSecret);
    private sealed record AuthResponse(string AccessToken, long ExpiresInSeconds);
    private sealed record PolicyResponse(
        string WorkWindowStart, string WorkWindowEnd, string Timezone,
        int MaxChecksPerDay, int ResponseTimeoutSeconds, bool LivenessOnSessionStart);

    public async Task<DeviceCredentials> RegisterAsync(
        string enrollmentToken, string platform, string? hostname, string? agentVersion, CancellationToken ct)
    {
        var response = await _http.PostAsJsonAsync(
            "/api/v1/devices/register",
            new RegisterRequest(enrollmentToken, platform, hostname, agentVersion), ct);
        response.EnsureSuccessStatusCode();
        var body = (await response.Content.ReadFromJsonAsync<RegisterResponse>(ct))!;
        return new DeviceCredentials(body.DeviceId, body.DeviceSecret);
    }

    public async Task<string> AuthenticateAsync(DeviceCredentials credentials, CancellationToken ct)
    {
        var response = await _http.PostAsJsonAsync(
            "/api/v1/devices/auth", new AuthRequest(credentials.DeviceId, credentials.DeviceSecret), ct);
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
        return (await response.Content.ReadFromJsonAsync<CheckResult>(ct))!;
    }

    public async Task<CheckResult> GetResultAsync(string checkId, CancellationToken ct)
    {
        return (await _http.GetFromJsonAsync<CheckResult>($"/api/v1/checks/{checkId}/result", ct))!;
    }
}
