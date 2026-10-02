-- What a model streamed as reasoning during a call, kept as a fact about the
-- call. Beside first_token_ms, on the call's own row.
--
-- NEVER PART OF THE CONVERSATION. EntryStore reads a call's provenance through
-- its own query and never through the columns a projection reads, so nothing
-- written here is sent to a model on a later turn. It is recorded because an
-- endpoint may stream reasoning and count it as none (reasoning_tokens: 0 beside
-- a model that visibly thought), and what the model actually thought is then
-- the only record there is.
--
-- Nullable: a call that streamed no reasoning has none, and a row written before
-- this migration was never given any. Not rewritten: no existing CHECK.

ALTER TABLE entries ADD COLUMN thinking TEXT;

ALTER TABLE entries ADD CONSTRAINT entries_thinking_belongs_to_a_call
    CHECK (thinking IS NULL OR invocation IS NOT NULL);
