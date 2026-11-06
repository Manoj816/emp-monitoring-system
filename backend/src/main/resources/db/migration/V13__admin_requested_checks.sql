-- Admin-requested checks ("send a check now" in the portal). The request is a
-- timestamp on the device: the agent sees it on its next state poll (within a
-- minute) and opens the scan. Opening any check clears it, so the request can
-- never fire twice, and a stale request dies with the check that answered it.
ALTER TABLE devices ADD COLUMN check_requested_at TIMESTAMPTZ;

-- A check an administrator asked for is its own type in the record, so the
-- checks list says who initiated each scan: the day's random plan or an admin.
ALTER TABLE verification_checks DROP CONSTRAINT IF EXISTS verification_checks_check_type_check;
ALTER TABLE verification_checks ADD CONSTRAINT verification_checks_check_type_check
    CHECK (check_type IN ('ENROLLMENT', 'SESSION_START', 'PERIODIC', 'LIVENESS', 'ESCALATION', 'ON_DEMAND'));
