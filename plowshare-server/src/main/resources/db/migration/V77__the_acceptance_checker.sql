-- The acceptance checker (implementation rationale).
--
-- Measured 2026-09-30, orc_3190A00C6035D3B5 (Space Invaders): 51 tests green, nine of nine
-- acceptance commands passed, and game/main.py was still the scaffold's no-op. Nothing turned
-- "no command here can observe it" into "the person accepts it", so tests passing were taken for
-- the product working. This migration holds what closes that: a command that must keep running,
-- the person's own check of the product, and the concerns an opt-in hostile checker keeps.

-- runs-for (§1): an acceptance command that passes when it is still running this many seconds in,
-- and is then stopped. Null for a command held to its exit code, as every row before this.
ALTER TABLE orchestration_acceptance ADD COLUMN runs_for INT;
ALTER TABLE orchestration_acceptance ADD CONSTRAINT orchestration_acceptance_runs_for_is_seconds
    CHECK (runs_for IS NULL OR runs_for BETWEEN 1 AND 600);

-- orchestrations.checker (§2): the agent a definition's `checker:` named, pinned at start, and
-- only on a run that keeps its `acceptance: required` stage — a phase run has none, and its
-- root's checker covers the product. Null for a run with no checker.
ALTER TABLE orchestrations ADD COLUMN checker TEXT;

-- The person's check of the product at the acceptance stage (§1): the digest (sha-256, hex) of
-- exactly what the person is shown — the check: lines and the checker's concerns for them — while
-- the question is open, and that digest once they accepted it. The acceptance stage passes on
-- what the person accepted, and asks again about anything changed since.
ALTER TABLE orchestrations ADD COLUMN product_asked TEXT;
ALTER TABLE orchestrations ADD CONSTRAINT orchestrations_product_asked_is_a_digest
    CHECK (product_asked IS NULL OR product_asked ~ '^[0-9a-f]{64}$');
ALTER TABLE orchestrations ADD COLUMN product_accepted TEXT;
ALTER TABLE orchestrations ADD CONSTRAINT orchestrations_product_accepted_is_a_digest
    CHECK (product_accepted IS NULL OR product_accepted ~ '^[0-9a-f]{64}$');

-- The checker's concerns (§2), one row per concern per run, harness-written only: each with what
-- it is about, why it is a concern, and its state.
--   open            raised, and nothing outstanding; checked at the end
--   asked           the checker asked the conductor why (question), and waits on its answer
--   answered        the conductor answered (reason), and the checker gave no verdict on it
--   resolved        the checker accepted the conductor's reason, or the person did
--   for_the_person  waiting on the person: unresolved after its rounds (verdict null), or one the
--                   checker could not check itself at the end (verdict 'cannot_check')
--   checked         the end pass checked it: verdict 'holds' or 'does_not_hold', with its finding
-- rounds counts the WHY questions put to the conductor, at most 2. No foreign keys, on V59's
-- reasoning: runs are never deleted, and a key would make a fixture build a run to write one row.
CREATE TABLE orchestration_concerns (
    orchestration TEXT        NOT NULL,
    id            TEXT        NOT NULL,
    about         TEXT        NOT NULL,
    why           TEXT        NOT NULL,
    raised        TEXT        NOT NULL,
    state         TEXT        NOT NULL,
    rounds        INT         NOT NULL DEFAULT 0,
    question      TEXT,
    reason        TEXT,
    objection     TEXT,
    verdict       TEXT,
    finding       TEXT,
    person_check  TEXT,
    person_answer TEXT,
    created_at    TIMESTAMPTZ NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (orchestration, id),
    CONSTRAINT orchestration_concerns_id_is_numbered CHECK (id ~ '^c[1-9][0-9]*$'),
    CONSTRAINT orchestration_concerns_raised_is_known CHECK (raised IN ('plan', 'end')),
    CONSTRAINT orchestration_concerns_state_is_known CHECK (state IN ('open', 'asked',
        'answered', 'resolved', 'for_the_person', 'checked')),
    CONSTRAINT orchestration_concerns_rounds_are_bounded CHECK (rounds BETWEEN 0 AND 2),
    CONSTRAINT orchestration_concerns_asked_has_its_question
        CHECK (state <> 'asked' OR question IS NOT NULL),
    CONSTRAINT orchestration_concerns_verdict_is_known CHECK (verdict IS NULL
        OR verdict IN ('holds', 'does_not_hold', 'cannot_check')),
    CONSTRAINT orchestration_concerns_checked_has_its_verdict
        CHECK (state <> 'checked' OR verdict IN ('holds', 'does_not_hold'))
);

-- 'concerns' is the checker's question to the person at plan time: the concerns it could not
-- resolve with the conductor, each with the conductor's reason and why the checker does not accept
-- it. 'product_check' is the acceptance stage's: the check: lines and what the checker could not
-- check itself. Both only a person may answer, as V58's 'stuck'. 'uncovered' stays accepted for
-- the rows V65 wrote; nothing asks it any more. Both CHECKs are rewritten from V71, the latest
-- definition of each, so its 'install' is kept.
ALTER TABLE orchestrations DROP CONSTRAINT orchestrations_pending_cap_is_known;
ALTER TABLE orchestrations ADD CONSTRAINT orchestrations_pending_cap_is_known
    CHECK (pending_cap IS NULL
        OR pending_cap IN ('turn_cap', 'call_budget', 'stuck', 'uncovered', 'time_cap',
                           'check_failures', 'install', 'concerns', 'product_check'));

ALTER TABLE orchestration_messages DROP CONSTRAINT orchestration_messages_cap_kind_is_known;
ALTER TABLE orchestration_messages ADD CONSTRAINT orchestration_messages_cap_kind_is_known
    CHECK (cap_kind IS NULL
        OR (kind = 'answer'
            AND cap_kind IN ('turn_cap', 'call_budget', 'stuck', 'uncovered', 'time_cap',
                             'check_failures', 'install', 'concerns', 'product_check')));

-- From V60, the latest definition, with 'concern' added: every concern raised, every WHY and its
-- answer, every verdict and the person's answer is a line of the run's story, so /watch and the
-- panel show the checker's work beside the run's.
ALTER TABLE orchestration_record DROP CONSTRAINT orchestration_record_kind_is_known;
ALTER TABLE orchestration_record ADD CONSTRAINT orchestration_record_kind_is_known CHECK (kind IN (
    'run_started', 'run_ended', 'stage_moved', 'phase_started', 'phase_ended', 'check_ran',
    'approval_asked', 'approval_answered', 'question_asked', 'question_answered', 'stalled',
    'call_failure', 'delegated', 'delegate_returned', 'acceptance_ran', 'cap_continued',
    'tool_call', 'concern'));
