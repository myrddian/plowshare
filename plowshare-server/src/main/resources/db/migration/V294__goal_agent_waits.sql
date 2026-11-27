-- Retain the exact Goal dependency through owner settlement, continuation and restart.
ALTER TABLE conversation_goals DROP CONSTRAINT conversation_goals_state_check;
ALTER TABLE conversation_goals ADD CONSTRAINT conversation_goals_state_check
    CHECK (state IN ('READY','RUNNING','IDLE','WAITING','PAUSING','PAUSED','BLOCKED','STOPPING','COMPLETE','CANCELLED'));

CREATE TABLE goal_agent_waits (
    id UUID PRIMARY KEY,
    goal UUID NOT NULL REFERENCES conversation_goals(id) ON DELETE CASCADE,
    cycle UUID NOT NULL,
    origin_job TEXT NOT NULL,
    input_job TEXT NOT NULL,
    source_schedule TEXT,
    source_firing TEXT,
    source_generation UUID,
    source_fire_at TIMESTAMPTZ,
    turn_ordinal INTEGER NOT NULL CHECK (turn_ordinal > 0),
    answer_ordinal INTEGER NOT NULL CHECK (answer_ordinal > 0),
    call_id TEXT NOT NULL CHECK (length(call_id) BETWEEN 1 AND 1024),
    kind TEXT NOT NULL CHECK (kind IN ('APPROVAL', 'ORCHESTRATION')),
    dependency TEXT,
    request_id UUID,
    request_hash TEXT,
    definition TEXT,
    owner_ended_at TIMESTAMPTZ,
    state TEXT NOT NULL CHECK (state IN ('PARKING','WAITING','CLAIMED','SETTLED','ABANDONED')),
    continuation_job TEXT,
    cleanup_at TIMESTAMPTZ,
    UNIQUE (goal, origin_job, answer_ordinal, call_id),
    CHECK ((kind = 'APPROVAL' AND dependency IS NOT NULL AND request_id IS NULL
            AND request_hash IS NULL AND definition IS NULL AND owner_ended_at IS NULL)
        OR (kind = 'ORCHESTRATION' AND request_id IS NOT NULL AND request_hash IS NOT NULL
            AND definition IS NOT NULL)),
    CHECK (state NOT IN ('CLAIMED', 'SETTLED') OR continuation_job IS NOT NULL),
    CHECK (num_nonnulls(source_schedule,source_firing,source_generation,source_fire_at) IN (0,4)),
    CHECK (cleanup_at IS NULL OR state = 'ABANDONED')
);

CREATE UNIQUE INDEX goal_agent_wait_open ON goal_agent_waits(goal)
    WHERE state IN ('PARKING','WAITING','CLAIMED');
CREATE UNIQUE INDEX goal_agent_wait_approval ON goal_agent_waits(dependency) WHERE kind = 'APPROVAL';
CREATE UNIQUE INDEX goal_agent_wait_request ON goal_agent_waits(request_id) WHERE request_id IS NOT NULL;
CREATE INDEX goal_agent_wait_cleanup ON goal_agent_waits(id)
    WHERE state = 'ABANDONED' AND cleanup_at IS NULL;

-- The journal retains its effect evidence when its Goal owner abandons future continuation.
ALTER TABLE suspended_run_calls ADD COLUMN owner_retired_at TIMESTAMPTZ;
