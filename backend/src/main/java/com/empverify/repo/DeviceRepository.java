package com.empverify.repo;

import com.empverify.domain.Device;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceRepository extends JpaRepository<Device, UUID> {
    List<Device> findByEmployeeId(UUID employeeId);
}
