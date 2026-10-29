namespace EmpVerify.Agent.Core;

/// <summary>Enrollment = the first live scan after assignment, matched against the uploaded ID/photo.</summary>
public enum CheckType { Enrollment, SessionStart, Periodic, Liveness, Escalation }

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

public sealed record CaptureSession(string CaptureUrl, DateTimeOffset ExpiresAt);

/// <summary>
/// The device's own server-side state. Assigned is false until an admin links it to
/// an employee; Enrolled is false until the employee's first live scan has matched
/// the uploaded ID/photo (until then the next check is the enrollment scan).
/// </summary>
/// <param name="EmployeeId">The linked employee (null while unassigned); a change means the system was reassigned.</param>
public sealed record DeviceState(string Status, bool Assigned, bool Enrolled, string? EmployeeId = null);

public sealed record CheckResult(
    string CheckId,
    CheckStatus Status,
    bool MismatchFlag,
    double? SimilarityScore,
    double? LivenessConfidence,
    DateTimeOffset? CompletedAt);

public sealed record CapturedFrame(byte[] Jpeg, string CameraDeviceName);
