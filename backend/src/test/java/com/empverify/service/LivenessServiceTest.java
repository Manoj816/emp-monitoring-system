package com.empverify.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.empverify.domain.CheckPolicy;
import com.empverify.domain.Enums.CheckStatus;
import com.empverify.domain.Enums.CheckType;
import com.empverify.domain.Enums.ReferenceImageSource;
import com.empverify.domain.Enums.ReferenceImageStatus;
import com.empverify.domain.ReferenceImage;
import com.empverify.domain.VerificationCheck;
import com.empverify.repo.ReferenceImageRepository;
import com.empverify.repo.VerificationCheckRepository;
import com.empverify.storage.ImageStorage;
import com.empverify.verification.FaceVerifier.Outcome;
import com.empverify.verification.LivenessProvider;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class LivenessServiceTest {

    @Mock private VerificationCheckRepository checks;
    @Mock private ReferenceImageRepository referenceImages;
    @Mock private LivenessProvider provider;
    @Mock private ImageStorage storage;
    @Mock private PolicyService policyService;
    @Mock private VerificationService verificationService;
    @Mock private DeviceService deviceService;
    @Mock private AuditService audit;

    private LivenessService service;
    private final UUID employeeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new LivenessService(
                checks, referenceImages, provider, storage, policyService,
                verificationService, deviceService, audit, "http://localhost:5174", 80.0);
        // recordOutcome echoes the status onto the check so assertions can read it.
        lenient().when(verificationService.recordOutcome(any(), any(), anyBoolean(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    VerificationCheck c = inv.getArgument(0);
                    c.setStatus(inv.getArgument(1));
                    return c;
                });
    }

    private VerificationCheck pending(String sessionId) {
        var check = new VerificationCheck();
        check.setDeviceId(UUID.randomUUID());
        check.setEmployeeId(employeeId);
        check.setCheckType(CheckType.PERIODIC);
        check.setNonce("the-nonce");
        check.setExpiresAt(Instant.now().plusSeconds(120));
        check.setLivenessSessionId(sessionId);
        return check;
    }

    /** The captured frame's best match: a reference of the given origin at the given similarity. */
    private void stubBestMatch(double similarity, ReferenceImageSource source) {
        var ref = new ReferenceImage();
        ref.setEmployeeId(employeeId);
        ref.setSource(source);
        ref.setS3Key("reference/key.jpg");
        when(verificationService.bestMatch(eq(employeeId), any()))
                .thenReturn(new VerificationService.ReferenceMatch(ref, Outcome.similarityOf(similarity)));
        when(policyService.effectiveForEmployee(employeeId)).thenReturn(new CheckPolicy()); // 90 / 75 defaults
    }

    private void stubNoLiveEnrollmentYet() {
        when(referenceImages.findByEmployeeIdAndStatusAndSource(
                employeeId, ReferenceImageStatus.ACTIVE, ReferenceImageSource.LIVE_ENROLLMENT))
                .thenReturn(Optional.empty());
    }

    @Test
    void firstPassingMatchEnrollsTheLiveFrameAsSecondReference() {
        var check = pending("sess-1");
        when(checks.findById(check.getId())).thenReturn(Optional.of(check));
        when(provider.getResult("sess-1"))
                .thenReturn(new LivenessProvider.Result(98.0, new byte[] {9}, "SUCCEEDED"));
        stubBestMatch(95.0, ReferenceImageSource.ADMIN_UPLOAD);
        stubNoLiveEnrollmentYet();
        when(storage.store(any(), eq(new byte[] {9}))).thenReturn("reference/live.jpg");

        var result = service.completeLiveness(check.getId(), "the-nonce");

        assertEquals(CheckStatus.MATCH, result.getStatus());
        verify(referenceImages).save(org.mockito.ArgumentMatchers.argThat(
                r -> r.getSource() == ReferenceImageSource.LIVE_ENROLLMENT && r.getEmployeeId().equals(employeeId)));
        verify(audit).record(any(), eq("LIVE_REFERENCE_ENROLLED"), eq("employee"), eq(employeeId.toString()), any());
    }

    @Test
    void enrollmentScanOnASystemReplacesTheLiveReferenceAndEnrollsTheSystem() {
        // A reinstalled or reassigned system runs ENROLLMENT again even though the
        // employee already has a live reference from an earlier system.
        var check = pending("sess-1");
        check.setCheckType(CheckType.ENROLLMENT);
        when(checks.findById(check.getId())).thenReturn(Optional.of(check));
        when(provider.getResult("sess-1"))
                .thenReturn(new LivenessProvider.Result(98.0, new byte[] {7}, "SUCCEEDED"));
        stubBestMatch(95.0, ReferenceImageSource.ADMIN_UPLOAD);
        var previous = new ReferenceImage();
        previous.setEmployeeId(employeeId);
        previous.setSource(ReferenceImageSource.LIVE_ENROLLMENT);
        when(referenceImages.findByEmployeeIdAndStatusAndSource(
                employeeId, ReferenceImageStatus.ACTIVE, ReferenceImageSource.LIVE_ENROLLMENT))
                .thenReturn(Optional.of(previous));
        when(storage.store(any(), eq(new byte[] {7}))).thenReturn("reference/live2.jpg");

        var result = service.completeLiveness(check.getId(), "the-nonce");

        assertEquals(CheckStatus.MATCH, result.getStatus());
        assertEquals(ReferenceImageStatus.REPLACED, previous.getStatus());
        verify(referenceImages).save(org.mockito.ArgumentMatchers.argThat(
                r -> r.getSource() == ReferenceImageSource.LIVE_ENROLLMENT
                        && r.getStatus() == ReferenceImageStatus.ACTIVE && "reference/live2.jpg".equals(r.getS3Key())));
        verify(deviceService).markEnrolled(check.getDeviceId());
    }

    @Test
    void enrollmentScanWithoutPriorReferenceStoresItAndEnrollsTheSystem() {
        var check = pending("sess-1");
        check.setCheckType(CheckType.ENROLLMENT);
        when(checks.findById(check.getId())).thenReturn(Optional.of(check));
        when(provider.getResult("sess-1"))
                .thenReturn(new LivenessProvider.Result(98.0, new byte[] {7}, "SUCCEEDED"));
        stubBestMatch(95.0, ReferenceImageSource.ADMIN_UPLOAD);
        stubNoLiveEnrollmentYet();
        when(storage.store(any(), eq(new byte[] {7}))).thenReturn("reference/live.jpg");

        var result = service.completeLiveness(check.getId(), "the-nonce");

        assertEquals(CheckStatus.MATCH, result.getStatus());
        verify(referenceImages).save(any());
        verify(deviceService).markEnrolled(check.getDeviceId());
    }

    @Test
    void laterMatchesDoNotEnrollAgain() {
        var check = pending("sess-1");
        when(checks.findById(check.getId())).thenReturn(Optional.of(check));
        when(provider.getResult("sess-1"))
                .thenReturn(new LivenessProvider.Result(98.0, new byte[] {9}, "SUCCEEDED"));
        stubBestMatch(95.0, ReferenceImageSource.LIVE_ENROLLMENT);
        when(referenceImages.findByEmployeeIdAndStatusAndSource(
                employeeId, ReferenceImageStatus.ACTIVE, ReferenceImageSource.LIVE_ENROLLMENT))
                .thenReturn(Optional.of(new ReferenceImage()));

        var result = service.completeLiveness(check.getId(), "the-nonce");

        assertEquals(CheckStatus.MATCH, result.getStatus());
        verify(referenceImages, never()).save(any());
        verify(storage, never()).store(any(), any());
    }

    @Test
    void lowLivenessConfidenceIsCheckWithBothScoresEvidenceAndExplanation() {
        var check = pending("sess-1");
        when(checks.findById(check.getId())).thenReturn(Optional.of(check));
        when(provider.getResult("sess-1"))
                .thenReturn(new LivenessProvider.Result(76.0, new byte[] {9}, "SUCCEEDED"));
        stubBestMatch(95.0, ReferenceImageSource.ADMIN_UPLOAD);

        var result = service.completeLiveness(check.getId(), "the-nonce");

        assertEquals(CheckStatus.CHECK, result.getStatus());
        // The face comparison still runs and the frame is retained, so the reviewer
        // sees who it looked like, how live it looked, the picture, and why it was flagged.
        verify(verificationService).recordOutcome(
                any(), eq(CheckStatus.CHECK), anyBoolean(), eq(95.0), eq(76.0),
                contains("below the required 80"), eq(new byte[] {9}));
        verify(referenceImages, never()).save(any()); // a low-liveness scan never enrolls
    }

    @Test
    void faceBelowReviewThresholdIsMismatchFlagWithExplanation() {
        var check = pending("sess-1");
        when(checks.findById(check.getId())).thenReturn(Optional.of(check));
        when(provider.getResult("sess-1"))
                .thenReturn(new LivenessProvider.Result(95.0, new byte[] {9}, "SUCCEEDED"));
        stubBestMatch(40.0, ReferenceImageSource.LIVE_ENROLLMENT);

        var result = service.completeLiveness(check.getId(), "the-nonce");

        assertEquals(CheckStatus.CHECK, result.getStatus());
        verify(verificationService).recordOutcome(
                any(), eq(CheckStatus.CHECK), eq(true), eq(40.0), eq(95.0),
                contains("does not match the references on file"), eq(new byte[] {9}));
    }

    @Test
    void sessionThatDidNotSucceedIsTechnicalErrorNotSpoofFlag() {
        var check = pending("sess-1");
        when(checks.findById(check.getId())).thenReturn(Optional.of(check));
        when(provider.getResult("sess-1")).thenReturn(new LivenessProvider.Result(0.0, null, "FAILED"));

        var result = service.completeLiveness(check.getId(), "the-nonce");

        assertEquals(CheckStatus.VERIFICATION_ERROR, result.getStatus());
        verify(verificationService, never()).bestMatch(any(), any());
        verify(verificationService).recordOutcome(
                any(), eq(CheckStatus.VERIFICATION_ERROR), anyBoolean(), any(), any(),
                contains("session failed"), any());
    }

    @Test
    void lowConfidenceWithoutAnyFrameIsCheckWithNoComparisonAndNoEvidence() {
        // Rekognition judged "not a live person" and sent back no frame at all.
        var check = pending("sess-1");
        when(checks.findById(check.getId())).thenReturn(Optional.of(check));
        when(provider.getResult("sess-1")).thenReturn(new LivenessProvider.Result(0.0001, null, "SUCCEEDED"));

        var result = service.completeLiveness(check.getId(), "the-nonce");

        assertEquals(CheckStatus.CHECK, result.getStatus());
        // Nothing is sent to CompareFaces and no empty "evidence" is stored.
        verify(verificationService, never()).bestMatch(any(), any());
        verify(verificationService).recordOutcome(
                any(), eq(CheckStatus.CHECK), eq(false), eq(null), eq(0.0001),
                contains("returned no usable frame"), eq(null));
    }

    @Test
    void passingConfidenceWithoutAFrameIsATechnicalErrorToRetry() {
        var check = pending("sess-1");
        when(checks.findById(check.getId())).thenReturn(Optional.of(check));
        when(provider.getResult("sess-1")).thenReturn(new LivenessProvider.Result(96.0, new byte[0], "SUCCEEDED"));

        var result = service.completeLiveness(check.getId(), "the-nonce");

        assertEquals(CheckStatus.VERIFICATION_ERROR, result.getStatus());
        verify(verificationService, never()).bestMatch(any(), any());
        verify(verificationService).recordOutcome(
                any(), eq(CheckStatus.VERIFICATION_ERROR), eq(false), eq(null), eq(96.0),
                contains("no frame to compare"), eq(null));
    }

    @Test
    void missingReferenceAfterPassingLivenessIsError() {
        var check = pending("sess-1");
        when(checks.findById(check.getId())).thenReturn(Optional.of(check));
        when(provider.getResult("sess-1"))
                .thenReturn(new LivenessProvider.Result(98.0, new byte[] {9}, "SUCCEEDED"));
        when(verificationService.bestMatch(eq(employeeId), any())).thenReturn(null);

        var result = service.completeLiveness(check.getId(), "the-nonce");

        assertEquals(CheckStatus.VERIFICATION_ERROR, result.getStatus());
    }

    @Test
    void wrongNonceIsRejected() {
        var check = pending("sess-1");
        when(checks.findById(check.getId())).thenReturn(Optional.of(check));

        var ex = assertThrows(ApiException.class,
                () -> service.completeLiveness(check.getId(), "wrong"));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    @Test
    void alreadyCompletedCheckIsRejected() {
        var check = pending("sess-1");
        check.setStatus(CheckStatus.MATCH);
        when(checks.findById(check.getId())).thenReturn(Optional.of(check));

        var ex = assertThrows(ApiException.class,
                () -> service.completeLiveness(check.getId(), "the-nonce"));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    @Test
    void createSessionStoresSessionIdAndBuildsUrl() {
        var check = pending(null);
        var deviceId = UUID.randomUUID();
        when(verificationService.getOwnedCheck(deviceId, check.getId())).thenReturn(check);
        when(provider.createSession())
                .thenReturn(new LivenessProvider.Session("new-session", Instant.now().plusSeconds(300)));

        var session = service.createSession(deviceId, check.getId());

        assertEquals("new-session", check.getLivenessSessionId());
        assertTrue(session.livenessUrl().contains("session=new-session"));
        assertTrue(session.livenessUrl().contains("token=the-nonce"));
    }

    @Test
    void restartIssuesAFreshSessionForTheSamePendingCheck() {
        var check = pending("old-session");
        when(checks.findById(check.getId())).thenReturn(Optional.of(check));
        when(provider.createSession())
                .thenReturn(new LivenessProvider.Session("fresh-session", Instant.now().plusSeconds(300)));

        var sessionId = service.restartSession(check.getId(), "the-nonce");

        assertEquals("fresh-session", sessionId);
        assertEquals("fresh-session", check.getLivenessSessionId());
        assertEquals(CheckStatus.PENDING, check.getStatus()); // the check itself is untouched
    }

    @Test
    void restartIsRefusedOnceTheResponseWindowHasElapsed() {
        var check = pending("old-session");
        check.setExpiresAt(Instant.now().minusSeconds(1));
        when(checks.findById(check.getId())).thenReturn(Optional.of(check));

        var ex = assertThrows(ApiException.class, () -> service.restartSession(check.getId(), "the-nonce"));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        verify(provider, never()).createSession();
    }
}
