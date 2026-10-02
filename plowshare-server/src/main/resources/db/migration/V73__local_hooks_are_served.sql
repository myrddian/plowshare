-- Local hooks are served (implementation rationale §3).
--
-- A person's .plowshare/hooks/, read over their session's file channel when a log opens, stored
-- content-addressed like system_blocks (V32), and named on the log once. No data migration: every
-- existing log has no local hooks, which is what NULL says.

CREATE TABLE local_hook_sets (
    -- 'sha256:' and the hex SHA-256 of `files` as the server serialised it (HookFile.canonical).
    hash       TEXT PRIMARY KEY,
    -- [{name, text}] in file-name order: the set exactly as it was snapshotted.
    files      JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT local_hook_sets_a_hash_is_a_sha256 CHECK (hash ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT local_hook_sets_files_are_a_list CHECK (jsonb_typeof(files) = 'array')
);

-- The set a log was opened with (decision 3). Written once, by an UPDATE guarded on IS NULL,
-- and never changed: every fire in the log uses it, whatever the session does afterwards.
-- Nothing deletes a set a log still names; the foreign key refuses it.
ALTER TABLE conversations ADD COLUMN local_hooks TEXT;
ALTER TABLE conversations ADD CONSTRAINT conversations_local_hooks_are_a_set_this_table_holds
    FOREIGN KEY (local_hooks) REFERENCES local_hook_sets (hash);
