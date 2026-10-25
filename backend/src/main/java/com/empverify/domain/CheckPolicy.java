package com.empverify.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.UUID;

@Entity
@Table(name = "check_policies")
public class CheckPolicy {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(nullable = false, unique = true)
    private String name;

    @Column(name = "work_window_start", nullable = false)
    private LocalTime workWindowStart = LocalTime.of(9, 0);

    @Column(name = "work_window_end", nullable = false)
    private LocalTime workWindowEnd = LocalTime.of(17, 0);

    @Column(nullable = false)
    private String timezone = "UTC";

    @Column(name = "max_checks_per_day", nullable = false)
    private int maxChecksPerDay = 4;

    @Column(name = "response_timeout_seconds", nullable = false)
    private int responseTimeoutSeconds = 120;

    @Column(name = "match_threshold", nullable = false)
    private BigDecimal matchThreshold = new BigDecimal("90.00");

    @Column(name = "review_threshold", nullable = false)
    private BigDecimal reviewThreshold = new BigDecimal("75.00");

    @Column(name = "liveness_on_session_start", nullable = false)
    private boolean livenessOnSessionStart = true;

    public UUID getId() { return id; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public LocalTime getWorkWindowStart() { return workWindowStart; }
    public LocalTime getWorkWindowEnd() { return workWindowEnd; }
    public String getTimezone() { return timezone; }
    public int getMaxChecksPerDay() { return maxChecksPerDay; }
    public int getResponseTimeoutSeconds() { return responseTimeoutSeconds; }
    public BigDecimal getMatchThreshold() { return matchThreshold; }
    public BigDecimal getReviewThreshold() { return reviewThreshold; }
    public boolean isLivenessOnSessionStart() { return livenessOnSessionStart; }
}
