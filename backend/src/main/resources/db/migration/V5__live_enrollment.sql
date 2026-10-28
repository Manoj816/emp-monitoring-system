-- Live enrollment: the frame from the employee's first matching face scan becomes a
-- second reference (LIVE_ENROLLMENT) next to the admin-uploaded ID/photo
-- (ADMIN_UPLOAD). Later checks are compared against both; the best match decides.
ALTER TABLE reference_images ADD COLUMN source TEXT NOT NULL DEFAULT 'ADMIN_UPLOAD';

-- No scan at assignment or at launch any more: the first scan is the randomized
-- daily check (the enrollment scan), exactly like every check after it.
UPDATE check_policies SET liveness_on_session_start = FALSE;
