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
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
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

    public DeviceService(
            DeviceRepository devices,
            EmployeeRepository employees,
            EnrollmentTokenRepository enrollmentTokens,
            DeviceTokenService deviceTokens,
            AuditService audit,
            @Value("${empverify.security.enrollment-token-ttl-hours}") long enrollmentTokenTtlHours) {
        this.devices = devices;
        this.employees = employees;
        this.enrollmentTokens = enrollmentTokens;
        this.deviceTokens = deviceTokens;
        this.audit = audit;
        this.enrollmentTokenTtlHours = enrollmentTokenTtlHours;
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

    @Transactional
    public DeviceAuth authenticate(UUID deviceId, String deviceSecret) {
        var device = devices.findById(deviceId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "unknown device"));
        if (device.getStatus() != DeviceStatus.ACTIVE
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

    private static String randomSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return B64.encodeToString(bytes);
    }
}
