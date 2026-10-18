-- A consent decision resumes one retained tool call, not another model request. Marking started
-- precedes dispatch: after a crash, an uncertain external effect is reported, never replayed.
ALTER TABLE run_approvals ADD COLUMN denial_reason TEXT;
CREATE TABLE suspended_run_calls (
    sequence BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
    approval TEXT PRIMARY KEY REFERENCES run_approvals(id) ON DELETE CASCADE,
    conversation TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    session_id TEXT,
    owner_handle TEXT,
    side TEXT NOT NULL CHECK (side IN ('local', 'server')),
    call_id TEXT NOT NULL,
    arguments TEXT NOT NULL,
    state TEXT NOT NULL DEFAULT 'waiting' CHECK (state IN ('waiting', 'started', 'completed')),
    released BOOLEAN NOT NULL DEFAULT FALSE,
    result TEXT,
    acknowledged_at TIMESTAMPTZ,
    CHECK ((state = 'completed') = (result IS NOT NULL)),
    CHECK (acknowledged_at IS NULL OR state = 'completed'),
    CHECK (length(call_id) > 0 AND length(arguments) > 0)
);
CREATE INDEX suspended_run_calls_pending ON suspended_run_calls(conversation, sequence)
    WHERE acknowledged_at IS NULL;
