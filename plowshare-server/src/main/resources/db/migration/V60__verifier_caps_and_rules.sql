-- The verifier, caps the person controls, and rules the harness enforces
-- (implementation rationale).
--
-- orchestrations.artifacts_dir: the directory a run's first message named, stored so the fence on
-- a conductor's file_edit (§3 rule 4), the hand-off note (rule 6) and the acceptance section's
-- spec.md (§1b) all read the one path the conductor was told. Null for a run with none.
-- orchestrations.phase_todo: the parent's todo item a phase run was started under (rule 1).
-- orchestrations.cap_continues: caps this run passed without asking (§2 auto-continue).
ALTER TABLE orchestrations ADD COLUMN artifacts_dir TEXT;
ALTER TABLE orchestrations ADD COLUMN phase_todo TEXT;
ALTER TABLE orchestrations ADD COLUMN cap_continues INT NOT NULL DEFAULT 0;
ALTER TABLE orchestrations ADD CONSTRAINT orchestrations_cap_continues_counts
    CHECK (cap_continues >= 0);

-- A run's acceptance commands (§1b), as registered from its spec.md: one row per command, in the
-- section's order, each with the consent it runs under — OrchestrationChecks' shape, per command.
CREATE TABLE orchestration_acceptance (
    orchestration TEXT        NOT NULL,
    position      INT         NOT NULL,
    line          TEXT        NOT NULL,
    argv          JSONB       NOT NULL,
    stdin         TEXT,
    exit_code     INT         NOT NULL,
    expect        TEXT,
    side          TEXT        NOT NULL,
    cwd           TEXT        NOT NULL,
    consent       TEXT        NOT NULL,
    approval      TEXT,
    set_at        TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (orchestration, position),
    CONSTRAINT orchestration_acceptance_consent_is_known CHECK (consent IN ('open', 'approval')),
    CONSTRAINT orchestration_acceptance_approval_when_asked
        CHECK ((consent = 'approval') = (approval IS NOT NULL))
);
-- Every answer to an approval raised under a conductor asks whether it is one of these
-- (Orchestrations.continueApproved, OrchestrationAcceptance.byApproval): by approval, not by run.
CREATE INDEX orchestration_acceptance_by_approval ON orchestration_acceptance (approval)
    WHERE approval IS NOT NULL;

-- The requirements a run's acceptance set was approved against (§1b, fixed after the final
-- review): spec.md outside its ## Acceptance section, as it stood when the set was registered.
-- The acceptance stage refuses a spec whose requirements changed since, and the verifier is given
-- these, so a conductor cannot rewrite the requirements and the commands together into a weaker
-- pair the verifier finds consistent. One row per run, replaced with the set.
CREATE TABLE orchestration_acceptance_requirements (
    orchestration TEXT        PRIMARY KEY,
    requirements  TEXT        NOT NULL,
    set_at        TIMESTAMPTZ NOT NULL
);

-- From V59, with acceptance_ran and cap_continued added.
ALTER TABLE orchestration_record DROP CONSTRAINT orchestration_record_kind_is_known;
ALTER TABLE orchestration_record ADD CONSTRAINT orchestration_record_kind_is_known CHECK (kind IN (
    'run_started', 'run_ended', 'stage_moved', 'phase_started', 'phase_ended', 'check_ran',
    'approval_asked', 'approval_answered', 'question_asked', 'question_answered', 'stalled',
    'call_failure', 'delegated', 'delegate_returned', 'acceptance_ran', 'cap_continued',
    'tool_call'));

-- orchestration_record.conversation (spec 2026-09-29 §1a, fixed after review): the conversation a
-- tool_call belongs to, or a delegated line's own delegate — the child conversation RecordKeeper
-- was already handed, on both. Null for every other kind, which has no one conversation more its
-- own than the run's. Without this, two delegations to the same agent in one run (the first still
-- AWAITING an approval when the second is dispatched through agent_run) could not be told apart
-- by actor name or by "the latest delegated line" alone, and the facts footer of one leaked into
-- the other's. This is exact instead: each delegation's own child conversation is unique.
ALTER TABLE orchestration_record ADD COLUMN conversation TEXT;
