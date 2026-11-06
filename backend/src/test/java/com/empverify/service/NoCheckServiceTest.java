package com.empverify.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        service = new NoCheckService(employees, devices, checks, policyService, audit, ZONE.getId(), false);
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
    void anEmployeeOffTheDailyRosterIsNeverJudgedNoCheck() {
        // No check was ever going to run for them, so an empty day is not a miss.
        employee.setDailyChecksEnabled(false);

        assertEquals(0, service.recordNoCheckDays(at(MONDAY, LocalTime.of(17, 15))));

        verify(checks, org.mockito.Mockito.never()).save(any());
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
        assertTrue(row.getErrorDetail().contains("Nov 1, 2026 4:12 PM"), row.getErrorDetail());
        verify(audit).record(eq("system"), eq("NO_CHECK_RECORDED"), eq("check"), eq(row.getId().toString()), any());
    }

    // ---- what the row says happened ------------------------------------------
    //
    // The agent signs in about every minute while it runs, so the last-seen time
    // tells an administrator whether it was alive during the window. A machine in
    // use with no agent on it must not be reported as a machine that was off.

    private String detailAfterSweep() {
        when(checks.existsByEmployeeIdAndRequestedAtGreaterThanEqualAndRequestedAtLessThan(
                eq(employee.getId()), any(), any())).thenReturn(false);
        service.recordNoCheckDays(at(MONDAY, LocalTime.of(17, 15)));
        var saved = ArgumentCaptor.forClass(VerificationCheck.class);
        verify(checks).save(saved.capture());
        return saved.getValue().getErrorDetail();
    }

    @Test
    void noContactDuringTheWindowDoesNotClaimTheMachineWasOff() {
        system.setLastSeenAt(at(MONDAY.minusDays(1), LocalTime.of(21, 27))); // the evening before

        String detail = detailAfterSweep();

        assertTrue(detail.contains("before the window opened"), detail);
        assertTrue(detail.contains("either the machine was off or the agent was not running on it"), detail);
        // The point of the rewrite: an employee who used their PC all day is not
        // told the machine was off, and the administrator is pointed at the agent.
        assertTrue(detail.contains("check that it starts at sign-in"), detail);
    }

    @Test
    void contactDuringTheWindowMeansTheAgentWasRunningAndSaysSo() {
        system.setLastSeenAt(at(MONDAY, LocalTime.of(13, 40))); // mid-window

        String detail = detailAfterSweep();

        assertTrue(detail.contains("in touch during the window"), detail);
        assertTrue(detail.contains("the machine was on and the agent running"), detail);
        assertFalse(detail.contains("was off"), detail);
    }

    @Test
    void contactOnlyAfterTheWindowIsNotTreatedAsContactDuringIt() {
        system.setLastSeenAt(at(MONDAY, LocalTime.of(17, 12))); // after the window closed

        String detail = detailAfterSweep();

        assertTrue(detail.contains("after the window had closed"), detail);
        assertTrue(detail.contains("cannot be told from the last-contact time alone"), detail);
    }

    @Test
    void aSystemThatHasNeverBeenOnlineSaysThat() {
        system.setLastSeenAt(null);

        String detail = detailAfterSweep();

        assertTrue(detail.contains("has never reached EmpVerify"), detail);
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
    void weekendsAreJudgedWhenTheWeekendFlagIsOn() {
        service = new NoCheckService(employees, devices, checks, policyService, audit, ZONE.getId(), true);
        when(checks.existsByEmployeeIdAndRequestedAtGreaterThanEqualAndRequestedAtLessThan(
                eq(employee.getId()), any(), any())).thenReturn(false);

        int recorded = service.recordNoCheckDays(at(SUNDAY, LocalTime.of(18, 0)));

        assertEquals(1, recorded, "testing mode: Saturdays and Sundays get a NO_CHECK record too");
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
