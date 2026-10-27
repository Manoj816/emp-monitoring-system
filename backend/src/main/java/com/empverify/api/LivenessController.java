package com.empverify.api;

import com.empverify.service.LivenessService;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Browser-facing liveness callback. Called by the hosted React liveness page
 * after the challenge completes. Public (no device token): authorized by the
 * one-time check nonce, which is single-use because the check leaves PENDING.
 */
@RestController
@RequestMapping("/api/v1/liveness")
public class LivenessController {

    private final LivenessService livenessService;

    public LivenessController(LivenessService livenessService) {
        this.livenessService = livenessService;
    }

    @PostMapping("/{checkId}/complete")
    public CheckController.CheckResultResponse complete(
            @PathVariable UUID checkId, @RequestParam @NotBlank String token) {
        return CheckController.toResult(livenessService.completeLiveness(checkId, token));
    }
}
