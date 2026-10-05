-- Admissions copy their input before advancing the subscription. No publication FK: topic
-- eviction must not cancel or erase already admitted work. Project/subscription removal is explicit.
CREATE TABLE relay_admissions (
    project_id BIGINT NOT NULL,
    topic TEXT NOT NULL,
    subscriber TEXT NOT NULL,
    publication_position BIGINT NOT NULL CHECK (publication_position > 0),
    event_id TEXT NOT NULL CHECK (length(event_id) BETWEEN 1 AND 256),
    publisher TEXT NOT NULL CHECK (length(publisher) BETWEEN 1 AND 256),
    occurred_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ NOT NULL,
    correlation_id TEXT CHECK (length(correlation_id) BETWEEN 1 AND 256),
    causation_id TEXT CHECK (length(causation_id) BETWEEN 1 AND 256),
    payload_kind TEXT NOT NULL CHECK (payload_kind IN ('EMPTY','TEXT','SCHEDULE_DUE')),
    schema_version INTEGER NOT NULL CHECK (schema_version = 1),
    payload JSONB NOT NULL CHECK (jsonb_typeof(payload) = 'object' AND octet_length(payload::text) <= 262144),
    routing_path TEXT NOT NULL CHECK (length(routing_path) BETWEEN 1 AND 256),
    routing_source TEXT NOT NULL CHECK (octet_length(routing_source) <= 131072),
    routing_hash TEXT NOT NULL CHECK (routing_hash ~ '^[0-9a-f]{64}$'),
    branch_count INTEGER NOT NULL CHECK (branch_count BETWEEN 0 AND 32),
    admitted_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (project_id,topic,subscriber,publication_position),
    FOREIGN KEY (project_id,topic,subscriber)
        REFERENCES relay_subscriptions(project_id,topic,subscriber) ON DELETE CASCADE
);

CREATE TABLE relay_deliveries (
    id UUID PRIMARY KEY,
    project_id BIGINT NOT NULL,
    topic TEXT NOT NULL,
    subscriber TEXT NOT NULL,
    publication_position BIGINT NOT NULL,
    branch_index INTEGER NOT NULL CHECK (branch_index BETWEEN 0 AND 31),
    branch_name TEXT NOT NULL CHECK (length(branch_name) <= 160 AND branch_name ~ '^[a-z][a-z0-9]*([._-][a-z0-9]+)*$'),
    receiver TEXT NOT NULL CHECK (length(receiver) <= 160 AND receiver ~ '^[a-z][a-z0-9]*([._-][a-z0-9]+)*$'),
    handler_path TEXT CHECK (length(handler_path) BETWEEN 1 AND 256),
    handler_source TEXT CHECK (octet_length(handler_source) <= 131072),
    handler_hash TEXT CHECK (handler_hash ~ '^[0-9a-f]{64}$'),
    state TEXT NOT NULL DEFAULT 'READY' CHECK (state IN ('READY','CLAIMED','DISPATCHING','ACCEPTED','FAILED','UNCERTAIN')),
    fence BIGINT NOT NULL DEFAULT 0 CHECK (fence >= 0),
    worker TEXT CHECK (length(worker) BETWEEN 1 AND 256),
    lease_until TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    receipt_namespace TEXT,
    receipt_id TEXT,
    failure_code TEXT,
    FOREIGN KEY (project_id,topic,subscriber,publication_position)
        REFERENCES relay_admissions(project_id,topic,subscriber,publication_position) ON DELETE CASCADE,
    UNIQUE (project_id,topic,subscriber,publication_position,branch_index),
    UNIQUE (project_id,topic,subscriber,publication_position,branch_name),
    CHECK ((handler_path IS NULL AND handler_source IS NULL AND handler_hash IS NULL)
        OR (handler_path IS NOT NULL AND handler_source IS NOT NULL AND handler_hash IS NOT NULL)),
    CHECK ((state IN ('CLAIMED','DISPATCHING') AND worker IS NOT NULL AND fence > 0 AND lease_until IS NOT NULL)
        OR (state NOT IN ('CLAIMED','DISPATCHING') AND lease_until IS NULL)),
    CHECK ((state = 'ACCEPTED' AND receipt_namespace IS NOT NULL AND receipt_id IS NOT NULL)
        OR (state <> 'ACCEPTED' AND receipt_namespace IS NULL AND receipt_id IS NULL)),
    CHECK ((state IN ('FAILED','UNCERTAIN')) = (failure_code IS NOT NULL)),
    CHECK (receipt_namespace IS NULL OR (length(receipt_namespace) <= 160 AND receipt_namespace ~ '^[a-z][a-z0-9]*([._-][a-z0-9]+)*$')),
    CHECK (receipt_id IS NULL OR length(receipt_id) BETWEEN 1 AND 256),
    CHECK (failure_code IS NULL OR (length(failure_code) <= 160 AND failure_code ~ '^[a-z][a-z0-9]*([._-][a-z0-9]+)*$'))
);

CREATE INDEX relay_deliveries_ready ON relay_deliveries(project_id,topic,subscriber,publication_position,branch_index)
    WHERE state = 'READY';
CREATE INDEX relay_deliveries_expiring ON relay_deliveries(lease_until,id)
    WHERE state IN ('CLAIMED','DISPATCHING');
CREATE INDEX relay_admissions_retention ON relay_admissions(admitted_at);
