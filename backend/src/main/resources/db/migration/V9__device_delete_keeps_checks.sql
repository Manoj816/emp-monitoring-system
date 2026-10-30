-- Deleting a system (a stale duplicate row from before machine ids, a retired
-- machine) must not take the employee's verification history with it: the
-- checks keep their employee and only lose the link to the system.
ALTER TABLE verification_checks ALTER COLUMN device_id DROP NOT NULL;

-- V1 declared the foreign key inline, so its name is whatever Postgres chose:
-- look it up rather than assume it.
DO $$
DECLARE fk text;
BEGIN
    SELECT c.conname INTO fk
      FROM pg_constraint c
      JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
     WHERE c.conrelid = 'verification_checks'::regclass
       AND c.contype = 'f'
       AND a.attname = 'device_id';
    IF fk IS NOT NULL THEN
        EXECUTE format('ALTER TABLE verification_checks DROP CONSTRAINT %I', fk);
    END IF;
END $$;

ALTER TABLE verification_checks
    ADD CONSTRAINT verification_checks_device_id_fkey
    FOREIGN KEY (device_id) REFERENCES devices(id) ON DELETE SET NULL;
