-- Relay is independent of Board transport. Positions are allocated under a topic-row lock,
-- with append and allocation in one transaction; a global sequence cannot provide commit order.
CREATE TABLE relay_topics (
    project_id BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    name TEXT NOT NULL CHECK (length(name) <= 160 AND name ~ '^[a-z][a-z0-9]*([._-][a-z0-9]+)*$'),
    payload_kind TEXT NOT NULL CHECK (payload_kind IN ('EMPTY', 'TEXT', 'SCHEDULE_DUE')),
    retention_seconds BIGINT NOT NULL CHECK (retention_seconds BETWEEN 1 AND 315360000),
    max_records BIGINT CHECK (max_records > 0),
    last_position BIGINT NOT NULL DEFAULT 0 CHECK (last_position >= 0),
    expired_through BIGINT NOT NULL DEFAULT 0 CHECK (expired_through BETWEEN 0 AND last_position),
    PRIMARY KEY (project_id, name),
    CHECK ((name = 'schedule.due') = (payload_kind = 'SCHEDULE_DUE'))
);

CREATE TABLE relay_publications (
    project_id BIGINT NOT NULL,
    topic TEXT NOT NULL,
    position BIGINT NOT NULL CHECK (position > 0),
    event_id TEXT NOT NULL CHECK (length(event_id) BETWEEN 1 AND 256),
    publisher TEXT NOT NULL CHECK (length(publisher) BETWEEN 1 AND 256),
    occurred_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ NOT NULL,
    correlation_id TEXT CHECK (length(correlation_id) BETWEEN 1 AND 256),
    causation_id TEXT CHECK (length(causation_id) BETWEEN 1 AND 256),
    schema_version INTEGER NOT NULL CHECK (schema_version = 1),
    payload JSONB NOT NULL CHECK (jsonb_typeof(payload) = 'object' AND octet_length(payload::text) <= 262144),
    PRIMARY KEY (project_id, topic, position),
    UNIQUE (project_id, topic, event_id),
    FOREIGN KEY (project_id, topic) REFERENCES relay_topics(project_id, name) ON DELETE CASCADE
);

-- Subscriber offsets outlive expired publications. No FK to a publication should pin retention.
CREATE TABLE relay_subscriptions (
    project_id BIGINT NOT NULL,
    topic TEXT NOT NULL,
    subscriber TEXT NOT NULL CHECK (length(subscriber) <= 160 AND subscriber ~ '^[a-z][a-z0-9]*([._-][a-z0-9]+)*$'),
    seen_through BIGINT NOT NULL CHECK (seen_through >= 0),
    seen_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (project_id, topic, subscriber),
    FOREIGN KEY (project_id, topic) REFERENCES relay_topics(project_id, name) ON DELETE CASCADE
);
