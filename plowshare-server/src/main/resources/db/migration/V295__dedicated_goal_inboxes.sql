-- Dedicated goals retain a non-default Messaging instance and its exact conversation.
ALTER TABLE conversation_goals ADD COLUMN instance TEXT UNIQUE REFERENCES message_instances(id);
ALTER TABLE conversation_goals DROP CONSTRAINT conversation_goals_state_check;
ALTER TABLE conversation_goals ADD CONSTRAINT conversation_goals_state_check
    CHECK (state IN ('READY','RUNNING','IDLE','PAUSING','PAUSED','BLOCKED','STOPPING','COMPLETE','CANCELLED'));

CREATE TABLE goal_agent_launch_requests (
    executor TEXT NOT NULL,
    request UUID NOT NULL,
    fingerprint TEXT NOT NULL,
    goal UUID REFERENCES conversation_goals(id) ON DELETE CASCADE,
    PRIMARY KEY (executor, request)
);

CREATE TABLE goal_agent_listeners (
    id UUID PRIMARY KEY,
    goal UUID NOT NULL REFERENCES conversation_goals(id) ON DELETE CASCADE,
    project_id BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    topic TEXT NOT NULL,
    generation UUID NOT NULL,
    failure TEXT CHECK (failure IN ('SOURCE_CHANGED','RETENTION_GAP','UNSUPPORTED_PUBLICATION')),
    UNIQUE (goal, project_id, topic, generation)
);

-- Keep payloads and their originating receipts together before advancing a Relay cursor.
-- Addressed messages remain in Messaging; this inbox retains only the exact message reference.
CREATE TABLE goal_agent_inputs (
    sequence BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    goal UUID NOT NULL REFERENCES conversation_goals(id) ON DELETE CASCADE,
    message TEXT UNIQUE REFERENCES message_records(id) ON DELETE CASCADE,
    listener UUID REFERENCES goal_agent_listeners(id),
    position BIGINT CHECK (position > 0),
    event_id TEXT,
    body TEXT,
    settled BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (listener, position),
    CHECK ((message IS NOT NULL AND listener IS NULL AND position IS NULL AND event_id IS NULL AND body IS NULL)
        OR (message IS NULL AND listener IS NOT NULL AND position IS NOT NULL AND event_id IS NOT NULL))
);
CREATE INDEX goal_agent_inputs_ready ON goal_agent_inputs (goal, (message IS NULL), sequence)
    WHERE NOT settled;
CREATE INDEX goal_agent_inputs_message_settled ON goal_agent_inputs (sequence)
    WHERE settled AND message IS NOT NULL;
