-- Window facts are independent of the original UTF-8 text. NULL can still mean ejection.
ALTER TABLE entries ADD COLUMN file_window JSONB;
ALTER TABLE entries ADD CONSTRAINT entries_file_window_is_a_tool_result
    CHECK (file_window IS NULL OR (kind = 'tool_result' AND jsonb_typeof(file_window) = 'object'));
ALTER TABLE entries DROP CONSTRAINT entries_a_tool_result_is_not_blank;
ALTER TABLE entries ADD CONSTRAINT entries_a_tool_result_is_not_blank
    CHECK (kind <> 'tool_result' OR content <> '' OR file_window IS NOT NULL);

ALTER TABLE suspended_run_calls ADD COLUMN file_window JSONB;
ALTER TABLE suspended_run_calls ADD CONSTRAINT suspended_file_window_is_a_read_result
    CHECK (file_window IS NULL OR (tool_name = 'file_read' AND state = 'completed'
        AND jsonb_typeof(file_window) = 'object'));
