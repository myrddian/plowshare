-- Native scripted conductors retain an immutable zero-call allowance. The existing
-- nonnegative spending and spent-within-budget constraints still forbid any charge.
-- Ordinary public budget creation remains positive-only at the domain boundary.
ALTER TABLE conversations DROP CONSTRAINT conversations_budget_is_spendable;
ALTER TABLE conversations ADD CONSTRAINT conversations_budget_is_spendable
    CHECK (budget_total >= 0);
