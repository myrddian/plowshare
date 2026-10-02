-- Start acknowledgment recovery. The receipt and pinned run are one transaction.
CREATE TABLE orchestration_start_receipts (
    account TEXT NOT NULL REFERENCES admins(handle),
    request_id UUID NOT NULL,
    payload JSONB NOT NULL,
    run_id TEXT NOT NULL UNIQUE REFERENCES orchestrations(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (account, request_id)
);
