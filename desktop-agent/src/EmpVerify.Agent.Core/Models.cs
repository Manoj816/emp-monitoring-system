namespace EmpVerify.Agent.Core;

public enum CheckType { SessionStart, Periodic, Liveness, Escalation }

public enum CheckStatus { Pending, Match, Check, NoFace, MultipleFaces, VerificationError, Expired }

public sealed record DeviceCredentials(string DeviceId, string DeviceSecret);

public sealed record CheckPolicy(
    TimeOnly WorkWindowStart,
    TimeOnly WorkWindowEnd,
    string Timezone,
    int MaxChecksPerDay,
    int ResponseTimeoutSeconds,
    bool LivenessOnSessionStart);

public sealed record OpenCheck(string CheckId, string Nonce, DateTimeOffset ExpiresAt);

public sealed record LivenessSession(string LivenessUrl, DateTimeOffset ExpiresAt);

public sealed record CheckResult(
    string CheckId,
    CheckStatus Status,
    bool MismatchFlag,
    double? SimilarityScore,
    double? LivenessConfidence,
    DateTimeOffset? CompletedAt);

public sealed record CapturedFrame(byte[] Jpeg, string CameraDeviceName);
