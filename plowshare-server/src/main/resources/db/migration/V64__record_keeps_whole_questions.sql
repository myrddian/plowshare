-- orchestration_record.body: the whole text of the few rows a person has to read in full — a
-- question asked, its answer, a stall's notice and a run's result or failure — beside the one line
-- V59 keeps. Written by RecordKeeper only when that line is not all of it (cut at MOST_QUOTED, or
-- the text ran to more lines); null on every other row, and on every row of every other kind.
--
-- Measured 2026-09-29: in the runs panel and /watch a conductor's question read
--     13:44:07  conductor  ? asked: The spec's ## Acceptance section now includes commands that
--     import modules, check for key classes and methods, and run the game. Does this satisfy the
--     requirem…
-- and the person could not see the rest anywhere. `text` stays the one short line it is, which is
-- right for the tool lines and milestones a record is mostly made of; the body is what a reader
-- opens when the line is not enough. Newlines are the body's own and are kept. Bounded, so one
-- runaway result cannot make a page of the record megabytes: RecordStore cuts past it with `…`,
-- as it cuts a line, and this CHECK holds any other writer to the same.
ALTER TABLE orchestration_record ADD COLUMN body TEXT;
ALTER TABLE orchestration_record ADD CONSTRAINT orchestration_record_body_is_bounded
    CHECK (body IS NULL OR length(body) BETWEEN 1 AND 16000);
