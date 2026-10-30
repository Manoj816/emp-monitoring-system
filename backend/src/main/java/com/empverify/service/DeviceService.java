package com.empverify.service;

import com.empverify.domain.Device;
import com.empverify.domain.EnrollmentToken;
import com.empverify.domain.Enums.DevicePlatform;
import com.empverify.domain.Enums.DeviceStatus;
import com.empverify.domain.Enums.EmployeeStatus;
import com.empverify.repo.DeviceRepository;
import com.empverify.repo.EmployeeRepository;
import com.empverify.repo.EnrollmentTokenRepository;
import com.empverify.security.DeviceTokenService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeviceService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private final DeviceRepository devices;
    private final EmployeeRepository employees;
    private final EnrollmentTokenRepository enrollmentTokens;
    private final DeviceTokenService deviceTokens;
    private final AuditService audit;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final long enrollmentTokenTtlHours;
    private final byte[] provisioningToken; // null = device-first provisioning disabled

    public DeviceService(
            DeviceRepository devices,
            EmployeeRepository employees,
            EnrollmentTokenRepository enrollmentTokens,
            DeviceTokenService deviceTokens,
            AuditService audit,
            @Value("${empverify.security.enrollment-token-ttl-hours}") long enrollmentTokenTtlHours,
            @Value("${empverify.security.provisioning-token:}") String provisioningToken) {
        this.devices = devices;
        this.employees = employees;
        this.enrollmentTokens = enrollmentTokens;
        this.deviceTokens = deviceTokens;
        this.audit = audit;
        this.enrollmentTokenTtlHours = enrollmentTokenTtlHours;
        this.provisioningToken = provisioningToken == null || provisioningToken.isBlank()
                ? null : provisioningToken.getBytes(StandardCharsets.UTF_8);
    }

    public record Registration(UUID deviceId, String deviceSecret) {}

    public record DeviceAuth(String accessToken, long expiresInSeconds) {}

    /** Admin: one-time enrollment token "tokenId.secret"; only its hash is stored. */
    @Transactional
    public String issueEnrollmentToken(UUID employeeId, String createdBy) {
        var employee = employees.findById(employeeId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "employee not found"));
        if (employee.getStatus() != EmployeeStatus.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT, "employee is not active");
        }
        String secret = randomSecret();
        var token = new EnrollmentToken();
        token.setEmployeeId(employeeId);
        token.setTokenHash(encoder.encode(secret));
        token.setExpiresAt(Instant.now().plus(Duration.ofHours(enrollmentTokenTtlHours)));
        token.setCreatedBy(createdBy);
        enrollmentTokens.save(token);
        audit.record(createdBy, "ENROLLMENT_TOKEN_ISSUED", "employee", employeeId.toString(), null);
        return token.getId() + "." + secret;
    }

    public Registration register(String enrollmentToken, String platform, String hostname, String agentVersion) {
        return register(enrollmentToken, platform, hostname, agentVersion, null);
    }

    @Transactional
    public Registration register(
            String enrollmentToken, String platform, String hostname, String agentVersion, String machineId) {
        String[] parts = enrollmentToken.split("\\.", 2);
        if (parts.length != 2) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid enrollment token");
        }
        UUID tokenId;
        try {
            tokenId = UUID.fromString(parts[0]);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid enrollment token");
        }
        var token = enrollmentTokens.findById(tokenId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "invalid enrollment token"));
        if (token.getUsedAt() != null || token.getExpiresAt().isBefore(Instant.now())
                || !encoder.matches(parts[1], token.getTokenHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "enrollment token expired, used, or invalid");
        }

        String secret = randomSecret();
        var device = sameMachine(machineId).orElseGet(Device::new);
        boolean reused = device.getRegisteredAt() != null;
        device.setEmployeeId(token.getEmployeeId());
        device.setPlatform(DevicePlatform.valueOf(platform));
        device.setHostname(hostname);
        device.setAgentVersion(agentVersion);
        device.setMachineId(blankToNull(machineId));
        device.setStatus(DeviceStatus.ACTIVE);
        device.setEnrolledAt(null); // a fresh registration starts with the enrollment scan
        device.setCredentialHash(encoder.encode(secret));
        device.setRegisteredAt(Instant.now());
        devices.save(device);

        token.setUsedAt(Instant.now());
        enrollmentTokens.save(token);

        audit.record("device:" + device.getId(), reused ? "DEVICE_REREGISTERED" : "DEVICE_REGISTERED", "device",
                device.getId().toString(), "{\"platform\":\"" + platform + "\"}");
        return new Registration(device.getId(), secret);
    }

    /**
     * The existing row for this physical machine, if the agent sent a machine id
     * and one is on file. A reinstall, a wiped profile or a credential reset then
     * updates that row instead of adding a duplicate.
     */
    private java.util.Optional<Device> sameMachine(String machineId) {
        if (machineId == null || machineId.isBlank()) {
            return java.util.Optional.empty();
        }
        return devices.findByMachineIdOrderByRegisteredAtDesc(machineId.trim()).stream().findFirst();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /**
     * Device-first enrollment: a fresh install registers itself with the tenant
     * provisioning token (placed by the installer/MDM) and stays UNASSIGNED until an
     * admin links it to an employee. It can authenticate meanwhile so the agent can
     * wait for assignment, but every verification endpoint requires ACTIVE, which
     * only {@link #assignDevice} grants.
     */
    public Registration provision(String provisioningToken, String platform, String hostname, String agentVersion) {
        return provision(provisioningToken, platform, hostname, agentVersion, null);
    }

    /**
     * With a machine id, the same physical machine re-uses its existing row: a
     * still-assigned system keeps its employee (and restarts with the enrollment
     * scan); a revoked one comes back as Unassigned, so an administrator decides
     * again whether it may verify anyone.
     */
    @Transactional
    public Registration provision(
            String provisioningToken, String platform, String hostname, String agentVersion, String machineId) {
        if (this.provisioningToken == null || !MessageDigest.isEqual(
                this.provisioningToken, provisioningToken.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid provisioning token");
        }
        String secret = randomSecret();
        var existing = sameMachine(machineId);
        var device = existing.orElseGet(Device::new);
        String previousStatus = existing.map(d -> d.getStatus().name()).orElse(null);
        if (existing.isEmpty() || device.getStatus() == DeviceStatus.REVOKED || device.getEmployeeId() == null) {
            device.setEmployeeId(null);
            device.setStatus(DeviceStatus.PENDING);
        }
        device.setPlatform(DevicePlatform.valueOf(platform));
        device.setHostname(hostname);
        device.setAgentVersion(agentVersion);
        device.setMachineId(blankToNull(machineId));
        device.setEnrolledAt(null); // every (re)install starts with the enrollment scan
        device.setCredentialHash(encoder.encode(secret));
        device.setRegisteredAt(Instant.now());
        devices.save(device);

        audit.record("device:" + device.getId(), existing.isPresent() ? "DEVICE_REPROVISIONED" : "DEVICE_PROVISIONED",
                "device", device.getId().toString(),
                "{\"platform\":\"" + platform + "\",\"previousStatus\":"
                        + (previousStatus == null ? "null" : "\"" + previousStatus + "\"") + "}");
        return new Registration(device.getId(), secret);
    }

    /**
     * Admin: links a registered system to an employee and activates it. Also the
     * way to hand a system to a different employee (reassign): the agent notices
     * the new employee on its next state poll and, if that employee has no live
     * enrollment yet, runs the enrollment scan first.
     */
    @Transactional
    public Device assignDevice(UUID deviceId, UUID employeeId, String actor) {
        var device = devices.findById(deviceId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "device not found"));
        if (device.getStatus() == DeviceStatus.REVOKED) {
            throw new ApiException(HttpStatus.CONFLICT, "device is revoked");
        }
        var employee = employees.findById(employeeId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "employee not found"));
        if (employee.getStatus() != EmployeeStatus.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT, "employee is not active");
        }
        UUID previous = device.getEmployeeId();
        if (employeeId.equals(previous) && device.getStatus() == DeviceStatus.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT, "device is already assigned to this employee");
        }
        device.setEmployeeId(employeeId);
        device.setStatus(DeviceStatus.ACTIVE);
        device.setEnrolledAt(null); // every (re)assignment starts with the enrollment scan
        devices.save(device);

        audit.record(actor, previous == null ? "DEVICE_ASSIGNED" : "DEVICE_REASSIGNED", "device",
                deviceId.toString(),
                "{\"employeeId\":\"" + employeeId + "\",\"previousEmployeeId\":"
                        + (previous == null ? "null" : "\"" + previous + "\"") + "}");
        return device;
    }

    /** The device's own record regardless of status (used while it waits for assignment). */
    public Device getDevice(UUID deviceId) {
        return devices.findById(deviceId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "unknown device"));
    }

    public DeviceAuth authenticate(UUID deviceId, String deviceSecret) {
        return authenticate(deviceId, deviceSecret, null, null);
    }

    public DeviceAuth authenticate(UUID deviceId, String deviceSecret, String agentVersion) {
        return authenticate(deviceId, deviceSecret, agentVersion, null);
    }

    /**
     * @param agentVersion when given, recorded so the fleet view shows the version actually running
     * @param machineId when given, recorded so a later reinstall finds this row (back-fills older rows)
     */
    @Transactional
    public DeviceAuth authenticate(UUID deviceId, String deviceSecret, String agentVersion, String machineId) {
        var device = devices.findById(deviceId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "unknown device"));
        // PENDING (provisioned, not yet assigned) may authenticate so the agent can poll
        // for assignment; the verification endpoints still require ACTIVE.
        if (device.getStatus() == DeviceStatus.REVOKED
                || !encoder.matches(deviceSecret, device.getCredentialHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "device revoked or bad credentials");
        }
        device.setLastSeenAt(Instant.now());
        if (agentVersion != null && !agentVersion.isBlank()) {
            device.setAgentVersion(agentVersion.trim());
        }
        if (machineId != null && !machineId.isBlank() && device.getMachineId() == null) {
            device.setMachineId(machineId.trim());
        }
        devices.save(device);
        return new DeviceAuth(deviceTokens.issue(deviceId), deviceTokens.ttlSeconds());
    }

    public Device requireActiveDevice(UUID deviceId) {
        var device = devices.findById(deviceId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "unknown device"));
        if (device.getStatus() == DeviceStatus.PENDING) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "device not yet assigned to an employee");
        }
        if (device.getStatus() != DeviceStatus.ACTIVE) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "device revoked");
        }
        return device;
    }

    public List<Device> listDevices() {
        return devices.findAll();
    }

    public List<Device> devicesForEmployee(UUID employeeId) {
        return devices.findByEmployeeId(employeeId);
    }

    /**
     * A new ID/photo invalidates every enrollment made against the old one: each
     * of the employee's systems runs the enrollment scan again at its next check.
     */
    @Transactional
    public int resetEnrollmentForEmployee(UUID employeeId) {
        int reset = 0;
        for (var device : devices.findByEmployeeId(employeeId)) {
            if (device.getEnrolledAt() != null) {
                device.setEnrolledAt(null);
                devices.save(device);
                reset++;
            }
        }
        return reset;
    }

    /** Called when an ENROLLMENT scan on the device matched: later checks on it are periodic. */
    @Transactional
    public void markEnrolled(UUID deviceId) {
        devices.findById(deviceId).ifPresent(device -> {
            device.setEnrolledAt(Instant.now());
            devices.save(device);
        });
    }

    /**
     * Revokes a device's authorization. Takes effect immediately: every
     * authenticated agent endpoint re-checks active status, and re-authentication
     * is refused. This is the admin lever for a lost/retired machine.
     */
    @Transactional
    public void revokeDevice(UUID deviceId, String actor) {
        var device = devices.findById(deviceId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "device not found"));
        if (device.getStatus() != DeviceStatus.REVOKED) {
            device.setStatus(DeviceStatus.REVOKED);
            devices.save(device);
        }
        audit.record(actor, "DEVICE_REVOKED", "device", deviceId.toString(), null);
    }

    /**
     * Offboards an employee: marks them OFFBOARDED and, in the same step,
     * deregisters every system linked to them — the link is removed and the
     * system goes back to Unassigned (PENDING), so it can no longer verify anyone
     * and is ready to be handed to the next employee (PRD Requirement 7
     * offboarding). A system that was already revoked stays revoked. The agent
     * on the machine sees "unassigned" on its next state poll and stops checks.
     * Biometric reference deletion is handled under the retention policy separately.
     *
     * @return the number of systems deregistered
     */
    @Transactional
    public int offboardEmployee(UUID employeeId, String actor) {
        var employee = employees.findById(employeeId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "employee not found"));
        employee.setStatus(EmployeeStatus.OFFBOARDED);
        employees.save(employee);

        int deregistered = 0;
        for (var device : devices.findByEmployeeId(employeeId)) {
            device.setEmployeeId(null);
            device.setEnrolledAt(null);
            if (device.getStatus() != DeviceStatus.REVOKED) {
                device.setStatus(DeviceStatus.PENDING);
                deregistered++;
            }
            devices.save(device);
            audit.record(actor, "DEVICE_UNASSIGNED", "device", device.getId().toString(),
                    "{\"previousEmployeeId\":\"" + employeeId + "\",\"reason\":\"EMPLOYEE_OFFBOARDED\"}");
        }
        audit.record(actor, "EMPLOYEE_OFFBOARDED", "employee", employeeId.toString(),
                "{\"devicesDeregistered\":" + deregistered + "}");
        return deregistered;
    }

    private static String randomSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return B64.encodeToString(bytes);
    }
}
