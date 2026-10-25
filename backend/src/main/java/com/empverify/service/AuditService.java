package com.empverify.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Append-only audit trail for sensitive actions (enrollment, image changes,
 * verification outcomes, admin access). Hash chaining per entity: TODO Phase 1
 * hardening — columns already exist.
 */
@Service
public class AuditService {

    private final JdbcTemplate jdbc;

    public AuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String actor, String action, String entityType, String entityId, String detailsJson) {
        jdbc.update(
                "INSERT INTO audit_log (actor, action, entity_type, entity_id, details) VALUES (?, ?, ?, ?, ?::jsonb)",
                actor, action, entityType, entityId, detailsJson == null ? "{}" : detailsJson);
    }
}
