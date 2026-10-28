package com.empverify.repo;

import com.empverify.domain.Enums.ReferenceImageSource;
import com.empverify.domain.Enums.ReferenceImageStatus;
import com.empverify.domain.ReferenceImage;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReferenceImageRepository extends JpaRepository<ReferenceImage, UUID> {

    /** All references a check is compared against (the uploaded ID/photo and, once enrolled, the live scan). */
    List<ReferenceImage> findAllByEmployeeIdAndStatus(UUID employeeId, ReferenceImageStatus status);

    /** One reference of a given origin; at most one is ACTIVE per employee and source. */
    Optional<ReferenceImage> findByEmployeeIdAndStatusAndSource(
            UUID employeeId, ReferenceImageStatus status, ReferenceImageSource source);
}
