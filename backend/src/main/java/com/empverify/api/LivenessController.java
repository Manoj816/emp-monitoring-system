package com.empverify.api;

import com.empverify.service.LivenessService;
import com.empverify.service.VerificationService;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Browser-facing liveness callbacks. Called by the hosted React liveness page
 * after the challenge completes, or when the employee cancels / the scan cannot
 * run. Public (no device token): authorized by the one-time check nonce, which
 * is single-use because the check leaves PENDING.
 */
@RestController
@RequestMapping("/api/v1/liveness")
public class LivenessController {

    private final LivenessService livenessService;
    private final VerificationService verificationService;

    public LivenessController(LivenessService livenessService, VerificationService verificationService) {
        this.livenessService = livenessService;
        this.verificationService = verificationService;
    }

    @PostMapping("/{checkId}/complete")
    public CheckController.CheckResultResponse complete(
            @PathVariable UUID checkId, @RequestParam @NotBlank String token) {
        return CheckController.toResult(livenessService.completeLiveness(checkId, token));
    }

    public record RestartResponse(String sessionId) {}

    /**
     * A fresh liveness session for the same check, so a scan the employee cancelled
     * or that errored starts again in place. The check is required: cancelling only
     * restarts it, and the response window keeps running.
     */
    @PostMapping("/{checkId}/restart")
    public RestartResponse restart(@PathVariable UUID checkId, @RequestParam @NotBlank String token) {
        return new RestartResponse(livenessService.restartSession(checkId, token));
    }

    /**
     * The page gave up (restarts exhausted, or the scan cannot run on this device).
     * Ends the check as EXPIRED with the reason right away, so the agent (polling
     * the result) can offer the scan again instead of waiting for the full window.
     */
    @PostMapping("/{checkId}/cancel")
    public CheckController.CheckResultResponse cancel(
            @PathVariable UUID checkId,
            @RequestParam @NotBlank String token,
            @RequestParam(required = false) String reason) {
        return CheckController.toResult(verificationService.cancelFromBrowser(
                checkId, token, VerificationService.CancelReason.parse(reason)));
    }
}
