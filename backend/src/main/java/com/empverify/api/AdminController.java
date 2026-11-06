package com.empverify.api;

import com.empverify.domain.Employee;
import com.empverify.domain.Enums.ReferenceImageSource;
import com.empverify.domain.Enums.ReferenceImageStatus;
import com.empverify.repo.EmployeeRepository;
import com.empverify.repo.ReferenceImageRepository;
import com.empverify.repo.VerificationCheckRepository;
import com.empverify.service.ApiException;
import com.empverify.service.AuditService;
import com.empverify.service.DeviceService;
import com.empverify.service.EmployeeImportService;
import com.empverify.service.ReferenceImageService;
import com.empverify.storage.ImageStorage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Admin API consumed by the admin portal. Phase 1 auth: X-Admin-Key header
 * (Cognito user-pool JWTs replace it before production).
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final EmployeeRepository employees;
    private final ReferenceImageRepository referenceImages;
    private final VerificationCheckRepository checks;
    private final com.empverify.repo.CheckReviewRepository reviews;
    private final com.empverify.repo.CheckPolicyRepository policies;
    private final DeviceService deviceService;
    private final EmployeeImportService employeeImport;
    private final ReferenceImageService referenceImageService;
    private final ImageStorage storage;
    private final AuditService audit;

    public AdminController(
            EmployeeRepository employees,
            ReferenceImageRepository referenceImages,
            VerificationCheckRepository checks,
            com.empverify.repo.CheckReviewRepository reviews,
            com.empverify.repo.CheckPolicyRepository policies,
            DeviceService deviceService,
            EmployeeImportService employeeImport,
            ReferenceImageService referenceImageService,
            ImageStorage storage,
            AuditService audit) {
        this.employees = employees;
        this.referenceImages = referenceImages;
        this.checks = checks;
        this.reviews = reviews;
        this.policies = policies;
        this.deviceService = deviceService;
        this.employeeImport = employeeImport;
        this.referenceImageService = referenceImageService;
        this.storage = storage;
        this.audit = audit;
    }

    public record EmployeeResponse(
            String id, String employeeCode, String fullName, String email,
            String department, String status,
            /** An administrator has uploaded the ID/photo reference. */
            boolean hasReferenceImage,
            /** The employee's first live scan matched it and is now the second reference. */
            boolean hasLiveEnrollment,
            /** On the daily-check roster; off = admin-sent checks only. */
            boolean dailyChecks) {}

    public record EnrollmentTokenResponse(String enrollmentToken) {}

    private static final java.util.regex.Pattern EMAIL =
            java.util.regex.Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    /**
     * Creates an employee together with their ID/photo reference in one step
     * (multipart form). The image is mandatory: an employee without a reference
     * can never be verified, so the record is not created without one. Either
     * everything is stored or nothing is.
     */
    @PostMapping(value = "/employees", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public EmployeeResponse createEmployee(
            @RequestParam(value = "employeeCode", required = false) String employeeCode,
            @RequestParam(value = "fullName", required = false) String fullName,
            @RequestParam(value = "email", required = false) String email,
            @RequestParam(value = "department", required = false) String department,
            @RequestParam(value = "image", required = false) MultipartFile image) {
        employeeCode = required(employeeCode, "Domain ID is required");
        fullName = required(fullName, "Full name is required");
        email = required(email, "Email is required");
        department = required(department, "Department is required");
        if (!EMAIL.matcher(email).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Email address is not valid");
        }
        if (image == null || image.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "A photo or ID image is required to create an employee");
        }
        requireImage(image);
        if (employees.existsByEmployeeCodeIgnoreCase(employeeCode)) {
            throw new ApiException(HttpStatus.CONFLICT, "An employee with Domain ID " + employeeCode + " already exists");
        }
        if (employees.existsByEmailIgnoreCase(email)) {
            throw new ApiException(HttpStatus.CONFLICT, "An employee with email " + email + " already exists");
        }

        var employee = new Employee();
        employee.setEmployeeCode(employeeCode);
        employee.setFullName(fullName);
        employee.setEmail(email);
        employee.setDepartment(department);
        employees.save(employee);

        saveAdminUpload(employee.getId(), image);
        audit.record("admin", "EMPLOYEE_CREATED", "employee", employee.getId().toString(), null);
        audit.record("admin", "REFERENCE_IMAGE_UPLOADED", "employee", employee.getId().toString(), null);
        return toResponse(employee);
    }

    /**
     * Creates many employees from one CSV file.
     *
     * <p>No ID/photo comes with them: a CSV holds text, and a picture per row
     * could only arrive as a second upload picked by hand, which stops being a
     * workflow long before the twenty-thousand-row files this path exists for.
     * Imported employees show as "ID / photo: missing" until somebody uploads one
     * per person, and cannot be verified until then.
     *
     * <p>Always answers with a report rather than an error for a bad row: the
     * upload itself succeeded, and the administrator needs the list of what the
     * file got wrong, not one sentence about the first problem.
     */
    @PostMapping(value = "/employees/import", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public EmployeeImportService.ImportReport importEmployees(
            @RequestParam(value = "file", required = false) MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Choose a CSV file to import.");
        }
        var name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        if (!name.isEmpty() && !name.endsWith(".csv") && !name.endsWith(".txt")) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "The import file has to be a .csv. Save the spreadsheet as CSV and upload that.");
        }
        try {
            return employeeImport.importCsv(file.getBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String required(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, message);
        }
        return value.trim();
    }

    /** A reference photo this large is a mistake; the import CSV is what needs the bigger limit. */
    private static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;

    /** Only JPEG/PNG uploads are accepted; anything else cannot be a usable reference. */
    private static void requireImage(MultipartFile image) {
        if (image.getSize() > MAX_IMAGE_BYTES) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "The image is too large (maximum 10 MB)");
        }
        var type = image.getContentType() == null ? "" : image.getContentType().toLowerCase();
        var name = image.getOriginalFilename() == null ? "" : image.getOriginalFilename().toLowerCase();
        boolean typed = type.equals("image/jpeg") || type.equals("image/png") || type.equals("image/jpg");
        boolean named = name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png");
        if (!typed && !named) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The ID/photo must be a JPEG or PNG image");
        }
    }

    /** Stores the upload and records it as the employee's ACTIVE ADMIN_UPLOAD reference. */
    private void saveAdminUpload(UUID employeeId, MultipartFile image) {
        try {
            referenceImageService.storeAdminUpload(employeeId, image.getBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @GetMapping("/employees")
    public List<EmployeeResponse> listEmployees() {
        return employees.findAll().stream().map(this::toResponse).toList();
    }

    public record UpdateEmployeeRequest(String employeeCode, String fullName, String email, String department) {}

    /**
     * Corrects an employee's details — a name change, a department move, a typo in
     * the email or code. The ID/photo and the status have their own actions; none
     * of these fields affect verification, so nothing is re-enrolled.
     */
    @org.springframework.web.bind.annotation.PutMapping("/employees/{employeeId}")
    public EmployeeResponse updateEmployee(
            @PathVariable UUID employeeId, @RequestBody UpdateEmployeeRequest request) {
        var employee = employees.findById(employeeId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "employee not found"));
        String employeeCode = required(request.employeeCode(), "Domain ID is required");
        String fullName = required(request.fullName(), "Full name is required");
        String email = required(request.email(), "Email is required");
        String department = required(request.department(), "Department is required");
        if (!EMAIL.matcher(email).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Email address is not valid");
        }
        // Uniqueness, excluding the employee themselves (a pure case change is fine).
        if (!employeeCode.equalsIgnoreCase(employee.getEmployeeCode())
                && employees.existsByEmployeeCodeIgnoreCase(employeeCode)) {
            throw new ApiException(HttpStatus.CONFLICT, "An employee with Domain ID " + employeeCode + " already exists");
        }
        if (!email.equalsIgnoreCase(employee.getEmail())
                && employees.existsByEmailIgnoreCase(email)) {
            throw new ApiException(HttpStatus.CONFLICT, "An employee with email " + email + " already exists");
        }
        employee.setEmployeeCode(employeeCode);
        employee.setFullName(fullName);
        employee.setEmail(email);
        employee.setDepartment(department);
        employees.save(employee);
        audit.record("admin", "EMPLOYEE_UPDATED", "employee", employeeId.toString(), null);
        return toResponse(employee);
    }

    /** One-time token the employee enters in the agent's registration screen. */
    @PostMapping("/employees/{employeeId}/enrollment-tokens")
    public EnrollmentTokenResponse issueEnrollmentToken(@PathVariable UUID employeeId) {
        return new EnrollmentTokenResponse(deviceService.issueEnrollmentToken(employeeId, "admin"));
    }

    public record DeviceSummary(
            String id, String employeeId, String employeeName, String platform,
            String hostname, String status, String agentVersion,
            java.time.Instant registeredAt, java.time.Instant lastSeenAt,
            /** When the enrollment scan on this system matched; null until its first check. */
            java.time.Instant enrolledAt) {}

    public record OffboardResponse(String employeeId, String status, int devicesDeregistered) {}

    /** All registered devices with employee context (device-management view). */
    @GetMapping("/devices")
    public List<DeviceSummary> listDevices() {
        var employeesById = new java.util.HashMap<UUID, Employee>();
        employees.findAll().forEach(e -> employeesById.put(e.getId(), e));
        return deviceService.listDevices().stream().map(d -> {
            var emp = d.getEmployeeId() == null ? null : employeesById.get(d.getEmployeeId());
            return new DeviceSummary(
                    d.getId().toString(),
                    d.getEmployeeId() == null ? null : d.getEmployeeId().toString(),
                    emp == null ? null : emp.getFullName(),
                    d.getPlatform().name(), d.getHostname(), d.getStatus().name(),
                    d.getAgentVersion(), d.getRegisteredAt(), d.getLastSeenAt(), d.getEnrolledAt());
        }).toList();
    }

    public record AssignDeviceRequest(@NotBlank String employeeId) {}

    /** Links a registered (device-first) system to an employee and activates it. */
    @PostMapping("/devices/{deviceId}/assign")
    public DeviceSummary assignDevice(
            @PathVariable UUID deviceId, @Valid @RequestBody AssignDeviceRequest request) {
        var employeeId = UUID.fromString(request.employeeId());
        var d = deviceService.assignDevice(deviceId, employeeId, "admin");
        var emp = employees.findById(employeeId).orElse(null);
        return new DeviceSummary(
                d.getId().toString(), employeeId.toString(),
                emp == null ? null : emp.getFullName(),
                d.getPlatform().name(), d.getHostname(), d.getStatus().name(),
                d.getAgentVersion(), d.getRegisteredAt(), d.getLastSeenAt(), d.getEnrolledAt());
    }

    /** Revokes a device's authorization (lost/retired machine). */
    @PostMapping("/devices/{deviceId}/revoke")
    public void revokeDevice(@PathVariable UUID deviceId) {
        deviceService.revokeDevice(deviceId, "admin");
    }

    /**
     * Removes a system row for good (a stale duplicate, a retired machine). The
     * employee's check history is kept: the checks only lose their link to it.
     */
    @org.springframework.web.bind.annotation.DeleteMapping("/devices/{deviceId}")
    public void deleteDevice(@PathVariable UUID deviceId) {
        deviceService.deleteDevice(deviceId, "admin");
    }

    /** Verifies the tamper-evident audit log hash chain is intact. */
    @GetMapping("/audit/verify")
    public java.util.Map<String, Object> verifyAudit() {
        return java.util.Map.of("intact", audit.verifyChain());
    }

    /** Offboards an employee: marks OFFBOARDED and deregisters their systems (back to Unassigned). */
    @PostMapping("/employees/{employeeId}/offboard")
    public OffboardResponse offboard(@PathVariable UUID employeeId) {
        int deregistered = deviceService.offboardEmployee(employeeId, "admin");
        return new OffboardResponse(employeeId.toString(), "OFFBOARDED", deregistered);
    }

    /** Uploads/replaces the employee's reference image; the previous one is marked REPLACED. */
    @PostMapping("/employees/{employeeId}/reference-image")
    @Transactional
    public EmployeeResponse uploadReferenceImage(
            @PathVariable UUID employeeId, @RequestParam(value = "image", required = false) MultipartFile image) {
        var employee = employees.findById(employeeId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "employee not found"));
        if (image == null || image.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A photo or ID image is required");
        }
        requireImage(image);

        // A new ID/photo supersedes the previous upload AND the live enrollment that
        // was matched against it: the employee's next check re-enrolls.
        referenceImages.findAllByEmployeeIdAndStatus(employeeId, ReferenceImageStatus.ACTIVE)
                .forEach(previous -> {
                    previous.setStatus(ReferenceImageStatus.REPLACED);
                    referenceImages.save(previous);
                });
        // Write the retirements before the new row: in one transaction Hibernate
        // would otherwise insert first and trip the one-active-per-source index.
        referenceImages.flush();

        saveAdminUpload(employeeId, image);
        deviceService.resetEnrollmentForEmployee(employeeId); // each system re-enrolls against the new photo
        audit.record("admin", "REFERENCE_IMAGE_UPLOADED", "employee", employeeId.toString(), null);
        return toResponse(employee);
    }

    public record ReEnrollResponse(String employeeId, int systemsReset) {}

    /**
     * Asks for the enrollment scan again without changing the ID/photo: the
     * employee's current live reference is retired and every system of theirs
     * runs the enrollment scan at its next check (the agent opens it within a
     * minute when the PC is on). On a match, that scan becomes the new live reference.
     */
    @PostMapping("/employees/{employeeId}/re-enroll")
    @Transactional
    public ReEnrollResponse requestReEnrollment(@PathVariable UUID employeeId) {
        employees.findById(employeeId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "employee not found"));
        referenceImages.findByEmployeeIdAndStatusAndSource(
                employeeId, ReferenceImageStatus.ACTIVE, ReferenceImageSource.LIVE_ENROLLMENT)
                .ifPresent(live -> {
                    live.setStatus(ReferenceImageStatus.REPLACED);
                    referenceImages.save(live);
                });
        int reset = deviceService.resetEnrollmentForEmployee(employeeId);
        audit.record("admin", "RE_ENROLLMENT_REQUESTED", "employee", employeeId.toString(),
                "{\"systemsReset\":" + reset + "}");
        return new ReEnrollResponse(employeeId.toString(), reset);
    }

    public record CheckWindowResponse(String workWindowStart, String workWindowEnd, String timezone) {}

    /** The daily check window of the default policy — when the day's random check can land. */
    @GetMapping("/policy/check-window")
    public CheckWindowResponse checkWindow() {
        var policy = defaultPolicy();
        return new CheckWindowResponse(
                policy.getWorkWindowStart().toString(), policy.getWorkWindowEnd().toString(),
                policy.getTimezone());
    }

    public record UpdateCheckWindowRequest(@NotBlank String workWindowStart, @NotBlank String workWindowEnd) {}

    /**
     * Sets the daily check window ("HH:mm"–"HH:mm"). Each agent re-reads the policy
     * within a minute: a day whose check has not fired yet is re-planned inside the
     * new window; a day already checked stays checked.
     */
    @org.springframework.web.bind.annotation.PutMapping("/policy/check-window")
    public CheckWindowResponse updateCheckWindow(@Valid @RequestBody UpdateCheckWindowRequest request) {
        java.time.LocalTime start;
        java.time.LocalTime end;
        try {
            start = java.time.LocalTime.parse(request.workWindowStart());
            end = java.time.LocalTime.parse(request.workWindowEnd());
        } catch (java.time.format.DateTimeParseException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Times must be HH:mm (e.g. 09:00)");
        }
        if (!end.isAfter(start)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "The window must end after it starts");
        }
        var policy = defaultPolicy();
        policy.setWorkWindowStart(start);
        policy.setWorkWindowEnd(end);
        policies.save(policy);
        audit.record("admin", "CHECK_WINDOW_UPDATED", "policy", policy.getId().toString(),
                "{\"workWindowStart\":\"" + start + "\",\"workWindowEnd\":\"" + end + "\"}");
        return new CheckWindowResponse(start.toString(), end.toString(), policy.getTimezone());
    }

    private com.empverify.domain.CheckPolicy defaultPolicy() {
        return policies.findByName("default")
                .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "default policy missing"));
    }

    public record DailyChecksRequest(boolean enabled) {}

    /**
     * Puts an employee on, or takes them off, the daily-check roster (the
     * Configuration tab). Off means no scheduled daily check and no NO_CHECK
     * judgement; enrollment and admin-sent checks still run. The agent re-reads
     * its policy every minute, so the change lands without a restart.
     */
    @org.springframework.web.bind.annotation.PutMapping("/employees/{employeeId}/daily-checks")
    public EmployeeResponse setDailyChecks(
            @PathVariable UUID employeeId, @Valid @RequestBody DailyChecksRequest request) {
        var employee = employees.findById(employeeId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "employee not found"));
        if (employee.isDailyChecksEnabled() != request.enabled()) {
            employee.setDailyChecksEnabled(request.enabled());
            employees.save(employee);
            audit.record("admin", request.enabled() ? "DAILY_CHECKS_ENABLED" : "DAILY_CHECKS_DISABLED",
                    "employee", employeeId.toString(), "{\"enabled\":" + request.enabled() + "}");
        }
        return toResponse(employee);
    }

    public record BulkDailyChecksRequest(
            boolean enabled, List<String> employeeIds, String department, Boolean all) {}

    public record BulkDailyChecksResponse(
            /** How many employees actually changed (already-matching ones are not counted). */
            int updated,
            /** How many the selection covered. */
            int matched) {}

    /**
     * Daily-check roster in bulk: the given employees, a whole department, or
     * every employee (all = true). Only ACTIVE employees are touched — the flag
     * is meaningless on an offboarded record. One audit row carries the scope and
     * the count, rather than a thousand rows saying the same sentence.
     */
    @org.springframework.web.bind.annotation.PutMapping("/employees/daily-checks")
    @Transactional
    public BulkDailyChecksResponse setDailyChecksBulk(@Valid @RequestBody BulkDailyChecksRequest request) {
        var department = trimToNull(request.department());
        var ids = new java.util.LinkedHashSet<UUID>();
        if (request.employeeIds() != null) {
            for (var raw : request.employeeIds()) {
                if (trimToNull(raw) != null) {
                    ids.add(UUID.fromString(raw.trim()));
                }
            }
        }

        List<Employee> targets;
        String scope;
        if (!ids.isEmpty()) {
            targets = new java.util.ArrayList<>();
            for (var id : ids) {
                targets.add(employees.findById(id)
                        .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "employee not found")));
            }
            scope = "ids:" + ids.size();
        } else if (department != null) {
            targets = employees.findAll().stream()
                    .filter(e -> department.equalsIgnoreCase(e.getDepartment()))
                    .toList();
            scope = "department:" + department;
        } else if (Boolean.TRUE.equals(request.all())) {
            targets = employees.findAll();
            scope = "all";
        } else {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Select employees, a department, or all = true");
        }

        int matched = 0;
        int updated = 0;
        for (var employee : targets) {
            if (employee.getStatus() != com.empverify.domain.Enums.EmployeeStatus.ACTIVE) {
                continue;
            }
            matched++;
            if (employee.isDailyChecksEnabled() != request.enabled()) {
                employee.setDailyChecksEnabled(request.enabled());
                employees.save(employee);
                updated++;
            }
        }
        if (updated > 0) {
            audit.record("admin",
                    request.enabled() ? "DAILY_CHECKS_BULK_ENABLED" : "DAILY_CHECKS_BULK_DISABLED",
                    "employee", scope,
                    "{\"updated\":" + updated + ",\"matched\":" + matched + "}");
        }
        return new BulkDailyChecksResponse(updated, matched);
    }

    public record RequestCheckRequest(String employeeId, List<String> employeeIds, String department) {}

    public record RequestCheckTarget(String employeeId, String employeeName, String department, int systems) {}

    /** One selected employee no check could be sent to, and why. */
    public record SkippedTarget(String employeeId, String employeeName, String reason) {}

    public record RequestCheckResponse(List<RequestCheckTarget> sent, List<SkippedTarget> skipped) {}

    /**
     * Sends a verification check now. The selection can be one employee, many
     * (the portal's multi-select / "select all"), or nobody — in which case it
     * goes to a random ACTIVE employee with an active system, across the whole
     * company or within the given department. Each chosen employee's check is
     * sent independently: one suspended record or retired PC skips that person
     * (reported in the response) instead of failing the whole batch. Only a
     * batch where NOBODY could receive a check is an error, so "sent" can never
     * silently mean "sent to no one". Each agent opens its scan within a minute
     * of being online; results land in the checks list as ON_DEMAND.
     */
    @PostMapping("/checks/request")
    public RequestCheckResponse requestCheck(
            @RequestBody(required = false) RequestCheckRequest request) {
        var department = request == null ? null : trimToNull(request.department());

        // The selection, in the order it was made (employeeId is the single-select
        // form the employee row uses; a duplicate id is one employee, not two checks).
        var ids = new java.util.LinkedHashSet<UUID>();
        if (request != null && request.employeeIds() != null) {
            for (var raw : request.employeeIds()) {
                if (trimToNull(raw) != null) {
                    ids.add(UUID.fromString(raw.trim()));
                }
            }
        }
        if (request != null && trimToNull(request.employeeId()) != null) {
            ids.add(UUID.fromString(request.employeeId().trim()));
        }

        List<Employee> targets;
        if (ids.isEmpty()) {
            targets = List.of(pickRandomCheckable(department));
        } else {
            targets = new java.util.ArrayList<>();
            for (var id : ids) {
                targets.add(employees.findById(id)
                        .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "employee not found")));
            }
        }

        var sent = new java.util.ArrayList<RequestCheckTarget>();
        var skipped = new java.util.ArrayList<SkippedTarget>();
        for (var target : targets) {
            if (target.getStatus() != com.empverify.domain.Enums.EmployeeStatus.ACTIVE) {
                skipped.add(new SkippedTarget(
                        target.getId().toString(), target.getFullName(), "not an active employee"));
                continue;
            }
            int systems = deviceService.requestCheck(target.getId(), "admin");
            if (systems == 0) {
                skipped.add(new SkippedTarget(
                        target.getId().toString(), target.getFullName(), "no active system"));
                continue;
            }
            sent.add(new RequestCheckTarget(
                    target.getId().toString(), target.getFullName(), target.getDepartment(), systems));
        }

        if (sent.isEmpty()) {
            if (skipped.size() == 1) {
                var only = skipped.get(0);
                throw new ApiException(HttpStatus.CONFLICT, "no active system".equals(only.reason())
                        ? only.employeeName() + " has no active system to open a check on"
                        : only.employeeName() + " is not an active employee");
            }
            throw new ApiException(HttpStatus.CONFLICT,
                    "No check could be sent: none of the selected employees has an active system");
        }
        return new RequestCheckResponse(List.copyOf(sent), List.copyOf(skipped));
    }

    /**
     * A random ACTIVE employee who actually has an active system — picking someone
     * whose PC cannot open a scan would be a check that silently never happens.
     */
    private Employee pickRandomCheckable(String department) {
        var checkable = new java.util.HashSet<UUID>();
        deviceService.listDevices().forEach(d -> {
            if (d.getStatus() == com.empverify.domain.Enums.DeviceStatus.ACTIVE && d.getEmployeeId() != null) {
                checkable.add(d.getEmployeeId());
            }
        });
        var candidates = employees.findAll().stream()
                .filter(e -> e.getStatus() == com.empverify.domain.Enums.EmployeeStatus.ACTIVE)
                .filter(e -> checkable.contains(e.getId()))
                .filter(e -> department == null || department.equalsIgnoreCase(e.getDepartment()))
                .toList();
        if (candidates.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, department == null
                    ? "No active employee has an active system to check"
                    : "No active employee in " + department + " has an active system to check");
        }
        return candidates.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(candidates.size()));
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    public record AdminCheckRow(
            String checkId, String status, boolean mismatchFlag, Double similarityScore,
            Double livenessConfidence,
            String checkType, String employeeId, String employeeCode, String employeeName,
            String employeeDepartment,
            String deviceId, boolean hasEvidence, String errorDetail,
            java.time.Instant requestedAt, java.time.Instant completedAt,
            String reviewResolution) {}

    /** Recent checks across the fleet, newest first, with employee context (dashboard feed). */
    @GetMapping("/checks")
    public List<AdminCheckRow> recentChecks(@RequestParam(defaultValue = "50") int limit) {
        var rows = checks.findAllByOrderByRequestedAtDesc(PageRequest.of(0, Math.min(limit, 1000)));
        var employeesById = new java.util.HashMap<UUID, Employee>();
        employees.findAllById(rows.stream().map(c -> c.getEmployeeId()).distinct().toList())
                .forEach(e -> employeesById.put(e.getId(), e));
        var reviewByCheck = new java.util.HashMap<UUID, String>();
        reviews.findByCheckIdInOrderByCreatedAtDesc(rows.stream().map(c -> c.getId()).toList())
                .forEach(r -> reviewByCheck.putIfAbsent(r.getCheckId(), r.getResolution()));

        return rows.stream().map(c -> {
            var emp = employeesById.get(c.getEmployeeId());
            return new AdminCheckRow(
                    c.getId().toString(), c.getStatus().name(), c.isMismatchFlag(),
                    c.getSimilarityScore() == null ? null : c.getSimilarityScore().doubleValue(),
                    c.getLivenessConfidence() == null ? null : c.getLivenessConfidence().doubleValue(),
                    c.getCheckType().name(), c.getEmployeeId().toString(),
                    emp == null ? null : emp.getEmployeeCode(),
                    emp == null ? null : emp.getFullName(),
                    emp == null ? null : emp.getDepartment(),
                    c.getDeviceId() == null ? null : c.getDeviceId().toString(),
                    c.getEvidenceS3Key() != null, c.getErrorDetail(),
                    c.getRequestedAt(), c.getCompletedAt(),
                    reviewByCheck.get(c.getId()));
        }).toList();
    }

    public record ReviewRequest(
            @NotBlank @jakarta.validation.constraints.Pattern(
                regexp = "CONFIRMED_EMPLOYEE|CONFIRMED_MISMATCH|TECHNICAL_ISSUE|INCONCLUSIVE")
            String resolution,
            String notes) {}

    /** Records a human review of a flagged check. CHECK is a review status, never a verdict. */
    @PostMapping("/checks/{checkId}/review")
    public AdminCheckRow review(@PathVariable UUID checkId, @Valid @RequestBody ReviewRequest request) {
        var check = checks.findById(checkId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "check not found"));
        var review = new com.empverify.domain.CheckReview();
        review.setCheckId(checkId);
        review.setReviewer("admin");
        review.setResolution(request.resolution());
        review.setNotes(request.notes());
        reviews.save(review);
        audit.record("admin", "CHECK_REVIEWED", "check", checkId.toString(),
                "{\"resolution\":\"" + request.resolution() + "\"}");
        var emp = employees.findById(check.getEmployeeId()).orElse(null);
        return new AdminCheckRow(
                check.getId().toString(), check.getStatus().name(), check.isMismatchFlag(),
                check.getSimilarityScore() == null ? null : check.getSimilarityScore().doubleValue(),
                check.getLivenessConfidence() == null ? null : check.getLivenessConfidence().doubleValue(),
                check.getCheckType().name(), check.getEmployeeId().toString(),
                emp == null ? null : emp.getEmployeeCode(),
                emp == null ? null : emp.getFullName(),
                emp == null ? null : emp.getDepartment(),
                check.getDeviceId() == null ? null : check.getDeviceId().toString(),
                check.getEvidenceS3Key() != null, check.getErrorDetail(),
                check.getRequestedAt(), check.getCompletedAt(),
                request.resolution());
    }

    /** The uploaded ID/photo reference, shown next to the evidence when reviewing a check. */
    @GetMapping("/employees/{employeeId}/reference-image")
    public org.springframework.http.ResponseEntity<byte[]> referenceImage(@PathVariable UUID employeeId) {
        return serveReference(employeeId, ReferenceImageSource.ADMIN_UPLOAD);
    }

    /** The frame from the employee's first matching live scan (the live-enrollment reference). */
    @GetMapping("/employees/{employeeId}/enrolled-image")
    public org.springframework.http.ResponseEntity<byte[]> enrolledImage(@PathVariable UUID employeeId) {
        return serveReference(employeeId, ReferenceImageSource.LIVE_ENROLLMENT);
    }

    private org.springframework.http.ResponseEntity<byte[]> serveReference(
            UUID employeeId, ReferenceImageSource source) {
        var reference = referenceImages
                .findByEmployeeIdAndStatusAndSource(employeeId, ReferenceImageStatus.ACTIVE, source)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "no active " + source + " reference"));
        audit.record("admin", "REFERENCE_IMAGE_VIEWED", "employee", employeeId.toString(),
                "{\"source\":\"" + source + "\"}");
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Type", "image/jpeg")
                .body(storage.load(reference.getS3Key()));
    }

    /** Evidence image for a flagged check (retained only for non-MATCH outcomes). */
    @GetMapping("/checks/{checkId}/evidence")
    public org.springframework.http.ResponseEntity<byte[]> evidence(@PathVariable UUID checkId) {
        var check = checks.findById(checkId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "check not found"));
        if (check.getEvidenceS3Key() == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "no evidence retained for this check");
        }
        var bytes = storage.load(check.getEvidenceS3Key());
        if (!ReferenceImageService.looksLikeImage(bytes)) {
            // Defensive: a retained frame that is not a picture (e.g. an empty frame
            // from the liveness service) is reported, not served as a broken image.
            throw new ApiException(HttpStatus.NOT_FOUND, "the retained frame for this check is not a readable image");
        }
        audit.record("admin", "EVIDENCE_VIEWED", "check", checkId.toString(), null);
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Type", "image/jpeg")
                .body(bytes);
    }

    private EmployeeResponse toResponse(Employee employee) {
        boolean hasImage = referenceImages.findByEmployeeIdAndStatusAndSource(
                employee.getId(), ReferenceImageStatus.ACTIVE, ReferenceImageSource.ADMIN_UPLOAD).isPresent();
        boolean hasLive = referenceImages.findByEmployeeIdAndStatusAndSource(
                employee.getId(), ReferenceImageStatus.ACTIVE, ReferenceImageSource.LIVE_ENROLLMENT).isPresent();
        return new EmployeeResponse(
                employee.getId().toString(), employee.getEmployeeCode(), employee.getFullName(),
                employee.getEmail(), employee.getDepartment(), employee.getStatus().name(), hasImage, hasLive,
                employee.isDailyChecksEnabled());
    }
}
