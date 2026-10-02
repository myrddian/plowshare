-- Orchestrations: a conductor's own conversation, a turn that ends waiting for an answer, and the
-- record of each run and its questions. See implementation rationale
-- §5 and implementation rationale
--
-- EVERY CHECK BELOW THAT ALREADY EXISTED IS REWRITTEN FROM THE MIGRATION THAT LAST DEFINED IT:
-- conversations_origin_is_known and conversations_an_allowance_is_owned_or_shared from V40
-- (keeping `OR budget_lifted`), turns_ending_is_known from V9, jobs_ending_is_known from V24,
-- user_inbox_kind_is_known from V46.

-- From V40.
ALTER TABLE conversations DROP CONSTRAINT conversations_origin_is_known;
ALTER TABLE conversations ADD CONSTRAINT conversations_origin_is_known
    CHECK (origin IN ('turn', 'delegation', 'curator', 'submission', 'memory', 'event',
                      'orchestration'));

-- From V40, keeping its `OR budget_lifted`. A conductor's conversation owns its allowance: the
-- orchestration's max-model-calls, spent across every turn it takes.
ALTER TABLE conversations DROP CONSTRAINT conversations_an_allowance_is_owned_or_shared;
ALTER TABLE conversations ADD CONSTRAINT conversations_an_allowance_is_owned_or_shared
    CHECK ((origin IN ('turn', 'submission', 'memory', 'event', 'orchestration'))
           = (budget_total IS NOT NULL OR budget_lifted));

-- From V9. AWAITING: the conductor asked a question and its turn ended to wait for the answer.
ALTER TABLE turns DROP CONSTRAINT turns_ending_is_known;
ALTER TABLE turns ADD CONSTRAINT turns_ending_is_known CHECK (ending IN (
    'ANSWERED', 'TURN_CAP', 'CALL_BUDGET', 'CANCELLED', 'STUCK', 'UNAVAILABLE',
    'SUB_AGENT_FAILED', 'SESSION_GONE', 'AWAITING'));

-- From V24.
ALTER TABLE jobs DROP CONSTRAINT jobs_ending_is_known;
ALTER TABLE jobs ADD CONSTRAINT jobs_ending_is_known CHECK (ending IS NULL OR ending IN (
    'ANSWERED', 'TURN_CAP', 'CALL_BUDGET', 'CANCELLED', 'STUCK', 'UNAVAILABLE',
    'SUB_AGENT_FAILED', 'SESSION_GONE', 'AWAITING'));

-- From V46. An orchestration's question or result for a caller with no conversation to hear it in.
ALTER TABLE user_inbox DROP CONSTRAINT user_inbox_kind_is_known;
ALTER TABLE user_inbox ADD CONSTRAINT user_inbox_kind_is_known
    CHECK (kind IN ('run', 'sync.conflict', 'orchestration'));

CREATE TABLE orchestrations (
    id                       TEXT        PRIMARY KEY,
    definition_name          TEXT        NOT NULL,
    tier                     TEXT        NOT NULL,
    definition_hash          TEXT        NOT NULL,
    -- Pinned at start, so an edited definition cannot strand a run: [{"id":..,"may_return_to":[..]}]
    stages                   JSONB       NOT NULL,
    max_returns              INT         NOT NULL,
    returns_used             INT         NOT NULL DEFAULT 0,
    project                  TEXT,
    conductor_conversation   TEXT        NOT NULL UNIQUE REFERENCES conversations (id),
    caller_conversation      TEXT        REFERENCES conversations (id),
    caller_agent             TEXT        NOT NULL,
    caller_handle            TEXT        REFERENCES admins (handle),
    state                    TEXT        NOT NULL,
    result                   TEXT,
    failure                  TEXT,
    restarts                 INT         NOT NULL DEFAULT 0,
    nudges                   INT         NOT NULL DEFAULT 0,
    result_delivered_at      TIMESTAMPTZ,
    created_at               TIMESTAMPTZ NOT NULL,
    ended_at                 TIMESTAMPTZ,
    CONSTRAINT orchestrations_state_is_known CHECK (state IN
        ('running', 'asking', 'finished', 'failed', 'capped', 'cancelled')),
    -- Stores OrchestrationDefinition.Tier's own constant names, as turns.ending stores
    -- Ending's, rather than wire names the way state above stores them like origin does.
    CONSTRAINT orchestrations_tier_is_known CHECK (tier IN ('PROJECT', 'SESSION', 'GLOBAL', 'SHIPPED')),
    CONSTRAINT orchestrations_returns_within_limit CHECK (returns_used >= 0 AND returns_used <= max_returns),
    CONSTRAINT orchestrations_max_returns_natural CHECK (max_returns >= 0),
    CONSTRAINT orchestrations_ended_iff_terminal
        CHECK ((state IN ('finished', 'failed', 'capped', 'cancelled')) = (ended_at IS NOT NULL)),
    CONSTRAINT orchestrations_result_iff_finished CHECK ((state = 'finished') = (result IS NOT NULL)),
    CONSTRAINT orchestrations_failure_only_when_it_stopped
        CHECK (failure IS NULL OR state IN ('failed', 'capped', 'cancelled'))
);

CREATE INDEX orchestrations_live ON orchestrations (state) WHERE ended_at IS NULL;
CREATE INDEX orchestrations_undelivered_results
    ON orchestrations (ended_at) WHERE ended_at IS NOT NULL AND result_delivered_at IS NULL;

CREATE TABLE orchestration_messages (
    id              TEXT        PRIMARY KEY,
    orchestration   TEXT        NOT NULL REFERENCES orchestrations (id),
    kind            TEXT        NOT NULL,
    text            TEXT        NOT NULL,
    author          TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    delivered_at    TIMESTAMPTZ,
    CONSTRAINT orchestration_messages_kind_is_known CHECK (kind IN ('question', 'answer')),
    CONSTRAINT orchestration_messages_text_named CHECK (text <> ''),
    CONSTRAINT orchestration_messages_author_named CHECK (author <> '')
);

CREATE INDEX orchestration_messages_by_run ON orchestration_messages (orchestration, created_at);
CREATE INDEX orchestration_messages_undelivered
    ON orchestration_messages (created_at) WHERE delivered_at IS NULL;
