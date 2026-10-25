package com.empverify.repo;

import com.empverify.domain.EnrollmentToken;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EnrollmentTokenRepository extends JpaRepository<EnrollmentToken, UUID> {
}
