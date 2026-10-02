-- What a call thought is a row of its own in the log, not a column on the
-- call's row. V42 put it in entries.thinking; it moves to an entry of kind
-- 'thinking', written just before the answer or refusal of the call that thought
-- it, and like every kind with no role it is recorded and never projected.
--
-- V42 is not edited: it is on master (MigrationsAreImmutableTest), so undoing it
-- is this migration's job. No row carries the column yet -- nothing wrote it
-- before this -- so dropping it loses nothing.
--
-- FILED AS V43 AND RENUMBERED V44: master's V43__hook_entries merged in first.
--
-- entries_kind_is_known IS REWRITTEN FROM V43, which last defined it (adding
-- 'hook'); V41 and V42 did not touch it. entries_role_matches_kind is NOT
-- rewritten: its CASE gives an unlisted kind a NULL role, which is exactly what
-- a thinking row has. Nor V39's entries_only_a_model_says_something_by_a_call
-- or entries_only_a_completed_operation_is_timed: a thinking row carries no
-- invocation and no duration, which both already permit for any kind.

ALTER TABLE entries DROP CONSTRAINT entries_thinking_belongs_to_a_call;
ALTER TABLE entries DROP COLUMN thinking;

ALTER TABLE entries DROP CONSTRAINT entries_kind_is_known;
ALTER TABLE entries ADD CONSTRAINT entries_kind_is_known CHECK (kind IN (
    'utterance', 'answer', 'tool_result', 'summary',
    'attempt_failed', 'runtime_note', 'plan', 'diagnostic',
    'refusal',
    'notice',
    'hook',
    'thinking'
));

-- Restated from V40, which last set it (V43 added 'hook' without restating it),
-- so the column's own description names both kinds added since.
COMMENT ON COLUMN entries.kind IS
    'What kind of thing this is. utterance, answer, tool_result and summary '
    'project into a request as user, assistant, tool and system, and notice -- '
    'the harness telling a bot about its speaker''s unread inbox -- as user; '
    'attempt_failed, runtime_note, plan, diagnostic, refusal, hook and thinking '
    'are recorded and never reach a model. A kind with no role does not project, so '
    'a kind added without a decision is invisible rather than accidentally '
    'visible. A refusal is a model''s probable refusal that the agent''s fallback '
    'was dispatched to answer in its place. A thinking row is what a model '
    'streamed as reasoning during one call, just before what that call said.';
