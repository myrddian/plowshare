-- PROC lists model calls chronologically by (created_at, call_id), oldest or newest first, and
-- continues a page with a row-value cursor on the same pair. The inventory page walks calls in that
-- order, checking custody per candidate, and stops at the page boundary; it must read an index in
-- key order (forwards for oldest, backwards for newest) and seek the cursor rather than sort every
-- retained call. The V85 time indexes all lead with another column and cannot serve that walk.
-- call_id breaks ties between calls captured in the same microsecond, so the key is unique.
CREATE INDEX inference_calls_created_call ON inference_calls(created_at, call_id);
