-- A file question is a typed, once-only operation, never a synthetic shell command.
ALTER TABLE run_approvals ADD COLUMN file_operation jsonb;
ALTER TABLE run_approvals ADD CONSTRAINT run_approvals_file_shape CHECK (
  file_operation IS NULL OR (
    budget_calls IS NULL AND commands IS NULL AND argv = '[]'::jsonb
    AND prefix IS NULL AND (scope IS NULL OR scope = 'once')
    AND jsonb_typeof(file_operation) = 'object'
  )
);
ALTER TABLE suspended_run_calls ADD COLUMN tool_name text NOT NULL DEFAULT 'run';
ALTER TABLE suspended_run_calls ADD CONSTRAINT suspended_approval_tool CHECK (
  tool_name IN ('run', 'file_read', 'file_edit', 'file_delete', 'file_move')
);

-- Eligibility evidence is distinct from consent. These rows never grant execution themselves.
CREATE TABLE human_approval_history (
  approval text PRIMARY KEY REFERENCES run_approvals(id) ON DELETE CASCADE,
  owner_handle text NOT NULL REFERENCES admins(handle) ON DELETE CASCADE,
  target_digest text NOT NULL CHECK (target_digest ~ '^[0-9a-f]{64}$'),
  approved_at timestamptz NOT NULL
);
CREATE INDEX human_approval_history_target ON human_approval_history (owner_handle, target_digest, approved_at DESC);
