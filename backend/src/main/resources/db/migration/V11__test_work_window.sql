-- TEMPORARY (testing, 2026-10-31): narrow the default work window to
-- 12:00-15:00 so the daily random check and the end-of-window NO_CHECK sweep
-- can be exercised inside a three-hour window instead of a full 9-to-5 day.
-- The agent applies this window in the PC's local time; the NO_CHECK sweep
-- judges it in WORK_ZONE. Revert to '09:00'/'17:00' with a later migration
-- when testing is done.
UPDATE check_policies
SET work_window_start = '12:00',
    work_window_end   = '15:00',
    updated_at        = now()
WHERE name = 'default';
