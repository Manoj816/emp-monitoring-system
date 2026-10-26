package com.empverify.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.empverify.domain.VerificationCheck;
import com.empverify.repo.VerificationCheckRepository;
import com.empverify.storage.ImageStorage;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RetentionServiceTest {

    @Mock private VerificationCheckRepository checks;
    @Mock private ImageStorage storage;

    @Test
    void purgesExpiredEvidenceAndClearsReference() {
        var check = new VerificationCheck();
        check.setEvidenceS3Key("evidence/old.jpg");
        var service = new RetentionService(checks, storage, 90);
        when(checks.findByEvidenceS3KeyIsNotNullAndCompletedAtBefore(any()))
                .thenReturn(List.of(check));
        when(checks.save(any())).thenAnswer(i -> i.getArgument(0));

        int purged = service.purgeExpiredEvidence(Instant.parse("2026-10-26T00:00:00Z"));

        assertEquals(1, purged);
        verify(storage).delete("evidence/old.jpg");
        assertNull(check.getEvidenceS3Key(), "evidence reference cleared after purge");
    }

    @Test
    void cutoffIsRetentionWindowBeforeNow() {
        var service = new RetentionService(checks, storage, 30);
        when(checks.findByEvidenceS3KeyIsNotNullAndCompletedAtBefore(any())).thenReturn(List.of());

        service.purgeExpiredEvidence(Instant.parse("2026-10-26T00:00:00Z"));

        var cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(checks).findByEvidenceS3KeyIsNotNullAndCompletedAtBefore(cutoff.capture());
        assertEquals(Instant.parse("2026-09-26T00:00:00Z"), cutoff.getValue());
    }

    @Test
    void storageFailureStillClearsReference() {
        var check = new VerificationCheck();
        check.setEvidenceS3Key("evidence/x.jpg");
        var service = new RetentionService(checks, storage, 90);
        when(checks.findByEvidenceS3KeyIsNotNullAndCompletedAtBefore(any())).thenReturn(List.of(check));
        when(checks.save(any())).thenAnswer(i -> i.getArgument(0));
        org.mockito.Mockito.doThrow(new RuntimeException("s3 down")).when(storage).delete(any());

        int purged = service.purgeExpiredEvidence(Instant.now());

        assertEquals(1, purged);
        assertNull(check.getEvidenceS3Key());
    }
}
