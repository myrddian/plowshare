-- What an orchestration needs to rebuild its conductor after a restart, and the caller's session.
-- See implementation rationale, decisions 1–2.
--
-- definition_source is the orchestration file's text as it was read at start, and
-- definition_origin where it was read from; the engine re-parses them rather than re-resolving
-- a tier that may have changed or, for a laptop session, gone. The defaults exist only so the
-- columns can be added to a table that may hold test rows; every insert writes both.

ALTER TABLE orchestrations ADD COLUMN definition_source TEXT NOT NULL DEFAULT '';
ALTER TABLE orchestrations ALTER COLUMN definition_source DROP DEFAULT;
ALTER TABLE orchestrations ADD COLUMN definition_origin TEXT NOT NULL DEFAULT '';
ALTER TABLE orchestrations ALTER COLUMN definition_origin DROP DEFAULT;
ALTER TABLE orchestrations ADD COLUMN caller_session TEXT;

-- A conductor that hit a cap asks its caller whether to raise it; this says which cap the open
-- question is about, and only a run that is asking can have one.
ALTER TABLE orchestrations ADD COLUMN pending_cap TEXT;
ALTER TABLE orchestrations ADD CONSTRAINT orchestrations_pending_cap_is_known
    CHECK (pending_cap IS NULL OR pending_cap IN ('turn_cap', 'call_budget'));
ALTER TABLE orchestrations ADD CONSTRAINT orchestrations_a_cap_is_pending_only_while_asking
    CHECK (pending_cap IS NULL OR state = 'asking');

-- Which cap an answer answered, copied from the row when the answer was written, because the row's
-- pending_cap is cleared by that same write and a busy conductor may be spoken to later.
ALTER TABLE orchestration_messages ADD COLUMN cap_kind TEXT;
ALTER TABLE orchestration_messages ADD CONSTRAINT orchestration_messages_cap_kind_is_known
    CHECK (cap_kind IS NULL OR (kind = 'answer' AND cap_kind IN ('turn_cap', 'call_budget')));
