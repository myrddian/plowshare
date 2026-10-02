-- The hook entry: one row per hook decision, recorded and never projected. See
-- implementation rationale §9.
--
-- REWRITTEN FROM V40, which last defined entries_kind_is_known (keeping
-- 'refusal' and 'notice'). entries_role_matches_kind is NOT rewritten: a kind
-- absent from its CASE must carry a NULL role, which is exactly a hook's.
-- entries_only_a_completed_operation_is_timed is NOT rewritten either: a hook
-- keeps its duration inside its JSON content, not in took_ms.

ALTER TABLE entries DROP CONSTRAINT entries_kind_is_known;
ALTER TABLE entries ADD CONSTRAINT entries_kind_is_known CHECK (kind IN (
    'utterance', 'answer', 'tool_result', 'summary',
    'attempt_failed', 'runtime_note', 'plan', 'diagnostic',
    'refusal',
    'notice',
    'hook'
));
