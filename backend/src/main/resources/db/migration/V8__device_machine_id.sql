-- A stable identifier of the physical installation (Windows MachineGuid), sent by
-- the agent when it registers and on every sign-in. A reinstall or a credential
-- reset on the same machine re-uses its existing device row instead of creating
-- a duplicate.
ALTER TABLE devices ADD COLUMN machine_id TEXT;
CREATE INDEX idx_devices_machine_id ON devices(machine_id);
