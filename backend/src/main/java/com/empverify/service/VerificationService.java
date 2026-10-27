package com.empverify.service;

import com.empverify.domain.Enums.CheckStatus;
import com.empverify.domain.Enums.CheckType;
import com.empverify.domain.Enums.ReferenceImageStatus;
import com.empverify.domain.VerificationCheck;
import com.empverify.repo.ReferenceImageRepository;
import com.empverify.repo.VerificationCheckRepository;
import com.empverify.storage.ImageStorage;
import com.empverify.verification.FaceVerifier;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VerificationService {

    /** Known virtual-camera drivers; a capture from one can never auto-MATCH. */
    private static final Pattern VIRTUAL_CAMERA =
            Pattern.compile("obs|manycam|droidcam|snap camera|virtual|vcam", Pattern.CASE_INSENSITIVE);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final VerificationCheckRepository checks;
    private final ReferenceImageRepository referenceImages;
    private final DeviceService deviceService;
    private final PolicyService policyService;
    private final FaceVerifier verifier;
    private final ImageStorage storage;
    private final AuditService audit;
    private final long checkExpirySeconds;
    private final String livenessUiUrl;

    public VerificationService(
            VerificationCheckRepository checks,
            ReferenceImageRepository referenceImages,
            DeviceService deviceService,
            PolicyService policyService,
            FaceVerifier verifier,
            ImageStorage storage,
            AuditService audit,
            @Value("${empverify.verification.check-expiry-seconds}") long checkExpirySeconds,
            @Value("${empverify.liveness-ui-url}") String livenessUiUrl) {
        this.checks = checks;
        this.referenceImages = referenceImages;
        this.deviceService = deviceService;
        this.policyService = policyService;
        this.verifier = verifier;
        this.storage = storage;
        this.audit = audit;
        this.checkExpirySeconds = checkExpirySeconds;
        this.livenessUiUrl = livenessUiUrl;
    }

    public record CaptureSession(String captureUrl, Instant expiresAt) {}

    /**
     * Agent-initiated: the one-time browser URL for the interactive capture page.
     * The employee opens it, captures a photo, and the browser submits it to
     * {@link #captureFromBrowser}. Mirrors the liveness-session flow.
     */
    @Transactional(readOnly = true)
    public CaptureSession captureSession(UUID deviceId, UUID checkId) {
        var check = getOwnedCheck(deviceId, checkId);
        if (check.getStatus() != CheckStatus.PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, "check already completed");
        }
        String url = livenessUiUrl + "/capture?checkId=" + checkId + "&token=" + check.getNonce();
        return new CaptureSession(url, check.getExpiresAt());
    }

    /**
     * Browser-initiated capture: the interactive page uploads the photo the
     * employee took. Public, gated by the one-time check nonce (single-use
     * because the check leaves PENDING), so no device token is required.
     */
    @Transactional
    public VerificationCheck captureFromBrowser(
            UUID checkId, String nonce, byte[] imageBytes, String cameraDeviceName) {
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
        if (check.getExpiresAt().isBefore(Instant.now())) {
            check.setStatus(CheckStatus.EXPIRED);
            check.setCompletedAt(Instant.now());
            return checks.save(check);
        }
        return runComparison(check, imageBytes, cameraDeviceName);
    }

    @Transactional
    public VerificationCheck openCheck(UUID deviceId, CheckType type) {
        var device = deviceService.requireActiveDevice(deviceId);
        var check = new VerificationCheck();
        check.setDeviceId(device.getId());
        check.setEmployeeId(device.getEmployeeId());
        check.setCheckType(type);
        check.setNonce(randomNonce());
        check.setExpiresAt(Instant.now().plusSeconds(checkExpirySeconds));
        return checks.save(check);
    }

    @Transactional
    public VerificationCheck capture(
            UUID deviceId, UUID checkId, String nonce, byte[] imageBytes, String cameraDeviceName) {
        var check = checks.findById(checkId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "check not found"));
        if (!check.getDeviceId().equals(deviceId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "check belongs to another device");
        }
        if (check.getStatus() != CheckStatus.PENDING) {
            throw new ApiException(HttpStatus.CONFLICT, "check already completed");
        }
        if (!MessageDigest.isEqual(
                check.getNonce().getBytes(StandardCharsets.UTF_8),
                nonce.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.CONFLICT, "nonce mismatch");
        }
        if (check.getExpiresAt().isBefore(Instant.now())) {
            check.setStatus(CheckStatus.EXPIRED);
            check.setCompletedAt(Instant.now());
            return checks.save(check);
        }

        return runComparison(check, imageBytes, cameraDeviceName);
    }

    /**
     * Shared verdict logic for both the device capture and the browser capture:
     * compare the frame to the enrolled reference, map similarity to a status,
     * flag virtual cameras, and record the outcome.
     */
    private VerificationCheck runComparison(
            VerificationCheck check, byte[] imageBytes, String cameraDeviceName) {
        var reference = referenceImages
                .findByEmployeeIdAndStatus(check.getEmployeeId(), ReferenceImageStatus.ACTIVE)
                .orElse(null);
        if (reference == null) {
            return complete(check, CheckStatus.VERIFICATION_ERROR, false, null,
                    "no active reference image enrolled", imageBytes);
        }

        var outcome = verifier.verify(imageBytes, storage.load(reference.getS3Key()));

        if (outcome.similarity() == null) {
            return complete(check, outcome.status(), false, null, outcome.errorDetail(), imageBytes);
        }

        var policy = policyService.effectiveForEmployee(check.getEmployeeId());
        var mapped = mapSimilarity(outcome.similarity(),
                policy.getMatchThreshold().doubleValue(), policy.getReviewThreshold().doubleValue());

        CheckStatus status = mapped.status();
        boolean mismatch = mapped.mismatchFlag();
        String detail = null;
        if (cameraDeviceName != null && VIRTUAL_CAMERA.matcher(cameraDeviceName).find()) {
            status = CheckStatus.CHECK;
            detail = "virtual camera flagged: " + cameraDeviceName.toLowerCase(Locale.ROOT);
        }
        return complete(check, status, mismatch, outcome.similarity(), detail, imageBytes);
    }

    public VerificationCheck getOwnedCheck(UUID deviceId, UUID checkId) {
        var check = checks.findById(checkId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "check not found"));
        if (!check.getDeviceId().equals(deviceId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "check belongs to another device");
        }
        return check;
    }

    public record MappedResult(CheckStatus status, boolean mismatchFlag) {}

    /**
     * Threshold policy: >= match -> MATCH; >= review -> CHECK (borderline);
     * below review -> CHECK with mismatch flag. CHECK is a review status, never
     * an automatic verdict.
     */
    public static MappedResult mapSimilarity(double similarity, double matchThreshold, double reviewThreshold) {
        if (similarity >= matchThreshold) {
            return new MappedResult(CheckStatus.MATCH, false);
        }
        if (similarity >= reviewThreshold) {
            return new MappedResult(CheckStatus.CHECK, false);
        }
        return new MappedResult(CheckStatus.CHECK, true);
    }

    private VerificationCheck complete(
            VerificationCheck check, CheckStatus status, boolean mismatch,
            Double similarity, String detail, byte[] imageBytes) {
        return recordOutcome(check, status, mismatch, similarity, null, detail, imageBytes);
    }

    /**
     * Finalizes a check: sets outcome fields, retains evidence only for non-MATCH
     * results (data minimization), persists, and writes the audit entry. Shared by
     * the passive capture path and the liveness path.
     */
    @Transactional
    public VerificationCheck recordOutcome(
            VerificationCheck check, CheckStatus status, boolean mismatch,
            Double similarity, Double livenessConfidence, String detail, byte[] imageBytes) {
        check.setStatus(status);
        check.setMismatchFlag(mismatch);
        check.setSimilarityScore(similarity == null ? null : BigDecimal.valueOf(similarity));
        check.setLivenessConfidence(livenessConfidence == null ? null : BigDecimal.valueOf(livenessConfidence));
        check.setErrorDetail(detail);
        check.setCompletedAt(Instant.now());

        // Data minimization: evidence is retained only for non-MATCH outcomes.
        if (status != CheckStatus.MATCH && imageBytes != null && imageBytes.length > 0) {
            check.setEvidenceS3Key(storage.store("evidence/" + check.getId() + ".jpg", imageBytes));
        }
        var saved = checks.save(check);
        audit.record("device:" + check.getDeviceId(), "CHECK_COMPLETED", "check",
                check.getId().toString(),
                "{\"status\":\"" + status + "\",\"mismatch\":" + mismatch + "}");
        return saved;
    }

    private static String randomNonce() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
