-- Account login sessions survive server restarts. Only token digests are stored.
-- Bootstrap tokens, operator tokens and single-use WS tickets remain ephemeral.
CREATE TABLE auth_session_chains (
    id UUID PRIMARY KEY,
    handle TEXT NOT NULL REFERENCES admins(handle) ON DELETE CASCADE,
    restricted BOOLEAN NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE auth_session_grants (
    digest TEXT PRIMARY KEY,
    chain_id UUID NOT NULL REFERENCES auth_session_chains(id) ON DELETE CASCADE,
    kind TEXT NOT NULL CHECK (kind IN ('access', 'refresh')),
    spent BOOLEAN NOT NULL DEFAULT FALSE,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX auth_session_grants_chain ON auth_session_grants(chain_id);
CREATE INDEX auth_session_chains_expiry ON auth_session_chains(expires_at);
