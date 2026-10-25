package com.empverify.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "check_reviews")
public class CheckReview {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "check_id", nullable = false)
    private UUID checkId;

    @Column(nullable = false)
    private String reviewer;

    /** CONFIRMED_EMPLOYEE | CONFIRMED_MISMATCH | TECHNICAL_ISSUE | INCONCLUSIVE */
    @Column(nullable = false)
    private String resolution;

    private String notes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public UUID getCheckId() { return checkId; }
    public void setCheckId(UUID v) { this.checkId = v; }
    public String getReviewer() { return reviewer; }
    public void setReviewer(String v) { this.reviewer = v; }
    public String getResolution() { return resolution; }
    public void setResolution(String v) { this.resolution = v; }
    public String getNotes() { return notes; }
    public void setNotes(String v) { this.notes = v; }
    public Instant getCreatedAt() { return createdAt; }
}
