-- pgvector ships `vector` as an extension, not a built-in type, and the
-- pgvector/pgvector image only makes it *available* — it does not install it
-- into the database. Without this line the CREATE TABLE below fails with
-- `ERROR: type "vector" does not exist` at line 8, which reads as a broken
-- image rather than a missing statement. Anchor's V1__schema.sql opens the
-- same way, for the same reason.
CREATE EXTENSION IF NOT EXISTS vector;

-- One row per memory, in every state. Nothing is ever deleted: `superseded`
-- and `invalidated` rows are tombstones, and the reason on a tombstone is what
-- stops a future agent rediscovering a stale fact and writing it back in.
--
-- `project` NULL means the global tier. Global is the absence of a project
-- rather than a project named 'global', so no project can be named into
-- becoming it.
CREATE TABLE memories (
    id              TEXT PRIMARY KEY,
    project         TEXT,
    summary         TEXT        NOT NULL,
    scope           TEXT        NOT NULL,
    body            TEXT        NOT NULL,
    state           TEXT        NOT NULL,
    pinned          BOOLEAN     NOT NULL DEFAULT FALSE,
    uses            INT         NOT NULL DEFAULT 0,
    last_used       TIMESTAMPTZ,
    formed_at       TIMESTAMPTZ NOT NULL,
    formed_by       TEXT        NOT NULL,
    formed_where    TEXT        NOT NULL,
    supersedes      TEXT,
    superseded_by   TEXT,
    invalidated_at  TIMESTAMPTZ,
    invalidated_by  TEXT,
    invalidated_why TEXT,
    embedding       vector(768),

    -- The four states, and nothing else. Excalibur could be handed a memory
    -- file with a nonsense `state:` key, so its store skips unreadable records
    -- with a warning; a row cannot be hand-edited the same way, but a stray
    -- migration or a psql session still can, and a fifth state is not a record
    -- the reader can skip — it is a record that is in neither the search path
    -- nor the retired set, and so silently unreachable from both. Rejected at
    -- the write instead, where whoever wrote it is still standing there.
    CONSTRAINT memories_state_known
        CHECK (state IN ('active', 'cold', 'superseded', 'invalidated')),

    -- Blank is not global, and it is not a project either. `Home.of` already
    -- refuses it, but the empty string is what arrives from an omitted request
    -- field, and a row holding one would belong to a tier nobody can name and
    -- no read can reach.
    CONSTRAINT memories_project_named
        CHECK (project IS NULL OR project <> '')
);
-- 768 matches nomic-embed-text, which is what Anchor already runs on the same
-- box. Changing the embedding model changes this number and invalidates every
-- stored vector, so it is a migration rather than a config change.
--
-- `embedding` is nullable on purpose: a memory is written before it is
-- embedded, and an embedding endpoint being down must lose the vector, never
-- the write.

-- Recall reads one tier at a time and only ever wants live records, so the
-- index leads with the two columns every read filters on.
CREATE INDEX memories_home_state ON memories (project, state);
