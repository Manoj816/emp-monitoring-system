-- Liveness session reference for interactive Face Liveness checks.
ALTER TABLE verification_checks ADD COLUMN liveness_session_id TEXT;
