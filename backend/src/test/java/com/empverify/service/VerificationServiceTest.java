package com.empverify.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.empverify.domain.CheckPolicy;
import com.empverify.domain.Device;
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
class VerificationServiceTest {

    @Mock private VerificationCheckRepository checks;
    @Mock private ReferenceImageRepository referenceImages;
    @Mock private DeviceService deviceService;
    @Mock private PolicyService policyService;
    @Mock private FaceVerifier verifier;
    @Mock private ImageStorage storage;
    @Mock private AuditService audit;

    private VerificationService service;

    private final UUID deviceId = UUID.randomUUID();
    private final UUID employeeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new VerificationService(
                checks, referenceImages, deviceService, policyService, verifier, storage, audit, 120);
    }

    private VerificationCheck pendingCheck(String nonce, Instant expiresAt) {
        var check = new VerificationCheck();
        check.setDeviceId(deviceId);
        check.setEmployeeId(employeeId);
        check.setCheckType(CheckType.PERIODIC);
        check.setNonce(nonce);
        check.setExpiresAt(expiresAt);
        return check;
    }

    private void stubReferenceAndSave() {
        var ref = new ReferenceImage();
        ref.setEmployeeId(employeeId);
        ref.setS3Key("reference/key.jpg");
        when(referenceImages.findByEmployeeIdAndStatus(employeeId, ReferenceImageStatus.ACTIVE))
                .thenReturn(Optional.of(ref));
        when(storage.load("reference/key.jpg")).thenReturn(new byte[] {1, 2, 3});
        var device = new Device();
        device.setEmployeeId(employeeId);
        lenient().when(deviceService.requireActiveDevice(deviceId)).thenReturn(device);
        lenient().when(policyService.effectiveFor(any())).thenReturn(new CheckPolicy()); // 90 / 75 defaults
        when(checks.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void highSimilarityCaptureReturnsMatchAndDiscardsEvidence() {
        var check = pendingCheck("nonce-1", Instant.now().plusSeconds(60));
        when(checks.findById(check.getId())).thenReturn(Optional.of(check));
        stubReferenceAndSave();
        when(verifier.verify(any(), any())).thenReturn(Outcome.similarityOf(95.0));

        var result = service.capture(deviceId, check.getId(), "nonce-1", new byte[] {9}, "Integrated Camera");

        assertEquals(CheckStatus.MATCH, result.getStatus());
        assertFalse(result.isMismatchFlag());
        // Data minimization: no evidence stored for a MATCH.
        verify(storage, never()).store(any(), any());
    }

    @Test
    void lowSimilarityReturnsCheckWithMismatchAndStoresEvidence() {
        var check = pendingCheck("nonce-1", Instant.now().plusSeconds(60));
        when(checks.findById(check.getId())).thenReturn(Optional.of(check));
        stubReferenceAndSave();
        when(verifier.verify(any(), any())).thenReturn(Outcome.similarityOf(40.0));

        var result = service.capture(deviceId, check.getId(), "nonce-1", new byte[] {9}, "Integrated Camera");

        assertEquals(CheckStatus.CHECK, result.getStatus());
        assertTrue(result.isMismatchFlag());
        verify(storage).store(any(), any()); // evidence retained for non-MATCH
    }

    @Test
    void virtualCameraNeverAutoMatchesEvenWithHighSimilarity() {
        var check = pendingCheck("nonce-1", Instant.now().plusSeconds(60));
        when(checks.findById(check.getId())).thenReturn(Optional.of(check));
        stubReferenceAndSave();
        when(verifier.verify(any(), any())).thenReturn(Outcome.similarityOf(99.0));

        var result = service.capture(deviceId, check.getId(), "nonce-1", new byte[] {9}, "OBS Virtual Camera");

        assertEquals(CheckStatus.CHECK, result.getStatus());
    }

    @Test
    void noFaceOutcomePassesThroughWithoutComparison() {
        var check = pendingCheck("nonce-1", Instant.now().plusSeconds(60));
        when(checks.findById(check.getId())).thenReturn(Optional.of(check));
        var ref = new ReferenceImage();
        ref.setS3Key("reference/key.jpg");
        when(referenceImages.findByEmployeeIdAndStatus(employeeId, ReferenceImageStatus.ACTIVE))
                .thenReturn(Optional.of(ref));
        when(storage.load(any())).thenReturn(new byte[] {1});
        when(checks.save(any())).thenAnswer(i -> i.getArgument(0));
        when(verifier.verify(any(), any()))
                .thenReturn(Outcome.failure(CheckStatus.NO_FACE, "no face"));

        var result = service.capture(deviceId, check.getId(), "nonce-1", new byte[] {9}, "Integrated Camera");

        assertEquals(CheckStatus.NO_FACE, result.getStatus());
        assertNull(result.getSimilarityScore());
    }

    @Test
    void missingReferenceImageYieldsVerificationError() {
        var check = pendingCheck("nonce-1", Instant.now().plusSeconds(60));
        when(checks.findById(check.getId())).thenReturn(Optional.of(check));
        when(referenceImages.findByEmployeeIdAndStatus(employeeId, ReferenceImageStatus.ACTIVE))
                .thenReturn(Optional.empty());
        when(checks.save(any())).thenAnswer(i -> i.getArgument(0));

        var result = service.capture(deviceId, check.getId(), "nonce-1", new byte[] {9}, "Integrated Camera");

        assertEquals(CheckStatus.VERIFICATION_ERROR, result.getStatus());
        verify(verifier, never()).verify(any(), any());
    }

    @Test
    void wrongNonceIsRejectedWithConflict() {
        var check = pendingCheck("real-nonce", Instant.now().plusSeconds(60));
        when(checks.findById(check.getId())).thenReturn(Optional.of(check));

        var ex = assertThrows(ApiException.class,
                () -> service.capture(deviceId, check.getId(), "wrong-nonce", new byte[] {9}, "cam"));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        verify(verifier, never()).verify(any(), any());
    }

    @Test
    void expiredCheckIsMarkedExpiredNotVerified() {
        var check = pendingCheck("nonce-1", Instant.now().minusSeconds(1));
        when(checks.findById(check.getId())).thenReturn(Optional.of(check));
        when(checks.save(any())).thenAnswer(i -> i.getArgument(0));

        var result = service.capture(deviceId, check.getId(), "nonce-1", new byte[] {9}, "cam");

        assertEquals(CheckStatus.EXPIRED, result.getStatus());
        verify(verifier, never()).verify(any(), any());
    }

    @Test
    void captureForAnotherDeviceIsForbidden() {
        var check = pendingCheck("nonce-1", Instant.now().plusSeconds(60));
        when(checks.findById(check.getId())).thenReturn(Optional.of(check));

        var ex = assertThrows(ApiException.class,
                () -> service.capture(UUID.randomUUID(), check.getId(), "nonce-1", new byte[] {9}, "cam"));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    @Test
    void alreadyCompletedCheckCannotBeReused() {
        var check = pendingCheck("nonce-1", Instant.now().plusSeconds(60));
        check.setStatus(CheckStatus.MATCH);
        when(checks.findById(check.getId())).thenReturn(Optional.of(check));

        var ex = assertThrows(ApiException.class,
                () -> service.capture(deviceId, check.getId(), "nonce-1", new byte[] {9}, "cam"));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    @Test
    void openCheckIssuesPendingCheckWithNonce() {
        var device = new Device();
        device.setEmployeeId(employeeId);
        when(deviceService.requireActiveDevice(deviceId)).thenReturn(device);
        when(checks.save(any())).thenAnswer(i -> i.getArgument(0));

        var check = service.openCheck(deviceId, CheckType.SESSION_START);

        assertEquals(CheckStatus.PENDING, check.getStatus());
        assertFalse(check.getNonce().isBlank());
        assertEquals(CheckType.SESSION_START, check.getCheckType());
    }

    @Test
    void getOwnedCheckRejectsForeignDevice() {
        var check = pendingCheck("n", Instant.now().plusSeconds(60));
        when(checks.findById(check.getId())).thenReturn(Optional.of(check));

        assertThrows(ApiException.class, () -> service.getOwnedCheck(UUID.randomUUID(), check.getId()));
        assertEquals(check, service.getOwnedCheck(deviceId, check.getId()));
    }
}
