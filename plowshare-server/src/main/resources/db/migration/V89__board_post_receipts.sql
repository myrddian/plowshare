-- A person post and its wake obligations commit with one attributable receipt.
CREATE TABLE board_post_receipts (
    account TEXT NOT NULL REFERENCES admins(handle),
    request_id UUID NOT NULL,
    payload JSONB NOT NULL,
    message_id TEXT NOT NULL UNIQUE REFERENCES board_messages(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (account, request_id)
);
