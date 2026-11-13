-- A tool receipt's state is whether its conversation holds a result entry answering that call id.
-- PROC probes this once per listed receipt (and once per candidate for the active filter). The
-- primary key (conversation_id, ordinal) does not cover tool_call_id, so each probe walked and
-- fetched every entry of the conversation: quadratic in transcript length, enough to push the
-- newest-receipts page past the PROC read timeout on long agent conversations. Only tool_result
-- entries carry a tool_call_id (V11's CHECK), so the partial index holds just those rows.
CREATE INDEX entries_tool_results ON entries(conversation_id, tool_call_id)
    WHERE tool_call_id IS NOT NULL;
