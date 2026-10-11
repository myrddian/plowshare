-- Application releases remain immutable. This override is operational state, keyed
-- to an owned schedule file; reconciliation clears it when desired content changes.
ALTER TABLE schedule_files ADD COLUMN paused_override BOOLEAN;
