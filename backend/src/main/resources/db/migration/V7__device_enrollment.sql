-- Enrollment is per system, not per employee: a newly registered, reinstalled or
-- reassigned system always starts with the enrollment scan, which (re)captures
-- the employee's live reference. Set when an ENROLLMENT check on this device
-- matches; cleared on (re)assignment and when the ID/photo is replaced.
ALTER TABLE devices ADD COLUMN enrolled_at TIMESTAMPTZ;
