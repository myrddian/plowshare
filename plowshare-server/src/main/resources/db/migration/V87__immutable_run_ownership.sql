-- One immutable execution snapshot for each turn; resumption maps its new turn to the same run.
-- No source foreign keys: retention must not erase accounting identity or ancestor ownership.
CREATE TABLE inference_run_ownership (
    conversation_id TEXT NOT NULL,
    turn_ordinal BIGINT NOT NULL CHECK (turn_ordinal >= 1),
    attribution JSONB NOT NULL,
    PRIMARY KEY (conversation_id, turn_ordinal)
);
