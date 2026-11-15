-- Application output belongs to its project; handle remains producer provenance.
-- NULL preserves the existing personal Inbox ownership and acknowledgement contract.
ALTER TABLE user_inbox ADD COLUMN application_project_id BIGINT REFERENCES projects(id) ON DELETE CASCADE;
ALTER TABLE user_inbox ADD COLUMN application_attention BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE user_inbox ADD CONSTRAINT application_attention_has_owner
    CHECK (NOT application_attention OR application_project_id IS NOT NULL);
CREATE INDEX user_inbox_application_page
    ON user_inbox(application_project_id, arrived_at DESC, id DESC) INCLUDE(read_at)
    WHERE application_project_id IS NOT NULL AND settled_at IS NULL;

-- Preserve V74's complete set while adding explicit published application attention categories.
ALTER TABLE user_inbox DROP CONSTRAINT user_inbox_kind_is_known;
ALTER TABLE user_inbox ADD CONSTRAINT user_inbox_kind_is_known
    CHECK (kind IN ('run', 'sync.conflict', 'orchestration', 'approval', 'hook', 'board',
                    'application', 'finding', 'failure', 'attention'));

-- Personal page/count scans must not walk a producer's growing application history.
CREATE INDEX user_inbox_personal_page
    ON user_inbox(handle, arrived_at DESC, id DESC) INCLUDE(read_at)
    WHERE application_project_id IS NULL AND settled_at IS NULL;
