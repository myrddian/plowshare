-- Current filesystem observations are separate from immutable information revisions.
-- Source bodies and declarations stay in the document corpus or a run-local cache.
CREATE TABLE code_workspaces (
    id TEXT PRIMARY KEY CHECK (length(id) = 64),
    project_id BIGINT REFERENCES projects(id) ON DELETE CASCADE,
    owner_handle TEXT NOT NULL,
    agent_name TEXT NOT NULL,
    session_id TEXT,
    pattern TEXT NOT NULL,
    generation BIGINT NOT NULL DEFAULT 0,
    state TEXT NOT NULL CHECK (state IN ('checking', 'observed', 'partial', 'dirty', 'unavailable')),
    issues JSONB NOT NULL DEFAULT '[]'::jsonb,
    checked_at TIMESTAMPTZ,
    next_poll TIMESTAMPTZ NOT NULL,
    lease_until TIMESTAMPTZ
);
CREATE INDEX code_workspaces_due ON code_workspaces(next_poll);
CREATE INDEX code_workspaces_project ON code_workspaces(project_id);
CREATE TABLE code_workspace_sources (
    workspace_id TEXT NOT NULL REFERENCES code_workspaces(id) ON DELETE CASCADE,
    source_key TEXT NOT NULL,
    provider TEXT NOT NULL,
    root TEXT NOT NULL,
    path TEXT NOT NULL,
    relative_path TEXT NOT NULL,
    source_hash TEXT NOT NULL CHECK (source_hash ~ '^[a-f0-9]{64}$'),
    source_bytes BIGINT NOT NULL CHECK (source_bytes >= 0),
    language TEXT NOT NULL,
    outline_status TEXT NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (workspace_id, source_key)
);

-- Writers fence polling across concurrent jobs. Crash recovery expires these leases;
-- expiry never retries a command or infers that it completed successfully.
CREATE TABLE code_workspace_mutations (
    token TEXT PRIMARY KEY,
    project_id BIGINT REFERENCES projects(id) ON DELETE CASCADE,
    owner_handle TEXT NOT NULL,
    session_id TEXT,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX code_workspace_mutations_expiry ON code_workspace_mutations(expires_at);
CREATE INDEX code_workspace_mutations_project ON code_workspace_mutations(project_id);
