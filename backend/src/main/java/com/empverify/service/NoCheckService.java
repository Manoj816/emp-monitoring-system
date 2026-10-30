package com.empverify.service;

import com.empverify.domain.CheckPolicy;
import com.empverify.domain.Device;
import com.empverify.domain.Employee;
import com.empverify.domain.Enums.CheckStatus;
import com.empverify.domain.Enums.CheckType;
import com.empverify.domain.Enums.DeviceStatus;
import com.empverify.domain.Enums.EmployeeStatus;
import com.empverify.domain.VerificationCheck;
import com.empverify.repo.DeviceRepository;
import com.empverify.repo.EmployeeRepository;
import com.empverify.repo.VerificationCheckRepository;
import jakarta.annotation.PostConstruct;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records the days on which an employee was never checked. The daily check is
 * planned by the agent on the employee's machine, so a machine that stays off
 * all day opens no check and the server would otherwise have nothing to show.
 * Once the work window has closed (plus a grace period for a check still in
 * progress), every active employee with an active system that existed during
 * the window and no check of any outcome that day gets one NO_CHECK row, dated
 * at the end of the window, which the portal lists under Missed. Weekends are
 * skipped. The day is judged in the configured zone (empverify.verification.zone),
 * since the server has no zone of its own.
 */
@Component
public class NoCheckService {

    private static final Logger log = LoggerFactory.getLogger(NoCheckService.class);

    /** A check opened in the last minutes of the window may still be running: judge the day a little later. */
    static final Duration GRACE = Duration.ofMinutes(10);

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter MOMENT = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a", Locale.ENGLISH);

    private final EmployeeRepository employees;
    private final DeviceRepository devices;
    private final VerificationCheckRepository checks;
    private final PolicyService policyService;
    private final AuditService audit;
    private final ZoneId zone;

    public NoCheckService(
            EmployeeRepository employees,
            DeviceRepository devices,
            VerificationCheckRepository checks,
            PolicyService policyService,
            AuditService audit,
            @Value("${empverify.verification.zone:UTC}") String zone) {
        this.employees = employees;
        this.devices = devices;
        this.checks = checks;
        this.policyService = policyService;
        this.audit = audit;
        this.zone = ZoneId.of(zone);
    }

    @PostConstruct
    void announce() {
        log.info("No-check sweep active: work days are judged in {} once the window has closed", zone);
    }

    @Scheduled(fixedDelayString = "${empverify.verification.no-check-sweep-ms:300000}")
    public void sweep() {
        try {
            int recorded = recordNoCheckDays(Instant.now());
            if (recorded > 0) {
                log.info("Recorded {} no-check day(s)", recorded);
            }
        } catch (Exception e) {
            log.warn("No-check sweep failed: {}", e.getMessage());
        }
    }

    /**
     * Judges the current day (in the configured zone) for every employee and
     * records a NO_CHECK row where nothing was checked. Idempotent: the recorded
     * row itself counts as the day's check on the next sweep.
     *
     * @return how many rows were recorded in this pass
     */
    @Transactional
    public int recordNoCheckDays(Instant now) {
        LocalDate day = now.atZone(zone).toLocalDate();
        if (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return 0;
        }
        int recorded = 0;
        for (Employee employee : employees.findAll()) {
            if (employee.getStatus() != EmployeeStatus.ACTIVE) {
                continue;
            }
            List<Device> systems = devices.findByEmployeeId(employee.getId()).stream()
                    .filter(d -> d.getStatus() == DeviceStatus.ACTIVE)
                    .toList();
            if (systems.isEmpty()) {
                continue;
            }
            CheckPolicy policy = policyService.effectiveForEmployee(employee.getId());
            Instant windowEnd = day.atTime(policy.getWorkWindowEnd()).atZone(zone).toInstant();
            if (now.isBefore(windowEnd.plus(GRACE))) {
                continue; // the day is not over yet
            }
            // A system registered in the window's last minutes (or after it) could not
            // have run a check: only judge employees whose system existed in time.
            boolean existed = systems.stream().anyMatch(d ->
                    d.getRegisteredAt() != null && d.getRegisteredAt().isBefore(windowEnd.minus(GRACE)));
            if (!existed) {
                continue;
            }
            Instant dayStart = day.atStartOfDay(zone).toInstant();
            Instant dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant();
            if (checks.existsByEmployeeIdAndRequestedAtGreaterThanEqualAndRequestedAtLessThan(
                    employee.getId(), dayStart, dayEnd)) {
                continue; // something was opened today (match, miss, error): the day has its record
            }
            record(employee, systems, policy, day, windowEnd, now);
            recorded++;
        }
        return recorded;
    }

    private void record(
            Employee employee, List<Device> systems, CheckPolicy policy, LocalDate day, Instant windowEnd, Instant now) {
        Instant lastSeen = systems.stream()
                .map(Device::getLastSeenAt)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
        String detail = String.format(Locale.ROOT,
                "No check ran on %s: no system of this employee opened a check during the work window "
                        + "(%s-%s %s), so the machine was most likely off or the agent was not running. %s",
                day.format(DAY), policy.getWorkWindowStart(), policy.getWorkWindowEnd(), zone,
                lastSeen == null
                        ? "The system has never been online."
                        : "The system was last online " + lastSeen.atZone(zone).format(MOMENT) + ".");

        var check = new VerificationCheck();
        check.setDeviceId(systems.get(0).getId());
        check.setEmployeeId(employee.getId());
        check.setCheckType(CheckType.PERIODIC);
        check.setStatus(CheckStatus.NO_CHECK);
        check.setNonce(UUID.randomUUID().toString());
        check.setRequestedAt(windowEnd);
        check.setExpiresAt(windowEnd);
        check.setCompletedAt(now);
        check.setErrorDetail(detail);
        checks.save(check);
        audit.record("system", "NO_CHECK_RECORDED", "check", check.getId().toString(),
                "{\"employeeId\":\"" + employee.getId() + "\",\"date\":\"" + day + "\"}");
    }
}
