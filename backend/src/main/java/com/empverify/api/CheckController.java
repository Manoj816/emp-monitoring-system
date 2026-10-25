package com.empverify.api;

import com.empverify.domain.Enums.CheckType;
import com.empverify.domain.VerificationCheck;
import com.empverify.service.PolicyService;
import com.empverify.service.DeviceService;
import com.empverify.service.VerificationService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Verification-check lifecycle used by the desktop agent. All verification decisions
 * are made here, server-side — the agent never computes or reports its own result.
 * Contract: docs/openapi/agent-api.yaml
 */
@RestController
@RequestMapping("/api/v1")
public class CheckController {

    private final VerificationService verificationService;
    private final DeviceService deviceService;
    private final PolicyService policyService;

    public CheckController(
            VerificationService verificationService,
            DeviceService deviceService,
            PolicyService policyService) {
        this.verificationService = verificationService;
        this.deviceService = deviceService;
        this.policyService = policyService;
    }

    public record PolicyResponse(
            String workWindowStart, String workWindowEnd, String timezone,
            int maxChecksPerDay, int responseTimeoutSeconds, boolean livenessOnSessionStart) {}

    public record OpenCheckResponse(String checkId, String nonce, OffsetDateTime expiresAt) {}

    public record CheckResultResponse(
            String checkId, String status, boolean mismatchFlag,
            Double similarityScore, Double livenessConfidence, OffsetDateTime completedAt) {}

    /** Current check policy for the authenticated device; fetched at session start. */
    @GetMapping("/policy")
    public PolicyResponse policy(Authentication auth) {
        var device = deviceService.requireActiveDevice(deviceId(auth));
        var policy = policyService.effectiveFor(device);
        return new PolicyResponse(
                policy.getWorkWindowStart().toString(),
                policy.getWorkWindowEnd().toString(),
                policy.getTimezone(),
                policy.getMaxChecksPerDay(),
                policy.getResponseTimeoutSeconds(),
                policy.isLivenessOnSessionStart());
    }

    /** Opens a check: issues a one-time check ID + nonce with a short expiry. */
    @PostMapping("/checks")
    public OpenCheckResponse openCheck(@RequestParam String checkType, Authentication auth) {
        var check = verificationService.openCheck(deviceId(auth), CheckType.valueOf(checkType));
        return new OpenCheckResponse(
                check.getId().toString(), check.getNonce(), check.getExpiresAt().atOffset(ZoneOffset.UTC));
    }

    /**
     * Uploads the captured frame for a pending check. The server validates the nonce
     * and expiry, then runs face detection + comparison and records the outcome.
     */
    @PostMapping("/checks/{checkId}/capture")
    public CheckResultResponse capture(
            @PathVariable UUID checkId,
            @RequestParam String nonce,
            @RequestParam("image") MultipartFile image,
            @RequestParam(required = false) String cameraDeviceName,
            Authentication auth) {
        byte[] bytes;
        try {
            bytes = image.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        var check = verificationService.capture(deviceId(auth), checkId, nonce, bytes, cameraDeviceName);
        return toResult(check);
    }

    /** Creates a Face Liveness session for this check (Phase 2). */
    @PostMapping("/checks/{checkId}/liveness-session")
    public void livenessSession(@PathVariable UUID checkId) {
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Phase 2");
    }

    /** Polled by the agent for the outcome of a check. */
    @GetMapping("/checks/{checkId}/result")
    public CheckResultResponse result(@PathVariable UUID checkId, Authentication auth) {
        return toResult(verificationService.getOwnedCheck(deviceId(auth), checkId));
    }

    private static UUID deviceId(Authentication auth) {
        return UUID.fromString(auth.getName());
    }

    static CheckResultResponse toResult(VerificationCheck check) {
        return new CheckResultResponse(
                check.getId().toString(),
                check.getStatus().name(),
                check.isMismatchFlag(),
                check.getSimilarityScore() == null ? null : check.getSimilarityScore().doubleValue(),
                check.getLivenessConfidence() == null ? null : check.getLivenessConfidence().doubleValue(),
                check.getCompletedAt() == null ? null : check.getCompletedAt().atOffset(ZoneOffset.UTC));
    }
}
