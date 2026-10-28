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

    @Transactional
    public Registration register(String enrollmentToken, String platform, String hostname, String agentVersion) {
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
        var device = new Device();
        device.setEmployeeId(token.getEmployeeId());
        device.setPlatform(DevicePlatform.valueOf(platform));
        device.setHostname(hostname);
        device.setAgentVersion(agentVersion);
        device.setStatus(DeviceStatus.ACTIVE);
        device.setCredentialHash(encoder.encode(secret));
        device.setRegisteredAt(Instant.now());
        devices.save(device);

        token.setUsedAt(Instant.now());
        enrollmentTokens.save(token);

        audit.record("device:" + device.getId(), "DEVICE_REGISTERED", "device",
                device.getId().toString(), "{\"platform\":\"" + platform + "\"}");
        return new Registration(device.getId(), secret);
    }

    /**
     * Device-first enrollment: a fresh install registers itself with the tenant
     * provisioning token (placed by the installer/MDM) and stays UNASSIGNED until an
     * admin links it to an employee. It can authenticate meanwhile so the agent can
     * wait for assignment, but every verification endpoint requires ACTIVE, which
     * only {@link #assignDevice} grants.
     */
    @Transactional
    public Registration provision(String provisioningToken, String platform, String hostname, String agentVersion) {
        if (this.provisioningToken == null || !MessageDigest.isEqual(
                this.provisioningToken, provisioningToken.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid provisioning token");
        }
        String secret = randomSecret();
        var device = new Device();
        device.setPlatform(DevicePlatform.valueOf(platform));
        device.setHostname(hostname);
        device.setAgentVersion(agentVersion);
        device.setStatus(DeviceStatus.PENDING);
        device.setCredentialHash(encoder.encode(secret));
        device.setRegisteredAt(Instant.now());
        devices.save(device);

        audit.record("device:" + device.getId(), "DEVICE_PROVISIONED", "device",
                device.getId().toString(), "{\"platform\":\"" + platform + "\"}");
        return new Registration(device.getId(), secret);
    }

    /** Admin: links a registered system to an employee and activates it. */
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
        device.setEmployeeId(employeeId);
        device.setStatus(DeviceStatus.ACTIVE);
        devices.save(device);

        audit.record(actor, "DEVICE_ASSIGNED", "device", deviceId.toString(),
                "{\"employeeId\":\"" + employeeId + "\",\"previousEmployeeId\":"
                        + (previous == null ? "null" : "\"" + previous + "\"") + "}");
        return device;
    }

    /** The device's own record regardless of status (used while it waits for assignment). */
    public Device getDevice(UUID deviceId) {
        return devices.findById(deviceId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "unknown device"));
    }

    @Transactional
    public DeviceAuth authenticate(UUID deviceId, String deviceSecret) {
        var device = devices.findById(deviceId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "unknown device"));
        // PENDING (provisioned, not yet assigned) may authenticate so the agent can poll
        // for assignment; the verification endpoints still require ACTIVE.
        if (device.getStatus() == DeviceStatus.REVOKED
                || !encoder.matches(deviceSecret, device.getCredentialHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "device revoked or bad credentials");
        }
        device.setLastSeenAt(Instant.now());
        devices.save(device);
        return new DeviceAuth(deviceTokens.issue(deviceId), deviceTokens.ttlSeconds());
    }

    public Device requireActiveDevice(UUID deviceId) {
        var device = devices.findById(deviceId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "unknown device"));
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
     * Offboards an employee: marks them OFFBOARDED and revokes all their devices
     * so none can continue verifying (PRD Requirement 7 offboarding). Biometric
     * reference deletion is handled under the retention policy separately.
     *
     * @return the number of devices revoked
     */
    @Transactional
    public int offboardEmployee(UUID employeeId, String actor) {
        var employee = employees.findById(employeeId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "employee not found"));
        employee.setStatus(EmployeeStatus.OFFBOARDED);
        employees.save(employee);

        int revoked = 0;
        for (var device : devices.findByEmployeeId(employeeId)) {
            if (device.getStatus() != DeviceStatus.REVOKED) {
                device.setStatus(DeviceStatus.REVOKED);
                devices.save(device);
                revoked++;
            }
        }
        audit.record(actor, "EMPLOYEE_OFFBOARDED", "employee", employeeId.toString(),
                "{\"devicesRevoked\":" + revoked + "}");
        return revoked;
    }

    private static String randomSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return B64.encodeToString(bytes);
    }
}
