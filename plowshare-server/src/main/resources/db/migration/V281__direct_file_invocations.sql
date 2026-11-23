-- A retained invocation may be created by direct admission or by a human approval.
-- Existing result and trace foreign keys follow the identity rename in PostgreSQL.
ALTER TABLE suspended_run_calls RENAME COLUMN approval TO invocation_id;
ALTER TABLE suspended_run_calls DROP CONSTRAINT suspended_run_calls_approval_fkey;
ALTER TABLE suspended_run_calls ADD COLUMN approval TEXT UNIQUE REFERENCES run_approvals(id) ON DELETE CASCADE;
UPDATE suspended_run_calls SET approval = invocation_id;
ALTER TABLE suspended_run_calls ALTER COLUMN side DROP NOT NULL;
ALTER TABLE suspended_run_calls ALTER COLUMN arguments DROP NOT NULL;
ALTER TABLE suspended_run_calls ADD CONSTRAINT tool_invocation_admission CHECK (
    (approval IS NOT NULL AND approval = invocation_id AND side IS NOT NULL AND arguments IS NOT NULL)
    OR (approval IS NULL AND side IS NULL AND arguments IS NULL AND state <> 'waiting' AND NOT released
        AND tool_name IN ('file_edit', 'file_delete', 'file_move'))
);

ALTER TABLE suspended_run_calls DROP CONSTRAINT suspended_call_effect_observation;
ALTER TABLE suspended_run_calls ADD CONSTRAINT tool_invocation_effect_observation CHECK (
    (effect_observation IS NULL AND effect_request_id IS NULL)
    OR (effect_observation IS NOT NULL AND state <> 'waiting' AND (
        (effect_observation = 'PREFLIGHT_REFUSED' AND effect_request_id IS NULL)
        OR (tool_name IN ('file_edit', 'file_delete', 'file_move') AND (
            (effect_observation IN ('FILE_SENT', 'FILE_REPLY_TIMEOUT', 'FILE_EFFECT_CONFIRMED',
                'FILE_EFFECT_VERIFIED', 'FILE_EFFECT_UNCHANGED', 'FILE_REPLY_UNVERIFIED')
                AND effect_request_id IS NOT NULL AND effect_request_id ~ '^[a-zA-Z0-9_-]{1,128}$')
            OR (approval IS NULL AND effect_observation IN ('FILE_DISPATCH_UNCERTAIN',
                'FILE_LOCAL_CHANGED', 'FILE_LOCAL_UNCHANGED', 'FILE_LOCAL_UNVERIFIED')
                AND effect_request_id IS NULL)
        ))
    ))
);
