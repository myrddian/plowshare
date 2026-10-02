-- A run asks about the time it has taken and about a check that keeps failing.
--
-- Measured 2026-09-29/30, orc_318DFD3782228160: a root run went 663 minutes with no working
-- product and nothing ever asked the person whether to go on -- every turn stayed under its step
-- cap and every run under its model-call budget. Phase 05-bullet's check failed 21 times over 183
-- minutes, and 07-sounds' 13 times; its output would have shown the person the real problem at
-- once (a library crashing on a missing audio device). So a project's caps: gain `time` (minutes a
-- run goes before it asks) and `failed-checks` (check failures a run takes before it asks).
--
--   asking_since     when the run last began asking (a question, a cap, stuck, uncovered...), or
--                    null while it is not. Time spent asking is not counted against its time cap.
--   asked_seconds    the seconds it has spent asking over its life, each counted when it is
--                    answered.
--   time_span_from   the counted seconds -- since created_at, less asked_seconds -- at which its
--                    current time span began: 0 at the start, and moved on by every "go on" to
--                    the time cap, the person's or an auto-continue's.
--   check_failures   how many times its check (and its acceptance commands) failed since it
--                    started, or since the person last answered `check_failures`.
ALTER TABLE orchestrations ADD COLUMN asking_since TIMESTAMPTZ;
ALTER TABLE orchestrations ADD COLUMN asked_seconds BIGINT NOT NULL DEFAULT 0;
ALTER TABLE orchestrations ADD CONSTRAINT orchestrations_asked_seconds_not_negative
    CHECK (asked_seconds >= 0);
ALTER TABLE orchestrations ADD COLUMN time_span_from BIGINT NOT NULL DEFAULT 0;
ALTER TABLE orchestrations ADD CONSTRAINT orchestrations_time_span_from_not_negative
    CHECK (time_span_from >= 0);
ALTER TABLE orchestrations ADD COLUMN check_failures INT NOT NULL DEFAULT 0;
ALTER TABLE orchestrations ADD CONSTRAINT orchestrations_check_failures_not_negative
    CHECK (check_failures >= 0);

-- 'time_cap' is a cap, asked of the model and the person at once; 'check_failures' is a question
-- only the person may answer, as V58's 'stuck' and V65's 'uncovered' are. Both CHECKs are
-- rewritten from V65, the latest definition of each -- no migration since has touched either, so
-- nothing between is dropped.
ALTER TABLE orchestrations DROP CONSTRAINT orchestrations_pending_cap_is_known;
ALTER TABLE orchestrations ADD CONSTRAINT orchestrations_pending_cap_is_known
    CHECK (pending_cap IS NULL
        OR pending_cap IN ('turn_cap', 'call_budget', 'stuck', 'uncovered', 'time_cap',
                           'check_failures'));

ALTER TABLE orchestration_messages DROP CONSTRAINT orchestration_messages_cap_kind_is_known;
ALTER TABLE orchestration_messages ADD CONSTRAINT orchestration_messages_cap_kind_is_known
    CHECK (cap_kind IS NULL
        OR (kind = 'answer' AND cap_kind IN ('turn_cap', 'call_budget', 'stuck', 'uncovered',
                                             'time_cap', 'check_failures')));
