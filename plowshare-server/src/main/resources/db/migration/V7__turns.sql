-- What was said in a conversation, and what came back. One row per turn.
--
-- V6 declined this table by name -- "the turn table, if there is one, belongs to
-- the task that builds turns rather than to this one" -- and this is that table.
-- It is a separate migration and not an edit to V6, although V6 has not shipped
-- and is still editable: the two have different subjects, and a file holding
-- both would make the argument for a conversation's budget and the argument for
-- its history one thing that has to be read together for ever.
--
-- WHY THIS IS DURABLE AT ALL, WHEN A JOB IS NOT. A turn is a job, and
-- `agents.JobStore` keeps jobs in a map for the life of the process -- V6 says
-- so, and that is why nothing here points at one. But a conversation's SPENDING
-- survives a restart, and a row saying eleven of twenty model calls are gone
-- with nothing anywhere saying what they were spent on is a receipt for a
-- purchase nobody wrote down. Either both survive or neither should, and V6
-- already made that choice for the budget.
--
-- NO JOB ID COLUMN, and that is a measurement rather than a preference.
-- `JobStore` mints ids from a `new AtomicLong()` field -- a per-process counter
-- that starts again at job_000001 on every boot -- so a column holding one would
-- be an identifier that silently collides across restarts, and the first person
-- to join on it would join two turns from two different runs of this server. A
-- turn is identified by the conversation it belongs to and its place in it,
-- which is what the primary key below says.
--
-- NO INSTANT COLUMN. The ordinal is the order; `conversations.last_turn_at`
-- already records when the last turn ended; and a column nothing reads is a
-- question nobody asked -- the rule that keeps `GET /v1/jobs` and
-- `GET /v1/projects` out of this server, applied to a schema. If a per-turn
-- instant ever gains a reader it is one migration away, and inventing it now
-- would also mean inventing a clock for `TurnStore` that no test could pull on.
CREATE TABLE turns (
    -- The conversation this was said in. A turn in a conversation nobody opened
    -- is not a record, it is a bug -- V5's sentence about `memory_reasons
    -- .memory_id`, and it holds here for the same reason: nothing in this schema
    -- deletes a conversation in any state, so the only way to break this key is
    -- to name an id that was never opened.
    --
    -- Named rather than left to Postgres's `turns_conversation_id_fkey`, so that
    -- a violated key reads as the rule it is rather than as a column and a
    -- suffix.
    --
    -- No ON DELETE clause: there is no delete path to choose a behaviour for,
    -- and a CASCADE written ahead of one would be a decision to discard a
    -- person's history taken by whoever first writes that path, silently.
    conversation_id TEXT NOT NULL,

    -- Where in the conversation this turn came: 1 for the first thing said.
    -- Assigned by `TurnStore.record` from what the conversation already holds,
    -- never by its caller.
    ordinal         INT  NOT NULL,

    -- What the person said. NOT NULL and non-blank below: `JobStore.submit`
    -- already refuses a blank task, so a blank utterance could only arrive from
    -- outside Java.
    utterance       TEXT NOT NULL,

    -- What the turn came to -- `Outcome.text`, whatever the ending.
    --
    -- NOT NULL, and deliberately no unconditional `<> ''` check, unlike every
    -- other non-blank TEXT column in this schema. `Outcome` permits an ANSWERED
    -- run to carry the empty string: "a model that stops on its first token has
    -- answered with nothing, which is a decision and not a failure". The
    -- conditional rule that IS true of it is below.
    answer          TEXT NOT NULL,

    -- Which of `Outcome.Ending`'s constants this turn reached, spelled as the
    -- constant is -- `Outcome.Ending.name()`, which is also what `JobView`
    -- already puts on the wire. Not a lower-cased wire name like
    -- `memory_reasons.kind`, because `Ending` has no `wireName` to be the second
    -- spelling of.
    --
    -- A turn that ended other than ANSWERED is still a turn and the conversation
    -- goes on; the column exists so the transcript can say which one it was.
    ending          TEXT NOT NULL,

    -- What this turn's prompt cost, as the model's own tokenizer counted it, or
    -- NULL for a turn nobody measured.
    --
    -- THE COLUMN COMPACTION IS DECIDED FROM. The next task compacts when this
    -- measurement plus headroom would exceed the model's loaded context length,
    -- so the one thing this column must never do is make "not measured" look
    -- like a number. NULL is the absence -- an endpoint that omitted `usage`,
    -- or a caller with nothing to report -- and `turns_prompt_tokens_are_a
    -- _measurement` below refuses the zero it could otherwise be confused with.
    --
    -- NULLABLE IS NOT A PLACEHOLDER. When this file was written there was also
    -- no path at all from a finished run to a prompt-token count -- `Outcome`
    -- carries an ending, a text, two counts and a detail, and no usage, and
    -- `LedgerEntry` carries usage with nothing naming the job it belongs to --
    -- and building that path was the compaction task's. It built it, through
    -- `agents.Transcript`, so that half of the argument is spent. What remains
    -- is the half that was always load-bearing and is permanent: an endpoint may
    -- omit `usage` entirely, and a turn that died before its first completion
    -- came back has nothing to report either. Both are absences, and neither is
    -- a zero.
    prompt_tokens   INT,

    -- One turn per place in a conversation. This is the identity of a turn --
    -- see the header on why it is not a job id -- and it is also the backstop
    -- for the one race `TurnStore.record` cannot rule out: `agents.Turn` holds a
    -- conversation to one turn at a time, but it does that in one process's map,
    -- so a second process writing the same conversation collides here loudly
    -- instead of appending a second turn number three.
    --
    -- It is the index the one read uses, too. `WHERE conversation_id = ? ORDER
    -- BY ordinal` is exactly this key's leading column and then its second, so
    -- there is no separate index here as there is in V6.
    CONSTRAINT turns_one_per_place_in_a_conversation
        PRIMARY KEY (conversation_id, ordinal),

    CONSTRAINT turns_belong_to_a_conversation
        FOREIGN KEY (conversation_id) REFERENCES conversations (id),

    -- Turns are numbered from one, so that "the first turn" is one thing rather
    -- than a choice between 0 and 1 that two readers make differently. A zero or
    -- negative ordinal is a history whose order says nothing, and it is what a
    -- caller that computed an offset instead of a count would write.
    CONSTRAINT turns_are_numbered_from_one CHECK (ordinal >= 1),

    -- Nothing said is not a turn. The same sentence `projects_name_named` and
    -- `conversations_id_named` carry: the empty string is what an omitted field
    -- arrives as, and a row holding one reads to a person as a turn in which
    -- somebody said nothing and got an answer anyway.
    CONSTRAINT turns_utterance_is_not_blank CHECK (utterance <> ''),

    -- The seven endings and nothing else, for the reason V5 gives about
    -- `memory_reasons.kind`: an eighth would be a row `Outcome.Ending.valueOf`
    -- refuses to read back, so it would be written successfully and then be
    -- unreadable for ever.
    --
    -- ADDING AN ENDING THEREFORE NEEDS A MIGRATION, and that is correct rather
    -- than a cost -- but it does mean this file is now one of the places
    -- `Outcome.Ending`'s own javadoc lists as having to change when the set
    -- grows, none of which is a compile error. Its list has been updated to name
    -- this constraint, and `TurnStoreTest.every_ending_this_server_can_reach_is
    -- _a_turn_this_table_holds` is what fails when an eighth constant arrives
    -- without an eighth migration.
    CONSTRAINT turns_ending_is_known CHECK (ending IN (
        'ANSWERED',
        'TURN_CAP',
        'CALL_BUDGET',
        'CANCELLED',
        'UNAVAILABLE',
        'SUB_AGENT_FAILED',
        'SESSION_GONE'
    )),

    -- A turn that STOPPED says how far it got. `Outcome`'s own guarantee, held
    -- here rather than only described: for every ending but ANSWERED the text is
    -- "a sentence this runtime wrote, naming the ending and listing the tools
    -- the run called in order ... never blank: an empty string reads to a human
    -- as a bug, while the tool trail says how far the run got".
    --
    -- This is the rule that whole type exists for, arriving through the
    -- database: a truncated run must never read as one that decided. A capped
    -- turn with an empty answer is indistinguishable, to a person reading the
    -- transcript, from a model that considered the question and said nothing.
    --
    -- Empty string only, where Java's guarantee is the stronger "non-blank". A
    -- second predicate reproducing `String.isBlank` in SQL would be a rule with
    -- a different boundary from the one it is copying, which is worse than a
    -- narrower rule that agrees with it.
    CONSTRAINT turns_a_turn_that_stopped_says_how
        CHECK (ending = 'ANSWERED' OR answer <> ''),

    -- A measurement or nothing, and never a zero. No chat completion has a
    -- prompt of no tokens -- every call carries at least the agent's system
    -- message and the utterance -- so a zero here is not a small cost, it is a
    -- caller that had no number and wrote one anyway. Compaction reads this
    -- column, and a zero read as a measurement says the history is empty and
    -- defers compaction for ever: a confidently wrong answer where the absence
    -- would have been a loud one.
    CONSTRAINT turns_prompt_tokens_are_a_measurement
        CHECK (prompt_tokens IS NULL OR prompt_tokens > 0)
);

COMMENT ON COLUMN turns.prompt_tokens IS
    'What this turn''s prompt cost, by the model''s own tokenizer, or NULL for a '
    'turn nobody measured. Never zero: absent and free are different facts, and '
    'compaction is decided from this number.';
