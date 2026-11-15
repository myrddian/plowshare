-- Budget approval is a one-time additive grant, distinct from command execution consent.
ALTER TABLE run_approvals ADD COLUMN budget_calls INTEGER;
ALTER TABLE run_approvals ADD COLUMN budget_steps INTEGER;
ALTER TABLE run_approvals ADD CONSTRAINT run_approvals_budget_shape CHECK (
  (budget_calls IS NULL AND budget_steps IS NULL) OR
  (budget_calls IS NOT NULL AND budget_steps IS NOT NULL AND
   budget_calls >= 0 AND budget_steps >= 0 AND budget_calls::BIGINT + budget_steps > 0 AND
   argv = '[]'::JSONB AND commands IS NULL AND
   (scope IS NULL OR scope = 'once') AND prefix IS NULL AND handle IS NOT NULL)
);

CREATE TABLE budget_extension_requests (
  approval TEXT PRIMARY KEY REFERENCES run_approvals(id) ON DELETE CASCADE,
  allowance_id UUID NOT NULL REFERENCES execution_allowances(id),
  checkpoint JSONB NOT NULL CHECK (jsonb_typeof(checkpoint) = 'object'),
  acknowledged_at TIMESTAMPTZ,
  recovery TEXT NOT NULL DEFAULT 'pending' CHECK (recovery IN ('pending', 'queued', 'started')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX budget_extension_requests_pending
  ON budget_extension_requests(allowance_id) WHERE acknowledged_at IS NULL;
