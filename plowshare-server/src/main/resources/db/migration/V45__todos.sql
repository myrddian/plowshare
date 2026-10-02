-- Todos: a list per conversation that an agent keeps and the harness shows back to it.
-- See implementation rationale §3.
--
-- FILED AS V41 AND RENUMBERED V45: master's V41__first_token_time, V42__call_thinking,
-- V43__hook_entries and V44__thinking_is_an_entry merged in first, and V1-V44 are immutable
-- (MigrationsAreImmutableTest).
--
-- `locked` and `stage_id` are written by nothing in this migration's slice. They are here
-- because slice 3 (orchestration stages) is the only writer and adding two columns to a table
-- that already holds rows is a second migration for no gain. An orchestration's stages are its
-- conversation's locked items; everything else a model adds is unlocked.
--
-- No foreign key to conversations: entries and turns are cleared by conversation id in the
-- archive's own retention paths, which name their tables explicitly, and a key here would make
-- this table a silent blocker on every one of them. Retention of todos is recorded as open in
-- implementation rationale

CREATE TABLE todos (
    id            TEXT        PRIMARY KEY,
    conversation  TEXT        NOT NULL,
    parent        TEXT        REFERENCES todos (id),
    position      INT         NOT NULL,
    text          TEXT        NOT NULL,
    status        TEXT        NOT NULL,
    summary       TEXT,
    locked        BOOLEAN     NOT NULL DEFAULT FALSE,
    stage_id      TEXT,
    updated_at    TIMESTAMPTZ NOT NULL,
    CONSTRAINT todos_text_named       CHECK (text <> ''),
    CONSTRAINT todos_status_is_known  CHECK (status IN ('pending', 'in_progress', 'done', 'dropped')),
    CONSTRAINT todos_position_natural CHECK (position >= 0),
    CONSTRAINT todos_a_stage_is_locked CHECK (stage_id IS NULL OR locked)
);

CREATE INDEX todos_by_conversation ON todos (conversation, parent, position);
