-- Skills use the existing delegated logs, run budgets, approvals and accounting.
-- This receipt pins instructions and makes repeated invocation IDs non-replaying.
CREATE TABLE skill_executions (
    account_handle TEXT NOT NULL REFERENCES admins(handle),
    invocation UUID NOT NULL,
    payload TEXT NOT NULL,
    parent_conversation TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    conversation TEXT REFERENCES conversations(id) ON DELETE CASCADE,
    executor TEXT NOT NULL,
    skill TEXT NOT NULL,
    source TEXT NOT NULL,
    origin TEXT NOT NULL,
    tier TEXT NOT NULL CHECK (tier IN ('PROJECT', 'SESSION', 'GLOBAL', 'SHIPPED')),
    mode TEXT NOT NULL CHECK (mode IN ('INHERITED', 'SUMMARISED', 'NEW', 'DIRECT')),
    state TEXT NOT NULL DEFAULT 'claimed' CHECK (state IN ('claimed', 'running', 'awaiting', 'finished', 'failed')),
    result TEXT,
    context_snapshot JSONB,
    context_prompt TEXT,
    source_through INTEGER CHECK (source_through >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (account_handle, invocation)
);
CREATE INDEX skill_executions_active ON skill_executions(conversation, created_at)
    WHERE state IN ('running', 'awaiting');
