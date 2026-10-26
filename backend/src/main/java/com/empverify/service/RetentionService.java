package com.empverify.service;

import com.empverify.repo.VerificationCheckRepository;
import com.empverify.storage.ImageStorage;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Enforces the evidence retention policy (PRD Requirement 7): capture evidence is
 * kept only for flagged outcomes and only for a bounded period. This is the
 * application-level backstop; in production S3 lifecycle rules enforce the same
 * window on the bucket.
 */
@Service
public class RetentionService {

    private final VerificationCheckRepository checks;
    private final ImageStorage storage;
    private final int evidenceDays;

    public RetentionService(
            VerificationCheckRepository checks,
            ImageStorage storage,
            @Value("${empverify.retention.evidence-days:90}") int evidenceDays) {
        this.checks = checks;
        this.storage = storage;
        this.evidenceDays = evidenceDays;
    }

    /** Runs daily at 03:15. Also callable directly from tests. */
    @Scheduled(cron = "0 15 3 * * *")
    public void purgeExpiredEvidenceDaily() {
        purgeExpiredEvidence(Instant.now());
    }

    /**
     * Deletes stored evidence for flagged checks older than the retention window
     * and clears the reference to it. The check record itself (status, score,
     * timestamps) is kept for the audit history.
     *
     * @return number of evidence objects purged
     */
    @Transactional
    public int purgeExpiredEvidence(Instant now) {
        Instant cutoff = now.minus(Duration.ofDays(evidenceDays));
        var expired = checks.findByEvidenceS3KeyIsNotNullAndCompletedAtBefore(cutoff);
        int purged = 0;
        for (var check : expired) {
            try {
                storage.delete(check.getEvidenceS3Key());
            } catch (RuntimeException e) {
                // Best-effort delete; clear the reference regardless so it is not retried forever.
            }
            check.setEvidenceS3Key(null);
            checks.save(check);
            purged++;
        }
        return purged;
    }
}
