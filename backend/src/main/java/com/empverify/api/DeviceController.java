package com.empverify.api;

import com.empverify.service.DeviceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;
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

    public DeviceController(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    public record RegisterRequest(
            @NotBlank String enrollmentToken,
            @NotBlank @Pattern(regexp = "WINDOWS|MACOS") String platform,
            String hostname,
            String agentVersion) {}

    public record RegisterResponse(String deviceId, String deviceSecret) {}

    public record AuthRequest(@NotBlank String deviceId, @NotBlank String deviceSecret) {}

    public record AuthResponse(String accessToken, long expiresInSeconds) {}

    /**
     * Exchanges a one-time enrollment token (issued by an admin) for a device identity.
     * The returned secret is shown exactly once; the agent stores it in the OS
     * credential store and only its hash is kept server-side.
     */
    @PostMapping("/register")
    public RegisterResponse register(@Valid @RequestBody RegisterRequest request) {
        var registration = deviceService.register(
                request.enrollmentToken(), request.platform(), request.hostname(), request.agentVersion());
        return new RegisterResponse(registration.deviceId().toString(), registration.deviceSecret());
    }

    @PostMapping("/auth")
    public AuthResponse auth(@Valid @RequestBody AuthRequest request) {
        var auth = deviceService.authenticate(UUID.fromString(request.deviceId()), request.deviceSecret());
        return new AuthResponse(auth.accessToken(), auth.expiresInSeconds());
    }
}
