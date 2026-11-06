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
                checks, referenceImages, deviceService, policyService, verifier, storage, audit, 120,
                "http://localhost:5174");
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
        when(referenceImages.findAllByEmployeeIdAndStatus(employeeId, ReferenceImageStatus.ACTIVE))
                .thenReturn(java.util.List.of(ref));
        when(storage.load("reference/key.jpg")).thenReturn(new byte[] {1, 2, 3});
        var device = new Device();
        device.setEmployeeId(employeeId);
        lenient().when(deviceService.requireActiveDevice(deviceId)).thenReturn(device);
        lenient().when(policyService.effectiveForEmployee(any())).thenReturn(new CheckPolicy()); // 90 / 75 defaults
        when(checks.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    // ---- a check nobody answered -------------------------------------------
    //
    // The photo is evidence of an empty desk for a person to look at. It must
    // never become a verdict, and it must never be kept unless the deployment
    // asked for it - the agent sends it, the server decides.

    @Test
    void anUnansweredCheckKeepsThePhotoAndSaysWhereItCameFrom() {
        var check = pendingCheck("nonce-u", Instant.now().plusSeconds(60));
        when(checks.findById(check.getId())).thenReturn(Optional.of(check));
        when(checks.save(any())).thenAnswer(i -> i.getArgument(0));
        when(storage.store(any(), any())).thenReturn("evidence/key.jpg");

        var result = service.recordUnanswered(deviceId, check.getId(), new byte[] {7, 7, 7});

        assertEquals(CheckStatus.EXPIRED, result.getStatus());
        assertEquals("evidence/key.jpg", result.getEvidenceS3Key());
        // Nothing was compared, so nothing may look like a comparison.
        assertNull(result.getSimilarityScore());
        assertNull(result.getLivenessConfidence());
        assertFalse(result.isMismatchFlag());
        verify(verifier, never()).verify(any(), any());
        assertTrue(result.getErrorDetail().contains("taken automatically"), result.getErrorDetail());
        assertTrue(result.getErrorDetail().contains("not been compared"), result.getErrorDetail());
    }

    @Test
    void withoutAPhotoAnUnansweredCheckIsTheSameMissAsBefore() {
        var check = pendingCheck("nonce-u", Instant.now().plusSeconds(60));
        when(checks.findById(check.getId())).thenReturn(Optional.of(check));
        when(checks.save(any())).thenAnswer(i -> i.getArgument(0));

        // null is what the controller passes when the policy is off, or the agent
        // had no camera: the miss is recorded exactly as it always was.
        var result = service.recordUnanswered(deviceId, check.getId(), null);

        assertEquals(CheckStatus.EXPIRED, result.getStatus());
        assertNull(result.getEvidenceS3Key());
        verify(storage, never()).store(any(), any());
        assertTrue(result.getErrorDetail().contains("nobody completed the scan"), result.getErrorDetail());
        assertFalse(result.getErrorDetail().contains("taken automatically"), result.getErrorDetail());
    }

    @Test
    void aPhotoIsNotAttachedToACheckThatWasAlreadyAnswered() {
        // The scan landed in the moment between the window closing and the agent
        // reporting. Attaching the frame would caption a completed check with
        // "nobody answered", which is a lie about the employee.
        var check = pendingCheck("nonce-u", Instant.now().plusSeconds(60));
        check.setStatus(CheckStatus.MATCH);
        when(checks.findById(check.getId())).thenReturn(Optional.of(check));

        var result = service.recordUnanswered(deviceId, check.getId(), new byte[] {7, 7, 7});

        assertEquals(CheckStatus.MATCH, result.getStatus());
        assertNull(result.getEvidenceS3Key());
        verify(storage, never()).store(any(), any());
        verify(checks, never()).save(any());
    }

    @Test
    void anUnansweredCheckOnSomebodyElsesDeviceIsRefused() {
        var check = pendingCheck("nonce-u", Instant.now().plusSeconds(60));
        when(checks.findById(check.getId())).thenReturn(Optional.of(check));

        var e = assertThrows(ApiException.class,
                () -> service.recordUnanswered(UUID.randomUUID(), check.getId(), new byte[] {7}));

        assertEquals(HttpStatus.FORBIDDEN, e.getStatus());
        verify(storage, never()).store(any(), any());
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
        when(referenceImages.findAllByEmployeeIdAndStatus(employeeId, ReferenceImageStatus.ACTIVE))
                .thenReturn(java.util.List.of(ref));
        when(storage.load(any())).thenReturn(new byte[] {1});
        when(checks.save(any())).thenAnswer(i -> i.getArgument(0));
        when(verifier.verify(any(), any()))
                .thenReturn(Outcome.failure(CheckStatus.NO_FACE, "no face"));

        var result = service.capture(deviceId, check.getId(), "nonce-1", new byte[] {9}, "Integrated Camera");

        assertEquals(CheckStatus.NO_FACE, result.getStatus());
        assertNull(result.getSimilarityScore());
    }

    @Test
    void browserCaptureMatchesAgainstReference() {
        var check = pendingCheck("nonce-b", Instant.now().plusSeconds(60));
        when(checks.findById(check.getId())).thenReturn(Optional.of(check));
        stubReferenceAndSave();
        when(verifier.verify(any(), any())).thenReturn(Outcome.similarityOf(95.0));

        // No device id: the browser path is gated by the one-time nonce only.
        var result = service.captureFromBrowser(check.getId(), "nonce-b", new byte[] {9}, "Integrated Camera");

        assertEquals(CheckStatus.MATCH, result.getStatus());
        assertFalse(result.isMismatchFlag());
    }

    @Test
    void browserCaptureRejectsNonceMismatch() {
        var check = pendingCheck("nonce-b", Instant.now().plusSeconds(60));
        when(checks.findById(check.getId())).thenReturn(Optional.of(check));

        assertThrows(ApiException.class,
                () -> service.captureFromBrowser(check.getId(), "wrong-nonce", new byte[] {9}, null));
        verify(verifier, never()).verify(any(), any());
    }

    @Test
    void missingReferenceImageYieldsVerificationError() {
        var check = pendingCheck("nonce-1", Instant.now().plusSeconds(60));
        when(checks.findById(check.getId())).thenReturn(Optional.of(check));
        when(referenceImages.findAllByEmployeeIdAndStatus(employeeId, ReferenceImageStatus.ACTIVE))
                .thenReturn(java.util.List.of());
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
    void onDemandOpensOnlyWhileARequestIsPending() {
        // One "send a check now" is one scan: the first open consumes the request,
        // and a duplicate ON_DEMAND open is refused instead of becoming a second
        // scan nobody asked for.
        var device = new Device();
        device.setEmployeeId(employeeId);
        device.setCheckRequestedAt(Instant.now());
        when(deviceService.requireActiveDevice(deviceId)).thenReturn(device);
        when(checks.save(any())).thenAnswer(i -> i.getArgument(0));

        var check = service.openCheck(deviceId, CheckType.ON_DEMAND);
        assertEquals(CheckType.ON_DEMAND, check.getCheckType());
        verify(deviceService).clearCheckRequest(device);

        device.setCheckRequestedAt(null); // what clearCheckRequest does on the real device
        var ex = assertThrows(ApiException.class, () -> service.openCheck(deviceId, CheckType.ON_DEMAND));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    @Test
    void openingAnyCheckSettlesAPendingAdminRequest() {
        // Whatever check opens first answers "send a check now": the request must
        // never produce a second scan after the employee was already checked.
        var device = new Device();
        device.setEmployeeId(employeeId);
        when(deviceService.requireActiveDevice(deviceId)).thenReturn(device);
        when(checks.save(any())).thenAnswer(i -> i.getArgument(0));

        service.openCheck(deviceId, CheckType.PERIODIC);

        verify(deviceService).clearCheckRequest(device);
    }

    @Test
    void getOwnedCheckRejectsForeignDevice() {
        var check = pendingCheck("n", Instant.now().plusSeconds(60));
        when(checks.findById(check.getId())).thenReturn(Optional.of(check));

        assertThrows(ApiException.class, () -> service.getOwnedCheck(UUID.randomUUID(), check.getId()));
        assertEquals(check, service.getOwnedCheck(deviceId, check.getId()));
    }

    // ---- checks that end without a scan (window closed, cancelled, timed out) ----

    @Test
    void pollPastDeadlineReportsExpiredWithReason() {
        var check = pendingCheck("n", Instant.now().minusSeconds(1));
        when(checks.findById(check.getId())).thenReturn(Optional.of(check));
        when(checks.save(any())).thenAnswer(i -> i.getArgument(0));

        var result = service.result(deviceId, check.getId());

        assertEquals(CheckStatus.EXPIRED, result.getStatus());
        assertEquals(VerificationService.CancelReason.TIMEOUT.detail(), result.getErrorDetail());
        verify(audit).record(any(), org.mockito.ArgumentMatchers.eq("CHECK_ABANDONED"), any(), any(), any());
    }

    @Test
    void pollBeforeDeadlineLeavesPendingCheckAlone() {
        var check = pendingCheck("n", Instant.now().plusSeconds(60));
        when(checks.findById(check.getId())).thenReturn(Optional.of(check));

        var result = service.result(deviceId, check.getId());

        assertEquals(CheckStatus.PENDING, result.getStatus());
        verify(checks, never()).save(any());
    }

    @Test
    void cancelRecordsWhyTheCheckEndedAndIsIdempotent() {
        var check = pendingCheck("n", Instant.now().plusSeconds(60));
        when(checks.findById(check.getId())).thenReturn(Optional.of(check));
        when(checks.save(any())).thenAnswer(i -> i.getArgument(0));

        var first = service.cancel(deviceId, check.getId(), VerificationService.CancelReason.CLOSED);
        var second = service.cancel(deviceId, check.getId(), VerificationService.CancelReason.TIMEOUT);

        assertEquals(CheckStatus.EXPIRED, first.getStatus());
        assertEquals(VerificationService.CancelReason.CLOSED.detail(), first.getErrorDetail());
        assertEquals(VerificationService.CancelReason.CLOSED.detail(), second.getErrorDetail()); // first reason wins
        verify(checks, org.mockito.Mockito.times(1)).save(any());
    }

    @Test
    void browserCancelIsNonceGated() {
        var check = pendingCheck("real", Instant.now().plusSeconds(60));
        when(checks.findById(check.getId())).thenReturn(Optional.of(check));

        assertThrows(ApiException.class, () -> service.cancelFromBrowser(
                check.getId(), "wrong", VerificationService.CancelReason.CANCELLED));
        verify(checks, never()).save(any());
    }

    @Test
    void sweepExpiresEveryOverduePendingCheck() {
        var a = pendingCheck("a", Instant.now().minusSeconds(5));
        var b = pendingCheck("b", Instant.now().minusSeconds(5));
        when(checks.findByStatusAndExpiresAtBefore(
                org.mockito.ArgumentMatchers.eq(CheckStatus.PENDING), any()))
                .thenReturn(java.util.List.of(a, b));
        when(checks.save(any())).thenAnswer(i -> i.getArgument(0));

        assertEquals(2, service.expireOverdueChecks());
        assertEquals(CheckStatus.EXPIRED, a.getStatus());
        assertEquals(CheckStatus.EXPIRED, b.getStatus());
    }

    @Test
    void cancelReasonParsingIsLenient() {
        assertEquals(VerificationService.CancelReason.CLOSED, VerificationService.CancelReason.parse("closed"));
        assertEquals(VerificationService.CancelReason.CANCELLED, VerificationService.CancelReason.parse("whatever"));
        assertEquals(VerificationService.CancelReason.CANCELLED, VerificationService.CancelReason.parse(null));
    }

    @Test
    void workstationStateReasonsAreParsedAndHaveReadableDetail() {
        assertEquals(VerificationService.CancelReason.LOCKED, VerificationService.CancelReason.parse("locked"));
        assertEquals(VerificationService.CancelReason.ASLEEP, VerificationService.CancelReason.parse("asleep"));
        assertEquals(VerificationService.CancelReason.SHUTDOWN, VerificationService.CancelReason.parse("shutdown"));
        assertTrue(VerificationService.CancelReason.LOCKED.detail().toLowerCase().contains("locked"));
        assertTrue(VerificationService.CancelReason.ASLEEP.detail().toLowerCase().contains("sleep"));
        assertTrue(VerificationService.CancelReason.SHUTDOWN.detail().toLowerCase().contains("shutting down"));
    }

    @Test
    void cancelWithLockedRecordsTheWorkstationState() {
        var check = pendingCheck("n", Instant.now().plusSeconds(60));
        when(checks.findById(check.getId())).thenReturn(Optional.of(check));
        when(checks.save(any())).thenAnswer(i -> i.getArgument(0));

        var result = service.cancel(deviceId, check.getId(), VerificationService.CancelReason.LOCKED);

        assertEquals(CheckStatus.EXPIRED, result.getStatus());
        assertEquals(VerificationService.CancelReason.LOCKED.detail(), result.getErrorDetail());
        verify(audit).record(any(), org.mockito.ArgumentMatchers.eq("CHECK_ABANDONED"), any(), any(), any());
    }

    // ---- a check nobody was ever there to answer --------------------------------

    @Test
    void unattendedIsAboutNobodyBeingThereNotAboutARefusal() {
        assertEquals(
                VerificationService.CancelReason.UNATTENDED,
                VerificationService.CancelReason.parse("unattended"));
        String detail = VerificationService.CancelReason.UNATTENDED.detail().toLowerCase();
        assertTrue(detail.contains("nobody"), detail);
        assertTrue(detail.contains("work window"), detail);
    }

    @Test
    void machineAwayIsAboutTheMomentBeingMissedNotTheEmployee() {
        assertEquals(
                VerificationService.CancelReason.MACHINE_AWAY,
                VerificationService.CancelReason.parse("machine_away"));
        String detail = VerificationService.CancelReason.MACHINE_AWAY.detail().toLowerCase();
        assertTrue(detail.contains("asleep"), detail);
        assertTrue(detail.contains("work window had closed"), detail);
    }

    @Test
    void theAgentsNoteIsKeptSoTheAdministratorReadsWhatWasObserved() {
        var check = pendingCheck("n", Instant.now().plusSeconds(60));
        when(checks.findById(check.getId())).thenReturn(Optional.of(check));
        when(checks.save(any())).thenAnswer(i -> i.getArgument(0));

        var result = service.cancel(
                deviceId, check.getId(), VerificationService.CancelReason.UNATTENDED,
                "It came due at 15:10 and the work window closed at 17:00 first.");

        assertTrue(result.getErrorDetail().startsWith(
                VerificationService.CancelReason.UNATTENDED.detail()));
        assertTrue(result.getErrorDetail().contains("came due at 15:10"), result.getErrorDetail());
    }

    @Test
    void anOverlongNoteIsTrimmedRatherThanStored() {
        var check = pendingCheck("n", Instant.now().plusSeconds(60));
        when(checks.findById(check.getId())).thenReturn(Optional.of(check));
        when(checks.save(any())).thenAnswer(i -> i.getArgument(0));

        var result = service.cancel(
                deviceId, check.getId(), VerificationService.CancelReason.UNATTENDED, "x".repeat(5000));

        int reason = VerificationService.CancelReason.UNATTENDED.detail().length();
        assertTrue(result.getErrorDetail().length() <= reason + 1 + 400, "note was not trimmed");
    }

    @Test
    void noNoteLeavesTheReasonAlone() {
        var check = pendingCheck("n", Instant.now().plusSeconds(60));
        when(checks.findById(check.getId())).thenReturn(Optional.of(check));
        when(checks.save(any())).thenAnswer(i -> i.getArgument(0));

        var result = service.cancel(
                deviceId, check.getId(), VerificationService.CancelReason.UNATTENDED, "   ");

        assertEquals(VerificationService.CancelReason.UNATTENDED.detail(), result.getErrorDetail());
    }
}
