-- Events, schedules, triggers, firings and the user-inbox: the first thing this
-- server does without being asked. See
-- implementation rationale
--
-- FILED AS V39 AND RENUMBERED V40: master's V39__refusals_and_model_provenance
-- merged in first, and V1-V39 are immutable (MigrationsAreImmutableTest).
--
-- EVERY CHECK BELOW THAT ALREADY EXISTED IS REWRITTEN FROM THE MIGRATION THAT
-- LAST DEFINED IT: conversations_origin_is_known from V29,
-- conversations_an_allowance_is_owned_or_shared from V31 (which added
-- `OR budget_lifted`), entries_kind_is_known from V39 (which added 'refusal'),
-- entries_role_matches_kind from V11 (V39 did not touch it: a refusal takes the
-- CASE's NULL, as its own header says). Rewriting one from the file that
-- explains it rather than the one that last defined it silently drops whatever
-- came between.
--
-- NOT REWRITTEN, DELIBERATELY: entries_only_a_completed_operation_is_timed
-- (last V39) admits took_ms only on answer, tool_result and refusal. A notice
-- is written by the harness and no operation completed to produce it, so it
-- carries no duration and stays outside that list. Nor V39's
-- entries_only_a_model_says_something_by_a_call: no model call writes a notice,
-- so its invocation is NULL, which that CHECK already permits for any kind.

CREATE TABLE schedules (
    name          TEXT        PRIMARY KEY,
    cron          TEXT        NOT NULL,
    zone          TEXT        NOT NULL,
    emits         TEXT        NOT NULL,
    paused        BOOLEAN     NOT NULL DEFAULT FALSE,
    -- The claim column. A tick is claimed by moving this forward with a
    -- compare-and-set; only the server whose UPDATE matched emits the event.
    next_fire_at  TIMESTAMPTZ NOT NULL,
    defined_by    TEXT        NOT NULL REFERENCES admins (handle),
    defined_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT schedules_name_named  CHECK (name <> ''),
    CONSTRAINT schedules_cron_named  CHECK (cron <> ''),
    CONSTRAINT schedules_zone_named  CHECK (zone <> ''),
    CONSTRAINT schedules_emits_named CHECK (emits <> '')
);

CREATE INDEX schedules_due ON schedules (next_fire_at) WHERE NOT paused;

CREATE TABLE triggers (
    name             TEXT        PRIMARY KEY,
    event            TEXT        NOT NULL,
    project          TEXT,
    conversation     TEXT,
    agent            TEXT        NOT NULL,
    -- The instructions. Written by a person with an account; an event's data is
    -- never read as instructions (v1 design, "Deferred").
    task             TEXT        NOT NULL,
    max_model_calls  INT,
    max_turns        INT,
    queue_cap        INT         NOT NULL DEFAULT 1,
    paused           BOOLEAN     NOT NULL DEFAULT FALSE,
    defined_by       TEXT        NOT NULL REFERENCES admins (handle),
    defined_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT triggers_name_named   CHECK (name <> ''),
    CONSTRAINT triggers_event_named  CHECK (event <> ''),
    CONSTRAINT triggers_agent_named  CHECK (agent <> ''),
    CONSTRAINT triggers_task_named   CHECK (task <> ''),
    CONSTRAINT triggers_one_home     CHECK (project IS NULL OR conversation IS NULL),
    CONSTRAINT triggers_queue_cap_is_at_least_one CHECK (queue_cap >= 1),
    CONSTRAINT triggers_limits_are_positive
        CHECK ((max_model_calls IS NULL OR max_model_calls >= 1)
           AND (max_turns IS NULL OR max_turns >= 1))
);

CREATE INDEX triggers_by_event ON triggers (event) WHERE NOT paused;

-- The queue, deliberately. Not the table the concurrency spec refused: that was
-- parallel agent work serialised through the database onto one dispatch thread.
-- This is a record of events that arrived, some of which must wait because a
-- target cannot take two at once. Nothing polls it; it drains on job end and at
-- boot.
CREATE TABLE firings (
    id             TEXT        PRIMARY KEY,
    event          TEXT        NOT NULL,
    data           JSONB       NOT NULL DEFAULT '{}'::jsonb,
    schedule       TEXT,
    fire_at        TIMESTAMPTZ,
    trigger        TEXT,
    -- 'conversation:<id>' or 'trigger:<name>': what may hold one run at a time.
    target         TEXT,
    status         TEXT        NOT NULL,
    superseded_by  TEXT,
    reason         TEXT,
    job_id         TEXT,
    arrived_at     TIMESTAMPTZ NOT NULL,
    started_at     TIMESTAMPTZ,
    finished_at    TIMESTAMPTZ,
    CONSTRAINT firings_status_is_known CHECK (status IN
        ('queued', 'started', 'superseded', 'refused', 'unmatched')),
    CONSTRAINT firings_a_tick_is_both_halves_or_neither
        CHECK ((schedule IS NULL) = (fire_at IS NULL)),
    CONSTRAINT firings_unmatched_has_no_trigger
        CHECK ((status = 'unmatched') = (trigger IS NULL)),
    CONSTRAINT firings_started_names_its_job
        CHECK (status <> 'started' OR job_id IS NOT NULL),
    CONSTRAINT firings_superseded_names_its_successor
        CHECK ((status = 'superseded') = (superseded_by IS NOT NULL))
);

-- One firing per trigger per tick; the second guard against a double tick after
-- the compare-and-set on schedules.next_fire_at.
CREATE UNIQUE INDEX firings_one_per_tick
    ON firings (schedule, fire_at, COALESCE(trigger, ''))
    WHERE schedule IS NOT NULL;
CREATE INDEX firings_waiting ON firings (target, arrived_at) WHERE status = 'queued';
-- One running firing per target, enforced and not merely assumed: Dispatcher.drain reads
-- busy(target), then oldestWaiting, then claimStart as three separate statements, so two
-- overlapping drains (a job ending on one thread, an event arriving on another) can both see
-- the target free and both claim a firing. This index turns the second claim's UPDATE into a
-- unique-violation FiringStore.claimStart catches, rather than a second run silently starting.
CREATE UNIQUE INDEX firings_one_running_per_target
    ON firings (target) WHERE status = 'started' AND finished_at IS NULL;
CREATE INDEX firings_by_arrival ON firings (arrived_at);

-- The first ownership column in this schema, and it is read: inbox.list reads
-- it on every call. v1's warning was against ownership columns nothing reads.
CREATE TABLE user_inbox (
    id            TEXT        PRIMARY KEY,
    handle        TEXT        NOT NULL REFERENCES admins (handle),
    firing        TEXT        REFERENCES firings (id),
    conversation  TEXT        NOT NULL,
    ending        TEXT        NOT NULL,
    answer        TEXT        NOT NULL,
    arrived_at    TIMESTAMPTZ NOT NULL,
    read_at       TIMESTAMPTZ
);

CREATE INDEX user_inbox_unread ON user_inbox (handle, arrived_at) WHERE read_at IS NULL;

-- From V29.
ALTER TABLE conversations DROP CONSTRAINT conversations_origin_is_known;
ALTER TABLE conversations ADD CONSTRAINT conversations_origin_is_known
    CHECK (origin IN ('turn', 'delegation', 'curator', 'submission', 'memory', 'event'));

-- From V31, keeping its `OR budget_lifted`.
ALTER TABLE conversations DROP CONSTRAINT conversations_an_allowance_is_owned_or_shared;
ALTER TABLE conversations ADD CONSTRAINT conversations_an_allowance_is_owned_or_shared
    CHECK ((origin IN ('turn', 'submission', 'memory', 'event'))
           = (budget_total IS NOT NULL OR budget_lifted));

-- From V39, keeping its 'refusal'.
ALTER TABLE entries DROP CONSTRAINT entries_kind_is_known;
ALTER TABLE entries ADD CONSTRAINT entries_kind_is_known CHECK (kind IN (
    'utterance', 'answer', 'tool_result', 'summary',
    'attempt_failed', 'runtime_note', 'plan', 'diagnostic',
    'refusal',
    'notice'
));

-- From V11. A notice is the harness speaking, like a summary, but it projects as
-- a user message rather than a system one: every shipped agent has a non-blank
-- prompt, which already seats one system message at index zero, and
-- ChatRequest refuses a second one anywhere else. Projecting a notice as system
-- would mean folding it into that first message on every turn it arrives --
-- rewriting the very first byte of the prompt, which this extension-only cache
-- exists to avoid. USER, appended after the utterance, is the shape Reminding's
-- own message already takes for the same reason: added, never rewritten, so a
-- turn that logs one sends exactly the prior turn's messages plus its own new
-- ones on every turn after.
ALTER TABLE entries DROP CONSTRAINT entries_role_matches_kind;
ALTER TABLE entries ADD CONSTRAINT entries_role_matches_kind CHECK (
    role IS NOT DISTINCT FROM CASE kind
        WHEN 'utterance'   THEN 'user'
        WHEN 'answer'      THEN 'assistant'
        WHEN 'tool_result' THEN 'tool'
        WHEN 'summary'     THEN 'system'
        WHEN 'notice'      THEN 'user'
    END
);

-- Restated from V39, which last set it, so the column's own description names
-- the kind this migration adds.
COMMENT ON COLUMN entries.kind IS
    'What kind of thing this is. utterance, answer, tool_result and summary '
    'project into a request as user, assistant, tool and system, and notice -- '
    'the harness telling a bot about its speaker''s unread inbox -- as user; '
    'attempt_failed, runtime_note, plan, diagnostic and refusal are recorded and '
    'never reach a model. A kind with no role does not project, so a kind added '
    'without a decision is invisible rather than accidentally visible. A refusal '
    'is a model''s probable refusal that the agent''s fallback was dispatched to '
    'answer in its place.';
