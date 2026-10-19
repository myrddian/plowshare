-- Call allowances stay distinct from monetary accounting and transport capacity.
-- Operations may be ordinary admissions or closed internal service purposes.
CREATE TABLE operation_inference_budgets (
  operation_id UUID PRIMARY KEY,
  budget_id UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(operation_id,budget_id)
);
CREATE FUNCTION protect_operation_inference_budget() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'operation budget bindings are immutable'; END $$;
CREATE TRIGGER operation_inference_budget_immutable BEFORE UPDATE OR DELETE ON operation_inference_budgets
  FOR EACH ROW EXECUTE FUNCTION protect_operation_inference_budget();
CREATE TABLE inference_admissions (
  id UUID PRIMARY KEY,
  operation_id UUID NOT NULL,
  budget_id UUID NOT NULL,
  ordinal INTEGER NOT NULL CHECK(ordinal > 0),
  call_limit INTEGER CHECK(call_limit > 0 AND ordinal <= call_limit),
  submitted BOOLEAN NOT NULL DEFAULT false,
  accounting_call UUID UNIQUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(budget_id,ordinal),
  FOREIGN KEY(operation_id,budget_id) REFERENCES operation_inference_budgets(operation_id,budget_id)
);
CREATE FUNCTION protect_inference_admission() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP='DELETE' THEN RAISE EXCEPTION 'inference claims are immutable'; END IF;
  IF NEW.id<>OLD.id OR NEW.operation_id<>OLD.operation_id OR NEW.budget_id<>OLD.budget_id
    OR NEW.ordinal<>OLD.ordinal OR NEW.call_limit IS DISTINCT FROM OLD.call_limit
    OR NEW.created_at<>OLD.created_at OR (OLD.submitted AND NOT NEW.submitted)
    OR (OLD.accounting_call IS NOT NULL AND NEW.accounting_call IS DISTINCT FROM OLD.accounting_call)
    OR (NEW.accounting_call IS NOT NULL AND NOT NEW.submitted) THEN
    RAISE EXCEPTION 'inference claim cannot be rebound';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER inference_admission_immutable BEFORE UPDATE OR DELETE ON inference_admissions
  FOR EACH ROW EXECUTE FUNCTION protect_inference_admission();
