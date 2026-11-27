-- Older handoffs did not retain an authenticated input speaker. Leave their routes absent.
ALTER TABLE skill_executions
    ADD COLUMN handoff_human BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN handoff_message TEXT,
    ADD CONSTRAINT skill_handoff_return_source CHECK (
        (NOT handoff_human AND handoff_message IS NULL)
        OR (handoff_operation IS NOT NULL AND NOT (handoff_human AND handoff_message IS NOT NULL)));

CREATE TABLE goal_return_routes (
    goal UUID PRIMARY KEY REFERENCES conversation_goals(id) ON DELETE CASCADE,
    audience TEXT NOT NULL CHECK (audience IN ('HUMAN','BOT')),
    launch_message TEXT UNIQUE,
    caller_instance TEXT,
    goal_instance TEXT,
    CHECK ((audience = 'HUMAN' AND num_nonnulls(launch_message,caller_instance,goal_instance) = 0)
        OR (audience = 'BOT' AND num_nonnulls(launch_message,caller_instance,goal_instance) = 3
            AND caller_instance <> goal_instance))
);

CREATE TABLE goal_return_intents (
    id UUID PRIMARY KEY,
    goal UUID NOT NULL REFERENCES goal_return_routes(goal) ON DELETE CASCADE,
    kind TEXT NOT NULL CHECK (kind IN ('RESULT','QUESTION','BLOCKED','CANCELLED')),
    goal_version INTEGER NOT NULL CHECK (goal_version > 0),
    body TEXT NOT NULL CHECK (length(body) BETWEEN 1 AND 4000),
    delivery TEXT NOT NULL DEFAULT 'PENDING' CHECK (delivery IN ('PENDING','DELIVERED','WITHHELD')),
    receipt TEXT UNIQUE,
    answer_message TEXT UNIQUE,
    cleaned_at TIMESTAMPTZ,
    CHECK ((delivery = 'DELIVERED') = (receipt IS NOT NULL)),
    CHECK (answer_message IS NULL OR (kind = 'QUESTION' AND delivery = 'DELIVERED')),
    CHECK (cleaned_at IS NULL OR (kind = 'QUESTION' AND delivery = 'DELIVERED')),
    UNIQUE (id, goal)
);

CREATE INDEX goal_return_pending ON goal_return_intents(id) WHERE delivery = 'PENDING';
CREATE INDEX goal_question_cleanup ON goal_return_intents(id)
    WHERE kind = 'QUESTION' AND delivery = 'DELIVERED' AND cleaned_at IS NULL;

CREATE TABLE goal_return_answers (
    question UUID PRIMARY KEY,
    goal UUID NOT NULL,
    request_id UUID NOT NULL,
    body TEXT NOT NULL CHECK (length(body) BETWEEN 1 AND 16000),
    FOREIGN KEY (question,goal) REFERENCES goal_return_intents(id,goal) ON DELETE CASCADE,
    UNIQUE (goal,request_id)
);

ALTER TABLE goal_agent_waits
    DROP CONSTRAINT goal_agent_waits_kind_check,
    DROP CONSTRAINT goal_agent_waits_check,
    ADD CONSTRAINT goal_agent_waits_kind_check CHECK (kind IN ('APPROVAL','ORCHESTRATION','QUESTION')),
    ADD CONSTRAINT goal_agent_wait_dependency_shape CHECK (
        (kind = 'APPROVAL' AND dependency IS NOT NULL AND request_id IS NULL
            AND request_hash IS NULL AND definition IS NULL AND owner_ended_at IS NULL)
        OR (kind = 'ORCHESTRATION' AND request_id IS NOT NULL AND request_hash IS NOT NULL
            AND definition IS NOT NULL)
        OR (kind = 'QUESTION' AND request_id IS NOT NULL AND request_hash IS NULL
            AND definition IS NULL AND owner_ended_at IS NULL));

-- Goal notifications are account-owned Inbox references to the authorized conversation.
ALTER TABLE user_inbox DROP CONSTRAINT user_inbox_kind_is_known;
ALTER TABLE user_inbox ADD CONSTRAINT user_inbox_kind_is_known
    CHECK (kind IN ('run','sync.conflict','orchestration','approval','hook','board',
        'application','finding','failure','attention','goal.result','goal.question',
        'goal.blocked','goal.cancelled'));
