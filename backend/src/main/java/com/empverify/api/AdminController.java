package com.empverify.api;

import com.empverify.domain.Employee;
import com.empverify.domain.Enums.ReferenceImageSource;
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

    public record EmployeeResponse(
            String id, String employeeCode, String fullName, String email,
            String department, String status,
            /** An administrator has uploaded the ID/photo reference. */
            boolean hasReferenceImage,
            /** The employee's first live scan matched it and is now the second reference. */
            boolean hasLiveEnrollment) {}

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
        employeeCode = required(employeeCode, "Employee code is required");
        fullName = required(fullName, "Full name is required");
        email = required(email, "Email is required");
        if (!EMAIL.matcher(email).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Email address is not valid");
        }
        if (image == null || image.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "A photo or ID image is required to create an employee");
        }
        requireImage(image);
        if (employees.existsByEmployeeCodeIgnoreCase(employeeCode)) {
            throw new ApiException(HttpStatus.CONFLICT, "An employee with code " + employeeCode + " already exists");
        }
        if (employees.existsByEmailIgnoreCase(email)) {
            throw new ApiException(HttpStatus.CONFLICT, "An employee with email " + email + " already exists");
        }

        var employee = new Employee();
        employee.setEmployeeCode(employeeCode);
        employee.setFullName(fullName);
        employee.setEmail(email);
        employee.setDepartment(department == null || department.isBlank() ? null : department.trim());
        employees.save(employee);

        saveAdminUpload(employee.getId(), image);
        audit.record("admin", "EMPLOYEE_CREATED", "employee", employee.getId().toString(), null);
        audit.record("admin", "REFERENCE_IMAGE_UPLOADED", "employee", employee.getId().toString(), null);
        return toResponse(employee);
    }

    private static String required(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, message);
        }
        return value.trim();
    }

    /** Only JPEG/PNG uploads are accepted; anything else cannot be a usable reference. */
    private static void requireImage(MultipartFile image) {
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
        var reference = new ReferenceImage();
        reference.setEmployeeId(employeeId);
        reference.setSource(ReferenceImageSource.ADMIN_UPLOAD);
        reference.setCreatedBy("admin");
        try {
            reference.setS3Key(storage.store(
                    "reference/" + employeeId + "/" + reference.getId() + ".jpg", image.getBytes()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        referenceImages.save(reference);
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

        saveAdminUpload(employeeId, image);
        audit.record("admin", "REFERENCE_IMAGE_UPLOADED", "employee", employeeId.toString(), null);
        return toResponse(employee);
    }

    public record AdminCheckRow(
            String checkId, String status, boolean mismatchFlag, Double similarityScore,
            Double livenessConfidence,
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
                    c.getLivenessConfidence() == null ? null : c.getLivenessConfidence().doubleValue(),
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
                check.getLivenessConfidence() == null ? null : check.getLivenessConfidence().doubleValue(),
                check.getCheckType().name(), check.getEmployeeId().toString(),
                emp == null ? null : emp.getEmployeeCode(),
                emp == null ? null : emp.getFullName(),
                check.getDeviceId().toString(),
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
        audit.record("admin", "EVIDENCE_VIEWED", "check", checkId.toString(), null);
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Type", "image/jpeg")
                .body(storage.load(check.getEvidenceS3Key()));
    }

    private EmployeeResponse toResponse(Employee employee) {
        boolean hasImage = referenceImages.findByEmployeeIdAndStatusAndSource(
                employee.getId(), ReferenceImageStatus.ACTIVE, ReferenceImageSource.ADMIN_UPLOAD).isPresent();
        boolean hasLive = referenceImages.findByEmployeeIdAndStatusAndSource(
                employee.getId(), ReferenceImageStatus.ACTIVE, ReferenceImageSource.LIVE_ENROLLMENT).isPresent();
        return new EmployeeResponse(
                employee.getId().toString(), employee.getEmployeeCode(), employee.getFullName(),
                employee.getEmail(), employee.getDepartment(), employee.getStatus().name(), hasImage, hasLive);
    }
}
