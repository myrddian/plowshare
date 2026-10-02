-- How long a model call took to its first thinking or answer delta: the wait a
-- person has before anything moves. Beside took_ms, which is the whole call.
--
-- On the call's own row and nowhere else, so it is invocation's to carry: a row
-- no model call produced has no first delta to have waited for. Nullable within
-- a call too — a completion that only asked for tools may stream nothing, and a
-- row written before this migration was never timed. Absent is not zero.
--
-- No existing CHECK is rewritten. entries_a_model_call_is_recorded_whole (V39)
-- ties the columns that every call has; this one a call may lack, so it is
-- bounded on its own rather than joined to that list.

ALTER TABLE entries ADD COLUMN first_token_ms BIGINT;

ALTER TABLE entries ADD CONSTRAINT entries_a_first_token_belongs_to_a_call
    CHECK (first_token_ms IS NULL OR (invocation IS NOT NULL AND first_token_ms >= 0));
