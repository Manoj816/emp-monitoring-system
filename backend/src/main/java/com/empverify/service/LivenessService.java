package com.empverify.service;

import com.empverify.domain.Enums.CheckStatus;
import com.empverify.domain.Enums.CheckType;
import com.empverify.domain.Enums.ReferenceImageSource;
import com.empverify.domain.Enums.ReferenceImageStatus;
import com.empverify.domain.ReferenceImage;
import com.empverify.domain.VerificationCheck;
import com.empverify.repo.ReferenceImageRepository;
import com.empverify.repo.VerificationCheckRepository;
import com.empverify.storage.ImageStorage;
import com.empverify.verification.LivenessProvider;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Interactive Face Liveness: the agent opens a check, the browser runs the
 * challenge, and the browser then calls back here to finalize.
 *
 * Verdict: the frame captured during the scan is compared against every
 * reference on file (the ID/photo an administrator uploaded and, once enrolled,
 * the employee's first live scan). Live-person confidence below the threshold is
 * CHECK (possible spoof) whatever the face said; the first MATCH that passes
 * liveness stores its frame as the live-enrollment reference. Every flag carries
 * a reviewer-facing explanation of what went wrong.
 */
@Service
public class LivenessService {

    private final VerificationCheckRepository checks;
    private final ReferenceImageRepository referenceImages;
    private final LivenessProvider provider;
    private final ImageStorage storage;
    private final PolicyService policyService;
    private final VerificationService verificationService;
    private final DeviceService deviceService;
    private final AuditService audit;
    private final String livenessUiUrl;
    private final double livenessThreshold;

    public LivenessService(
            VerificationCheckRepository checks,
            ReferenceImageRepository referenceImages,
            LivenessProvider provider,
            ImageStorage storage,
            PolicyService policyService,
            VerificationService verificationService,
            DeviceService deviceService,
            AuditService audit,
            @Value("${empverify.liveness-ui-url}") String livenessUiUrl,
            @Value("${empverify.liveness-threshold:80}") double livenessThreshold) {
        this.checks = checks;
        this.referenceImages = referenceImages;
        this.provider = provider;
        this.storage = storage;
        this.policyService = policyService;
        this.verificationService = verificationService;
        this.deviceService = deviceService;
        this.audit = audit;
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

    /**
     * Browser-initiated (nonce-gated): a fresh provider session for the same
     * check, so a scan the employee cancelled or that errored can start again in
     * place. The check is required; cancelling is not a way out of it.
     */
    @Transactional
    public String restartSession(UUID checkId, String nonce) {
        var check = pendingCheck(checkId, nonce);
        if (check.getExpiresAt().isBefore(Instant.now())) {
            throw new ApiException(HttpStatus.CONFLICT, "check expired");
        }
        var session = provider.createSession();
        check.setLivenessSessionId(session.sessionId());
        checks.save(check);
        return session.sessionId();
    }

    /** Browser-initiated callback after the challenge; gated by the one-time nonce. */
    @Transactional
    public VerificationCheck completeLiveness(UUID checkId, String nonce) {
        var check = pendingCheck(checkId, nonce);
        if (check.getExpiresAt().isBefore(Instant.now()) || check.getLivenessSessionId() == null) {
            return verificationService.recordOutcome(
                    check, CheckStatus.EXPIRED, false, null, null, "liveness session expired", null);
        }

        var result = provider.getResult(check.getLivenessSessionId());

        // The scan itself did not complete (challenge failed, session expired or still
        // running): a technical outcome the agent retries, not a spoof signal.
        if (!result.succeeded()) {
            return verificationService.recordOutcome(
                    check, CheckStatus.VERIFICATION_ERROR, false, null, null,
                    "The face scan did not complete (session "
                            + String.valueOf(result.status()).toLowerCase(Locale.ROOT)
                            + "): the camera stream ended before the challenge finished. The scan is offered again.",
                    null);
        }

        // No usable frame came back from the liveness service: nothing to compare and
        // nothing to retain (an empty "evidence" would only show as a broken picture).
        byte[] captured = result.referenceJpeg();
        if (captured == null || captured.length == 0) {
            if (result.confidence() < livenessThreshold) {
                String detail = String.format(Locale.ROOT,
                        "Live-person confidence %.1f is below the required %.0f and the liveness service "
                                + "returned no usable frame of a face, so there is nothing to compare: the "
                                + "camera most likely did not show a live person (photo or screen replay, "
                                + "face not in view, or the lens covered). Please follow up with the employee.",
                        result.confidence(), livenessThreshold);
                return verificationService.recordOutcome(
                        check, CheckStatus.CHECK, false, null, result.confidence(), detail, null);
            }
            return verificationService.recordOutcome(
                    check, CheckStatus.VERIFICATION_ERROR, false, null, result.confidence(),
                    "The scan passed the live-person check but the liveness service returned no frame "
                            + "to compare against the references. The scan is offered again.",
                    null);
        }

        // Compare the frame captured during the scan to every reference on file; the
        // best match decides. This runs even when liveness is low, so a reviewer sees
        // both signals instead of a bare flag.
        var match = verificationService.bestMatch(check.getEmployeeId(), captured);
        if (match == null) {
            return verificationService.recordOutcome(
                    check, CheckStatus.VERIFICATION_ERROR, false, null, result.confidence(),
                    "No reference image is enrolled for this employee: an administrator must upload "
                            + "an ID or photo before the face can be verified.",
                    null);
        }
        var outcome = match.outcome();
        if (outcome.similarity() == null) {
            return verificationService.recordOutcome(
                    check, outcome.status(), false, null, result.confidence(), outcome.errorDetail(), captured);
        }

        var policy = policyService.effectiveForEmployee(check.getEmployeeId());
        double matchThreshold = policy.getMatchThreshold().doubleValue();
        double reviewThreshold = policy.getReviewThreshold().doubleValue();
        var mapped = VerificationService.mapSimilarity(outcome.similarity(), matchThreshold, reviewThreshold);
        String against = describe(match.reference().getSource());

        // Spoof suspected: live-person confidence below threshold -> review, whatever
        // the face match said. The captured frame is retained as evidence.
        if (result.confidence() < livenessThreshold) {
            String detail = String.format(Locale.ROOT,
                    "Live-person confidence %.1f is below the required %.0f, so the scan could not confirm "
                            + "a live person (possible photo or screen replay, poor lighting, or the face not "
                            + "filling the frame). Face similarity to %s: %.1f (%s).",
                    result.confidence(), livenessThreshold, against, outcome.similarity(), mapped.status());
            return verificationService.recordOutcome(
                    check, CheckStatus.CHECK, mapped.mismatchFlag(), outcome.similarity(),
                    result.confidence(), detail, captured);
        }

        if (mapped.status() == CheckStatus.MATCH) {
            if (check.getCheckType() == CheckType.ENROLLMENT) {
                // The enrollment scan of this system: its frame becomes (or refreshes)
                // the live reference, and the system is enrolled from here on.
                storeLiveReference(check, captured, true);
                deviceService.markEnrolled(check.getDeviceId());
            } else if (referenceImages.findByEmployeeIdAndStatusAndSource(
                    check.getEmployeeId(), ReferenceImageStatus.ACTIVE, ReferenceImageSource.LIVE_ENROLLMENT)
                    .isEmpty()) {
                storeLiveReference(check, captured, false); // first live match on record
            }
            return verificationService.recordOutcome(
                    check, CheckStatus.MATCH, false, outcome.similarity(), result.confidence(), null, null);
        }

        String detail = mapped.mismatchFlag()
                ? String.format(Locale.ROOT,
                        "Face similarity %.1f to %s is below the review threshold %.0f: the face does not "
                                + "match the references on file. Live-person confidence %.1f (OK).",
                        outcome.similarity(), against, reviewThreshold, result.confidence())
                : String.format(Locale.ROOT,
                        "Face similarity %.1f to %s is between the review threshold %.0f and the match "
                                + "threshold %.0f: a borderline match, please compare the pictures. "
                                + "Live-person confidence %.1f (OK).",
                        outcome.similarity(), against, reviewThreshold, matchThreshold, result.confidence());
        return verificationService.recordOutcome(
                check, mapped.status(), mapped.mismatchFlag(), outcome.similarity(),
                result.confidence(), detail, captured);
    }

    /**
     * A scan that passes liveness and matches the uploaded ID/photo becomes the
     * employee's second reference: later checks are compared against both, so
     * day-to-day matching is against a real frame of the person at their desk and
     * not only an ID photo. An enrollment scan replaces the previous live reference
     * (a reinstalled or reassigned system re-enrolls); other matches only fill a gap.
     */
    private void storeLiveReference(VerificationCheck check, byte[] captured, boolean replaceExisting) {
        var existing = referenceImages.findByEmployeeIdAndStatusAndSource(
                check.getEmployeeId(), ReferenceImageStatus.ACTIVE, ReferenceImageSource.LIVE_ENROLLMENT);
        if (existing.isPresent()) {
            if (!replaceExisting) {
                return;
            }
            existing.get().setStatus(ReferenceImageStatus.REPLACED);
            referenceImages.save(existing.get());
            referenceImages.flush(); // retire before insert (one active live reference per employee)
        }
        var reference = new ReferenceImage();
        reference.setEmployeeId(check.getEmployeeId());
        reference.setSource(ReferenceImageSource.LIVE_ENROLLMENT);
        reference.setCreatedBy("liveness-check:" + check.getId());
        reference.setS3Key(storage.store(
                "reference/" + check.getEmployeeId() + "/live-" + reference.getId() + ".jpg", captured));
        referenceImages.save(reference);
        audit.record("device:" + check.getDeviceId(), "LIVE_REFERENCE_ENROLLED", "employee",
                check.getEmployeeId().toString(), "{\"checkId\":\"" + check.getId() + "\"}");
    }

    private static String describe(ReferenceImageSource source) {
        return source == ReferenceImageSource.LIVE_ENROLLMENT ? "the enrolled live scan" : "the uploaded ID/photo";
    }

    private VerificationCheck pendingCheck(UUID checkId, String nonce) {
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
        return check;
    }
}
