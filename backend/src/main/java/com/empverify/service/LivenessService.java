package com.empverify.service;

import com.empverify.domain.Enums.CheckStatus;
import com.empverify.domain.Enums.ReferenceImageStatus;
import com.empverify.domain.VerificationCheck;
import com.empverify.repo.ReferenceImageRepository;
import com.empverify.repo.VerificationCheckRepository;
import com.empverify.storage.ImageStorage;
import com.empverify.verification.FaceVerifier;
import com.empverify.verification.LivenessProvider;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Interactive Face Liveness: the agent opens a liveness check, the browser runs
 * the challenge, and the browser then calls back here to finalize. Liveness
 * confidence below the threshold is treated as CHECK (possible spoof); a passing
 * check is compared 1:1 against the enrolled reference like any other match.
 */
@Service
public class LivenessService {

    private final VerificationCheckRepository checks;
    private final ReferenceImageRepository referenceImages;
    private final LivenessProvider provider;
    private final FaceVerifier verifier;
    private final ImageStorage storage;
    private final PolicyService policyService;
    private final VerificationService verificationService;
    private final String livenessUiUrl;
    private final double livenessThreshold;

    public LivenessService(
            VerificationCheckRepository checks,
            ReferenceImageRepository referenceImages,
            LivenessProvider provider,
            FaceVerifier verifier,
            ImageStorage storage,
            PolicyService policyService,
            VerificationService verificationService,
            @Value("${empverify.liveness-ui-url}") String livenessUiUrl,
            @Value("${empverify.liveness-threshold:80}") double livenessThreshold) {
        this.checks = checks;
        this.referenceImages = referenceImages;
        this.provider = provider;
        this.verifier = verifier;
        this.storage = storage;
        this.policyService = policyService;
        this.verificationService = verificationService;
        this.livenessUiUrl = livenessUiUrl;
        this.livenessThreshold = livenessThreshold;
    }

    public record LivenessSession(String livenessUrl, Instant expiresAt) {}

    /** Agent-initiated: creates a provider session and the one-time browser URL. */
    @Transactional
    public LivenessSession createSession(UUID deviceId, UUID checkId) {
        var check = verificationService.getOwnedCheck(deviceId, checkId);
        if (check.getStatus() != CheckStatus.PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, "check already completed");
        }
        var session = provider.createSession();
        check.setLivenessSessionId(session.sessionId());
        checks.save(check);

        String url = livenessUiUrl + "?checkId=" + checkId
                + "&token=" + check.getNonce()
                + "&session=" + session.sessionId();
        return new LivenessSession(url, session.expiresAt());
    }

    /** Browser-initiated callback after the challenge; gated by the one-time nonce. */
    @Transactional
    public VerificationCheck completeLiveness(UUID checkId, String nonce) {
        var check = checks.findById(checkId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "check not found"));
        if (check.getStatus() != CheckStatus.PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, "check already completed");
        }
        if (!MessageDigest.isEqual(
                check.getNonce().getBytes(StandardCharsets.UTF_8),
                nonce.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.CONFLICT, "nonce mismatch");
        }
        if (check.getExpiresAt().isBefore(Instant.now()) || check.getLivenessSessionId() == null) {
            return verificationService.recordOutcome(
                    check, CheckStatus.EXPIRED, false, null, null, "liveness session expired", null);
        }

        var result = provider.getResult(check.getLivenessSessionId());

        // Spoof suspected: live-person confidence below threshold -> review.
        if (result.confidence() < livenessThreshold) {
            return verificationService.recordOutcome(
                    check, CheckStatus.CHECK, false, null, result.confidence(),
                    "liveness confidence below threshold", null);
        }

        var reference = referenceImages
                .findByEmployeeIdAndStatus(check.getEmployeeId(), ReferenceImageStatus.ACTIVE)
                .orElse(null);
        if (reference == null) {
            return verificationService.recordOutcome(
                    check, CheckStatus.VERIFICATION_ERROR, false, null, result.confidence(),
                    "no active reference image enrolled", null);
        }

        // Compare the liveness reference frame (or a stub placeholder) to the enrolled image.
        byte[] captured = result.referenceJpeg() != null ? result.referenceJpeg() : new byte[] {1};
        var outcome = verifier.verify(captured, storage.load(reference.getS3Key()));
        if (outcome.similarity() == null) {
            return verificationService.recordOutcome(
                    check, outcome.status(), false, null, result.confidence(), outcome.errorDetail(), captured);
        }

        var policy = policyService.effectiveForEmployee(check.getEmployeeId());
        var mapped = VerificationService.mapSimilarity(
                outcome.similarity(), policy.getMatchThreshold().doubleValue(),
                policy.getReviewThreshold().doubleValue());
        byte[] evidence = mapped.status() == CheckStatus.MATCH ? null : captured;
        return verificationService.recordOutcome(
                check, mapped.status(), mapped.mismatchFlag(), outcome.similarity(),
                result.confidence(), null, evidence);
    }
}
