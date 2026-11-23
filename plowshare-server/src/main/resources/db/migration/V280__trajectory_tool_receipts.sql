-- Bind a retained invocation to the exact answer, since model call IDs can repeat across turns.
ALTER TABLE suspended_run_calls ADD COLUMN answer_ordinal INTEGER;
ALTER TABLE suspended_run_calls ADD FOREIGN KEY (conversation, answer_ordinal)
    REFERENCES entries(conversation_id, ordinal) ON DELETE SET NULL (answer_ordinal);
ALTER TABLE suspended_run_calls ADD UNIQUE (approval, conversation, call_id);
CREATE UNIQUE INDEX suspended_run_calls_answer ON suspended_run_calls(conversation, answer_ordinal, call_id)
    WHERE answer_ordinal IS NOT NULL;

ALTER TABLE entries ADD COLUMN retained_invocation TEXT;
ALTER TABLE entries ADD CHECK (retained_invocation IS NULL OR kind = 'tool_result');
ALTER TABLE entries ADD FOREIGN KEY (retained_invocation, conversation_id, tool_call_id)
    REFERENCES suspended_run_calls(approval, conversation, call_id)
    ON DELETE SET NULL (retained_invocation);
CREATE UNIQUE INDEX entries_retained_invocation ON entries(retained_invocation)
    WHERE retained_invocation IS NOT NULL;

-- Debug metadata has a separate read path and can disappear independently of the receipt.
CREATE TABLE tool_call_trace (
    invocation_id TEXT PRIMARY KEY REFERENCES suspended_run_calls(approval) ON DELETE CASCADE,
    first_observed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at TIMESTAMPTZ,
    reply_observed_at TIMESTAMPTZ,
    reply_timed_out_at TIMESTAMPTZ
);
