-- Recovery secrets are held only in private client storage. The server retains a hash and the
-- original logical attachment; neither a public session ID nor an account login proves continuity.
CREATE TABLE workspace_recovery_bindings (
    key_hash TEXT PRIMARY KEY CHECK (key_hash ~ '^[a-f0-9]{64}$'),
    session_id TEXT NOT NULL CHECK (length(session_id) BETWEEN 1 AND 1024),
    account TEXT NOT NULL CHECK (length(account) BETWEEN 1 AND 1024),
    machine TEXT NOT NULL CHECK (length(machine) BETWEEN 1 AND 4096),
    root TEXT NOT NULL CHECK (length(root) BETWEEN 1 AND 4096),
    project TEXT NOT NULL CHECK (length(project) BETWEEN 1 AND 4096)
);
