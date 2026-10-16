-- Declarations and their source head commit together. Withdrawn rows remain as inert references
-- for consent and receipt history; reactivation always uses the newly active source's ceiling.
CREATE TABLE acting_grants (
    application TEXT NOT NULL REFERENCES application_identities(project_name),
    name TEXT NOT NULL CHECK (name ~ '^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$'),
    revision UUID NOT NULL REFERENCES application_releases(revision),
    declared BOOLEAN NOT NULL,
    ceiling TEXT NOT NULL CHECK (ceiling IN ('VIEWER','CONTRIBUTOR')),
    personal BOOLEAN NOT NULL,
    acting_token BOOLEAN NOT NULL,
    PRIMARY KEY(application,name)
);
CREATE TABLE acting_eligible_accounts (
    application TEXT NOT NULL,
    grant_name TEXT NOT NULL,
    account TEXT NOT NULL REFERENCES admins(handle),
    FOREIGN KEY(application,grant_name) REFERENCES acting_grants(application,name),
    PRIMARY KEY(application,grant_name,account)
);
CREATE TABLE acting_eligible_projects (
    application TEXT NOT NULL,
    grant_name TEXT NOT NULL,
    project_id BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    FOREIGN KEY(application,grant_name) REFERENCES acting_grants(application,name),
    PRIMARY KEY(application,grant_name,project_id)
);
CREATE TABLE acting_tokens (
    id UUID PRIMARY KEY,
    application TEXT NOT NULL,
    grant_name TEXT NOT NULL,
    actor TEXT NOT NULL REFERENCES admins(handle) CHECK (actor ~ '^@service/[0-9a-f-]{36}$'),
    subject TEXT NOT NULL REFERENCES admins(handle),
    ceiling TEXT NOT NULL CHECK (ceiling IN ('VIEWER','CONTRIBUTOR')),
    personal BOOLEAN NOT NULL,
    digest TEXT NOT NULL UNIQUE CHECK (digest ~ '^[0-9a-f]{64}$'),
    external_subject TEXT CHECK (length(external_subject) BETWEEN 1 AND 512),
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    FOREIGN KEY(application,grant_name) REFERENCES acting_grants(application,name),
    CHECK (expires_at > created_at)
);
CREATE INDEX acting_tokens_subject ON acting_tokens(subject,created_at DESC,id);
-- Bind scopes to row identities. Deleting/recreating a same-named project cannot restore consent.
CREATE TABLE acting_token_projects (
    token_id UUID NOT NULL REFERENCES acting_tokens(id),
    project_id BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    PRIMARY KEY(token_id,project_id)
);
CREATE TABLE acting_mutation_receipts (
    request_id UUID PRIMARY KEY,
    actor TEXT NOT NULL REFERENCES admins(handle),
    fingerprint TEXT NOT NULL CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    action TEXT NOT NULL CHECK (action IN ('ISSUE','REVOKE','ELIGIBILITY')),
    application TEXT NOT NULL,
    grant_name TEXT NOT NULL,
    token_id UUID REFERENCES acting_tokens(id),
    completed_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY(application,grant_name) REFERENCES acting_grants(application,name),
    CHECK ((action='ELIGIBILITY') = (token_id IS NULL))
);
-- Metadata-only lifecycle audit; credentials, digests and external identity labels are excluded.
CREATE TABLE acting_audit (
    id BIGSERIAL PRIMARY KEY,
    actor TEXT NOT NULL REFERENCES admins(handle),
    action TEXT NOT NULL CHECK (action IN ('ACTIVATE','ISSUE','REVOKE','ELIGIBILITY')),
    application TEXT NOT NULL REFERENCES application_identities(project_name),
    grant_name TEXT,
    token_id UUID REFERENCES acting_tokens(id),
    request_id UUID UNIQUE REFERENCES acting_mutation_receipts(request_id),
    revision UUID REFERENCES application_releases(revision),
    occurred_at TIMESTAMPTZ NOT NULL
);
