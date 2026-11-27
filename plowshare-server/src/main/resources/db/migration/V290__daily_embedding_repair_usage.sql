SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '10min';

-- This cohort has a single successful attempt and no user or execution lineage.
-- Keep failures, retries and all scoped detail queries on their authoritative rows.
CREATE FUNCTION inference_repair_rollup_eligible(c inference_calls) RETURNS boolean
LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
SELECT c.operation = 'EMBEDDING_REPAIR' AND c.lane = 'EMBEDDING'
    AND c.attribution_status = 'SYSTEM' AND c.lifecycle = 'SUCCEEDED'
    AND c.attempt_count = 1 AND c.account_handle IS NULL AND c.agent_name IS NULL
    AND c.conversation_id IS NULL AND c.run_id IS NULL AND c.orchestration_id IS NULL
    AND cardinality(c.ancestor_conversation_ids) = 0 AND cardinality(c.ancestor_run_ids) = 0
    AND cardinality(c.ancestor_orchestration_ids) = 0
$$;

CREATE TABLE inference_daily_embedding_repairs (
    journal_id uuid NOT NULL,
    day date NOT NULL,
    project_id text,
    pool text NOT NULL,
    wire_model text NOT NULL,
    billing_route text NOT NULL,
    cost_currency text,
    calls bigint NOT NULL,
    incomplete_attempts bigint NOT NULL,
    unknown_cost_attempts bigint NOT NULL,
    cost_amount numeric,
    input_tokens numeric NOT NULL,
    input_tokens_known bigint NOT NULL,
    output_tokens numeric NOT NULL,
    output_tokens_known bigint NOT NULL,
    provider_total_tokens numeric NOT NULL,
    provider_total_tokens_known bigint NOT NULL,
    cache_read_tokens numeric NOT NULL,
    cache_read_tokens_known bigint NOT NULL,
    cache_write_tokens numeric NOT NULL,
    cache_write_tokens_known bigint NOT NULL,
    reasoning_tokens numeric NOT NULL,
    reasoning_tokens_known bigint NOT NULL,
    UNIQUE NULLS NOT DISTINCT (day, journal_id, project_id, pool, wire_model, billing_route, cost_currency),
    CHECK ((cost_amount IS NULL) = (cost_currency IS NULL))
);

CREATE VIEW inference_daily_embedding_repair_inputs AS
SELECT c.call_id, i.journal_id, (c.created_at AT TIME ZONE 'UTC')::date AS day,
       c.project_id, c.pool, c.wire_model, c.billing_route,
       CASE WHEN a.cost_amount IS NOT NULL THEN a.cost_currency END AS cost_currency,
       (a.input_tokens IS NULL OR a.usage_coverage = 'INVALID')::integer AS incomplete_attempts,
       (a.cost_kind IN ('UNKNOWN', 'PARTIAL'))::integer AS unknown_cost_attempts,
       CASE WHEN a.cost_currency IS NOT NULL THEN a.cost_amount END AS cost_amount,
       a.input_tokens, a.output_tokens, a.provider_total_tokens,
       a.cache_read_tokens, a.cache_write_tokens, a.reasoning_tokens
FROM inference_calls c
JOIN inference_attempts a ON a.call_id = c.call_id AND a.attempt_number = 1
JOIN inference_accounting_instances i ON i.instance_id = c.instance_id
WHERE inference_repair_rollup_eligible(c);

-- Prevent a completion from falling between the backfill snapshot and trigger installation.
LOCK TABLE inference_calls IN SHARE ROW EXCLUSIVE MODE;
INSERT INTO inference_daily_embedding_repairs
SELECT journal_id, day, project_id, pool, wire_model, billing_route, cost_currency,
       count(*), sum(incomplete_attempts), sum(unknown_cost_attempts), sum(cost_amount),
       coalesce(sum(input_tokens), 0), count(input_tokens),
       coalesce(sum(output_tokens), 0), count(output_tokens),
       coalesce(sum(provider_total_tokens), 0), count(provider_total_tokens),
       coalesce(sum(cache_read_tokens), 0), count(cache_read_tokens),
       coalesce(sum(cache_write_tokens), 0), count(cache_write_tokens),
       coalesce(sum(reasoning_tokens), 0), count(reasoning_tokens)
FROM inference_daily_embedding_repair_inputs
GROUP BY journal_id, day, project_id, pool, wire_model, billing_route, cost_currency;

CREATE FUNCTION inference_rollup_completed_repair() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    -- The attempt is terminal before its call closes. Projection serializes by
    -- journal and ignores duplicate receipts, so each call contributes once.
    INSERT INTO inference_daily_embedding_repairs AS daily
    SELECT journal_id, day, project_id, pool, wire_model, billing_route, cost_currency,
           1, incomplete_attempts, unknown_cost_attempts, cost_amount,
           coalesce(input_tokens, 0), (input_tokens IS NOT NULL)::integer,
           coalesce(output_tokens, 0), (output_tokens IS NOT NULL)::integer,
           coalesce(provider_total_tokens, 0), (provider_total_tokens IS NOT NULL)::integer,
           coalesce(cache_read_tokens, 0), (cache_read_tokens IS NOT NULL)::integer,
           coalesce(cache_write_tokens, 0), (cache_write_tokens IS NOT NULL)::integer,
           coalesce(reasoning_tokens, 0), (reasoning_tokens IS NOT NULL)::integer
    FROM inference_daily_embedding_repair_inputs WHERE call_id = NEW.call_id
    ON CONFLICT (day, journal_id, project_id, pool, wire_model, billing_route, cost_currency)
    DO UPDATE SET calls = daily.calls + excluded.calls,
        incomplete_attempts = daily.incomplete_attempts + excluded.incomplete_attempts,
        unknown_cost_attempts = daily.unknown_cost_attempts + excluded.unknown_cost_attempts,
        cost_amount = daily.cost_amount + excluded.cost_amount,
        input_tokens = daily.input_tokens + excluded.input_tokens,
        input_tokens_known = daily.input_tokens_known + excluded.input_tokens_known,
        output_tokens = daily.output_tokens + excluded.output_tokens,
        output_tokens_known = daily.output_tokens_known + excluded.output_tokens_known,
        provider_total_tokens = daily.provider_total_tokens + excluded.provider_total_tokens,
        provider_total_tokens_known = daily.provider_total_tokens_known + excluded.provider_total_tokens_known,
        cache_read_tokens = daily.cache_read_tokens + excluded.cache_read_tokens,
        cache_read_tokens_known = daily.cache_read_tokens_known + excluded.cache_read_tokens_known,
        cache_write_tokens = daily.cache_write_tokens + excluded.cache_write_tokens,
        cache_write_tokens_known = daily.cache_write_tokens_known + excluded.cache_write_tokens_known,
        reasoning_tokens = daily.reasoning_tokens + excluded.reasoning_tokens,
        reasoning_tokens_known = daily.reasoning_tokens_known + excluded.reasoning_tokens_known;
    RETURN NULL;
END $$;

CREATE TRIGGER inference_rollup_completed_repair
AFTER UPDATE OF lifecycle ON inference_calls
FOR EACH ROW WHEN (OLD.lifecycle IS DISTINCT FROM NEW.lifecycle AND inference_repair_rollup_eligible(NEW))
EXECUTE FUNCTION inference_rollup_completed_repair();

CREATE FUNCTION inference_reset_daily_repairs() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    TRUNCATE inference_daily_embedding_repairs;
    RETURN NULL;
END $$;

CREATE TRIGGER inference_reset_daily_repairs AFTER TRUNCATE ON inference_calls
FOR EACH STATEMENT EXECUTE FUNCTION inference_reset_daily_repairs();
