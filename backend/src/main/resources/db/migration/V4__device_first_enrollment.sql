-- Device-first enrollment: a system registers itself with the tenant provisioning
-- token and stays unassigned (PENDING) until an admin links it to an employee.
ALTER TABLE devices ALTER COLUMN employee_id DROP NOT NULL;
