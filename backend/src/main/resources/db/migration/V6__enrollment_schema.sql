-- Enrollment model follow-up (see V5): the schema still enforced the single-
-- reference, four-check-type world of V1.
--
-- 1. An employee now has up to two ACTIVE references: the uploaded ID/photo
--    (ADMIN_UPLOAD) and the frame from the first matching live scan
--    (LIVE_ENROLLMENT). The V1 unique index allowed only one ACTIVE row per
--    employee, so storing the live enrollment failed with a duplicate key.
DROP INDEX IF EXISTS idx_reference_images_active;
CREATE UNIQUE INDEX idx_reference_images_active
    ON reference_images(employee_id, source) WHERE status = 'ACTIVE';

-- 2. The first live scan is its own check type, ENROLLMENT; the V1 check
--    constraint rejected it, so every enrollment check failed to open.
ALTER TABLE verification_checks DROP CONSTRAINT IF EXISTS verification_checks_check_type_check;
ALTER TABLE verification_checks ADD CONSTRAINT verification_checks_check_type_check
    CHECK (check_type IN ('ENROLLMENT', 'SESSION_START', 'PERIODIC', 'LIVENESS', 'ESCALATION'));
