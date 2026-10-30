package com.empverify.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    void sameMachineReProvisioningReusesTheRowButComesBackUnassigned() {
        var employee = activeEmployee();
        var existing = new Device();
        existing.setEmployeeId(employee.getId());
        existing.setStatus(DeviceStatus.ACTIVE);
        existing.setMachineId("MACHINE-GUID-1");
        existing.setRegisteredAt(Instant.now().minusSeconds(3600));
        existing.setEnrolledAt(Instant.now());
        when(devices.findByMachineIdOrderByRegisteredAtDesc("MACHINE-GUID-1")).thenReturn(List.of(existing));
        when(devices.save(any())).thenAnswer(i -> i.getArgument(0));

        var registration = service.provision("tenant-token", "WINDOWS", "LAPTOP-1", "0.9.0.0", "MACHINE-GUID-1");

        assertEquals(existing.getId(), registration.deviceId(), "no duplicate row for the same machine");
        assertEquals(null, existing.getEmployeeId(), "a reinstalled system must be assigned again");
        assertEquals(DeviceStatus.PENDING, existing.getStatus());
        assertEquals(null, existing.getEnrolledAt(), "the first check after assignment is the enrollment scan");
        assertEquals("0.9.0.0", existing.getAgentVersion());
        verify(audit).record(any(), eq("DEVICE_REPROVISIONED"), eq("device"), eq(existing.getId().toString()), any());
    }

    @Test
    void revokedMachineComesBackUnassigned() {
        var revoked = new Device();
        revoked.setEmployeeId(UUID.randomUUID());
        revoked.setStatus(DeviceStatus.REVOKED);
        revoked.setMachineId("MACHINE-GUID-2");
        revoked.setRegisteredAt(Instant.now().minusSeconds(3600));
        when(devices.findByMachineIdOrderByRegisteredAtDesc("MACHINE-GUID-2")).thenReturn(List.of(revoked));
        when(devices.save(any())).thenAnswer(i -> i.getArgument(0));

        var registration = service.provision("tenant-token", "WINDOWS", "LAPTOP-2", "0.9.0.0", "MACHINE-GUID-2");

        assertEquals(revoked.getId(), registration.deviceId());
        assertEquals(DeviceStatus.PENDING, revoked.getStatus(), "an administrator must assign it again");
        assertEquals(null, revoked.getEmployeeId());
    }

    @Test
    void unknownMachineStillGetsANewRow() {
        when(devices.findByMachineIdOrderByRegisteredAtDesc("NEW-GUID")).thenReturn(List.of());
        when(devices.save(any())).thenAnswer(i -> i.getArgument(0));

        var registration = service.provision("tenant-token", "WINDOWS", "LAPTOP-3", "0.9.0.0", "NEW-GUID");

        assertNotNull(registration.deviceId());
        verify(audit).record(any(), eq("DEVICE_PROVISIONED"), eq("device"), any(), any());
    }

    @Test
    void signInBackfillsTheMachineIdOnce() {
        var device = new Device();
        device.setStatus(DeviceStatus.ACTIVE);
        device.setCredentialHash(encoder.encode("secret"));
        when(devices.findById(device.getId())).thenReturn(Optional.of(device));
        when(devices.save(any())).thenAnswer(i -> i.getArgument(0));
        when(deviceTokens.issue(device.getId())).thenReturn("token");
        when(deviceTokens.ttlSeconds()).thenReturn(3600L);

        service.authenticate(device.getId(), "secret", "0.9.0.0", "MACHINE-GUID-3");
        service.authenticate(device.getId(), "secret", "0.9.0.0", "OTHER-GUID");

        assertEquals("MACHINE-GUID-3", device.getMachineId(), "the first reported id sticks");
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
    void deleteDeviceRemovesTheRowAndAuditsIt() {
        var device = new Device();
        device.setStatus(DeviceStatus.REVOKED);
        device.setHostname("LAPTOP-OLD");
        when(devices.findById(device.getId())).thenReturn(Optional.of(device));

        service.deleteDevice(device.getId(), "admin");

        verify(devices).delete(device);
        verify(audit).record(eq("admin"), eq("DEVICE_DELETED"), eq("device"), eq(device.getId().toString()), any());
    }

    @Test
    void deleteUnknownDeviceIs404() {
        var id = UUID.randomUUID();
        when(devices.findById(id)).thenReturn(Optional.empty());

        assertThrows(ApiException.class, () -> service.deleteDevice(id, "admin"));
    }

    @Test
    void offboardEmployeeMarksOffboardedAndDeregistersTheirSystems() {
        var employee = activeEmployee();
        var active = new Device();
        active.setEmployeeId(employee.getId());
        active.setStatus(DeviceStatus.ACTIVE);
        var alreadyRevoked = new Device();
        alreadyRevoked.setEmployeeId(employee.getId());
        alreadyRevoked.setStatus(DeviceStatus.REVOKED);
        when(employees.findById(employee.getId())).thenReturn(Optional.of(employee));
        when(employees.save(any())).thenAnswer(i -> i.getArgument(0));
        when(devices.findByEmployeeId(employee.getId())).thenReturn(List.of(active, alreadyRevoked));
        lenient().when(devices.save(any())).thenAnswer(i -> i.getArgument(0));

        int deregistered = service.offboardEmployee(employee.getId(), "admin");

        assertEquals(1, deregistered, "the active system is deregistered; the revoked one stays revoked");
        assertEquals(EmployeeStatus.OFFBOARDED, employee.getStatus());
        // The system is unlinked and back to Unassigned, ready for the next employee.
        assertEquals(null, active.getEmployeeId());
        assertEquals(DeviceStatus.PENDING, active.getStatus());
        assertEquals(null, alreadyRevoked.getEmployeeId());
        assertEquals(DeviceStatus.REVOKED, alreadyRevoked.getStatus());
        verify(audit).record(eq("admin"), eq("DEVICE_UNASSIGNED"), eq("device"), eq(active.getId().toString()), any());
        verify(audit).record(eq("admin"), eq("EMPLOYEE_OFFBOARDED"), eq("employee"), eq(employee.getId().toString()), any());
    }

    @Test
    void assigningAnAssignedSystemToAnotherEmployeeIsAReassignment() {
        var first = activeEmployee();
        var second = activeEmployee();
        var device = new Device();
        device.setEmployeeId(first.getId());
        device.setStatus(DeviceStatus.ACTIVE);
        when(devices.findById(device.getId())).thenReturn(Optional.of(device));
        when(employees.findById(second.getId())).thenReturn(Optional.of(second));
        when(devices.save(any())).thenAnswer(i -> i.getArgument(0));

        device.setEnrolledAt(Instant.now());

        service.assignDevice(device.getId(), second.getId(), "admin");

        assertEquals(second.getId(), device.getEmployeeId());
        assertEquals(DeviceStatus.ACTIVE, device.getStatus());
        assertEquals(null, device.getEnrolledAt(), "a reassigned system starts with the enrollment scan again");
        verify(audit).record(eq("admin"), eq("DEVICE_REASSIGNED"), eq("device"), eq(device.getId().toString()), any());
    }

    @Test
    void newPhotoResetsEnrollmentOnEveryEnrolledSystemOfTheEmployee() {
        var employee = activeEmployee();
        var enrolled = new Device();
        enrolled.setEmployeeId(employee.getId());
        enrolled.setEnrolledAt(Instant.now());
        var notYet = new Device();
        notYet.setEmployeeId(employee.getId());
        when(devices.findByEmployeeId(employee.getId())).thenReturn(List.of(enrolled, notYet));
        when(devices.save(any())).thenAnswer(i -> i.getArgument(0));

        int reset = service.resetEnrollmentForEmployee(employee.getId());

        assertEquals(1, reset);
        assertEquals(null, enrolled.getEnrolledAt());
    }

    @Test
    void markEnrolledStampsTheDevice() {
        var device = new Device();
        when(devices.findById(device.getId())).thenReturn(Optional.of(device));
        when(devices.save(any())).thenAnswer(i -> i.getArgument(0));

        service.markEnrolled(device.getId());

        assertNotNull(device.getEnrolledAt());
    }

    @Test
    void reassigningToTheSameEmployeeIsRefused() {
        var employee = activeEmployee();
        var device = new Device();
        device.setEmployeeId(employee.getId());
        device.setStatus(DeviceStatus.ACTIVE);
        when(devices.findById(device.getId())).thenReturn(Optional.of(device));
        when(employees.findById(employee.getId())).thenReturn(Optional.of(employee));

        assertThrows(ApiException.class, () -> service.assignDevice(device.getId(), employee.getId(), "admin"));
    }
}
