-- Retain Personal authority independently of the temporary socket context. Project row identity
-- prevents relocation/recreation from restoring authority. Metadata only: no tasks or credentials.
-- Binding tombstones and receipts survive conversation/project deletion: forgetting history cannot
-- free a used request UUID for execution. Live ownership checks join the current owning records.
CREATE TABLE acting_work_conversations (
    conversation TEXT PRIMARY KEY,
    personal_project_id BIGINT NOT NULL,
    actor TEXT NOT NULL REFERENCES admins(handle),
    subject TEXT NOT NULL REFERENCES admins(handle),
    application TEXT NOT NULL,
    grant_name TEXT NOT NULL,
    proof UUID NOT NULL REFERENCES acting_tokens(id),
    ceiling TEXT NOT NULL CHECK (ceiling IN ('VIEWER','CONTRIBUTOR')),
    FOREIGN KEY(application,grant_name) REFERENCES acting_grants(application,name)
);
CREATE TABLE acting_work_receipts (
    actor TEXT NOT NULL REFERENCES admins(handle),
    request_id UUID NOT NULL,
    fingerprint TEXT NOT NULL CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    conversation TEXT NOT NULL REFERENCES acting_work_conversations(conversation),
    agent TEXT NOT NULL CHECK (length(agent) BETWEEN 1 AND 64),
    state TEXT NOT NULL CHECK (state IN ('ADMITTED','STARTED','FINISHED','UNCERTAIN')),
    job TEXT UNIQUE,
    admitted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(actor,request_id),
    CHECK (state NOT IN ('STARTED','FINISHED') OR job IS NOT NULL)
);
