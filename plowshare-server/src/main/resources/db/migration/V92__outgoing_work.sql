-- Durable external sends. Claim commits before the adapter performs network I/O.
-- A claimed send with no returned remote identity is never automatically replayed.
CREATE TABLE outgoing_peers (
    account TEXT NOT NULL REFERENCES admins(handle),
    project_key BIGINT NOT NULL, -- 0 denotes global; real ids are positive
    peer TEXT NOT NULL,
    advertised_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(account, project_key, peer)
);
CREATE TABLE outgoing_work (
    id UUID PRIMARY KEY,
    account TEXT NOT NULL REFERENCES admins(handle),
    request_id UUID NOT NULL,
    peer TEXT NOT NULL,
    project_id BIGINT REFERENCES projects(id) ON DELETE CASCADE,
    conversation TEXT REFERENCES conversations(id) ON DELETE SET NULL,
    message JSONB NOT NULL,
    state TEXT NOT NULL CHECK(state IN ('QUEUED','DISPATCHED','WORKING','INPUT_REQUIRED','AUTH_REQUIRED','COMPLETED','FAILED','CANCELED','REJECTED','UNKNOWN')),
    cancel_requested BOOLEAN NOT NULL DEFAULT false,
    worker TEXT,
    lease_until TIMESTAMPTZ,
    remote_task TEXT,
    remote_context TEXT,
    result JSONB,
    error TEXT,
    revision BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(account, request_id)
);
CREATE INDEX outgoing_work_claim ON outgoing_work(account, state, lease_until);
