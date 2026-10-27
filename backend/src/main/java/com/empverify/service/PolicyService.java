package com.empverify.service;

import com.empverify.domain.CheckPolicy;
import com.empverify.domain.Device;
import com.empverify.repo.CheckPolicyRepository;
import com.empverify.repo.EmployeeRepository;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class PolicyService {

    private final CheckPolicyRepository policies;
    private final EmployeeRepository employees;

    public PolicyService(CheckPolicyRepository policies, EmployeeRepository employees) {
        this.policies = policies;
        this.employees = employees;
    }

    /** Employee's assigned policy, falling back to the seeded 'default' policy. */
    public CheckPolicy effectiveFor(Device device) {
        return effectiveForEmployee(device.getEmployeeId());
    }

    public CheckPolicy effectiveForEmployee(UUID employeeId) {
        UUID policyId = employees.findById(employeeId)
                .map(e -> e.getPolicyId())
                .orElse(null);
        if (policyId != null) {
            var assigned = policies.findById(policyId);
            if (assigned.isPresent()) {
                return assigned.get();
            }
        }
        return policies.findByName("default")
                .orElseThrow(() -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "default policy missing"));
    }
}
