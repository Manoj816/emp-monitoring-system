package com.empverify.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.empverify.domain.CheckPolicy;
import com.empverify.domain.Enums.CheckStatus;
import com.empverify.domain.Enums.CheckType;
import com.empverify.domain.Enums.ReferenceImageStatus;
import com.empverify.domain.ReferenceImage;
import com.empverify.domain.VerificationCheck;
import com.empverify.repo.ReferenceImageRepository;
import com.empverify.repo.VerificationCheckRepository;
import com.empverify.storage.ImageStorage;
import com.empverify.verification.FaceVerifier;
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
    @Mock private FaceVerifier verifier;
    @Mock private ImageStorage storage;
    @Mock private PolicyService policyService;
    @Mock private VerificationService verificationService;

    private LivenessService service;
    private final UUID employeeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new LivenessService(
                checks, referenceImages, provider, verifier, storage, policyService,
                verificationService, "http://localhost:5174", 80.0);
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
        check.setEmployeeId(employeeId);
        check.setCheckType(CheckType.LIVENESS);
        check.setNonce("the-nonce");
        check.setExpiresAt(Instant.now().plusSeconds(120));
        check.setLivenessSessionId(sessionId);
        return check;
    }

    private void stubReference() {
        var ref = new ReferenceImage();
        ref.setS3Key("reference/key.jpg");
        when(referenceImages.findByEmployeeIdAndStatus(employeeId, ReferenceImageStatus.ACTIVE))
                .thenReturn(Optional.of(ref));
        when(storage.load(any())).thenReturn(new byte[] {1, 2, 3});
        var policy = new CheckPolicy(); // 90 / 75 defaults
        when(policyService.effectiveForEmployee(employeeId)).thenReturn(policy);
    }

    @Test
    void passingLivenessWithGoodMatchReturnsMatch() {
        var check = pending("sess-1");
        when(checks.findById(check.getId())).thenReturn(Optional.of(check));
        when(provider.getResult("sess-1"))
                .thenReturn(new LivenessProvider.Result(98.0, new byte[] {9}, "SUCCEEDED"));
        stubReference();
        when(verifier.verify(any(), any())).thenReturn(Outcome.similarityOf(95.0));

        var result = service.completeLiveness(check.getId(), "the-nonce");

        assertEquals(CheckStatus.MATCH, result.getStatus());
    }

    @Test
    void lowLivenessConfidenceIsCheckWithBothScoresAndEvidenceForReview() {
        var check = pending("sess-1");
        when(checks.findById(check.getId())).thenReturn(Optional.of(check));
        when(provider.getResult("sess-1"))
                .thenReturn(new LivenessProvider.Result(76.0, new byte[] {9}, "SUCCEEDED"));
        stubReference();
        when(verifier.verify(any(), any())).thenReturn(Outcome.similarityOf(95.0));

        var result = service.completeLiveness(check.getId(), "the-nonce");

        assertEquals(CheckStatus.CHECK, result.getStatus());
        // The face comparison still runs and the frame is retained, so the reviewer
        // sees who it looked like, how live it looked, and the picture itself.
        org.mockito.Mockito.verify(verificationService).recordOutcome(
                any(), org.mockito.ArgumentMatchers.eq(CheckStatus.CHECK), anyBoolean(),
                org.mockito.ArgumentMatchers.eq(95.0), org.mockito.ArgumentMatchers.eq(76.0),
                org.mockito.ArgumentMatchers.contains("below threshold"),
                org.mockito.ArgumentMatchers.eq(new byte[] {9}));
    }

    @Test
    void sessionThatDidNotSucceedIsTechnicalErrorNotSpoofFlag() {
        var check = pending("sess-1");
        when(checks.findById(check.getId())).thenReturn(Optional.of(check));
        when(provider.getResult("sess-1")).thenReturn(new LivenessProvider.Result(0.0, null, "FAILED"));

        var result = service.completeLiveness(check.getId(), "the-nonce");

        assertEquals(CheckStatus.VERIFICATION_ERROR, result.getStatus());
        org.mockito.Mockito.verifyNoInteractions(verifier);
        org.mockito.Mockito.verify(verificationService).recordOutcome(
                any(), org.mockito.ArgumentMatchers.eq(CheckStatus.VERIFICATION_ERROR), anyBoolean(),
                any(), any(), org.mockito.ArgumentMatchers.contains("session failed"), any());
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
    void missingReferenceAfterPassingLivenessIsError() {
        var check = pending("sess-1");
        when(checks.findById(check.getId())).thenReturn(Optional.of(check));
        when(provider.getResult("sess-1"))
                .thenReturn(new LivenessProvider.Result(98.0, new byte[] {9}, "SUCCEEDED"));
        when(referenceImages.findByEmployeeIdAndStatus(employeeId, ReferenceImageStatus.ACTIVE))
                .thenReturn(Optional.empty());

        var result = service.completeLiveness(check.getId(), "the-nonce");

        assertEquals(CheckStatus.VERIFICATION_ERROR, result.getStatus());
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
        org.junit.jupiter.api.Assertions.assertTrue(session.livenessUrl().contains("session=new-session"));
        org.junit.jupiter.api.Assertions.assertTrue(session.livenessUrl().contains("token=the-nonce"));
    }
}
