-- Organization settings are shared. Upstream grants belong to exact human/service principals.
-- Revisions fence pending authorization and rotation; no executor secret-release API is implied.
CREATE TABLE provider_connections (
    id UUID PRIMARY KEY,
    project_id BIGINT REFERENCES projects(id) ON DELETE SET NULL,
    revision UUID NOT NULL,
    enabled BOOLEAN NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    definition JSONB NOT NULL CHECK (jsonb_typeof(definition)='object'),
    CHECK (NOT deleted OR NOT enabled)
);
CREATE INDEX provider_connections_project ON provider_connections(project_id,id);
CREATE TABLE provider_credentials (
    connection_id UUID NOT NULL REFERENCES provider_connections(id),
    subject TEXT NOT NULL REFERENCES admins(handle),
    generation UUID NOT NULL,
    connection_revision UUID NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('PENDING','READY','REFRESHING','UNCERTAIN','REVOKED')),
    expires_at TIMESTAMPTZ,
    key_id TEXT CHECK (length(key_id) BETWEEN 1 AND 64),
    nonce TEXT,
    ciphertext TEXT,
    attempt UUID,
    state_hash TEXT UNIQUE,
    initiator TEXT REFERENCES admins(handle),
    intent_expires_at TIMESTAMPTZ,
    PRIMARY KEY(connection_id,subject),
    CHECK ((state IN ('PENDING','REFRESHING')) = (attempt IS NOT NULL)),
    CHECK ((state IN ('PENDING','REFRESHING')) = (intent_expires_at IS NOT NULL)),
    CHECK ((state IN ('PENDING','REFRESHING')) = (initiator IS NOT NULL)),
    CHECK (state_hash IS NULL OR state_hash ~ '^[0-9a-f]{64}$'),
    CHECK (state='READY' OR expires_at IS NULL),
    CHECK ((state='PENDING') = (state_hash IS NOT NULL)),
    CHECK ((state IN ('UNCERTAIN','REVOKED')) = (ciphertext IS NULL)),
    CHECK ((ciphertext IS NULL) = (key_id IS NULL)),
    CHECK ((ciphertext IS NULL) = (nonce IS NULL)),
    CHECK (nonce IS NULL OR length(nonce)=16),
    CHECK (ciphertext IS NULL OR length(ciphertext) BETWEEN 24 AND 131072)
);
CREATE TABLE provider_credential_receipts (
    request_id UUID PRIMARY KEY,
    actor TEXT NOT NULL REFERENCES admins(handle),
    project_name TEXT NOT NULL,
    receipt JSONB NOT NULL CHECK (jsonb_typeof(receipt)='object'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Project deletion revokes material in the same transaction, while retaining connection UUID
-- tombstones. Recreating a project name cannot adopt old grants or pending authorizations.
CREATE FUNCTION revoke_deleted_project_provider_grants() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    UPDATE provider_connections SET revision=gen_random_uuid(),enabled=FALSE,deleted=TRUE
        WHERE project_id=OLD.id;
    UPDATE provider_credentials g SET generation=gen_random_uuid(),connection_revision=c.revision,
        state='REVOKED',expires_at=NULL,key_id=NULL,nonce=NULL,ciphertext=NULL,attempt=NULL,
        state_hash=NULL,initiator=NULL,intent_expires_at=NULL
        FROM provider_connections c WHERE g.connection_id=c.id AND c.project_id=OLD.id;
    RETURN OLD;
END;
$$;
CREATE TRIGGER project_provider_grant_revocation BEFORE DELETE ON projects
    FOR EACH ROW EXECUTE FUNCTION revoke_deleted_project_provider_grants();
