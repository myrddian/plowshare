-- The orchestration record: what happened in a run tree, one line per event, for a person
-- (implementation rationale §2). Written by the harness
-- only, and never what a model was sent: the real logs stay the record of that.
--
-- One story per tree: `root` is the root run, `run` the run in it the event belongs to. `ordinal`
-- is 1.. per root, assigned by RecordStore's INSERT ... SELECT MAX + 1 and kept unique by the
-- primary key. No foreign keys, on V54's reasoning: runs are never deleted, and a key would make
-- the store's own test build a whole orchestration to write one row. Kept as long as the runs.
--
-- The one write to an existing row is a tool line's outcome, set once into `detail` when the call
-- returns (RecordStore.settle).
CREATE TABLE orchestration_record (
    root    TEXT        NOT NULL,
    ordinal INT         NOT NULL,
    at      TIMESTAMPTZ NOT NULL,
    run     TEXT        NOT NULL,
    actor   TEXT        NOT NULL,
    kind    TEXT        NOT NULL,
    text    TEXT        NOT NULL,
    detail  TEXT,
    PRIMARY KEY (root, ordinal),
    CONSTRAINT orchestration_record_numbered_from_one CHECK (ordinal >= 1),
    CONSTRAINT orchestration_record_kind_is_known CHECK (kind IN ('run_started', 'run_ended',
        'stage_moved', 'phase_started', 'phase_ended', 'check_ran', 'approval_asked',
        'approval_answered', 'question_asked', 'question_answered', 'stalled', 'call_failure',
        'delegated', 'delegate_returned', 'tool_call')),
    CONSTRAINT orchestration_record_text_is_one_short_line
        CHECK (length(text) BETWEEN 1 AND 400 AND position(E'\n' IN text) = 0),
    CONSTRAINT orchestration_record_detail_is_one_short_line
        CHECK (detail IS NULL OR (length(detail) <= 400 AND position(E'\n' IN detail) = 0))
);

-- A narrowed read ("the latest tool line", "the last milestones") walks one kind of one tree.
CREATE INDEX orchestration_record_by_kind ON orchestration_record (root, kind, ordinal);
