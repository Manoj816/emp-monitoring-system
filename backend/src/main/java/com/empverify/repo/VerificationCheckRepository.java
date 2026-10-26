package com.empverify.repo;

import com.empverify.domain.VerificationCheck;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VerificationCheckRepository extends JpaRepository<VerificationCheck, UUID> {
    List<VerificationCheck> findByDeviceIdOrderByRequestedAtDesc(UUID deviceId, Pageable pageable);
    List<VerificationCheck> findAllByOrderByRequestedAtDesc(Pageable pageable);

    /** Checks whose retained evidence is older than the retention cutoff. */
    List<VerificationCheck> findByEvidenceS3KeyIsNotNullAndCompletedAtBefore(Instant cutoff);
}
