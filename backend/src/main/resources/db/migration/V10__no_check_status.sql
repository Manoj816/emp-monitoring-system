-- A work day on which none of an employee's systems opened a check (machine
-- off, agent not running) used to leave no trace at all. The server now records
-- it after the work window as a NO_CHECK row, so the administrator sees the
-- absence under Missed. V1's status constraint has to learn the value.
ALTER TABLE verification_checks DROP CONSTRAINT IF EXISTS verification_checks_status_check;
ALTER TABLE verification_checks ADD CONSTRAINT verification_checks_status_check
    CHECK (status IN ('PENDING', 'MATCH', 'CHECK', 'NO_FACE', 'MULTIPLE_FACES',
                      'VERIFICATION_ERROR', 'EXPIRED', 'NO_CHECK'));
