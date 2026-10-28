package com.empverify.domain;

public final class Enums {
    private Enums() {}

    public enum EmployeeStatus { ACTIVE, SUSPENDED, OFFBOARDED }

    public enum DevicePlatform { WINDOWS, MACOS }

    public enum DeviceStatus { PENDING, ACTIVE, REVOKED }

    public enum ReferenceImageStatus { ACTIVE, REPLACED, DELETED }

    /**
     * Where a reference image came from: the ID/photo an administrator uploaded, or
     * the frame captured by the employee's first matching live face scan.
     */
    public enum ReferenceImageSource { ADMIN_UPLOAD, LIVE_ENROLLMENT }

    /** ENROLLMENT = the first live scan after assignment, matched against the uploaded ID/photo. */
    public enum CheckType { ENROLLMENT, SESSION_START, PERIODIC, LIVENESS, ESCALATION }

    public enum CheckStatus { PENDING, MATCH, CHECK, NO_FACE, MULTIPLE_FACES, VERIFICATION_ERROR, EXPIRED }
}
