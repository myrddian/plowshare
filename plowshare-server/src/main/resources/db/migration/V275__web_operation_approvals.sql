ALTER TABLE run_approvals ADD COLUMN web_operation jsonb;
ALTER TABLE run_approvals ADD CONSTRAINT run_approvals_web_shape CHECK (
  web_operation IS NULL OR (
    budget_calls IS NULL AND file_operation IS NULL AND commands IS NULL
    AND argv = '[]'::jsonb AND prefix IS NULL AND (scope IS NULL OR scope = 'once')
    AND jsonb_typeof(web_operation) = 'object'
  )
);

ALTER TABLE suspended_run_calls DROP CONSTRAINT suspended_approval_tool;
ALTER TABLE suspended_run_calls ADD CONSTRAINT suspended_approval_tool CHECK (
  tool_name IN ('run', 'file_read', 'file_edit', 'file_delete', 'file_move', 'web_search', 'web_fetch')
);
