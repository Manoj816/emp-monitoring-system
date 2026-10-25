package com.empverify.domain;

public final class Enums {
    private Enums() {}

    public enum EmployeeStatus { ACTIVE, SUSPENDED, OFFBOARDED }

    public enum DevicePlatform { WINDOWS, MACOS }

    public enum DeviceStatus { PENDING, ACTIVE, REVOKED }

    public enum ReferenceImageStatus { ACTIVE, REPLACED, DELETED }

    public enum CheckType { SESSION_START, PERIODIC, LIVENESS, ESCALATION }

    public enum CheckStatus { PENDING, MATCH, CHECK, NO_FACE, MULTIPLE_FACES, VERIFICATION_ERROR, EXPIRED }
}
