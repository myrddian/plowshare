-- Existing accounts retain their authority; new SQL-created accounts are regular users.
ALTER TABLE admins ALTER COLUMN server_admin SET DEFAULT FALSE;
ALTER TABLE project_members ADD COLUMN role TEXT NOT NULL DEFAULT 'CONTRIBUTOR'
    CHECK (role IN ('VIEWER', 'CONTRIBUTOR', 'MANAGER'));
-- Existing server administrators keep their effective project management authority.
UPDATE project_members m SET role='MANAGER' FROM admins a WHERE a.handle=m.handle AND a.server_admin;
UPDATE project_members m SET role='MANAGER' FROM projects p WHERE p.id=m.project_id AND p.personal_owner=m.handle;
CREATE TABLE project_access_audit (
    id BIGSERIAL PRIMARY KEY,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    project_id BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    actor TEXT NOT NULL,
    target TEXT NOT NULL,
    action TEXT NOT NULL,
    role TEXT
);
CREATE INDEX project_access_audit_project ON project_access_audit(project_id,id DESC);

-- Read admission stays in SQL before pagination and ancestor checks. Administrator authority
-- applies to ordinary projects; private Personal and client projects retain their boundaries.
CREATE FUNCTION information_project_readable(target BIGINT, account TEXT)
RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
    SELECT EXISTS (
        SELECT 1 FROM projects p JOIN admins a ON a.handle=account AND a.enabled AND NOT a.bootstrap
        WHERE p.id=target AND (
            (p.personal_owner IS NOT NULL AND p.personal_owner=account)
            OR (p.personal_owner IS NULL AND (
                (p.name NOT LIKE 'client:%' AND p.name NOT LIKE 'personal:%' AND a.server_admin)
                OR EXISTS (SELECT 1 FROM project_members m WHERE m.project_id=p.id AND m.handle=account)
            ))
        )
    );
$$;

CREATE OR REPLACE FUNCTION information_readable(target UUID, account TEXT, selected_scope TEXT,
    selected_project TEXT, include_shared BOOLEAN) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
    WITH RECURSIVE inputs(id) AS (
        SELECT target UNION SELECT i.input_revision FROM information_inputs i JOIN inputs ON i.derived_revision=inputs.id
    )
    SELECT EXISTS (SELECT 1 FROM admins a WHERE a.handle=account AND a.enabled AND NOT a.bootstrap) AND coalesce(bool_and(
        (p.document_id IS NOT NULL OR (p.document_id IS NULL AND q.owner_handle=account AND q.namespace<>'legacy'
            AND ((q.project_id IS NULL AND selected_scope='personal') OR (selected_scope='project' AND EXISTS(
                SELECT 1 FROM projects pr
                WHERE pr.id=q.project_id AND pr.name=selected_project AND information_project_readable(pr.id,account))))))
        AND coalesce(r.availability='active',true)
        AND (
            ((selected_scope='shared' OR include_shared) AND p.visibility='shared')
            OR (selected_scope='personal' AND ((p.visibility='personal' AND p.owner_handle=account) OR (p.document_id IS NULL AND q.owner_handle=account AND q.project_id IS NULL AND q.namespace<>'legacy')))
            OR (selected_scope='project' AND (
                ((p.visibility='project' OR (p.document_id IS NULL AND q.owner_handle=account AND q.namespace<>'legacy')) AND EXISTS(SELECT 1 FROM projects pr
                    WHERE pr.id=coalesce(p.project_id,q.project_id) AND pr.name=selected_project AND information_project_readable(pr.id,account)))
                OR (p.visibility='personal' AND p.owner_handle=account AND EXISTS(SELECT 1 FROM information_links l
                    JOIN projects pr ON pr.id=l.project_id
                    WHERE l.revision_id=inputs.id AND pr.name=selected_project AND information_project_readable(pr.id,account)))
            ))
        )
    ),false)
    FROM inputs LEFT JOIN information_document_policies p ON p.document_id=inputs.id
    LEFT JOIN information_revisions r ON r.id=inputs.id
    LEFT JOIN information_resources q ON q.id=r.resource_id;
$$;
