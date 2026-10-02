-- The acceptance verifier advises the person; it is not a wall only a model can climb.
--
-- Measured 2026-09-29, orc_318D26A46144920B (implement_specification, a pygame Space Invaders):
-- from 05:10 to 05:44 the conductor marked `spec` done eight times, and the verifier refused it
-- every time. A model gate on a model's writing, with nobody able to settle it, loops. So the gate
-- counts the verifier's findings against a run's `acceptance: written` stage, and on the third it
-- asks the person instead of refusing again: `accept` lets the section stand, anything else is
-- passed to the conductor as the person's direction. Either answer starts the count again.
--
--   verifier_refusals  how many times the verifier has found requirements uncovered since the
--                      person last answered; AcceptanceGate asks the person on the third.
--   verifier_asked     the digest (sha-256, hex) of exactly what the person is being shown — the
--                      requirements and the command lines, as the verifier was shown them — while
--                      the question is open; the answer reads it, because the spec may be edited
--                      after it. Null when nothing is asked.
--   verifier_waived    that digest, once the person answered `accept`. The gate skips the verifier
--                      for exactly this pair; a changed spec or command set is verified again.
ALTER TABLE orchestrations ADD COLUMN verifier_refusals INT NOT NULL DEFAULT 0;
ALTER TABLE orchestrations ADD CONSTRAINT orchestrations_verifier_refusals_not_negative
    CHECK (verifier_refusals >= 0);
ALTER TABLE orchestrations ADD COLUMN verifier_asked TEXT;
ALTER TABLE orchestrations ADD CONSTRAINT orchestrations_verifier_asked_is_a_digest
    CHECK (verifier_asked IS NULL OR verifier_asked ~ '^[0-9a-f]{64}$');
ALTER TABLE orchestrations ADD COLUMN verifier_waived TEXT;
ALTER TABLE orchestrations ADD CONSTRAINT orchestrations_verifier_waived_is_a_digest
    CHECK (verifier_waived IS NULL OR verifier_waived ~ '^[0-9a-f]{64}$');

-- 'uncovered' is the question itself: a harness question only the person may answer, as V58's
-- 'stuck' is. Both CHECKs are rewritten from V58, the latest definition of each — no migration
-- since has touched either, so nothing between is dropped.
ALTER TABLE orchestrations DROP CONSTRAINT orchestrations_pending_cap_is_known;
ALTER TABLE orchestrations ADD CONSTRAINT orchestrations_pending_cap_is_known
    CHECK (pending_cap IS NULL
        OR pending_cap IN ('turn_cap', 'call_budget', 'stuck', 'uncovered'));

ALTER TABLE orchestration_messages DROP CONSTRAINT orchestration_messages_cap_kind_is_known;
ALTER TABLE orchestration_messages ADD CONSTRAINT orchestration_messages_cap_kind_is_known
    CHECK (cap_kind IS NULL
        OR (kind = 'answer' AND cap_kind IN ('turn_cap', 'call_budget', 'stuck', 'uncovered')));
