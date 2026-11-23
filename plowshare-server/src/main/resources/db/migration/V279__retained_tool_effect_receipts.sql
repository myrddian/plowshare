-- Retain evidence independently of the result body and the approval claim.
-- Legacy calls have no observation. A claimed call without one is still unsafe to replay.
ALTER TABLE suspended_run_calls
    ADD COLUMN effect_observation TEXT,
    ADD COLUMN effect_request_id TEXT,
    ADD CONSTRAINT suspended_call_effect_observation CHECK (
        (effect_observation IS NULL AND effect_request_id IS NULL)
        OR (effect_observation IS NOT NULL AND state <> 'waiting' AND (
            (effect_observation = 'PREFLIGHT_REFUSED' AND effect_request_id IS NULL)
            OR (effect_observation IN ('FILE_SENT', 'FILE_REPLY_TIMEOUT', 'FILE_EFFECT_CONFIRMED')
                AND tool_name IN ('file_edit', 'file_delete', 'file_move')
                AND effect_request_id IS NOT NULL AND effect_request_id ~ '^[a-zA-Z0-9_-]{1,128}$')
        ))
    );
