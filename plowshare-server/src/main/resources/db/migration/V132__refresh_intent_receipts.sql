-- One short-lived reconstruction receipt per spent refresh grant. No bearer values.
-- Chain locking, account authority and current-successor checks remain in the owning store.
CREATE TABLE auth_refresh_receipts (
    parent_digest TEXT PRIMARY KEY REFERENCES auth_session_grants(digest) ON DELETE CASCADE,
    intent UUID NOT NULL,
    nonce TEXT NOT NULL CHECK (nonce ~ '^[0-9a-f]{48}$'),
    issued_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    CHECK (expires_at > issued_at AND expires_at <= issued_at + INTERVAL '30 seconds')
);
