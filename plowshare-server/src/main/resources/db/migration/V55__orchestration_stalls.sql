-- When a running orchestration went quiet, as the stall sweep last judged it
-- (implementation rationale §4). Set by compare-and-set so a stall
-- is reported once even when several servers share this database; cleared when the run moves again.
ALTER TABLE orchestrations ADD COLUMN stalled_since TIMESTAMPTZ;
ALTER TABLE orchestrations ADD CONSTRAINT orchestrations_only_a_running_run_is_stalled
    CHECK (stalled_since IS NULL OR state = 'running');
