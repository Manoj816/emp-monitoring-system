-- A check nobody answers leaves only a reason behind, so "the desk was empty" and
-- "the employee ignored it" look identical in the record. With this on, the agent
-- takes one frame at the moment the response window closes and attaches it to the
-- missed check as evidence for a person to look at. Nothing is compared against it.
--
-- Off by default, deliberately: when nobody is at the desk the camera sees whatever
-- is behind it, which may be a room and people who are not the employee. Each
-- deployment turns this on for itself, and the check window tells the employee so
-- before the countdown starts.
ALTER TABLE check_policies
    ADD COLUMN capture_on_timeout BOOLEAN NOT NULL DEFAULT FALSE;
