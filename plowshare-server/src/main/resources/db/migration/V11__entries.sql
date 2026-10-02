-- Every message a conversation has held, in the order it was held, whether or
-- not a model ever sees it again.
--
-- THE RULE THIS TABLE IS SHAPED BY, and the reason `kind` is first among the
-- columns that carry meaning: an entry is RECORDED, and whether it reaches a
-- model is a property of its kind rather than a decision taken when a request is
-- built. Three kinds are the OpenAI-shaped roles a conversation can carry and a
-- fourth is the harness's own seam; the rest are recorded and invisible. A kind
-- nobody classified does not project, so a kind added without deciding is a
-- missing message rather than a leaked one.
--
-- WHAT THIS TABLE HOLDS THAT NO TABLE IN THIS SCHEMA HAS EVER HELD, and the
-- operator reading this file is the person it has to be said to. `tool_result`
-- content is whatever a tool returned: file contents read from the operator's
-- own disk, memory bodies, glob output, a delegated agent's whole answer. V7
-- deliberately kept all of that out of `turns` -- "tool calls and tool results
-- are NOT replayed ... a turn's working" -- and this table is where it now
-- lands. That is the substantive consequence of making a request a projection
-- of a log rather than a construction, and it is stated here rather than left to
-- be discovered by whoever first runs `SELECT content FROM entries`. There is a
-- COMMENT ON TABLE at the foot of this file saying the same thing to anybody
-- reading the catalogue instead of the migration.
--
-- WHY THE CONVERSATION AND NOT THE JOB. `agents.Job` carries an id and an agent
-- and does not know its conversation; the link lives only in `agents.Turn`,
-- which orchestrates both. Keying on the job would need a relationship no table
-- has, and `JobStore` mints ids from a per-process counter that starts again at
-- job_000001 on every boot -- V7's own argument for why `turns` has no job id
-- column, and it holds one table over.
--
-- WHY `turns` STAYS. The two describe different things. `turns` is the run-level
-- record: `ending` and `prompt_tokens` are facts about a request and its
-- outcome, `turns_prompt_tokens_are_a_measurement` refuses a zero because
-- `agents.Compaction` decides folds from it, and
-- `turns_a_turn_that_stopped_says_how` requires a stopped turn to say so. Those
-- constraints are untouched by this file. `entries` is the message-level record,
-- and one turn produces many of them. `turns.utterance` and `turns.answer`
-- become derivable from this table and are NOT removed here: deleting columns
-- whose constraints encode two hard-won rules is a change with its own argument.
CREATE TABLE entries (
    -- The conversation this was said in. Same key and same reasoning as
    -- `turns.conversation_id`: nothing in this schema deletes a conversation, so
    -- the only way to break this is to name an id nobody opened, and there is no
    -- ON DELETE clause because there is no delete path to choose a behaviour
    -- for.
    conversation_id TEXT   NOT NULL,

    -- Where in the conversation's log this entry came: 1 for the first. Assigned
    -- by `archive.EntryStore.append` from what the conversation already holds,
    -- never by its caller -- `TurnStore.record`'s scheme exactly, for the same
    -- reason.
    --
    -- ACROSS THE WHOLE CONVERSATION AND NOT WITHIN A TURN. A turn produces many
    -- entries and the projection is one flat ordered list, so a per-turn
    -- numbering would need the turn ordinal beside it in every ORDER BY and
    -- would make "the entry before this one" a two-column comparison.
    ordinal         INT    NOT NULL,

    -- What kind of thing this is. The classified set, and the CHECK below is
    -- what makes "unclassified" unwritable rather than merely undefined.
    kind            TEXT   NOT NULL,

    -- Which chat role this becomes when it is projected, or NULL for a kind that
    -- does not project.
    --
    -- DERIVABLE AND STORED ANYWAY, which is a thing this schema does nowhere
    -- else and needs its argument. It is stored because it is the projection
    -- rule, and the rule is what this table exists to hold: a person reading
    -- `SELECT kind, role FROM entries` can see which kinds reach a model without
    -- reading any Java, and `entries_role_matches_kind` below makes the two
    -- columns one fact rather than two that can disagree. The alternative -- a
    -- role computed in the projection alone -- puts the whole guarantee in one
    -- method and leaves the table saying nothing about it.
    role            TEXT,

    -- The text. Never NULL, and blank is legal for exactly one shape: an
    -- assistant turn that is entirely tool calls has empty content, which is one
    -- of the two ways `Completion` documents arriving with none, and an
    -- `answer` recording a model that stopped on its first token is the other --
    -- `Outcome` permits it, and `turns.answer` already does. Every other kind
    -- must say something; see the three CHECKs below.
    content         TEXT   NOT NULL,

    -- Which call this result answers. The correlation `ChatMessage.toolCallId`
    -- carries, and the reason a tool result is a message the model can place
    -- rather than a paragraph it has to infer from.
    tool_call_id    TEXT,

    -- What the model asked to call, as the JSON array `ToolCall` serialises to,
    -- or NULL for an answer that asked for nothing.
    --
    -- JSONB AND NOT TEXT, although nothing queries inside it today. The column
    -- holds a document with a shape this server both writes and reads back, and
    -- jsonb refuses a malformed one at the INSERT -- which is where a caller
    -- that concatenated a string wants to find out, rather than on the read that
    -- fails to build the request. `arguments` inside it stays a raw string,
    -- unparsed, exactly as `protocol.ToolCall` keeps it: the transport has no
    -- schema for an arbitrary tool and neither has this table.
    tool_calls      JSONB,

    -- The ordinal of the summary entry that folded this one away, or NULL for an
    -- entry no fold covers.
    --
    -- A FOLD IS AN APPEND AND NOT A REWRITE. `compactions` already says the same
    -- thing about `turns` -- "compacting rewrites what the MODEL is shown on the
    -- next turn and rewrites nothing a person can read" -- and this is that rule
    -- expressed inside the log: the summary arrives as a new row, the rows it
    -- stands for keep their text, their order and their ordinals, and the
    -- projection skips them. Nothing is ever deleted and no entry's content is
    -- ever edited.
    --
    -- IT IS WRITTEN AT MOST ONCE PER ROW. `EntryStore.supersede` names
    -- `superseded_by IS NULL` in its WHERE, so a second fold covering the same
    -- range leaves the first fold's answer standing rather than repointing it.
    -- Which summary superseded a row is a fact about when it happened, and a
    -- column that moved would lose it.
    superseded_by   INT,

    -- Which turn of the conversation produced this entry.
    --
    -- NO FOREIGN KEY TO `turns`, and this is the one place this file departs
    -- from the design's own sketch. It cannot have one. A `turns` row is written
    -- when a turn ENDS -- `agents.Turn.writeDown`, from inside the ending
    -- callback -- and entries are written as the turn RUNS, so every entry would
    -- be refused by a key pointing at a row that does not exist yet. Worse, the
    -- whole reason this table exists is the run that never gets there: a run
    -- that dies mid-tool-call leaves entries and no turn row at all, and that
    -- run is precisely the one a resumption is supposed to read. A key that
    -- refuses the case the table was built for is not an integrity rule, it is
    -- the feature deleted.
    --
    -- NOT NULL ANYWAY, because every entry belongs to some turn even when that
    -- turn never finished: it is the count of turns already in the conversation,
    -- plus one, which is what the turn's own ordinal will be if it lands.
    --
    -- FOR A SUMMARY ENTRY IT IS THE REACH, which is the same sentence read
    -- slightly differently: the last turn the entry stands for.
    -- `compactions.through_ordinal` holds the same number for the same fold, and
    -- the projection needs it here because the seam sentence names it.
    --
    -- IT IS READ. `EntryStore.supersede` folds `turn_ordinal <= reach`, and the
    -- projection renders a summary's seam from it. V7's rule about a column
    -- nothing reads applies and is satisfied.
    turn_ordinal    INT    NOT NULL,

    -- One entry per place in a conversation's log. Identity, and the backstop
    -- for the race `EntryStore.append` cannot rule out on its own: two writers
    -- that computed the same next ordinal collide here loudly instead of both
    -- landing.
    --
    -- It is the index the one read uses as well. `WHERE conversation_id = ?
    -- ORDER BY ordinal` is this key's leading column and then its second, so
    -- there is no separate index here, exactly as in V7.
    CONSTRAINT entries_one_per_place_in_a_conversation
        PRIMARY KEY (conversation_id, ordinal),

    CONSTRAINT entries_belong_to_a_conversation
        FOREIGN KEY (conversation_id) REFERENCES conversations (id),

    -- Numbered from one, so "the first entry" is one thing rather than a choice
    -- between 0 and 1 that two readers make differently. V7's sentence.
    CONSTRAINT entries_are_numbered_from_one CHECK (ordinal >= 1),

    CONSTRAINT entries_belong_to_a_turn_numbered_from_one CHECK (turn_ordinal >= 1),

    -- THE CLASSIFIED SET. An eighth kind is a row `agents.EntryKind.of` refuses
    -- to read back, so it would be written successfully and be unreadable for
    -- ever -- V5's argument about `memory_reasons.kind` and V7's about
    -- `turns.ending`, and the same consequence follows: adding a kind needs a
    -- migration, nothing holds this list and the Java enum together at compile
    -- time, and `EntryStoreTest.every_kind_this_server_can_write_is_an_entry_
    -- this_table_holds` is the seam that fails on the build that adds one
    -- without the other rather than on the first conversation to reach it.
    --
    -- The three that project are the three roles a conversation can carry.
    -- `summary` is the fourth and is the harness speaking: a seam is neither the
    -- person nor the model, and `Compaction` has always introduced one as a
    -- system message. The last three are recorded and invisible -- a refused or
    -- abandoned model call is a fact about the run that never reached the model,
    -- so replaying it would invent history; the repeat nudge is already
    -- delivered in-run, so projecting it would double it; and a plan is read
    -- deliberately rather than carried in every request.
    CONSTRAINT entries_kind_is_known CHECK (kind IN (
        'utterance',
        'answer',
        'tool_result',
        'summary',
        'attempt_failed',
        'runtime_note',
        'plan'
    )),

    -- THE PROJECTION RULE, AS A CONSTRAINT. Each projecting kind has exactly one
    -- role and each non-projecting kind has none, and the CASE has no ELSE
    -- because a kind it does not name yields NULL -- which this predicate then
    -- requires the row to hold. So a kind added to the list above without being
    -- given a role can only be written with role NULL, and a NULL role is
    -- exactly what the projection skips. The safety net and the constraint are
    -- the same mechanism.
    --
    -- IS NOT DISTINCT FROM and not `=`, because `=` is NULL for a NULL operand
    -- and a CHECK passes on NULL. Written with `=` this constraint would be
    -- satisfied by every non-projecting row whatever role it carried, which is
    -- the direction that leaks.
    CONSTRAINT entries_role_matches_kind CHECK (
        role IS NOT DISTINCT FROM CASE kind
            WHEN 'utterance'   THEN 'user'
            WHEN 'answer'      THEN 'assistant'
            WHEN 'tool_result' THEN 'tool'
            WHEN 'summary'     THEN 'system'
        END
    ),

    -- A tool result answers a call, and nothing else carries an id to answer
    -- with. Both directions in one predicate because they are one fact: a
    -- `tool_result` with no id is a result the model cannot place --
    -- `ChatMessage` refuses that message outright -- and an id on any other kind
    -- is a correlation to a call that kind never made.
    CONSTRAINT entries_a_tool_result_is_exactly_what_answers_a_call
        CHECK ((kind = 'tool_result') = (tool_call_id IS NOT NULL)),

    -- Only an answer asks for tools. The converse is not required: an answer
    -- that asked for nothing has NULL here, which is the ordinary shape of the
    -- last answer of a turn.
    CONSTRAINT entries_only_an_answer_asks_for_tools
        CHECK (tool_calls IS NULL OR kind = 'answer'),

    -- Nothing said is not an utterance. `turns_utterance_is_not_blank`, one
    -- table over, and `JobStore.submit` refuses a blank task before either.
    CONSTRAINT entries_an_utterance_is_not_blank
        CHECK (kind <> 'utterance' OR content <> ''),

    -- A blank tool result reads to a model as a tool that does not work.
    -- `ChatMessage` refuses one and `JobRuntime.usable` substitutes a sentence
    -- rather than passing one through, so this is those two rules arriving in
    -- the one place neither of them can reach.
    CONSTRAINT entries_a_tool_result_is_not_blank
        CHECK (kind <> 'tool_result' OR content <> ''),

    -- A blank summary is not a short history, it is a fold that lost everything
    -- and said nothing about it -- `compactions_summary_says_something`, said
    -- again about the row that now carries the same text.
    CONSTRAINT entries_a_summary_is_not_blank
        CHECK (kind <> 'summary' OR content <> ''),

    -- A fold covers what came before it. The summary is appended after the
    -- entries it stands for, so its ordinal is larger than every one of theirs;
    -- a row pointing at itself or forward-to-backward would be a log whose order
    -- said nothing.
    CONSTRAINT entries_a_fold_covers_what_came_before_it
        CHECK (superseded_by IS NULL OR superseded_by > ordinal),

    -- And it points at an entry that really exists, in this same conversation.
    -- Composite against this table's own primary key, so one key carries both
    -- halves of the claim -- the shape
    -- `compactions_reach_a_turn_that_was_spoken` takes against `turns`.
    --
    -- It does not say the target is a summary. Postgres cannot express that in a
    -- foreign key, and a trigger to say it would be a second mechanism guarding
    -- a rule one writer already holds.
    CONSTRAINT entries_are_superseded_by_an_entry
        FOREIGN KEY (conversation_id, superseded_by)
        REFERENCES entries (conversation_id, ordinal)
);

COMMENT ON TABLE entries IS
    'Every message a conversation has held, in order. A request to a model is a '
    'projection of these rows and never a construction, so what a model can be '
    'shown is exactly what is here. THIS TABLE HOLDS TOOL OUTPUT: file contents '
    'read from the operator''s disk, memory bodies, glob output, whole answers '
    'from delegated agents. The turns table deliberately holds none of that.';

COMMENT ON COLUMN entries.kind IS
    'What kind of thing this is. utterance, answer, tool_result and summary '
    'project into a request as user, assistant, tool and system; '
    'attempt_failed, runtime_note and plan are recorded and never reach a '
    'model. A kind with no role does not project, so a kind added without a '
    'decision is invisible rather than accidentally visible.';

COMMENT ON COLUMN entries.superseded_by IS
    'The ordinal of the summary entry that folded this one away, or NULL. The '
    'projection skips a superseded entry; nothing is deleted and no content is '
    'ever edited. Written at most once per row.';

COMMENT ON COLUMN entries.turn_ordinal IS
    'Which turn produced this entry, or for a summary the last turn it stands '
    'for. There is deliberately no foreign key to turns: a turn row is written '
    'when the turn ends and entries are written as it runs, and a run that died '
    'mid-call leaves entries and no turn row at all.';
