package com.empverify.domain;

import com.empverify.domain.Enums.DevicePlatform;
import com.empverify.domain.Enums.DeviceStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "devices")
public class Device {

    @Id
    private UUID id = UUID.randomUUID();

    /** Null while a device-first registration is still waiting for an admin to assign it. */
    @Column(name = "employee_id")
    private UUID employeeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DevicePlatform platform;

    private String hostname;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeviceStatus status = DeviceStatus.PENDING;

    @Column(name = "credential_hash")
    private String credentialHash;

    @Column(name = "agent_version")
    private String agentVersion;

    @Column(name = "registered_at")
    private Instant registeredAt;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    /** When an ENROLLMENT scan on this system matched; null until then, and again after (re)assignment. */
    @Column(name = "enrolled_at")
    private Instant enrolledAt;

    /** Stable id of the physical installation (Windows MachineGuid); lets a reinstall re-use this row. */
    @Column(name = "machine_id")
    private String machineId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public UUID getEmployeeId() { return employeeId; }
    public void setEmployeeId(UUID v) { this.employeeId = v; }
    public DevicePlatform getPlatform() { return platform; }
    public void setPlatform(DevicePlatform v) { this.platform = v; }
    public String getHostname() { return hostname; }
    public void setHostname(String v) { this.hostname = v; }
    public DeviceStatus getStatus() { return status; }
    public void setStatus(DeviceStatus v) { this.status = v; }
    public String getCredentialHash() { return credentialHash; }
    public void setCredentialHash(String v) { this.credentialHash = v; }
    public String getAgentVersion() { return agentVersion; }
    public void setAgentVersion(String v) { this.agentVersion = v; }
    public Instant getRegisteredAt() { return registeredAt; }
    public void setRegisteredAt(Instant v) { this.registeredAt = v; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Instant v) { this.lastSeenAt = v; }
    public Instant getEnrolledAt() { return enrolledAt; }
    public void setEnrolledAt(Instant v) { this.enrolledAt = v; }
    public String getMachineId() { return machineId; }
    public void setMachineId(String v) { this.machineId = v; }
}
