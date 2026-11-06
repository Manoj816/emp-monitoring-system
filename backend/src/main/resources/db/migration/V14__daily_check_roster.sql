-- Which employees get the daily random check. ON for everyone by default; the
-- administrator manages the roster on the portal's Configuration tab. An
-- excluded employee is still enrolled, still answers admin-sent checks, and is
-- never judged NO_CHECK for a day on which no check was ever going to run.
ALTER TABLE employees ADD COLUMN daily_checks_enabled BOOLEAN NOT NULL DEFAULT TRUE;
