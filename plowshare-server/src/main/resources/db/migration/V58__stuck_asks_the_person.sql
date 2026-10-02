-- A run that ended three turns in a row without making progress asks the person whether it goes
-- on, instead of failing `stuck` (spec 2026-09-28, "stuck" tells the person why). Measured that
-- day, orc_3187D648AC346812: the person got "stuck" with no reason and no way on.
--
-- The question rides V49's cap machinery: pending_cap says which harness question the open one is,
-- and the answer copies it onto its own cap_kind, because the same write clears the row's copy.
-- 'stuck' is the one kind only a person may answer; OrchestrationStore refuses a model's answer to
-- it inside the answer's own lock.
--
-- Both CHECKs are rewritten from V49, which is the latest definition of each: no migration since
-- has touched either, so nothing between is dropped (rewrite from the last definer, not the
-- migration that explains the column).

ALTER TABLE orchestrations DROP CONSTRAINT orchestrations_pending_cap_is_known;
ALTER TABLE orchestrations ADD CONSTRAINT orchestrations_pending_cap_is_known
    CHECK (pending_cap IS NULL OR pending_cap IN ('turn_cap', 'call_budget', 'stuck'));

ALTER TABLE orchestration_messages DROP CONSTRAINT orchestration_messages_cap_kind_is_known;
ALTER TABLE orchestration_messages ADD CONSTRAINT orchestration_messages_cap_kind_is_known
    CHECK (cap_kind IS NULL
        OR (kind = 'answer' AND cap_kind IN ('turn_cap', 'call_budget', 'stuck')));

-- Whether the conductor has EVER ended a turn in prose. `nudges` used to mean that as well as the
-- stuck limit's count, and progress now resets the count (a stage moved, a delegation returned, the
-- person's "go on"); read for the forced tool call, a reset would have let a conductor that had
-- already narrated once end its next turn in prose again. So the mark is its own column, set with
-- every nudge and never cleared. A run nudged before this file carries the mark it had.
ALTER TABLE orchestrations ADD COLUMN ended_in_prose BOOLEAN NOT NULL DEFAULT false;
UPDATE orchestrations SET ended_in_prose = true WHERE nudges > 0;
