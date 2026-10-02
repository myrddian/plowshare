-- Whether the learner has seen this row, and therefore what is still waiting
-- for it.
--
-- WHAT V19 LEFT AND WHY IT LEFT IT. That file says of itself: "No learner and no
-- `learned_at` column -- ejection is not gated on learning, and the case that
-- settles it is that tool results do not push a fold at all, so the largest
-- payloads in the system are exactly the ones a fold-based rule would never
-- reach." Every word of that stands. This column does not gate anything: no
-- sweep reads it, `archive.Retention` does not join to it, and a payload is
-- ejected on exactly the schedule it was ejected on before this file existed.
-- What it adds is the other axis -- the row-level fact the log-scanning design
-- names, owned by the learning workflow and by nothing else.
--
--     | state   | superseded_by | learned_at | content |
--     | live    | NULL          | --         | present |
--     | folded  | set           | NULL       | present |
--     | learned | set           | set        | present |
--     | ejected | set           | set        | NULL    |
--
-- A ROW-LEVEL COLUMN AND NOT A WATERMARK, which is what the design proposed
-- before the two workflows were separated. A watermark -- "learned through turn
-- N" -- is a single scalar somebody has to keep right: it has to be advanced by
-- exactly the pass that succeeded, it cannot express a pass that covered part of
-- a span, and there is no way to ask it what is left except by trusting it. A
-- column cannot drift. `superseded_by IS NOT NULL AND learned_at IS NULL` is a
-- work queue that is idempotent (a second pass over a marked row selects
-- nothing), resumable (a pass that died halfway leaves the unmarked half
-- selected), and keeps no state outside the table a crash could disagree with.
--
-- ONE COLUMN WRITE PER STATE, which is the property that makes the four states
-- above cheap. Nothing in this table is ever edited except by moving a row one
-- state along, and each move is one UPDATE of one column guarded by that
-- column's own NULL -- `EntryStore.supersede` already writes the first,
-- `EntryStore.markLearned` writes this one, and `EntryStore.ejectPayload` writes
-- the third.

-- ---------------------------------------------------------------------------
-- 1. the column
-- ---------------------------------------------------------------------------

-- When the learner was given this row.
--
-- WHEN IT WAS GIVEN AND NOT WHEN IT WAS UNDERSTOOD, and the difference is the
-- load-bearing decision of the whole design. THE SYSTEM COMPUTES THE WINDOW; the
-- agent does not choose it. So the fact this column records is one the system
-- observed itself -- `learner.Learner` marks exactly the rows
-- `learner.LearningWindow` handed over -- rather than one an agent reported. Had
-- the agent chosen its own window, marking a row would have depended on a model
-- saying which rows it read, and the state machine would have been exactly as
-- reliable as a self-report. It is stamped after a pass returns, so a pass that
-- failed marks nothing and the next window is simply wider; that is the whole of
-- the failure handling and there is no retry and no backoff to configure.
--
-- IT IS NOT ON `archive.EntryRecord`, and that is deliberate rather than an
-- omission. `ejected_at` is on that record because `result_read` reports it to a
-- model -- "this was ejected on <date>" is an answer somebody reads. Nothing
-- reports this one. It is read by one query, written by one statement, and a
-- reader of a conversation is never told that a learner passed over it, which is
-- the same rule `diagnostic` entries are held to one column over: infrastructure
-- does not appear in the context going to a model.
ALTER TABLE entries ADD COLUMN learned_at TIMESTAMPTZ;

-- WHAT IS NOT HERE: A CHECK TYING THIS COLUMN TO `superseded_by`.
--
-- The obvious one is `learned_at IS NULL OR superseded_by IS NOT NULL` -- "only
-- folded material is learned" -- and it would be true of every row this build
-- writes, because `EntryStore.awaitingTheLearner` selects nothing else. It is
-- refused on the ground V19 refuses a transition trigger: it would be a second
-- mechanism guarding a rule one writer already holds, and this one has the
-- further problem that IT IS ALREADY KNOWN TO BE WRONG FOR A SHAPE THE DESIGN
-- NAMES.
--
-- The shape is a run that never folds. Tool results do not push a fold --
-- `Compaction.foldIfItWouldNotFit` measures what a turn sent and what it added,
-- and neither includes a result -- so "ask a question, read one 100 000-character
-- file, answer, done" ends with nothing superseded at all. Its rows are live for
-- ever, so a folded-only queue never offers them; and the argument that makes a
-- folded-only queue principled -- "a live row is still in the conversation, so
-- nothing is lost by not mining it yet" -- stops being true the moment somebody
-- archives that conversation, because an archived conversation takes no further
-- turn (`agents.Turn.speak` refuses one) and a conversation nobody will speak
-- into again has no later moment to be mined in. The same is true one state
-- further on, where the payload is about to leave altogether.
--
-- So the day the `archived` and `to_be_ejected` windows are widened to reach
-- live rows -- which is what would make those two triggers do anything at all
-- for a one-turn run -- this CHECK is the file that has to be dropped first. A
-- constraint whose repair is already written down is a constraint that should
-- not be added. Where "only folded" lives instead is the queue predicate, in one
-- readable place, in `EntryStore`.
--
-- AND NOTHING TIES THIS COLUMN TO `ejected_at` EITHER, in either direction, and
-- that absence is the one the owner was explicit about. The table at the top of
-- this file reads as though an ejected row has always been learned first; that
-- is the ORDINARY life of a row and not an invariant. LEARNING AND EJECTION ARE
-- SEPARATE WORKFLOWS. A payload ejected before any learner reached it is a legal
-- row -- it is what a deployment with a short retention age and a busy inference
-- pool produces -- and a CHECK saying otherwise would make the sweep fail on it,
-- which is ejection gated on learning arriving through the schema after the
-- design refused it in code.

-- The learner's read: what is folded and not yet learned, in one conversation.
--
-- PARTIAL, ON THE TWO FACTS THAT SELECT A ROW, exactly as
-- `entries_payloads_still_held` is: the index holds only the rows a pass can act
-- on, and it shrinks as they are marked. A conversation that has been mined out
-- contributes nothing to it, and a conversation that never folds never enters
-- it.
--
-- THE KIND IS NOT IN THE PREDICATE although the query has one. The window is
-- what was SAID -- utterances, answers and the summaries a later fold covered --
-- and not what tools returned; that is a policy about what is worth a model call
-- and the design says to revisit it. It is written in `EntryStore` where it can
-- be read and changed, and leaving it out here means the index still serves the
-- query on the day it changes. What is in the predicate is the half that is a
-- property of the workflow rather than of this month's judgement about bytes.
CREATE INDEX entries_awaiting_the_learner ON entries (conversation_id)
    WHERE superseded_by IS NOT NULL AND learned_at IS NULL;

COMMENT ON COLUMN entries.learned_at IS
    'When the learner was handed this row, or NULL for a row still waiting -- '
    'and for every row of a conversation that has never folded. Stamped by the '
    'system over the window it computed, never from an agent''s report of what '
    'it read. It gates nothing: retention does not consult it, and a payload '
    'may be ejected having never been learned.';
