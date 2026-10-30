package com.empverify.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.empverify.domain.CheckPolicy;
import com.empverify.domain.Device;
import com.empverify.domain.Employee;
import com.empverify.domain.Enums.CheckStatus;
import com.empverify.domain.Enums.DeviceStatus;
import com.empverify.domain.Enums.EmployeeStatus;
import com.empverify.domain.VerificationCheck;
import com.empverify.repo.DeviceRepository;
import com.empverify.repo.EmployeeRepository;
import com.empverify.repo.VerificationCheckRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NoCheckServiceTest {

    private static final ZoneId ZONE = ZoneId.of("America/New_York");
    private static final LocalDate MONDAY = LocalDate.of(2026, 11, 2);
    private static final LocalDate SUNDAY = LocalDate.of(2026, 11, 1);

    @Mock private EmployeeRepository employees;
    @Mock private DeviceRepository devices;
    @Mock private VerificationCheckRepository checks;
    @Mock private PolicyService policyService;
    @Mock private AuditService audit;

    private NoCheckService service;
    private Employee employee;
    private Device system;

    @BeforeEach
    void setUp() {
        service = new NoCheckService(employees, devices, checks, policyService, audit, ZONE.getId());
        employee = new Employee();
        employee.setEmployeeCode("E-1");
        employee.setFullName("Test");
        employee.setEmail("t@example.com");
        employee.setStatus(EmployeeStatus.ACTIVE);
        system = new Device();
        system.setStatus(DeviceStatus.ACTIVE);
        system.setEmployeeId(employee.getId());
        system.setHostname("LAPTOP-1");
        system.setRegisteredAt(at(MONDAY.minusDays(3), LocalTime.of(10, 0)));
        system.setLastSeenAt(at(MONDAY.minusDays(1), LocalTime.of(16, 12)));
        lenient().when(employees.findAll()).thenReturn(List.of(employee));
        lenient().when(devices.findByEmployeeId(employee.getId())).thenReturn(List.of(system));
        lenient().when(policyService.effectiveForEmployee(employee.getId())).thenReturn(new CheckPolicy()); // 09:00-17:00
        lenient().when(checks.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private static Instant at(LocalDate day, LocalTime time) {
        return day.atTime(time).atZone(ZONE).toInstant();
    }

    @Test
    void aDayWithNoCheckIsRecordedOnceTheWindowHasClosed() {
        when(checks.existsByEmployeeIdAndRequestedAtGreaterThanEqualAndRequestedAtLessThan(
                eq(employee.getId()), any(), any())).thenReturn(false);

        int recorded = service.recordNoCheckDays(at(MONDAY, LocalTime.of(17, 15)));

        assertEquals(1, recorded);
        var saved = ArgumentCaptor.forClass(VerificationCheck.class);
        verify(checks).save(saved.capture());
        var row = saved.getValue();
        assertEquals(CheckStatus.NO_CHECK, row.getStatus());
        assertEquals(employee.getId(), row.getEmployeeId());
        assertEquals(system.getId(), row.getDeviceId());
        assertEquals(at(MONDAY, LocalTime.of(17, 0)), row.getRequestedAt(), "dated at the end of the work window");
        assertTrue(row.getErrorDetail().contains("Mon, Nov 2, 2026"), row.getErrorDetail());
        assertTrue(row.getErrorDetail().contains("last online Nov 1, 2026 4:12 PM"), row.getErrorDetail());
        verify(audit).record(eq("system"), eq("NO_CHECK_RECORDED"), eq("check"), eq(row.getId().toString()), any());
    }

    @Test
    void nothingIsRecordedWhileTheWindowIsStillOpen() {
        int recorded = service.recordNoCheckDays(at(MONDAY, LocalTime.of(16, 30)));

        assertEquals(0, recorded);
        verify(checks, never()).save(any());
    }

    @Test
    void nothingIsRecordedInsideTheGracePeriodAfterTheWindow() {
        int recorded = service.recordNoCheckDays(at(MONDAY, LocalTime.of(17, 5)));

        assertEquals(0, recorded);
        verify(checks, never()).save(any());
    }

    @Test
    void aDayWithAnyCheckIsNotRecordedAgain() {
        when(checks.existsByEmployeeIdAndRequestedAtGreaterThanEqualAndRequestedAtLessThan(
                eq(employee.getId()), eq(at(MONDAY, LocalTime.MIDNIGHT)), eq(at(MONDAY.plusDays(1), LocalTime.MIDNIGHT))))
                .thenReturn(true);

        int recorded = service.recordNoCheckDays(at(MONDAY, LocalTime.of(18, 0)));

        assertEquals(0, recorded);
        verify(checks, never()).save(any());
    }

    @Test
    void weekendsAreSkipped() {
        int recorded = service.recordNoCheckDays(at(SUNDAY, LocalTime.of(18, 0)));

        assertEquals(0, recorded);
        verify(checks, never()).save(any());
    }

    @Test
    void employeesWithoutAnActiveSystemAreSkipped() {
        system.setStatus(DeviceStatus.PENDING);

        int recorded = service.recordNoCheckDays(at(MONDAY, LocalTime.of(18, 0)));

        assertEquals(0, recorded);
        verify(checks, never()).save(any());
    }

    @Test
    void aSystemRegisteredAfterTheWindowIsNotBlamed() {
        system.setRegisteredAt(at(MONDAY, LocalTime.of(17, 30)));

        int recorded = service.recordNoCheckDays(at(MONDAY, LocalTime.of(18, 0)));

        assertEquals(0, recorded);
        verify(checks, never()).save(any());
    }

    @Test
    void offboardedEmployeesAreSkipped() {
        employee.setStatus(EmployeeStatus.OFFBOARDED);

        int recorded = service.recordNoCheckDays(at(MONDAY, LocalTime.of(18, 0)));

        assertEquals(0, recorded);
        verify(checks, never()).save(any());
    }
}
