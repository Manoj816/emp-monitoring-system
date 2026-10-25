package com.empverify.repo;

import com.empverify.domain.Enums.ReferenceImageStatus;
import com.empverify.domain.ReferenceImage;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReferenceImageRepository extends JpaRepository<ReferenceImage, UUID> {
    Optional<ReferenceImage> findByEmployeeIdAndStatus(UUID employeeId, ReferenceImageStatus status);
}
