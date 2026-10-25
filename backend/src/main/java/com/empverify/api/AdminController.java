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
    private final DeviceService deviceService;
    private final ImageStorage storage;
    private final AuditService audit;

    public AdminController(
            EmployeeRepository employees,
            ReferenceImageRepository referenceImages,
            VerificationCheckRepository checks,
            DeviceService deviceService,
            ImageStorage storage,
            AuditService audit) {
        this.employees = employees;
        this.referenceImages = referenceImages;
        this.checks = checks;
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

    /** Recent checks across the fleet, newest first (dashboard feed). */
    @GetMapping("/checks")
    public List<CheckController.CheckResultResponse> recentChecks(
            @RequestParam(defaultValue = "50") int limit) {
        return checks.findAllByOrderByRequestedAtDesc(PageRequest.of(0, Math.min(limit, 200)))
                .stream().map(CheckController::toResult).toList();
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
