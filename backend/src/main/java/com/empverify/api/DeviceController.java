package com.empverify.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Device lifecycle endpoints used by the desktop agent.
 * Contract: docs/openapi/agent-api.yaml
 */
@RestController
@RequestMapping("/api/v1/devices")
public class DeviceController {

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
        // TODO: validate enrollment token, create device row, return generated credentials.
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Phase 1 service pending");
    }

    @PostMapping("/auth")
    public AuthResponse auth(@Valid @RequestBody AuthRequest request) {
        // TODO: verify secret hash, issue short-lived device token.
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Phase 1 service pending");
    }
}

