-- A command a run asked a person about, and what the person said. See
-- implementation rationale §3.
--
-- `conversation` is the root a person can answer in. `asked_in` keeps the exact child that
-- reached the gate, so delegation does not erase where the command came from.

CREATE TABLE run_approvals (
    id            TEXT        PRIMARY KEY,
    project_id    BIGINT      NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    conversation  TEXT        NOT NULL,
    asked_in      TEXT        NOT NULL,
    handle        TEXT,
    agent         TEXT        NOT NULL,
    side          TEXT        NOT NULL,
    argv          JSONB       NOT NULL,
    cwd           TEXT        NOT NULL,
    reason        TEXT,
    state         TEXT        NOT NULL,
    scope         TEXT,
    prefix        JSONB,
    answered_by   TEXT,
    answered_at   TIMESTAMPTZ,
    delivered_at  TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL,
    CONSTRAINT run_approvals_side_is_known CHECK (side IN ('server', 'local')),
    CONSTRAINT run_approvals_state_is_known
        CHECK (state IN ('asked', 'allowed', 'denied', 'used', 'revoked')),
    CONSTRAINT run_approvals_scope_is_known
        CHECK (scope IS NULL OR scope IN ('once', 'conversation', 'project')),
    CONSTRAINT run_approvals_an_answer_has_a_scope_or_is_a_denial
        CHECK ((state = 'asked') = (answered_at IS NULL)
               AND (state IN ('allowed', 'used', 'revoked')) = (scope IS NOT NULL)),
    CONSTRAINT run_approvals_only_a_project_approval_has_a_prefix
        CHECK ((scope = 'project') = (prefix IS NOT NULL))
);

CREATE INDEX run_approvals_by_conversation ON run_approvals (conversation, state);
CREATE INDEX run_approvals_by_project ON run_approvals (project_id, side, state);
CREATE INDEX run_approvals_awaiting_delivery
    ON run_approvals (conversation) WHERE state = 'asked' AND delivered_at IS NULL;
