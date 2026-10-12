-- Existing service tokens remain issued. Reserved Application tokens cannot authenticate
-- or authorize queued work until the explicit credential transaction commits.
ALTER TABLE service_tokens ADD COLUMN credential_issued BOOLEAN NOT NULL DEFAULT TRUE;
CREATE TABLE application_identities (
    project_name TEXT PRIMARY KEY CHECK (length(project_name) BETWEEN 1 AND 256),
    project_id BIGINT UNIQUE REFERENCES projects(id) ON DELETE SET NULL,
    service_handle TEXT NOT NULL UNIQUE REFERENCES admins(handle),
    role TEXT NOT NULL CHECK (role IN ('VIEWER','CONTRIBUTOR')),
    token_id UUID NOT NULL UNIQUE REFERENCES service_tokens(id),
    state TEXT NOT NULL DEFAULT 'RESERVED' CHECK (state IN ('RESERVED','ACTIVE')),
    credential_request_id UUID,
    reserved_by TEXT NOT NULL REFERENCES admins(handle),
    reserved_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (state<>'RESERVED' OR project_id IS NULL)
);
-- A reservation survives source staging failures and project deletion. Neither handle nor
-- principal can be adopted by another Application; recreation cannot silently restore scope.
CREATE TABLE application_token_receipts (
    request_id UUID PRIMARY KEY,
    actor TEXT NOT NULL REFERENCES admins(handle),
    project_name TEXT NOT NULL REFERENCES application_identities(project_name),
    token_id UUID NOT NULL REFERENCES service_tokens(id),
    expires_in_days INT NOT NULL CHECK (expires_in_days BETWEEN 1 AND 365),
    rotate BOOLEAN NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE application_identities ADD CONSTRAINT application_current_credential_receipt
    FOREIGN KEY (credential_request_id) REFERENCES application_token_receipts(request_id)
    DEFERRABLE INITIALLY DEFERRED;
CREATE OR REPLACE FUNCTION account_active(account TEXT) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
    SELECT EXISTS (SELECT 1 FROM admins a WHERE a.handle=account AND a.enabled AND (
        a.account_kind<>'SERVICE_TOKEN' OR EXISTS (
            SELECT 1 FROM service_tokens t JOIN admins owner ON owner.handle=t.owner_handle
            WHERE t.principal_handle=a.handle AND t.credential_issued
                AND t.revoked_at IS NULL AND t.expires_at>now()
                AND owner.enabled AND owner.account_kind='SERVICE'
        )
    ));
$$;
