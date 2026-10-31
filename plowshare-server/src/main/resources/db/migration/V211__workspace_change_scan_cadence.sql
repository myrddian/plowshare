-- Hint storms may invalidate an in-flight scan, but cannot start scans faster than the minimum
-- cadence. checked_at alone cannot bound retries when a scan never reaches publication.
ALTER TABLE code_workspaces ADD COLUMN scan_started_at TIMESTAMPTZ;
