-- What was summarised away, so that a compaction is an event and not a silence.
--
-- WHY THIS IS A TABLE AND NOT A FLAG ON `turns`. A compaction is not a turn.
-- Nobody said it, no ending describes it, and it answers no utterance -- so a
-- row in `turns` would have to invent an utterance and pick one of the seven
-- endings to wear, and it would then be indistinguishable from a turn somebody
-- really took. That is exactly the failure the design names: "a bad summary is
-- information loss wearing a receipt, which is worse than an obvious truncation
-- because it looks like continuity". The mitigation is that a person can see the
-- seam, and a seam that lives in its own table cannot be mistaken for the thing
-- either side of it.
--
-- NOTHING IS DELETED, EVER. Compacting rewrites what the MODEL is shown on the
-- next turn and rewrites nothing a person can read: the turns this row stands
-- for are still in `turns`, at their own ordinals, with their own text. Reading
-- behind the seam is `SELECT ... FROM turns WHERE conversation_id = ? AND
-- ordinal <= through_ordinal`, which is this table's whole promise and is why
-- there is no ON DELETE clause here either.
--
-- NO SUMMARY-OF-A-SUMMARY COLUMN, and no `from_ordinal`. A compaction always
-- reaches back to the first turn -- the second one summarises the first one's
-- summary together with everything said since -- so a start column would hold 1
-- in every row this server can write. V7 declined an instant column on the same
-- ground: a column nothing reads is a question nobody asked.
--
-- NO INSTANT COLUMN, for V7's reason exactly. `through_ordinal` is the order,
-- `conversations.last_turn_at` already records when the conversation last moved,
-- and inventing a clock here would mean inventing one for `CompactionStore` that
-- no test could pull on.
CREATE TABLE compactions (
    -- The conversation whose history this stands for. There is no separate
    -- foreign key to `conversations`: the composite one below points at `turns`,
    -- whose own `turns_belong_to_a_conversation` points here, so a compaction in
    -- a conversation nobody opened is already impossible by two hops. A second
    -- key would be a second thing to keep true about one fact.
    conversation_id TEXT NOT NULL,

    -- The last turn this summary stands for: turns 1 through this one are what
    -- the model is shown as a summary instead of verbatim.
    --
    -- A TURN THAT REALLY HAPPENED, and the foreign key below is what says so.
    -- A compaction reaching an ordinal nothing was ever said at would claim to
    -- have summarised turns that do not exist, and the sentence it puts in front
    -- of the model -- "turns 1 to N were summarised" -- would send a person
    -- looking in the transcript for rows nobody wrote.
    through_ordinal INT  NOT NULL,

    -- What the model is shown in place of those turns.
    --
    -- NOT NULL and non-blank below. A blank summary is not a short history, it
    -- is a compaction that lost everything and said nothing about it, which is
    -- the confident-empty-answer shape this project keeps finding: the turns
    -- would vanish from the prompt and the seam sentence would still claim they
    -- had been summarised. `Compaction` refuses to write one first; this stops
    -- anything that bypasses it.
    summary         TEXT NOT NULL,

    -- One summary per reach. A conversation compacted twice has two rows, at two
    -- different reaches, and both stay -- a person reading the transcript sees
    -- each seam where it fell. Writing the same reach twice is a second
    -- compaction of exactly the same turns, which is a caller that has lost
    -- track of what it already did.
    CONSTRAINT compactions_one_per_reach_in_a_conversation
        PRIMARY KEY (conversation_id, through_ordinal),

    -- The reach names a turn that was really spoken, in the conversation this
    -- row belongs to. Composite, against `turns_one_per_place_in_a_conversation`
    -- -- which is exactly (conversation_id, ordinal) -- so one key carries both
    -- halves of the claim.
    --
    -- `compactions_cover_at_least_one_turn CHECK (through_ordinal >= 1)` is
    -- DELIBERATELY ABSENT. `turns_are_numbered_from_one` already holds it on the
    -- row this key points at, so such a check could not fire on any row this
    -- key admits -- and a constraint no row can break reads to the next person
    -- as a guard something relies on.
    CONSTRAINT compactions_reach_a_turn_that_was_spoken
        FOREIGN KEY (conversation_id, through_ordinal)
        REFERENCES turns (conversation_id, ordinal),

    -- A summary that says nothing is not a summary. The same sentence
    -- `turns_utterance_is_not_blank` and `conversations_id_named` carry: the
    -- empty string is what an omitted field arrives as.
    CONSTRAINT compactions_summary_says_something CHECK (summary <> '')
);

COMMENT ON TABLE compactions IS
    'What a conversation''s older turns were summarised into, and how far back '
    'the summary reaches. The turns themselves are never deleted: this table '
    'changes what the model is shown on the next turn and changes nothing a '
    'person can read.';
