package com.empverify.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Append-only, tamper-evident audit trail for sensitive actions (enrollment,
 * image changes, verification outcomes, admin access, reviews).
 *
 * Each entry stores entry_hash = SHA-256 over the previous entry's hash plus this
 * entry's fields, forming a hash chain: altering or deleting any past row breaks
 * every subsequent hash, which {@link #verifyChain()} detects.
 *
 * Note: the chain is appended under a serialized section so concurrent writers
 * cannot fork it. Single-writer-per-insert is enforced with a row lock on the
 * latest entry; acceptable for the expected audit volume.
 */
@Service
public class AuditService {

    private final JdbcTemplate jdbc;

    public AuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Canonical hash of one audit entry. Pure and deterministic so it can be unit
     * tested and recomputed during verification.
     */
    public static String computeEntryHash(
            String prevHash, String actor, String action, String entityType,
            String entityId, String detailsJson, Instant createdAt) {
        String canonical = String.join("\u001f",
                prevHash == null ? "" : prevHash,
                actor == null ? "" : actor,
                action == null ? "" : action,
                entityType == null ? "" : entityType,
                entityId == null ? "" : entityId,
                detailsJson == null ? "" : detailsJson,
                createdAt == null ? "" : createdAt.toString());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @Transactional
    public void record(String actor, String action, String entityType, String entityId, String detailsJson) {
        String details = detailsJson == null ? "{}" : detailsJson;
        // Truncate to microseconds so the hashed timestamp survives the Postgres
        // timestamptz round-trip (which stores microsecond precision).
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        // Lock the latest row so concurrent inserts chain in a defined order.
        List<String> prev = jdbc.query(
                "SELECT entry_hash FROM audit_log ORDER BY id DESC LIMIT 1 FOR UPDATE",
                (rs, i) -> rs.getString(1));
        String prevHash = prev.isEmpty() ? null : prev.get(0);

        String entryHash = computeEntryHash(prevHash, actor, action, entityType, entityId, details, now);

        jdbc.update(
                "INSERT INTO audit_log (actor, action, entity_type, entity_id, details, prev_hash, entry_hash, created_at) "
                        + "VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?)",
                actor, action, entityType, entityId, details, prevHash, entryHash,
                java.sql.Timestamp.from(now));
    }

    /**
     * Recomputes the whole chain and returns whether it is intact. A broken link
     * means a row was altered, inserted, or deleted out of band.
     */
    public boolean verifyChain() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT actor, action, entity_type, entity_id, details::text AS details, "
                        + "prev_hash, entry_hash, created_at FROM audit_log ORDER BY id ASC");
        String expectedPrev = null;
        for (Map<String, Object> row : rows) {
            String prevHash = (String) row.get("prev_hash");
            if (!java.util.Objects.equals(prevHash, expectedPrev)) {
                return false;
            }
            Instant createdAt = ((java.sql.Timestamp) row.get("created_at")).toInstant();
            String recomputed = computeEntryHash(
                    prevHash, (String) row.get("actor"), (String) row.get("action"),
                    (String) row.get("entity_type"), (String) row.get("entity_id"),
                    (String) row.get("details"), createdAt);
            if (!recomputed.equals(row.get("entry_hash"))) {
                return false;
            }
            expectedPrev = recomputed;
        }
        return true;
    }
}
