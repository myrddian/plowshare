-- The trajectory explorer: implementation rationale §2.
--
-- Three nullable columns and no data migration. A row written before this file has no outcome,
-- no salients and no opening call; every reader draws that as unknown and never guesses one.
--
-- outcome: the word the runtime already held as `told` when it recorded a tool result
-- (ToolLines: ok, refused, not found, denied, error, asked, timed out, cancelled, ran, exit N, or a
-- delegation's ending). Stored because only the runtime knows the structural ones -- a hook's
-- denial, a refusal before the call, a throw -- and the page read sees a cut excerpt.
--
-- salients: call id -> ToolLines.salient(tool, arguments), on an answer that asked for tools.
-- Computed at record time from the whole arguments, because the page read cuts them in SQL and
-- a cut JSON string does not parse. A column of its own, never a key inside tool_calls, so
-- nothing a model is sent can change.
--
-- opened_by_call: on a delegated child, the id of the agent_run call that opened it -- the
-- one link from a call row to the log it started.
--
-- No existing CHECK is rewritten; three new ones bound the new columns.

ALTER TABLE entries ADD COLUMN outcome TEXT;
ALTER TABLE entries ADD COLUMN salients JSONB;
ALTER TABLE conversations ADD COLUMN opened_by_call TEXT;

ALTER TABLE entries ADD CONSTRAINT entries_only_a_tool_result_has_an_outcome
    CHECK (outcome IS NULL OR kind = 'tool_result');
ALTER TABLE entries ADD CONSTRAINT entries_only_an_answer_names_salients
    CHECK (salients IS NULL OR kind = 'answer');
ALTER TABLE conversations ADD CONSTRAINT conversations_only_a_child_was_opened_by_a_call
    CHECK (opened_by_call IS NULL OR parent_id IS NOT NULL);

CREATE INDEX conversations_opened_by_call ON conversations (parent_id, opened_by_call)
    WHERE opened_by_call IS NOT NULL;

COMMENT ON COLUMN entries.outcome IS
    'A tool result''s outcome in a word, as the runtime told it (ToolLines); NULL before V62.';
COMMENT ON COLUMN entries.salients IS
    'On an answer that asked for tools: call id -> the call''s salient argument; NULL before V62.';
COMMENT ON COLUMN conversations.opened_by_call IS
    'On a delegated child: the id of the agent_run call that opened it; NULL before V62.';
