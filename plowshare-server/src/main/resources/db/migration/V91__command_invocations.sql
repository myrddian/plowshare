-- Harness binding and model dispatch are separate durable events.
CREATE TABLE command_invocations (
    account_handle TEXT NOT NULL REFERENCES admins(handle),
    invocation UUID NOT NULL,
    conversation TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    source_run TEXT NOT NULL,
    caller TEXT NOT NULL,
    command TEXT NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('skill', 'orchestration')),
    name TEXT NOT NULL,
    definition_hash TEXT NOT NULL,
    arguments TEXT NOT NULL,
    mode TEXT,
    state TEXT NOT NULL DEFAULT 'bound' CHECK (state IN ('bound', 'dispatching', 'finished', 'failed')),
    result TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (account_handle, invocation),
    UNIQUE (account_handle, conversation, source_run)
);
CREATE INDEX command_invocations_pending ON command_invocations(conversation, created_at)
    WHERE state = 'bound';
