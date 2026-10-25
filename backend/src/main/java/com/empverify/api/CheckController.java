package com.empverify.api;

import java.time.OffsetDateTime;
import org.springframework.http.HttpStatus;
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
 * are made here, server-side â€” the agent never computes or reports its own result.
 * Contract: docs/openapi/agent-api.yaml
 */
@RestController
@RequestMapping("/api/v1")
public class CheckController {

    public record PolicyResponse(
            String workWindowStart, String workWindowEnd, String timezone,
            int maxChecksPerDay, int responseTimeoutSeconds, boolean livenessOnSessionStart) {}

    public record OpenCheckResponse(
            String checkId, String nonce, OffsetDateTime expiresAt) {}

    public record CheckResultResponse(
            String checkId, String status, boolean mismatchFlag,
            Double similarityScore, Double livenessConfidence, OffsetDateTime completedAt) {}

    public record LivenessSessionResponse(String livenessUrl, OffsetDateTime expiresAt) {}

    /** Current check policy for the authenticated device; fetched at session start. */
    @GetMapping("/policy")
    public PolicyResponse policy() {
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Phase 1 service pending");
    }

    /**
     * Opens a check: issues a one-time check ID + nonce with a short expiry.
     * checkType: SESSION_START | PERIODIC | LIVENESS | ESCALATION
     */
    @PostMapping("/checks")
    public OpenCheckResponse openCheck(@RequestParam String checkType) {
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Phase 1 service pending");
    }

    /**
     * Uploads the captured frame for a pending check. The server validates the nonce
     * and expiry, then runs DetectFaces + CompareFaces and records the outcome.
     */
    @PostMapping("/checks/{checkId}/capture")
    public CheckResultResponse capture(
            @PathVariable String checkId,
            @RequestParam String nonce,
            @RequestParam MultipartFile image) {
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Phase 1 service pending");
    }

    /**
     * Creates a Rekognition Face Liveness session for this check and returns the
     * one-time URL the agent opens in the default browser (Phase 2).
     */
    @PostMapping("/checks/{checkId}/liveness-session")
    public LivenessSessionResponse livenessSession(@PathVariable String checkId) {
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Phase 2");
    }

    /** Polled by the agent for the outcome of a liveness check completed in the browser. */
    @GetMapping("/checks/{checkId}/result")
    public CheckResultResponse result(@PathVariable String checkId) {
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Phase 1 service pending");
    }
}

