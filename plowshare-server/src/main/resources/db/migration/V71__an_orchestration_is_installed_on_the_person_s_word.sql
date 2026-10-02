-- An orchestration is installed on the person's word — spec 2026-09-29-orchestration-studio §3.4.
--
-- 'install' is the question design_orchestration's orchestration_install asks: whether to write a
-- draft into the project's orchestrations. Only a person may answer it, as V58's 'stuck' and V65's
-- 'uncovered' — a model that drafted a definition does not also approve it. Both CHECKs are
-- rewritten from V69, the latest definition of each (master's time cap and failed-checks
-- question), so its 'time_cap' and 'check_failures' are kept.
ALTER TABLE orchestrations DROP CONSTRAINT orchestrations_pending_cap_is_known;
ALTER TABLE orchestrations ADD CONSTRAINT orchestrations_pending_cap_is_known
    CHECK (pending_cap IS NULL
        OR pending_cap IN ('turn_cap', 'call_budget', 'stuck', 'uncovered', 'time_cap',
                           'check_failures', 'install'));

ALTER TABLE orchestration_messages DROP CONSTRAINT orchestration_messages_cap_kind_is_known;
ALTER TABLE orchestration_messages ADD CONSTRAINT orchestration_messages_cap_kind_is_known
    CHECK (cap_kind IS NULL
        OR (kind = 'answer'
            AND cap_kind IN ('turn_cap', 'call_budget', 'stuck', 'uncovered', 'time_cap',
                             'check_failures', 'install')));
