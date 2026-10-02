-- Script source/hash remain pinned on orchestrations. JSON state is committed before each command.
CREATE TABLE orchestration_script_steps (
    conversation_id TEXT NOT NULL REFERENCES conversations (id) ON DELETE CASCADE,
    sequence INTEGER NOT NULL CHECK (sequence >= 0),
    source_hash TEXT NOT NULL,
    state JSONB NOT NULL,
    command JSONB NOT NULL,
    started_at TIMESTAMPTZ,
    effective_arguments TEXT,
    raw_result TEXT,
    result TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    executed_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    PRIMARY KEY (conversation_id, sequence),
    CHECK (result IS NULL OR raw_result IS NOT NULL)
);
