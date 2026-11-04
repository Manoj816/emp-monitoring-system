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
    private final com.empverify.service.LivenessService livenessService;

    public CheckController(
            VerificationService verificationService,
            DeviceService deviceService,
            PolicyService policyService,
            com.empverify.service.LivenessService livenessService) {
        this.verificationService = verificationService;
        this.deviceService = deviceService;
        this.policyService = policyService;
        this.livenessService = livenessService;
    }

    public record PolicyResponse(
            String workWindowStart, String workWindowEnd, String timezone,
            int maxChecksPerDay, int responseTimeoutSeconds, boolean livenessOnSessionStart,
            boolean captureOnTimeout) {}

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
                policy.isLivenessOnSessionStart(),
                policy.isCaptureOnTimeout());
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

    /**
     * A check whose response window closed with nobody answering it, with the frame
     * the agent took at that moment. The photo is kept only while this tenant's
     * policy asks for it: the agent reports what it saw, the server decides what is
     * retained. Sending it when the policy is off records the miss and drops the image.
     */
    @PostMapping("/checks/{checkId}/unanswered")
    public CheckResultResponse unanswered(
            @PathVariable UUID checkId,
            @RequestParam(value = "image", required = false) MultipartFile image,
            Authentication auth) {
        var device = deviceService.requireActiveDevice(deviceId(auth));
        byte[] bytes = null;
        if (image != null && !image.isEmpty() && policyService.effectiveFor(device).isCaptureOnTimeout()) {
            try {
                bytes = image.getBytes();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return toResult(verificationService.recordUnanswered(device.getId(), checkId, bytes));
    }

    public record CaptureSessionResponse(String captureUrl, java.time.OffsetDateTime expiresAt) {}

    /**
     * Creates an interactive-capture session for this check and returns the
     * one-time URL the agent opens in the browser. The employee captures a photo
     * there; the agent then polls /result.
     */
    @PostMapping("/checks/{checkId}/capture-session")
    public CaptureSessionResponse captureSession(@PathVariable UUID checkId, Authentication auth) {
        var session = verificationService.captureSession(deviceId(auth), checkId);
        return new CaptureSessionResponse(
                session.captureUrl(), session.expiresAt().atOffset(ZoneOffset.UTC));
    }

    public record LivenessSessionResponse(String livenessUrl, java.time.OffsetDateTime expiresAt) {}

    /**
     * Creates a liveness session for this check and returns the one-time URL the
     * agent opens in the default browser. The agent then polls /result.
     */
    @PostMapping("/checks/{checkId}/liveness-session")
    public LivenessSessionResponse livenessSession(@PathVariable UUID checkId, Authentication auth) {
        var session = livenessService.createSession(deviceId(auth), checkId);
        return new LivenessSessionResponse(
                session.livenessUrl(), session.expiresAt().atOffset(ZoneOffset.UTC));
    }

    /** Polled by the agent for the outcome of a check; a check past its deadline comes back EXPIRED. */
    @GetMapping("/checks/{checkId}/result")
    public CheckResultResponse result(@PathVariable UUID checkId, Authentication auth) {
        return toResult(verificationService.result(deviceId(auth), checkId));
    }

    /**
     * The agent reports that a pending check ended without a scan (the employee
     * closed the window, or the response window elapsed). Recorded as EXPIRED with
     * the reason, so administrators see a missed check rather than a stale one.
     * The optional note carries what the agent observed, which is what makes an
     * UNATTENDED miss readable ("held from 15:10 until the window closed at 17:00").
     */
    @PostMapping("/checks/{checkId}/cancel")
    public CheckResultResponse cancel(
            @PathVariable UUID checkId,
            @RequestParam(required = false) String reason,
            @RequestParam(required = false) String note,
            Authentication auth) {
        return toResult(verificationService.cancel(
                deviceId(auth), checkId, VerificationService.CancelReason.parse(reason), note));
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
