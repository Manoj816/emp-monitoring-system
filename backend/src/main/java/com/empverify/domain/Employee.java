package com.empverify.domain;

import com.empverify.domain.Enums.EmployeeStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "employees")
public class Employee {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "employee_code", nullable = false, unique = true)
    private String employeeCode;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(nullable = false, unique = true)
    private String email;

    private String department;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EmployeeStatus status = EmployeeStatus.ACTIVE;

    @Column(name = "policy_id")
    private UUID policyId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public UUID getId() { return id; }
    public String getEmployeeCode() { return employeeCode; }
    public void setEmployeeCode(String v) { this.employeeCode = v; }
    public String getFullName() { return fullName; }
    public void setFullName(String v) { this.fullName = v; }
    public String getEmail() { return email; }
    public void setEmail(String v) { this.email = v; }
    public String getDepartment() { return department; }
    public void setDepartment(String v) { this.department = v; }
    public EmployeeStatus getStatus() { return status; }
    public void setStatus(EmployeeStatus v) { this.status = v; }
    public UUID getPolicyId() { return policyId; }
    public void setPolicyId(UUID v) { this.policyId = v; }
    public Instant getCreatedAt() { return createdAt; }
}
