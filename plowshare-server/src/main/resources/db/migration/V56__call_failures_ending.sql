-- One more way a run can end: it kept writing tool calls as text instead of making them.
--
-- `Outcome.Ending.CALL_FAILURES` (implementation rationale §4).
-- A CHECK cannot be added to, only dropped and written out again whole (V9 says why), so both
-- lists below are rewritten FROM THE MIGRATION THAT LAST DEFINED THEM, V48, with the new name
-- appended. `TurnStoreTest.every_ending_this_server_can_reach_is_a_turn_this_table_holds` is what
-- fails when a constant arrives without a file like this one.
--
-- No data migration: no row can hold the new value before this file runs.

-- From V48.
ALTER TABLE turns DROP CONSTRAINT turns_ending_is_known;
ALTER TABLE turns ADD CONSTRAINT turns_ending_is_known CHECK (ending IN (
    'ANSWERED', 'TURN_CAP', 'CALL_BUDGET', 'CANCELLED', 'STUCK', 'UNAVAILABLE',
    'SUB_AGENT_FAILED', 'SESSION_GONE', 'AWAITING', 'CALL_FAILURES'));

-- From V48.
ALTER TABLE jobs DROP CONSTRAINT jobs_ending_is_known;
ALTER TABLE jobs ADD CONSTRAINT jobs_ending_is_known CHECK (ending IS NULL OR ending IN (
    'ANSWERED', 'TURN_CAP', 'CALL_BUDGET', 'CANCELLED', 'STUCK', 'UNAVAILABLE',
    'SUB_AGENT_FAILED', 'SESSION_GONE', 'AWAITING', 'CALL_FAILURES'));
