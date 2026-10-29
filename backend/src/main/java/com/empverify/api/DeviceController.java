package com.empverify.api;

import com.empverify.domain.Enums.DeviceStatus;
import com.empverify.domain.Enums.ReferenceImageSource;
import com.empverify.domain.Enums.ReferenceImageStatus;
import com.empverify.repo.ReferenceImageRepository;
import com.empverify.service.DeviceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Device lifecycle endpoints used by the desktop agent.
 * Contract: docs/openapi/agent-api.yaml
 */
@RestController
@RequestMapping("/api/v1/devices")
public class DeviceController {

    private final DeviceService deviceService;
    private final ReferenceImageRepository referenceImages;

    public DeviceController(DeviceService deviceService, ReferenceImageRepository referenceImages) {
        this.deviceService = deviceService;
        this.referenceImages = referenceImages;
    }

    public record RegisterRequest(
            @NotBlank String enrollmentToken,
            @NotBlank @Pattern(regexp = "WINDOWS|MACOS") String platform,
            String hostname,
            String agentVersion) {}

    public record ProvisionRequest(
            @NotBlank String provisioningToken,
            @NotBlank @Pattern(regexp = "WINDOWS|MACOS") String platform,
            String hostname,
            String agentVersion) {}

    public record RegisterResponse(String deviceId, String deviceSecret) {}

    /** @param agentVersion optional; reported on every sign-in so an upgraded agent shows its real version */
    public record AuthRequest(@NotBlank String deviceId, @NotBlank String deviceSecret, String agentVersion) {}

    public record AuthResponse(String accessToken, long expiresInSeconds) {}

    /**
     * @param assigned linked to an employee and ACTIVE; checks require this
     * @param enrolled the employee's first live scan has matched the uploaded ID/photo;
     *                 until then the next check is the enrollment scan
     * @param employeeId the linked employee (null while unassigned); lets the agent
     *                   notice a reassignment or an offboarding without a restart
     */
    public record MeResponse(
            String deviceId, String status, boolean assigned, boolean enrolled, String employeeId) {}

    /**
     * Exchanges a one-time enrollment token (issued by an admin for one employee)
     * for a device identity bound to that employee. The returned secret is shown
     * exactly once; the agent stores it in the OS credential store and only its
     * hash is kept server-side.
     */
    @PostMapping("/register")
    public RegisterResponse register(@Valid @RequestBody RegisterRequest request) {
        var registration = deviceService.register(
                request.enrollmentToken(), request.platform(), request.hostname(), request.agentVersion());
        return new RegisterResponse(registration.deviceId().toString(), registration.deviceSecret());
    }

    /**
     * Device-first enrollment with the tenant provisioning token placed by the
     * installer/MDM. The device starts UNASSIGNED; an admin links it to an employee.
     */
    @PostMapping("/provision")
    public RegisterResponse provision(@Valid @RequestBody ProvisionRequest request) {
        var registration = deviceService.provision(
                request.provisioningToken(), request.platform(), request.hostname(), request.agentVersion());
        return new RegisterResponse(registration.deviceId().toString(), registration.deviceSecret());
    }

    @PostMapping("/auth")
    public AuthResponse auth(@Valid @RequestBody AuthRequest request) {
        var auth = deviceService.authenticate(
                UUID.fromString(request.deviceId()), request.deviceSecret(), request.agentVersion());
        return new AuthResponse(auth.accessToken(), auth.expiresInSeconds());
    }

    /** The device's own state; works while PENDING so the agent can wait for assignment. */
    @GetMapping("/me")
    public MeResponse me(Authentication auth) {
        var device = deviceService.getDevice(UUID.fromString(auth.getName()));
        boolean assigned = device.getEmployeeId() != null && device.getStatus() == DeviceStatus.ACTIVE;
        boolean enrolled = assigned && referenceImages.findByEmployeeIdAndStatusAndSource(
                device.getEmployeeId(), ReferenceImageStatus.ACTIVE, ReferenceImageSource.LIVE_ENROLLMENT)
                .isPresent();
        return new MeResponse(device.getId().toString(), device.getStatus().name(), assigned, enrolled,
                assigned ? device.getEmployeeId().toString() : null);
    }
}
