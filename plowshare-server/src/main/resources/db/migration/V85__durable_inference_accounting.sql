-- Accounting owns immutable identity snapshots, never cascading transcript/project references.
CREATE TABLE inference_accounting_journals (
    journal_id UUID PRIMARY KEY,
    projected_sequence BIGINT NOT NULL DEFAULT 0 CHECK (projected_sequence >= 0),
    tracking_started_at TIMESTAMPTZ NOT NULL,
    last_projected_at TIMESTAMPTZ
);

CREATE TABLE inference_accounting_instances (
    instance_id UUID PRIMARY KEY,
    journal_id UUID NOT NULL REFERENCES inference_accounting_journals(journal_id),
    started_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ
);

CREATE TABLE inference_pricing_versions (
    version TEXT PRIMARY KEY CHECK (length(version) = 64),
    billing_route TEXT NOT NULL,
    wire_model TEXT NOT NULL,
    currency TEXT,
    mode TEXT NOT NULL CHECK (mode IN ('TOKEN', 'INCLUDED', 'ZERO_RATE', 'UNPRICED')),
    rate_card JSONB NOT NULL
);

CREATE TABLE inference_calls (
    call_id UUID PRIMARY KEY,
    instance_id UUID NOT NULL REFERENCES inference_accounting_instances(instance_id),
    attribution JSONB NOT NULL,
    account_handle TEXT,
    project_id TEXT,
    scope TEXT NOT NULL CHECK (scope IN ('PROJECT', 'GLOBAL')),
    conversation_id TEXT,
    root_conversation_id TEXT,
    ancestor_conversation_ids TEXT[] NOT NULL,
    run_id TEXT,
    parent_run_id TEXT,
    root_run_id TEXT,
    ancestor_run_ids TEXT[] NOT NULL,
    orchestration_id TEXT,
    root_orchestration_id TEXT,
    ancestor_orchestration_ids TEXT[] NOT NULL,
    agent_name TEXT,
    turn_ordinal BIGINT CHECK (turn_ordinal >= 0),
    step_ordinal BIGINT CHECK (step_ordinal >= 0),
    operation TEXT NOT NULL,
    attribution_status TEXT NOT NULL CHECK (attribution_status IN ('ATTRIBUTED', 'SYSTEM', 'LEGACY_UNATTRIBUTED')),
    specifier TEXT NOT NULL,
    pool TEXT NOT NULL,
    wire_model TEXT NOT NULL,
    model_family TEXT,
    billing_route TEXT NOT NULL,
    price_version TEXT REFERENCES inference_pricing_versions(version),
    lane TEXT NOT NULL CHECK (lane IN ('CHAT', 'EMBEDDING')),
    created_at TIMESTAMPTZ NOT NULL,
    last_event_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ,
    lifecycle TEXT NOT NULL CHECK (lifecycle IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'REFUSED', 'FAILED', 'CANCELLED', 'INTERRUPTED', 'NOT_DISPATCHED')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    last_call_sequence BIGINT NOT NULL CHECK (last_call_sequence >= 1),
    preflight_observation JSONB,
    CHECK ((scope = 'PROJECT' AND project_id IS NOT NULL) OR (scope = 'GLOBAL' AND project_id IS NULL)),
    CHECK (ended_at IS NULL OR ended_at >= created_at),
    CHECK ((lifecycle IN ('QUEUED', 'RUNNING')) = (ended_at IS NULL))
);

CREATE TABLE inference_attempts (
    attempt_id UUID PRIMARY KEY,
    call_id UUID NOT NULL REFERENCES inference_calls(call_id),
    attempt_number INTEGER NOT NULL CHECK (attempt_number >= 1),
    started_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ,
    outcome TEXT NOT NULL CHECK (outcome IN ('RUNNING', 'SUCCEEDED', 'REFUSED', 'FAILED', 'CANCELLED', 'INTERRUPTED')),
    provider_request_id TEXT,
    http_status INTEGER CHECK (http_status BETWEEN 100 AND 599),
    finish_reason TEXT,
    observation JSONB,
    input_tokens BIGINT CHECK (input_tokens >= 0),
    output_tokens BIGINT CHECK (output_tokens >= 0),
    provider_total_tokens BIGINT CHECK (provider_total_tokens >= 0),
    cache_read_tokens BIGINT CHECK (cache_read_tokens >= 0),
    cache_write_tokens BIGINT CHECK (cache_write_tokens >= 0),
    reasoning_tokens BIGINT CHECK (reasoning_tokens >= 0),
    usage_source TEXT NOT NULL DEFAULT 'NONE',
    usage_coverage TEXT NOT NULL DEFAULT 'UNKNOWN',
    normalization_version INTEGER,
    cost_result JSONB,
    cost_kind TEXT NOT NULL DEFAULT 'UNKNOWN',
    cost_amount NUMERIC CHECK (cost_amount >= 0),
    cost_currency TEXT,
    price_version TEXT REFERENCES inference_pricing_versions(version),
    first_output_millis BIGINT CHECK (first_output_millis >= 0),
    duration_millis BIGINT CHECK (duration_millis >= 0),
    UNIQUE (call_id, attempt_number),
    CHECK (ended_at IS NULL OR ended_at >= started_at),
    CHECK ((outcome = 'RUNNING') = (ended_at IS NULL)),
    CHECK (first_output_millis IS NULL OR first_output_millis <= duration_millis)
);

-- Retain dedupe evidence with accounting, including after segment acknowledgement.
CREATE TABLE inference_accounting_events (
    event_id UUID PRIMARY KEY,
    journal_id UUID NOT NULL REFERENCES inference_accounting_journals(journal_id),
    journal_sequence BIGINT NOT NULL CHECK (journal_sequence >= 1),
    call_id UUID NOT NULL REFERENCES inference_calls(call_id),
    call_sequence BIGINT NOT NULL CHECK (call_sequence >= 1),
    event_data JSONB NOT NULL,
    UNIQUE (journal_id, journal_sequence),
    UNIQUE (call_id, call_sequence)
);

CREATE INDEX inference_calls_project_time ON inference_calls(project_id, created_at);
CREATE INDEX inference_calls_account_time ON inference_calls(account_handle, created_at);
CREATE INDEX inference_calls_conversation_time ON inference_calls(root_conversation_id, created_at);
CREATE INDEX inference_calls_run_time ON inference_calls(run_id, created_at);
CREATE INDEX inference_calls_model_time ON inference_calls(wire_model, created_at);
CREATE INDEX inference_calls_route_time ON inference_calls(billing_route, created_at);
CREATE INDEX inference_calls_conversation_ancestry ON inference_calls USING GIN(ancestor_conversation_ids);
CREATE INDEX inference_calls_run_ancestry ON inference_calls USING GIN(ancestor_run_ids);
CREATE INDEX inference_calls_orchestration_ancestry ON inference_calls USING GIN(ancestor_orchestration_ids);
CREATE INDEX inference_calls_instance_open ON inference_calls(instance_id) WHERE ended_at IS NULL;
CREATE INDEX inference_attempts_call ON inference_attempts(call_id);
