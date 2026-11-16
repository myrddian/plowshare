CREATE TABLE workspace_pauses (
    job_id TEXT PRIMARY KEY REFERENCES jobs(id) ON DELETE CASCADE,
    root TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    snapshot JSONB NOT NULL CHECK (jsonb_typeof(snapshot) = 'object'),
    owner TEXT NOT NULL CHECK (length(owner) BETWEEN 1 AND 1024),
    lease_until TIMESTAMPTZ NOT NULL,
    generation BIGINT NOT NULL DEFAULT 1 CHECK (generation > 0),
    state TEXT NOT NULL CHECK (state IN ('paused', 'claimed', 'started', 'blocked', 'done', 'cancelled')),
    reason TEXT CHECK (length(reason) <= 4000)
);
CREATE UNIQUE INDEX workspace_pauses_active_root ON workspace_pauses(root) WHERE state NOT IN ('done','cancelled');
