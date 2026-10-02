-- The board resolves (spec 2026-09-29 §7; board step 2b-i). A closed topic's resolution is
-- delivered to the conversation a bot opened it from, or to the opener's inbox, exactly once:
-- resolution_delivered_at is that once, as orchestrations.result_delivered_at is for a run (V48).
ALTER TABLE board_topics ADD COLUMN resolution_delivered_at TIMESTAMPTZ;
ALTER TABLE board_topics ADD CONSTRAINT board_topics_delivered_only_once_resolved
    CHECK (resolution_delivered_at IS NULL OR resolution IS NOT NULL);
CREATE INDEX board_topics_undelivered_resolutions ON board_topics (closed_at)
    WHERE resolution IS NOT NULL AND resolution_delivered_at IS NULL;

-- A title and a label are one line. Board.fold makes them so for every topic Board.open writes;
-- this makes it structural for every writer, child topics (step 2b-ii) included, because both
-- reach other models' prompts in the harness's own wake line.
ALTER TABLE board_topics ADD CONSTRAINT board_topics_title_and_label_are_one_line
    CHECK (position(E'\n' in title) = 0 AND position(E'\r' in title) = 0
       AND position(E'\n' in label) = 0 AND position(E'\r' in label) = 0);
