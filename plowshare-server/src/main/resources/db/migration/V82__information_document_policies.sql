-- Additive groundwork. Adapters are cut over together in the information plan;
-- this migration does not claim that the existing unscoped readers are secured.
-- Attribution (ingested_by) is never interpreted as authenticated ownership.
CREATE TABLE information_document_policies (
    document_id UUID PRIMARY KEY REFERENCES documents (id) ON DELETE CASCADE,
    owner_handle TEXT REFERENCES admins (handle),
    visibility TEXT NOT NULL DEFAULT 'quarantined',
    project_id BIGINT REFERENCES projects (id),
    assigned_at TIMESTAMPTZ,
    CONSTRAINT information_document_policy_shape CHECK (
        (visibility = 'quarantined' AND owner_handle IS NULL
            AND project_id IS NULL AND assigned_at IS NULL)
        OR (visibility IN ('personal', 'shared') AND owner_handle IS NOT NULL
            AND project_id IS NULL AND assigned_at IS NOT NULL)
        OR (visibility = 'project' AND owner_handle IS NOT NULL
            AND project_id IS NOT NULL AND assigned_at IS NOT NULL)
    )
);

INSERT INTO information_document_policies (document_id)
SELECT id FROM documents;

CREATE INDEX information_documents_by_owner
    ON information_document_policies (owner_handle, visibility);
CREATE INDEX information_documents_by_project
    ON information_document_policies (project_id, visibility);

-- Kept independently of content so future cleanup can retain a tombstone/audit.
CREATE TABLE information_policy_assignments (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL,
    actor_handle TEXT NOT NULL REFERENCES admins (handle),
    owner_handle TEXT NOT NULL REFERENCES admins (handle),
    visibility TEXT NOT NULL CHECK (visibility IN ('personal', 'project', 'shared')),
    project_name TEXT,
    reason TEXT NOT NULL CHECK (btrim(reason) <> ''),
    assigned_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT information_assignment_project CHECK (
        (visibility = 'project' AND project_name IS NOT NULL AND btrim(project_name) <> '')
        OR (visibility <> 'project' AND project_name IS NULL)
    )
);
