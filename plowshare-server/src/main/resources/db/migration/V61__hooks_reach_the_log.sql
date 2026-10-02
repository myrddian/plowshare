-- Hooks reach the log, slice 1 (implementation rationale).
--
-- Three nullable columns on conversations and one CHECK rewritten. No data migration: every
-- existing log has no opening, no recorded owner and is not closed, which is what NULL says.

-- A log's fixed opening (decision 9): what its log.open hooks added, stored once in
-- system_blocks (V32) and sent after the agent's prompt in every request of the log. Written
-- once, by an UPDATE guarded on IS NULL, and never changed: a changed byte forfeits the prefix.
ALTER TABLE conversations ADD COLUMN opening_block TEXT;
ALTER TABLE conversations ADD CONSTRAINT conversations_an_opening_is_a_block_this_table_holds
    FOREIGN KEY (opening_block) REFERENCES system_blocks (hash);

-- The account a hook's { notify } reaches (decision 8): the person for a turn log, the caller's
-- handle for a machine log, a delegated child's parent's. No foreign key: a log is opened by
-- doors that do not all know an admins row, and a notice to an unknown handle fails at the
-- inbox's own key, recorded, rather than failing the open.
ALTER TABLE conversations ADD COLUMN owner_handle TEXT;
ALTER TABLE conversations ADD CONSTRAINT conversations_an_owner_is_named
    CHECK (owner_handle IS NULL OR owner_handle <> '');

-- When log.close fired: the once-only claim, so a turn log unarchived and archived again, or a
-- run ended twice by a race, closes once.
ALTER TABLE conversations ADD COLUMN log_closed_at TIMESTAMPTZ;

-- From V53, the latest definition (V54..V60 do not touch it), with 'hook' appended: a hook's
-- { notify } lands in the owner's inbox under this kind.
ALTER TABLE user_inbox DROP CONSTRAINT user_inbox_kind_is_known;
ALTER TABLE user_inbox ADD CONSTRAINT user_inbox_kind_is_known
    CHECK (kind IN ('run', 'sync.conflict', 'orchestration', 'approval', 'hook'));
