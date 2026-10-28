package com.empverify.api;

import com.empverify.domain.Employee;
import com.empverify.domain.Enums.ReferenceImageStatus;
import com.empverify.domain.ReferenceImage;
import com.empverify.repo.EmployeeRepository;
import com.empverify.repo.ReferenceImageRepository;
import com.empverify.repo.VerificationCheckRepository;
import com.empverify.service.ApiException;
import com.empverify.service.AuditService;
import com.empverify.service.DeviceService;
import com.empverify.storage.ImageStorage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
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
    private final DeviceService deviceService;
    private final ImageStorage storage;
    private final AuditService audit;

    public AdminController(
            EmployeeRepository employees,
            ReferenceImageRepository referenceImages,
            VerificationCheckRepository checks,
            com.empverify.repo.CheckReviewRepository reviews,
            DeviceService deviceService,
            ImageStorage storage,
            AuditService audit) {
        this.employees = employees;
        this.referenceImages = referenceImages;
        this.checks = checks;
        this.reviews = reviews;
        this.deviceService = deviceService;
        this.storage = storage;
        this.audit = audit;
    }

    public record CreateEmployeeRequest(
            @NotBlank String employeeCode,
            @NotBlank String fullName,
            @NotBlank @Email String email,
            String department) {}

    public record EmployeeResponse(
            String id, String employeeCode, String fullName, String email,
            String department, String status, boolean hasReferenceImage) {}

    public record EnrollmentTokenResponse(String enrollmentToken) {}

    @PostMapping("/employees")
    public EmployeeResponse createEmployee(@Valid @RequestBody CreateEmployeeRequest request) {
        var employee = new Employee();
        employee.setEmployeeCode(request.employeeCode());
        employee.setFullName(request.fullName());
        employee.setEmail(request.email());
        employee.setDepartment(request.department());
        employees.save(employee);
        audit.record("admin", "EMPLOYEE_CREATED", "employee", employee.getId().toString(), null);
        return toResponse(employee);
    }

    @GetMapping("/employees")
    public List<EmployeeResponse> listEmployees() {
        return employees.findAll().stream().map(this::toResponse).toList();
    }

    /** One-time token the employee enters in the agent's registration screen. */
    @PostMapping("/employees/{employeeId}/enrollment-tokens")
    public EnrollmentTokenResponse issueEnrollmentToken(@PathVariable UUID employeeId) {
        return new EnrollmentTokenResponse(deviceService.issueEnrollmentToken(employeeId, "admin"));
    }

    public record DeviceSummary(
            String id, String employeeId, String employeeName, String platform,
            String hostname, String status, String agentVersion,
            java.time.Instant registeredAt, java.time.Instant lastSeenAt) {}

    public record OffboardResponse(String employeeId, String status, int devicesRevoked) {}

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
                    d.getAgentVersion(), d.getRegisteredAt(), d.getLastSeenAt());
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
                d.getAgentVersion(), d.getRegisteredAt(), d.getLastSeenAt());
    }

    /** Revokes a device's authorization (lost/retired machine). */
    @PostMapping("/devices/{deviceId}/revoke")
    public void revokeDevice(@PathVariable UUID deviceId) {
        deviceService.revokeDevice(deviceId, "admin");
    }

    /** Verifies the tamper-evident audit log hash chain is intact. */
    @GetMapping("/audit/verify")
    public java.util.Map<String, Object> verifyAudit() {
        return java.util.Map.of("intact", audit.verifyChain());
    }

    /** Offboards an employee: marks OFFBOARDED and revokes all their devices. */
    @PostMapping("/employees/{employeeId}/offboard")
    public OffboardResponse offboard(@PathVariable UUID employeeId) {
        int revoked = deviceService.offboardEmployee(employeeId, "admin");
        return new OffboardResponse(employeeId.toString(), "OFFBOARDED", revoked);
    }

    /** Uploads/replaces the employee's reference image; the previous one is marked REPLACED. */
    @PostMapping("/employees/{employeeId}/reference-image")
    public EmployeeResponse uploadReferenceImage(
            @PathVariable UUID employeeId, @RequestParam("image") MultipartFile image) {
        var employee = employees.findById(employeeId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "employee not found"));

        referenceImages.findByEmployeeIdAndStatus(employeeId, ReferenceImageStatus.ACTIVE)
                .ifPresent(previous -> {
                    previous.setStatus(ReferenceImageStatus.REPLACED);
                    referenceImages.save(previous);
                });

        var reference = new ReferenceImage();
        reference.setEmployeeId(employeeId);
        reference.setCreatedBy("admin");
        try {
            reference.setS3Key(storage.store(
                    "reference/" + employeeId + "/" + reference.getId() + ".jpg", image.getBytes()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        referenceImages.save(reference);
        audit.record("admin", "REFERENCE_IMAGE_UPLOADED", "employee", employeeId.toString(), null);
        return toResponse(employee);
    }

    public record AdminCheckRow(
            String checkId, String status, boolean mismatchFlag, Double similarityScore,
            String checkType, String employeeId, String employeeCode, String employeeName,
            String deviceId, boolean hasEvidence, String errorDetail,
            java.time.Instant requestedAt, java.time.Instant completedAt,
            String reviewResolution) {}

    /** Recent checks across the fleet, newest first, with employee context (dashboard feed). */
    @GetMapping("/checks")
    public List<AdminCheckRow> recentChecks(@RequestParam(defaultValue = "50") int limit) {
        var rows = checks.findAllByOrderByRequestedAtDesc(PageRequest.of(0, Math.min(limit, 200)));
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
                    c.getCheckType().name(), c.getEmployeeId().toString(),
                    emp == null ? null : emp.getEmployeeCode(),
                    emp == null ? null : emp.getFullName(),
                    c.getDeviceId().toString(),
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
                check.getCheckType().name(), check.getEmployeeId().toString(),
                emp == null ? null : emp.getEmployeeCode(),
                emp == null ? null : emp.getFullName(),
                check.getDeviceId().toString(),
                check.getEvidenceS3Key() != null, check.getErrorDetail(),
                check.getRequestedAt(), check.getCompletedAt(),
                request.resolution());
    }

    /** Evidence image for a flagged check (retained only for non-MATCH outcomes). */
    @GetMapping("/checks/{checkId}/evidence")
    public org.springframework.http.ResponseEntity<byte[]> evidence(@PathVariable UUID checkId) {
        var check = checks.findById(checkId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "check not found"));
        if (check.getEvidenceS3Key() == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "no evidence retained for this check");
        }
        audit.record("admin", "EVIDENCE_VIEWED", "check", checkId.toString(), null);
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Type", "image/jpeg")
                .body(storage.load(check.getEvidenceS3Key()));
    }

    private EmployeeResponse toResponse(Employee employee) {
        boolean hasImage = referenceImages
                .findByEmployeeIdAndStatus(employee.getId(), ReferenceImageStatus.ACTIVE)
                .isPresent();
        return new EmployeeResponse(
                employee.getId().toString(), employee.getEmployeeCode(), employee.getFullName(),
                employee.getEmail(), employee.getDepartment(), employee.getStatus().name(), hasImage);
    }
}
