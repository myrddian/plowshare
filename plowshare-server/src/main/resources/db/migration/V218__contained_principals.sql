-- The ephemeral principal of a contained scope.
--
-- This is deliberately the opposite of ADR 0017's Application-owned service identity, which
-- reserves a handle, retains one token UUID, survives deletion and can be authenticated. A
-- contained principal is an identity for attribution and for narrowing authority across a
-- containment hop, nothing more. It therefore holds NO credential material of any kind: no
-- token id, no handle, no digest, no reference into admins, authority_accounts or service_tokens.
-- Every authentication path resolves a presented secret through one of those tables, so a row
-- that is in none of them cannot be resolved by any of them. The absence is the structure, not a
-- flag a call site must remember to honour: there is no column here that could be set to enable a
-- login, and adding one would be a visible schema change rather than a forgotten check.
--
-- ON DELETE CASCADE from authority_scopes makes the principal die with its scope through the
-- same path that removes the scope, with no second code path to forget. This is safe despite
-- V216's RESTRICT on container: the cascade runs from the scope to its principal, not between
-- scopes.
CREATE TABLE contained_principals (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scope_id UUID NOT NULL UNIQUE REFERENCES authority_scopes(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
