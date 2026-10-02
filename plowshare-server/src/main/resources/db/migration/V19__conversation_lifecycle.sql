-- Where a conversation has got to under the policy its origin chose, and what
-- is left of a tool result once the policy has run.
--
-- WHAT V17 LEFT AND WHY IT LEFT IT. That file made every run a conversation and
-- said so about its own consequence: "a curator pass over a few hundred memories
-- is now a few hundred conversations, each holding its tool results whole. That
-- volume is the owner's decision and is not mitigated here." This is the
-- mitigation. `entries` holds file bodies up to `files.FileTools
-- .MAX_DISPLAY_CHARS` -- 100 000 characters -- read off an operator's disk,
-- stored whole, kept for ever, and retrievable through `result_read` from
-- several machines under presence. Nothing in this server has ever removed one.
--
-- TWO COLUMNS ON TWO TABLES, AND THEY ARE TWO FACTS. `conversations.lifecycle`
-- is where a conversation has got to; `entries.ejected_at` is what happened to
-- one payload. It is tempting to keep only the first and read the second off it,
-- and that is wrong in the direction that loses information: a conversation
-- reaches `ejected` once, and the rows under it were nulled at an instant, into
-- an export, and a model asking `result_read` for one of them is asking about
-- the row and not about the conversation. `result_read` would otherwise have to
-- join to a root to answer "this was ejected on <date>", which is a join on the
-- one path that must stay a single indexed lookup.
--
-- IT DOES NOT SUBSUME `origin` EITHER, and the two will go on being tempting to
-- merge. `origin` says WHICH POLICY APPLIES and is written once and never
-- recomputed; the lifecycle says WHERE THIS CONVERSATION HAS GOT TO under it and
-- moves. A single column would have to spell every pair.
--
-- WHAT THIS FILE DELIBERATELY DOES NOT BUILD. No scheduler: this server has
-- none, the retention design says so about itself, and `archive.Retention` is
-- driven by an explicit operation an operator triggers. No learner and no
-- `learned_at` column -- ejection is not gated on learning, and the case that
-- settles it is that tool results do not push a fold at all, so the largest
-- payloads in the system are exactly the ones a fold-based rule would never
-- reach. And no memory vocabulary: the log and the memory archive are different
-- systems, a log may delete, and nothing here is named for or constrained by
-- what `memories` promises.

-- ---------------------------------------------------------------------------
-- 1. the lifecycle
-- ---------------------------------------------------------------------------
--
-- THE JAVA TYPE IS `archive.ConversationLifecycle` AND NOT `archive.Lifecycle`,
-- which is already taken by the memory archive's state transitions -- the very
-- class an earlier draft of this design borrowed from and was corrected away
-- from. That class promises "nothing here deletes anything"; this column exists
-- so that something can. The collision is left visible on purpose: a reader who
-- reaches for the wrong one meets a compile error rather than a guarantee that
-- does not hold here.

-- Where this conversation has got to:
--
--     active  ->  archived  ->  to_be_ejected  ->  ejected
--
-- ON THE ROOT OF A TREE AND NOWHERE ELSE, which is the decision this column is
-- shaped around. A delegated child carries NULL and resolves to its root's
-- state; the CHECK two paragraphs down is what makes that structural rather
-- than a rule somebody has to remember. The alternative -- a state on every row,
-- kept in step by whoever writes them -- has one failure mode and it is the
-- one the design names: AN ARCHIVED ROOT WITH LIVE BRANCHES. With a column on
-- every row that is a row somebody forgot to update; with the column only on
-- roots it is not a state the table can hold, because a branch has no state of
-- its own to be live in.
--
-- The cost is that reading a child's state is a walk up `parent_id` rather than
-- a column read. That walk is `ConversationStore.lifecycleOf`, it is bounded by
-- delegation depth, and it is the same shape the design asks for in words --
-- "resolve to the root of the tree, and the root's origin decides the whole
-- tree" -- said once in SQL instead of once per caller.
--
-- NULLABLE WITH A BACKFILL. Every conversation this table already holds is
-- either a root, which has got exactly as far as `active`, or a delegated child,
-- which has no state of its own -- so the backfill invents nothing.
--
-- AND THE DEFAULT STAYS, WHERE V17 DROPPED `origin`'s. That file dropped its own
-- because "a run with no origin is a run nobody classified": there is no correct
-- value to supply for a row that declined to say which door it came through.
-- Here there is. A conversation that has been opened has got exactly as far as
-- being open, whichever door it came through and whoever wrote the row, so
-- `active` is not a guess about an INSERT that said nothing -- it is the only
-- state such a row can be in. Every other state is somewhere a conversation is
-- MOVED to afterwards, by a person or by a sweep.
--
-- What makes the default safe rather than merely convenient is that it is WRONG
-- FOR EXACTLY ONE SHAPE and that shape is refused rather than defaulted: a
-- delegated child must carry no state at all, and an INSERT that omitted the
-- column for one is stopped by
-- `conversations_a_root_is_where_the_lifecycle_lives` below rather than landing
-- with a state its root will later disagree with. V10's boolean keeps its
-- default on the same terms.
ALTER TABLE conversations ADD COLUMN lifecycle TEXT DEFAULT 'active';

UPDATE conversations SET lifecycle = NULL WHERE parent_id IS NOT NULL;

-- THE CLASSIFIED SET, the shape `entries_kind_is_known`, `turns_ending_is_known`
-- and `conversations_origin_is_known` all take, for the reason they all give: a
-- fifth state is a row `archive.ConversationLifecycle.of` refuses to read back,
-- so it would be written successfully and be unreadable for ever. Nothing holds
-- this list
-- and the Java enum together at compile time; `ConversationStoreTest
-- .every_lifecycle_state_this_server_can_write_is_one_this_table_holds` is the
-- seam that fails on the build that adds one without the other.
--
-- FOUR STATES AND NOT THREE. `archived` earns its place by being the only one a
-- PERSON sets: it is somebody deciding they are done with a conversation, and it
-- is reversible. `to_be_ejected` is the mark that a policy has selected this
-- conversation and the payload has not gone yet, which is the whole of what the
-- staged path buys -- a window in which somebody can still say no.
ALTER TABLE conversations ADD CONSTRAINT conversations_lifecycle_is_known
    CHECK (lifecycle IS NULL OR lifecycle IN (
        'active',
        'archived',
        'to_be_ejected',
        'ejected'
    ));

-- The lifecycle lives on the root, and a root is what has no parent. Both
-- directions in one predicate because they are one fact: a root with no state
-- is a conversation nothing can decide about, and a child with a state of its
-- own is the divergence this column is shaped to make impossible.
--
-- WRITTEN AGAINST `parent_id` AND NOT AGAINST `origin`, although V17's
-- `conversations_a_delegation_is_what_has_a_parent` makes the two equivalent.
-- "The state lives on the root" is a claim about the TREE, and the column that
-- says what a tree is is this one; keying it off the origin would make a reader
-- chain two constraints to find out where a state is allowed to be.
ALTER TABLE conversations ADD CONSTRAINT conversations_a_root_is_where_the_lifecycle_lives
    CHECK ((parent_id IS NULL) = (lifecycle IS NOT NULL));

-- WHAT IS NOT HERE: THE TRANSITIONS. A CHECK cannot see the value a column is
-- moving away from, so the ordering this column is named for -- and the three
-- properties the design settles, that `archived` is optional rather than a gate,
-- that `archived -> active` and `to_be_ejected -> active` both reverse, and that
-- `ejected` is terminal -- cannot be a CHECK. The two ways to say it in the
-- database are a trigger and a conditional UPDATE, and this schema has already
-- chosen between them twice: V17 declines a trigger for cycles because it "would
-- be a second mechanism guarding a rule one writer already holds", and
-- `ConversationStore.turnEnded` holds "spending never falls" as
-- `WHERE budget_spent <= ?` on the UPDATE itself.
--
-- So `ConversationStore.moveTo` names the states a move may come FROM in its own
-- WHERE, from `ConversationLifecycle.reachedFrom`, and a move from anywhere else changes no
-- row and is reported as the refusal it is. That is enforcement in the database
-- -- two writers racing cannot both land -- without a second mechanism, and it
-- keeps the table of what may follow what in the one place a reader will look
-- for it.

-- The sweep's read: every root of one origin, in one state, oldest first.
-- Leads with the two columns the WHERE names and ends with the one the ORDER BY
-- and the age comparison do.
--
-- Partial, on `lifecycle IS NOT NULL`, which is exactly the roots. A delegated
-- child is never selected by a sweep -- it is reached through its root -- and in
-- a tree-heavy deployment the children are most of the table, so indexing them
-- here would be indexing the rows this query is defined never to return.
CREATE INDEX conversations_under_retention
    ON conversations (lifecycle, origin, created_at)
    WHERE lifecycle IS NOT NULL;

-- The walk this column costs: a child's parent, then its parent's. Without it
-- resolving a state means a sequential scan per level.
CREATE INDEX conversations_children ON conversations (parent_id)
    WHERE parent_id IS NOT NULL;

COMMENT ON COLUMN conversations.lifecycle IS
    'Where the tree rooted at this conversation has got to: active, archived, '
    'to_be_ejected, ejected. NULL on a delegated child, which resolves to its '
    'root -- one state per tree, never one per conversation. An archived or '
    'ejected conversation accepts no new turn and no resumption; see '
    'agents.Turn.';

-- ---------------------------------------------------------------------------
-- 2. the payload
-- ---------------------------------------------------------------------------

-- DEMOTE THE RECORD, EJECT THE PAYLOAD. What retention removes is
-- `entries.content` for `tool_result` rows and nothing else. The row stays.
--
-- Three reasons, and the first two are the ones that decide it:
--
--   * THE TRAJECTORY STAYS COMPLETE. Deleting rows gives gaps in `ordinal`,
--     dangling `superseded_by` references, and a history with holes in it. V11's
--     whole argument is that "a request to a model is a projection of these rows
--     and never a construction", and a projection over a log with holes is a
--     conversation that cannot be read back at all. Nulling `content` keeps
--     every fact about the call -- that it happened, which tool, how large, when,
--     how long it took, its handle -- so a six-month-old run is still reviewable;
--     what is gone is the ability to re-read the file it read.
--
--   * THE LIABILITY IS THE BYTES AND NOT THE RECORD. A row costs almost nothing.
--     A 100 000-character file body, kept for ever and redeemable, is the thing
--     that accumulates.
--
--   * A HANDLE THAT RESOLVES BEATS ONE THAT DANGLES. `result_read` on an ejected
--     payload must not answer not-found -- that is the confident-empty answer
--     this project keeps deleting, arriving through the harness -- and it can
--     only avoid it if the row it addresses is still there to answer from.
--
-- A STATE AND NOT A RELOCATION. The row does not move to another table. Moving
-- rows at demotion time is where data goes missing, and it would mean two
-- schemas to keep in step for one concept.

-- The text, and NULL now means one thing: it was ejected.
--
-- WHY NULL AND NOT THE EMPTY STRING. Blank content already means something in
-- this table -- V11 gives it to an assistant turn that is entirely tool calls --
-- and `entries_a_tool_result_is_not_blank` refuses it for a result outright,
-- naming the reason: "a blank tool result reads to a model as a tool that does
-- not work". An ejected payload that came back as blank would be exactly that
-- misreading, told to a model, by the harness. NULL is not a value a tool could
-- have returned, so nothing can mistake it for one.
--
-- THAT CONSTRAINT IS LEFT EXACTLY AS IT IS and this is not an oversight.
-- `kind <> 'tool_result' OR content <> ''` evaluates to NULL for a result with
-- NULL content, and a CHECK passes on NULL -- so it goes on refusing the empty
-- string, which is what it was written to refuse, and says nothing about the
-- absence, which is what the pair below is for.
ALTER TABLE entries ALTER COLUMN content DROP NOT NULL;

-- When the payload went.
--
-- ON THE ROW AND NOT ON THE CONVERSATION, for the reason the header gives: this
-- is what `result_read` reports, and reporting it means reading the row the
-- handle already addresses rather than joining up a delegation tree on the one
-- path that has to stay a single indexed lookup. It is also the more precise
-- fact -- a conversation reaches `ejected` once, and each payload under it was
-- nulled at an instant into a particular export.
ALTER TABLE entries ADD COLUMN ejected_at TIMESTAMPTZ;

-- Where the bytes went, as the exporter wrote it down, or NULL for a payload
-- ejected by a deployment that keeps no export.
--
-- A RECORD OF WHERE IT WAS PUT AND NOT A LIVE POINTER. An operator who moves
-- their export directory afterwards has moved it; this column still says where
-- the bytes were written at the time, which is the fact a person tracing one
-- actually needs. Nothing in this server resolves it, opens it or checks it --
-- an export that only Plowshare can find would be the export nobody can open,
-- which is the failure the design names.
ALTER TABLE entries ADD COLUMN export TEXT;

-- How large the payload was, kept after the payload is not.
--
-- WITHOUT THIS THE SIZE GOES WITH THE BYTES, and the size is one of the three
-- facts a stored-result line carries -- `Compaction.REFERENCE` and
-- `ResultTools.Listing` both report it, and it is what a model weighs a turn
-- against. `length(content)` answers NULL once the content does, so `result_list`
-- would show an ejected result with no size at all: a row that says less about
-- itself than the row beside it, in the one listing whose job is to let a model
-- choose between them.
--
-- It is also what makes the record still worth keeping. "A 96 000-character file
-- read, ejected on the 4th" is a fact about what a run did; "a file read,
-- ejected" is barely one.
--
-- COUNTED IN THE SAME UNITS `length(content)` WAS, which is Postgres characters
-- -- the exporter writes `length(content)` into this column at the moment it
-- nulls the other one, so the number a model sees does not change across the
-- ejection. `octet_length` would have been a different number for the same
-- result, and both `Compaction`'s reference line and this table's page reads
-- already report characters.
ALTER TABLE entries ADD COLUMN ejected_chars INT;

-- The size is part of the ejection and not an independent fact. A count with no
-- ejection is a claim about a payload that is sitting right there and could be
-- measured; an ejection with no count is the loss this column exists to prevent,
-- arriving through a writer that forgot it.
ALTER TABLE entries ADD CONSTRAINT entries_an_ejected_payload_keeps_its_size
    CHECK ((ejected_at IS NULL) = (ejected_chars IS NULL));

-- And it is a length. Negative is not a smaller result, it is a subtraction that
-- went the wrong way -- `entries_a_duration_is_not_negative`'s sentence one
-- column over.
ALTER TABLE entries ADD CONSTRAINT entries_an_ejected_size_is_a_length
    CHECK (ejected_chars IS NULL OR ejected_chars >= 0);

-- Gone and marked gone are one fact. A row with no content and no instant is a
-- payload that vanished with nothing saying when, which is indistinguishable
-- from corruption; a row with an instant and content still in it is a claim the
-- text was ejected while it is sitting there, which is the direction that would
-- make `result_read` lie to a model about a result it could have returned.
ALTER TABLE entries ADD CONSTRAINT entries_an_ejected_payload_is_gone
    CHECK ((content IS NULL) = (ejected_at IS NOT NULL));

-- Only a tool result is ejected. Everything else in this table is what a person
-- said, what a model answered, what a fold summarised or what the harness noted
-- -- bounded text this server wrote itself, and the record rather than the
-- liability. An utterance with its content ejected is a conversation whose
-- history has a hole in it, which is the outcome the whole shape of this section
-- is chosen to avoid.
ALTER TABLE entries ADD CONSTRAINT entries_only_a_tool_result_is_ejected
    CHECK (ejected_at IS NULL OR kind = 'tool_result');

-- An export nothing names is not an export. V1's `memories_project_named`,
-- restated for the reason it is always restated: the empty string is what an
-- omitted field arrives as, and a row holding one would send somebody looking
-- for a file at no path at all. The absence is the meaningful state -- a
-- deployment that keeps no export -- and it is spelled as an absence.
ALTER TABLE entries ADD CONSTRAINT entries_an_export_is_named
    CHECK (export IS NULL OR export <> '');

-- And an export belongs to an ejection. A path on a row whose payload is still
-- here is a claim that this text is also somewhere else, which nothing wrote and
-- nothing would maintain.
ALTER TABLE entries ADD CONSTRAINT entries_only_an_ejected_payload_was_exported
    CHECK (export IS NULL OR ejected_at IS NOT NULL);

-- The eject stage's read: the payloads of one conversation that are still here.
-- Partial on the two facts that select them, so the index holds only the rows a
-- sweep can act on and shrinks as they are ejected.
CREATE INDEX entries_payloads_still_held ON entries (conversation_id)
    WHERE kind = 'tool_result' AND ejected_at IS NULL;

COMMENT ON COLUMN entries.content IS
    'The text. NULL means the payload was ejected -- see ejected_at and export '
    '-- and never that a tool returned nothing; blank is what a tool returning '
    'nothing would be, and entries_a_tool_result_is_not_blank refuses that for '
    'a result. A reader that treats NULL as empty is telling a model a tool '
    'does not work.';
