package com.empverify.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.empverify.domain.Device;
import com.empverify.domain.Employee;
import com.empverify.domain.EnrollmentToken;
import com.empverify.domain.Enums.DeviceStatus;
import com.empverify.domain.Enums.EmployeeStatus;
import com.empverify.repo.DeviceRepository;
import com.empverify.repo.EmployeeRepository;
import com.empverify.repo.EnrollmentTokenRepository;
import com.empverify.security.DeviceTokenService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@ExtendWith(MockitoExtension.class)
class DeviceServiceTest {

    @Mock private DeviceRepository devices;
    @Mock private EmployeeRepository employees;
    @Mock private EnrollmentTokenRepository enrollmentTokens;
    @Mock private DeviceTokenService deviceTokens;
    @Mock private AuditService audit;

    private DeviceService service;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @BeforeEach
    void setUp() {
        service = new DeviceService(devices, employees, enrollmentTokens, deviceTokens, audit, 24, "tenant-token");
    }

    private static Employee activeEmployee() {
        var e = new Employee();
        e.setEmployeeCode("E-1");
        e.setFullName("Test");
        e.setEmail("t@example.com");
        e.setStatus(EmployeeStatus.ACTIVE);
        return e;
    }

    @Test
    void issueEnrollmentTokenReturnsIdDotSecretAndPersistsHash() {
        var employee = activeEmployee();
        when(employees.findById(any())).thenReturn(Optional.of(employee));
        when(enrollmentTokens.save(any())).thenAnswer(i -> i.getArgument(0));

        var token = service.issueEnrollmentToken(employee.getId(), "admin");

        assertTrue(token.contains("."), "token should be tokenId.secret");
        verify(enrollmentTokens).save(any(EnrollmentToken.class));
        verify(audit).record(any(), any(), any(), any(), any());
    }

    @Test
    void issueEnrollmentTokenRejectedForInactiveEmployee() {
        var employee = activeEmployee();
        employee.setStatus(EmployeeStatus.OFFBOARDED);
        when(employees.findById(any())).thenReturn(Optional.of(employee));

        var ex = assertThrows(ApiException.class, () -> service.issueEnrollmentToken(employee.getId(), "admin"));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        verify(enrollmentTokens, never()).save(any());
    }

    @Test
    void registerWithValidTokenCreatesActiveDeviceAndConsumesToken() {
        var secret = "the-secret";
        var token = new EnrollmentToken();
        token.setEmployeeId(UUID.randomUUID());
        token.setTokenHash(encoder.encode(secret));
        token.setExpiresAt(Instant.now().plusSeconds(3600));
        when(enrollmentTokens.findById(token.getId())).thenReturn(Optional.of(token));
        when(devices.save(any())).thenAnswer(i -> i.getArgument(0));
        when(enrollmentTokens.save(any())).thenAnswer(i -> i.getArgument(0));

        var reg = service.register(token.getId() + "." + secret, "WINDOWS", "HOST", "0.1.0");

        assertNotNull(reg.deviceId());
        assertNotNull(reg.deviceSecret());
        assertNotNull(token.getUsedAt(), "token should be marked used");
    }

    @Test
    void registerWithAlreadyUsedTokenIsRejected() {
        var secret = "s";
        var token = new EnrollmentToken();
        token.setTokenHash(encoder.encode(secret));
        token.setExpiresAt(Instant.now().plusSeconds(3600));
        token.setUsedAt(Instant.now());
        when(enrollmentTokens.findById(token.getId())).thenReturn(Optional.of(token));

        var ex = assertThrows(ApiException.class,
                () -> service.register(token.getId() + "." + secret, "WINDOWS", "HOST", "0.1.0"));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
        verify(devices, never()).save(any());
    }

    @Test
    void registerWithExpiredTokenIsRejected() {
        var secret = "s";
        var token = new EnrollmentToken();
        token.setTokenHash(encoder.encode(secret));
        token.setExpiresAt(Instant.now().minusSeconds(1));
        when(enrollmentTokens.findById(token.getId())).thenReturn(Optional.of(token));

        var ex = assertThrows(ApiException.class,
                () -> service.register(token.getId() + "." + secret, "WINDOWS", "HOST", "0.1.0"));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
    }

    @Test
    void registerWithWrongSecretIsRejected() {
        var token = new EnrollmentToken();
        token.setTokenHash(encoder.encode("correct"));
        token.setExpiresAt(Instant.now().plusSeconds(3600));
        when(enrollmentTokens.findById(token.getId())).thenReturn(Optional.of(token));

        var ex = assertThrows(ApiException.class,
                () -> service.register(token.getId() + "." + "wrong", "WINDOWS", "HOST", "0.1.0"));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
    }

    @Test
    void authenticateActiveDeviceIssuesToken() {
        var secret = "device-secret";
        var device = new Device();
        device.setEmployeeId(UUID.randomUUID());
        device.setStatus(DeviceStatus.ACTIVE);
        device.setCredentialHash(encoder.encode(secret));
        when(devices.findById(device.getId())).thenReturn(Optional.of(device));
        when(devices.save(any())).thenAnswer(i -> i.getArgument(0));
        when(deviceTokens.issue(device.getId())).thenReturn("issued-token");
        when(deviceTokens.ttlSeconds()).thenReturn(3600L);

        var auth = service.authenticate(device.getId(), secret);

        assertEquals("issued-token", auth.accessToken());
        assertEquals(3600L, auth.expiresInSeconds());
    }

    @Test
    void authenticateRevokedDeviceIsRejected() {
        var secret = "device-secret";
        var device = new Device();
        device.setStatus(DeviceStatus.REVOKED);
        device.setCredentialHash(encoder.encode(secret));
        when(devices.findById(device.getId())).thenReturn(Optional.of(device));

        var ex = assertThrows(ApiException.class, () -> service.authenticate(device.getId(), secret));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
    }

    @Test
    void authenticateWithWrongSecretIsRejected() {
        var device = new Device();
        device.setStatus(DeviceStatus.ACTIVE);
        device.setCredentialHash(encoder.encode("correct"));
        when(devices.findById(device.getId())).thenReturn(Optional.of(device));

        var ex = assertThrows(ApiException.class, () -> service.authenticate(device.getId(), "wrong"));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
    }

    @Test
    void requireActiveDeviceThrowsForRevoked() {
        var device = new Device();
        device.setStatus(DeviceStatus.REVOKED);
        when(devices.findById(device.getId())).thenReturn(Optional.of(device));

        assertThrows(ApiException.class, () -> service.requireActiveDevice(device.getId()));
    }

    @Test
    void provisionWithTenantTokenCreatesUnassignedPendingDevice() {
        var saved = new Device[1];
        when(devices.save(any())).thenAnswer(i -> saved[0] = i.getArgument(0));

        var registration = service.provision("tenant-token", "WINDOWS", "LAPTOP-1", "1.0");

        assertEquals(DeviceStatus.PENDING, saved[0].getStatus());
        org.junit.jupiter.api.Assertions.assertNull(saved[0].getEmployeeId());
        org.junit.jupiter.api.Assertions.assertNotNull(registration.deviceSecret());
        verify(audit).record(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("DEVICE_PROVISIONED"), org.mockito.ArgumentMatchers.eq("device"),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void provisionWithWrongTokenIsRejected() {
        assertThrows(ApiException.class, () -> service.provision("nope", "WINDOWS", null, null));
        verify(devices, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void assignDeviceLinksEmployeeAndActivates() {
        var device = new Device();
        device.setPlatform(com.empverify.domain.Enums.DevicePlatform.WINDOWS);
        device.setStatus(DeviceStatus.PENDING);
        var employee = activeEmployee();
        when(devices.findById(device.getId())).thenReturn(Optional.of(device));
        when(employees.findById(employee.getId())).thenReturn(Optional.of(employee));
        when(devices.save(any())).thenAnswer(i -> i.getArgument(0));

        var assigned = service.assignDevice(device.getId(), employee.getId(), "admin");

        assertEquals(DeviceStatus.ACTIVE, assigned.getStatus());
        assertEquals(employee.getId(), assigned.getEmployeeId());
        verify(audit).record(org.mockito.ArgumentMatchers.eq("admin"),
                org.mockito.ArgumentMatchers.eq("DEVICE_ASSIGNED"), org.mockito.ArgumentMatchers.eq("device"),
                org.mockito.ArgumentMatchers.eq(device.getId().toString()), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void pendingDeviceCanAuthenticateWhileAwaitingAssignment() {
        var device = new Device();
        device.setPlatform(com.empverify.domain.Enums.DevicePlatform.WINDOWS);
        device.setStatus(DeviceStatus.PENDING);
        device.setCredentialHash(new BCryptPasswordEncoder().encode("secret"));
        when(devices.findById(device.getId())).thenReturn(Optional.of(device));
        when(devices.save(any())).thenAnswer(i -> i.getArgument(0));
        when(deviceTokens.issue(device.getId())).thenReturn("token");
        when(deviceTokens.ttlSeconds()).thenReturn(3600L);

        var auth = service.authenticate(device.getId(), "secret");

        assertEquals("token", auth.accessToken());
    }

    @Test
    void revokeDeviceSetsRevokedStatus() {
        var device = new Device();
        device.setStatus(DeviceStatus.ACTIVE);
        when(devices.findById(device.getId())).thenReturn(Optional.of(device));
        when(devices.save(any())).thenAnswer(i -> i.getArgument(0));

        service.revokeDevice(device.getId(), "admin");

        assertEquals(DeviceStatus.REVOKED, device.getStatus());
        verify(audit).record(any(), any(), any(), any(), any());
    }

    @Test
    void offboardEmployeeMarksOffboardedAndRevokesActiveDevices() {
        var employee = activeEmployee();
        var active = new Device();
        active.setStatus(DeviceStatus.ACTIVE);
        var alreadyRevoked = new Device();
        alreadyRevoked.setStatus(DeviceStatus.REVOKED);
        when(employees.findById(employee.getId())).thenReturn(Optional.of(employee));
        when(employees.save(any())).thenAnswer(i -> i.getArgument(0));
        when(devices.findByEmployeeId(employee.getId())).thenReturn(List.of(active, alreadyRevoked));
        lenient().when(devices.save(any())).thenAnswer(i -> i.getArgument(0));

        int revoked = service.offboardEmployee(employee.getId(), "admin");

        assertEquals(1, revoked, "only the active device is newly revoked");
        assertEquals(EmployeeStatus.OFFBOARDED, employee.getStatus());
        assertEquals(DeviceStatus.REVOKED, active.getStatus());
    }
}
