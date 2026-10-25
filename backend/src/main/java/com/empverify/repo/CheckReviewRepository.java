package com.empverify.repo;

import com.empverify.domain.CheckReview;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CheckReviewRepository extends JpaRepository<CheckReview, UUID> {
    List<CheckReview> findByCheckIdInOrderByCreatedAtDesc(List<UUID> checkIds);
}
