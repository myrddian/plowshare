-- An owning record outlives Relay retention. Absence of a broker event never permits replay.
CREATE TABLE relay_tool_invocations (
    id UUID PRIMARY KEY,
    project_id BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    account TEXT NOT NULL CHECK (length(account) BETWEEN 1 AND 256),
    run_id TEXT NOT NULL CHECK (length(run_id) BETWEEN 1 AND 256),
    call_id TEXT NOT NULL CHECK (length(call_id) BETWEEN 1 AND 256),
    fingerprint TEXT NOT NULL CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    binding TEXT NOT NULL CHECK (length(binding) BETWEEN 1 AND 65536),
    request TEXT NOT NULL CHECK (length(request) BETWEEN 1 AND 65536),
    occurred_at TIMESTAMPTZ NOT NULL,
    causation JSONB NOT NULL CHECK (relay_valid_causation(causation)),
    result TEXT CHECK (length(result) BETWEEN 1 AND 32768),
    completed_at TIMESTAMPTZ,
    CHECK ((result IS NULL) = (completed_at IS NULL))
);
CREATE INDEX relay_tool_invocations_owner ON relay_tool_invocations(project_id, account, run_id);
