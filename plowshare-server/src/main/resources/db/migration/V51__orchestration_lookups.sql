-- Two lookups orchestration slices 5 and 6 read on every call: the trap asks which orchestrations
-- a conversation already has live, and orchestration.list reads one account's runs newest first.
-- See implementation rationale

CREATE INDEX orchestrations_live_by_caller_conversation
    ON orchestrations (caller_conversation) WHERE ended_at IS NULL;
CREATE INDEX orchestrations_by_caller_handle
    ON orchestrations (caller_handle, created_at DESC);
