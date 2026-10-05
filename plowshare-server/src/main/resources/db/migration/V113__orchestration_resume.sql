-- Explicit recovery retains the run, pinned source, journal and spent allowance.
CREATE TABLE orchestration_resume_receipts (
    account TEXT NOT NULL REFERENCES admins(handle),
    request_id UUID NOT NULL,
    run_id TEXT NOT NULL REFERENCES orchestrations(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (account, request_id)
);
ALTER TABLE orchestration_record DROP CONSTRAINT orchestration_record_kind_is_known;
ALTER TABLE orchestration_record ADD CONSTRAINT orchestration_record_kind_is_known CHECK (kind IN (
    'run_started', 'run_ended', 'run_resumed', 'stage_moved', 'phase_started', 'phase_ended', 'check_ran',
    'approval_asked', 'approval_answered', 'question_asked', 'question_answered', 'stalled',
    'call_failure', 'delegated', 'delegate_returned', 'acceptance_ran', 'cap_continued',
    'tool_call', 'concern'));
