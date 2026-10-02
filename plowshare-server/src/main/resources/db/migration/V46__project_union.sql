-- A project rooted on a client may also be a union: its files are mirrored into
-- a git hub on this server so agents can keep working while every client is off.
-- Spec: implementation rationale
--
-- union_since is written only after the first push has landed, so a NULL is
-- "not a union" and never "a union half set up". Only a client-rooted row can
-- be one: a server-rooted project already lives here.

ALTER TABLE projects ADD COLUMN union_since TIMESTAMPTZ;

ALTER TABLE projects ADD CONSTRAINT projects_a_union_is_rooted_on_a_client
    CHECK (union_since IS NULL OR machine IS NOT NULL);

-- Hidden paths a union may sync. Stored here, not on a client, so every machine
-- joining the hub applies the same allowlist; the hub re-checks it on push.
ALTER TABLE projects ADD COLUMN sync_hidden TEXT[] NOT NULL DEFAULT '{}';

CREATE TABLE union_conflicts (
    project_id    BIGINT      NOT NULL,
    n             INT         NOT NULL,
    path          TEXT        NOT NULL,
    base_blob     TEXT,
    ours_blob     TEXT,
    theirs_blob   TEXT,
    theirs_author TEXT        NOT NULL,
    run_id        TEXT,
    opened_at     TIMESTAMPTZ NOT NULL,
    resolved_at   TIMESTAMPTZ,
    resolution    TEXT,
    PRIMARY KEY (project_id, n),
    CONSTRAINT union_conflicts_a_resolution_is_one_of_three
        CHECK (resolution IS NULL OR resolution IN ('mine', 'theirs', 'merged')),
    CONSTRAINT union_conflicts_a_resolution_has_a_time
        CHECK ((resolution IS NULL) = (resolved_at IS NULL))
);

CREATE INDEX union_conflicts_open ON union_conflicts (project_id) WHERE resolved_at IS NULL;

-- The user inbox is per account (handle), and until now every row was a run's
-- result and so named a conversation and an ending. A sync conflict is a notice
-- to the same account that no conversation produced.
ALTER TABLE user_inbox ADD COLUMN kind TEXT NOT NULL DEFAULT 'run';
ALTER TABLE user_inbox ADD CONSTRAINT user_inbox_kind_is_known
    CHECK (kind IN ('run', 'sync.conflict'));
ALTER TABLE user_inbox ALTER COLUMN conversation DROP NOT NULL;
ALTER TABLE user_inbox ALTER COLUMN ending DROP NOT NULL;
ALTER TABLE user_inbox ADD CONSTRAINT user_inbox_a_run_names_where_it_ended
    CHECK (kind <> 'run' OR (conversation IS NOT NULL AND ending IS NOT NULL));
