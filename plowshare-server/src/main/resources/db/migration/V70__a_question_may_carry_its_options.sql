-- A question may carry options, and an answer may name the ones it chose — spec
-- 2026-09-29-orchestration-studio §2.2.
--
--   structure  on a question: {"lead": …, "questions": [{header, question, multi, options}]} as
--              StructuredQuestions wrote it, and a harness-built question may add its own fields
--              beside those two. On an answer: {"choices": [{header, chosen, other?, note?}]} as
--              StructuredAnswers wrote it. Null on every row written before V70, and on any plain
--              question or answer.
--
-- `text` stays required and unchanged in meaning: it is the rendering every reader that does not
-- know the shape reads, the conductor included. Nothing here rewrites a CHECK: V48 defines
-- orchestration_messages' constraints and none of them is touched.
ALTER TABLE orchestration_messages ADD COLUMN structure JSONB;
ALTER TABLE orchestration_messages ADD CONSTRAINT orchestration_messages_structure_is_an_object
    CHECK (structure IS NULL OR jsonb_typeof(structure) = 'object');
