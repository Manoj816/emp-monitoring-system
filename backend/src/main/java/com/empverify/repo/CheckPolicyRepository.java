package com.empverify.repo;

import com.empverify.domain.CheckPolicy;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CheckPolicyRepository extends JpaRepository<CheckPolicy, UUID> {
    Optional<CheckPolicy> findByName(String name);
}
