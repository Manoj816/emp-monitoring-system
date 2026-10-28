package com.empverify.domain;

import com.empverify.domain.Enums.ReferenceImageSource;
import com.empverify.domain.Enums.ReferenceImageStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reference_images")
public class ReferenceImage {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    @Column(name = "s3_key", nullable = false)
    private String s3Key;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReferenceImageStatus status = ReferenceImageStatus.ACTIVE;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReferenceImageSource source = ReferenceImageSource.ADMIN_UPLOAD;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public ReferenceImageSource getSource() { return source; }
    public void setSource(ReferenceImageSource v) { this.source = v; }
    public Instant getCreatedAt() { return createdAt; }
    public UUID getEmployeeId() { return employeeId; }
    public void setEmployeeId(UUID v) { this.employeeId = v; }
    public String getS3Key() { return s3Key; }
    public void setS3Key(String v) { this.s3Key = v; }
    public ReferenceImageStatus getStatus() { return status; }
    public void setStatus(ReferenceImageStatus v) { this.status = v; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String v) { this.createdBy = v; }
}
