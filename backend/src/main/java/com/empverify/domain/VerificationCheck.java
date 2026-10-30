package com.empverify.domain;

import com.empverify.domain.Enums.CheckStatus;
import com.empverify.domain.Enums.CheckType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "verification_checks")
public class VerificationCheck {

    @Id
    private UUID id = UUID.randomUUID();

    /** The system the check ran on; null once that system was deleted (the check stays with the employee). */
    @Column(name = "device_id")
    private UUID deviceId;

    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "check_type", nullable = false)
    private CheckType checkType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CheckStatus status = CheckStatus.PENDING;

    @Column(name = "mismatch_flag", nullable = false)
    private boolean mismatchFlag;

    @Column(name = "similarity_score")
    private BigDecimal similarityScore;

    @Column(name = "liveness_confidence")
    private BigDecimal livenessConfidence;

    @Column(name = "evidence_s3_key")
    private String evidenceS3Key;

    @Column(nullable = false)
    private String nonce;

    @Column(name = "liveness_session_id")
    private String livenessSessionId;

    @Column(name = "error_detail")
    private String errorDetail;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    public UUID getId() { return id; }
    public UUID getDeviceId() { return deviceId; }
    public void setDeviceId(UUID v) { this.deviceId = v; }
    public UUID getEmployeeId() { return employeeId; }
    public void setEmployeeId(UUID v) { this.employeeId = v; }
    public CheckType getCheckType() { return checkType; }
    public void setCheckType(CheckType v) { this.checkType = v; }
    public CheckStatus getStatus() { return status; }
    public void setStatus(CheckStatus v) { this.status = v; }
    public boolean isMismatchFlag() { return mismatchFlag; }
    public void setMismatchFlag(boolean v) { this.mismatchFlag = v; }
    public BigDecimal getSimilarityScore() { return similarityScore; }
    public void setSimilarityScore(BigDecimal v) { this.similarityScore = v; }
    public BigDecimal getLivenessConfidence() { return livenessConfidence; }
    public void setLivenessConfidence(BigDecimal v) { this.livenessConfidence = v; }
    public String getEvidenceS3Key() { return evidenceS3Key; }
    public void setEvidenceS3Key(String v) { this.evidenceS3Key = v; }
    public String getNonce() { return nonce; }
    public void setNonce(String v) { this.nonce = v; }
    public String getLivenessSessionId() { return livenessSessionId; }
    public void setLivenessSessionId(String v) { this.livenessSessionId = v; }
    public String getErrorDetail() { return errorDetail; }
    public void setErrorDetail(String v) { this.errorDetail = v; }
    public Instant getRequestedAt() { return requestedAt; }
    /** Only for server-recorded rows (NO_CHECK), which are dated at the end of the work window. */
    public void setRequestedAt(Instant v) { this.requestedAt = v; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant v) { this.expiresAt = v; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant v) { this.completedAt = v; }
}
