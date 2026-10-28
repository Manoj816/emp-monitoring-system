package com.empverify.service;

import com.empverify.storage.ImageStorage;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Wipes all demo data — employees, devices, enrollment tokens, reference images,
 * checks, reviews and their stored images — so a demo starts from a clean slate.
 * Policies and the tamper-evident audit log are kept, and the reset itself is
 * audited. Refuses to run unless empverify.demo.reset-enabled=true; never enable
 * that in production.
 */
@Service
public class DemoResetService {

    private final JdbcTemplate jdbc;
    private final ImageStorage storage;
    private final AuditService audit;
    private final boolean enabled;

    public DemoResetService(
            JdbcTemplate jdbc,
            ImageStorage storage,
            AuditService audit,
            @Value("${empverify.demo.reset-enabled:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.audit = audit;
        this.enabled = enabled;
    }

    public record Summary(int employees, int devices, int checks, int imagesDeleted) {}

    @Transactional
    public Summary reset(String actor) {
        if (!enabled) {
            throw new ApiException(HttpStatus.FORBIDDEN, "demo reset is disabled on this server");
        }
        // Collect stored-image keys first; the rows are gone after the truncate.
        List<String> keys = new ArrayList<>();
        keys.addAll(jdbc.queryForList(
                "SELECT s3_key FROM reference_images WHERE s3_key IS NOT NULL", String.class));
        keys.addAll(jdbc.queryForList(
                "SELECT evidence_s3_key FROM verification_checks WHERE evidence_s3_key IS NOT NULL",
                String.class));

        int employees = count("employees");
        int devices = count("devices");
        int checks = count("verification_checks");

        jdbc.execute("TRUNCATE TABLE check_reviews, verification_checks, reference_images, "
                + "enrollment_tokens, devices, employees RESTART IDENTITY CASCADE");

        int deleted = 0;
        for (String key : keys) {
            try {
                storage.delete(key);
                deleted++;
            } catch (RuntimeException e) {
                // best effort: an orphaned object is harmless and retention will purge it
            }
        }

        audit.record(actor, "DEMO_RESET", "system", "all",
                "{\"employees\":" + employees + ",\"devices\":" + devices + ",\"checks\":" + checks + "}");
        return new Summary(employees, devices, checks, deleted);
    }

    private int count(String table) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
        return n == null ? 0 : n;
    }
}
