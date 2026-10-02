-- Every run gets a conversation, and this is what tells one kind from another.
--
-- WHAT CHANGED ABOVE THIS FILE, because the columns make no sense without it.
-- `agents.JobRuntime.run` is reached from four doors -- a person's turn, a
-- delegated child, a curator's ruling, and a submission that is nobody's turn --
-- and only the first kept a log. Every other passed `agents.Transcript.NONE`,
-- which returns an empty history and no-ops the prompt measurement. The
-- consequence was not a missing nicety: a delegated child wrote no entries, so
-- there was no trajectory; it measured no prompt, so it could not compact AT
-- ALL, and a child that read several large files accumulated until the endpoint
-- refused it; it stored no results, so `result_read` was meaningless there; and
-- it could not be resumed, because there was no log to resume from. A
-- `code_reviewer` run left no trace of what it did -- the parent's log held the
-- `agent_run` call and the result, and nothing in between.
--
-- So a child run becomes conversation-shaped. That is not a new mechanism: the
-- entries log is the only trajectory machinery this server has and it is keyed
-- by conversation, so making a run a conversation is what gives it compaction,
-- references, resumption and a readable trajectory with no second code path.
--
-- THE OBJECTION THIS HAS TO SURVIVE is that a child's entries must never reach
-- the parent's prompt; the whole justification for delegation is that the caller
-- does not hold the callee's noise. A separate conversation answers it
-- STRUCTURALLY -- separate log, separate projection, no filter anywhere. The
-- alternative, child entries in the parent's log excluded by ownership, would
-- add a second axis to a projection rule that today says KIND DECIDES, and V11
-- spends its header explaining why that rule is load-bearing.
--
-- WHAT THIS FILE DELIBERATELY DOES NOT BUILD. There is no retention here: no
-- lifecycle column, no archived/ejected states, no payload nulling, no export.
-- The design those belong to is written down
-- (implementation rationale
-- from section 4) and it is a separate task with its own argument. What IS worth
-- recording here is that this file is what makes that one urgent rather than
-- theoretical: a curator pass over a few hundred memories is now a few hundred
-- conversations, each holding its tool results whole. That volume is the
-- owner's decision and is not mitigated here.

-- ---------------------------------------------------------------------------
-- 1. origin
-- ---------------------------------------------------------------------------

-- Which door this conversation came through.
--
-- WHY IT IS A COLUMN AND NOT DERIVED FROM `parent_id`. A nullable parent is not
-- enough to decide anything, because a submission is parentless too and is not a
-- person's conversation. `GET /v1/conversations` is what needs the difference
-- first: it answers a console's opening screen, and filtering on "has no parent"
-- would fill a person's listing with every one-shot the machine has ever run.
--
-- WHY IT IS WRITTEN WHEN THE ROW IS WRITTEN AND NEVER DERIVED LATER. Two
-- reasons, and the second is the one that makes it permanent. A conversation's
-- origin decides which listing it belongs in AND which retention policy will
-- eventually apply to it; once a payload has been exported and nulled the row
-- cannot be reclassified, so whatever it said it was is what it stays. A column
-- computed at read time from whatever the code believes today is a column whose
-- value changes when the code does.
--
-- NOT NULL WITH A BACKFILL, and 'turn' is not a guess. Every conversation this
-- table holds was opened by `POST /v1/conversations` -- that was the only writer
-- there has ever been -- and every one of them is a person's. The DEFAULT is
-- dropped immediately afterwards so that no later INSERT can decline to say
-- which door it came through: V10's boolean keeps its default because "did not
-- decide" is a real state of a turn cap, and there is no comparable state here.
-- A run with no origin is a run nobody classified.
ALTER TABLE conversations ADD COLUMN origin TEXT NOT NULL DEFAULT 'turn';

ALTER TABLE conversations ALTER COLUMN origin DROP DEFAULT;

-- THE CLASSIFIED SET, and the shape `entries_kind_is_known` and
-- `turns_ending_is_known` take for the same reason: a fifth origin is a row
-- `archive.Origin.of` refuses to read back, so it would be written successfully
-- and be unreadable for ever. Adding one needs a migration, nothing holds this
-- list and the Java enum together at compile time, and
-- `ConversationStoreTest.every_origin_this_server_can_write_is_one_this_table_
-- holds` is the seam that fails on the build that adds one without the other
-- rather than on the first conversation to reach it.
--
-- TWO NAMES THE DESIGN ASKED FOR ARE NOT HERE, and leaving them out is the
-- decision rather than an oversight. Both would have been values nothing can
-- write, in a constraint whose entire purpose is that a value nobody has given
-- behaviour to cannot be written.
--
--   'schedule' describes a surface that does not exist. There is no scheduler in
--   this server -- the reference harness has cron and we do not -- so a row can
--   never carry it. The design says so about itself ("this document should not
--   later read as though it describes something built"), and a CHECK is the
--   worst place to be aspirational: an operator reading `\d conversations`
--   would be told this server has scheduled runs, by the schema, in the voice
--   the schema uses for facts.
--
--   'mcp' is the more interesting absence and it is a measurement rather than a
--   preference. The design counts MCP `agent_run` as a fifth surface; from
--   inside this server it is not one. `client.tools.AgentTools.run` calls
--   `POST /v1/agents/{name}/runs` with no conversation and no session -- the
--   same request a curl, a script or a CI job makes -- and by the time it
--   reaches `agents.JobStore.submit` there is nothing in it that says a foreign
--   harness is on the other end. So 'mcp' could not be STORED at write time, and
--   the paragraph above is exactly why that disqualifies it: a value that has to
--   be guessed later is not this column. Worse, the retention design gives `mcp`
--   its own disposition -- looser, ejected to cold storage by policy -- so
--   labelling every one-shot 'mcp' would apply a foreign harness's policy to a
--   person's own script.
--
-- 'submission' is what that door can honestly say about itself: a run started on
-- its own behalf, by whoever could reach the endpoint, that nobody is going to
-- speak to again. If the MCP client ever names itself on the wire, that is a
-- field on the request, a value in this list, and one migration -- and it will
-- be a fact the caller stated rather than one this server inferred.
ALTER TABLE conversations ADD CONSTRAINT conversations_origin_is_known
    CHECK (origin IN ('turn', 'delegation', 'curator', 'submission'));

-- ---------------------------------------------------------------------------
-- 2. the parent
-- ---------------------------------------------------------------------------

-- The conversation that delegated to this one, or NULL for a root.
--
-- One column is the whole of the tree, which is what makes the call stack
-- browsable: person asked X -> interlocutor -> delegated to code_reviewer ->
-- which read these files, as conversations joined by this.
--
-- SELF-REFERENTIAL AND NOT A SEPARATE EDGE TABLE. The relationship is
-- functional -- a conversation has at most one delegator -- so an edge table
-- would be a join that can hold a shape the domain cannot: two parents for one
-- child, which no run can produce and which every reader would then have to
-- decide what to do about.
--
-- NO ON DELETE CLAUSE, on V6's and V7's terms exactly: nothing in this schema
-- deletes a conversation, so there is no delete path to choose a behaviour for,
-- and a CASCADE written ahead of one would be a decision to discard a person's
-- history taken silently by whoever first writes that path. The retention design
-- (section 4) is explicit that its disposition is to null a payload and keep the
-- row, so the path that eventually exists is not a delete either.
ALTER TABLE conversations ADD COLUMN parent_id TEXT;

ALTER TABLE conversations ADD CONSTRAINT conversations_a_child_names_a_conversation
    FOREIGN KEY (parent_id) REFERENCES conversations (id);

-- Delegation and a parent are the same fact, so they are one predicate. A
-- delegated child with no parent is a tree with a severed branch -- and it is
-- precisely the row that breaks the retention rule the design states, that a
-- child "resolves to the root of the tree, and the root's origin decides the
-- whole tree". A parent on any other origin is a claim that a person's
-- conversation, or a curator's ruling, was delegated to by something; nothing
-- can produce one and no reader has a meaning for it.
ALTER TABLE conversations ADD CONSTRAINT conversations_a_delegation_is_what_has_a_parent
    CHECK ((origin = 'delegation') = (parent_id IS NOT NULL));

-- A conversation is not its own parent. The one-row cycle is the only one a
-- CHECK can see -- Postgres cannot express "no cycles" without a trigger, and a
-- trigger to say it would be a second mechanism guarding a rule one writer
-- already holds -- and it is also the only one this server could produce, since
-- a child's id is minted after its parent's is read. What this stops is the
-- longer-range version of that mistake: a caller that passed the child's own id
-- where the delegator's belonged would otherwise write a conversation that is
-- its own whole ancestry, and every walk up the tree would hang.
ALTER TABLE conversations ADD CONSTRAINT conversations_nothing_delegated_to_itself
    CHECK (parent_id IS NULL OR parent_id <> id);

-- ---------------------------------------------------------------------------
-- 3. the agent
-- ---------------------------------------------------------------------------

-- Which agent this conversation is, when it is one agent's.
--
-- WHY THIS COLUMN NOW, WHEN `api.ConversationController` HAS ARGUED AGAINST IT
-- SINCE CONVERSATIONS EXISTED. Its javadoc says a conversation names no agent,
-- because the agent is named per turn -- in the path of
-- `POST /v1/agents/{name}/runs` -- which is what lets one person open one
-- conversation and put two different agents' turns in it. That is still right,
-- and this column does not contradict it: see the CHECK below, which makes a
-- person's conversation exactly the one that names no agent.
--
-- What HAS expired is the second half of the sentence, "pinning an agent onto
-- the row would be a column with no reader today". There are readers now:
--
--   * `POST /v1/conversations/{id}/resume` takes an agent in its body BECAUSE
--     nothing recorded it -- `ResumeRunRequest.agent` says so in as many words
--     -- which is a caller supplying a fact the server already has and could
--     supply wrongly with nothing noticing;
--   * `GET /v1/conversations/{id}/context?agent=` does the same, and
--     `ContextView` names "nothing records which agent answered a turn" as the
--     reason it refuses to split a measurement;
--   * and a delegated child, a curator's ruling and a submission each have
--     EXACTLY ONE agent for the whole life of the conversation, which is a fact
--     about the row and not about a turn in it.
--
-- NULLABLE, AND THE NULL IS THE PERSON'S CASE rather than an unknown. It is the
-- shape `conversations.project` takes one column over: the absence is the
-- meaningful state and not a gap waiting to be filled.
ALTER TABLE conversations ADD COLUMN agent TEXT;

-- Blank is not an agent and it is not "no agent" either -- V1's
-- `memories_project_named`, restated for the reason it is always restated: the
-- empty string is what an omitted field arrives as, and a row holding one names
-- an agent nothing can look up.
ALTER TABLE conversations ADD CONSTRAINT conversations_agent_is_named
    CHECK (agent IS NULL OR agent <> '');

-- THE CONSTRAINT THAT KEEPS THE CONTROLLER'S ARGUMENT TRUE. A person's
-- conversation names no agent, because a person may put two agents' turns in it;
-- every other origin is one agent's run from end to end, and a row that did not
-- say which would be a conversation nothing could resume or price. Both
-- directions in one predicate because they are one fact, and `=` between two
-- booleans rather than two implications so that neither half can be relaxed
-- without the other being looked at.
--
-- WHICH AGENT ANSWERED A PARTICULAR TURN IS NOT THIS COLUMN and is `turns.agent`
-- below. The two say different things and both are wanted: this one is what the
-- whole conversation is, and that one is what one turn was.
ALTER TABLE conversations ADD CONSTRAINT conversations_a_person_s_conversation_names_no_agent
    CHECK ((origin = 'turn') = (agent IS NULL));

-- ---------------------------------------------------------------------------
-- 4. the budget, which is where this quietly goes wrong
-- ---------------------------------------------------------------------------

-- THE ONE THING THIS MIGRATION HAD TO GET RIGHT. V6 gave this table
-- `budget_total` and `budget_spent` and spent a paragraph on why they are two
-- columns and not a table -- "shared by reference down a delegation tree ...
-- what this row does NOT hold is the delegation tree's copy of it". That
-- sentence was written when a delegation had no row. It has one now, and the
-- five allowance stories this server actually has are:
--
--   a turn            spends the conversation's, read off this row;
--   a delegated child spends its PARENT'S, by reference, down the whole tree;
--   a curator ruling  spends one `Budget.of(curator-budget)` shared across a
--                     whole pass of many rulings, which is not a conversation
--                     and has no row anywhere;
--   a submission      spends one built from the definition's max-model-calls.
--
-- A child row carrying a copy of the numbers would be a SECOND allowance of the
-- same size, and it would double the accounting the first time anything summed
-- this column -- a "spent" total counting every model call once for the child
-- and again for the parent that shares the object. Worse, it would read
-- correctly: both rows would hold plausible numbers.
--
-- THE ANSWER IS STRUCTURAL AND NOT A CONVENTION. A conversation that spends an
-- allowance it does not own holds NO NUMBERS AT ALL. There is no second budget
-- to double-count because the columns are empty, and the CHECK below forbids
-- filling them. `archive.ConversationRecord.budget()` is null for such a row and
-- `agents.Turn.speak` refuses to speak into one, so a caller cannot reach a
-- shared allowance through the wrong door either.
--
-- NULLABLE, WHICH MEANS RELAXING TWO NOT NULLs V6 WROTE. That is the cost and it
-- is worth naming: V6's three budget CHECKs are all NULL-tolerant already --
-- `budget_total > 0`, `budget_spent >= 0` and `budget_spent <= budget_total` all
-- pass on a NULL operand, because a CHECK passes when its predicate is unknown
-- -- so every rule V6 wrote about a budget that IS there still holds exactly as
-- written, and none of them had to be dropped and restated.
--
-- THE DEFAULT GOES WITH THE NOT NULL. `budget_spent DEFAULT 0` would otherwise
-- write a zero into a row that is supposed to hold nothing, which is the one
-- value that makes "shares its parent's allowance" look like "has spent none of
-- its own".
ALTER TABLE conversations ALTER COLUMN budget_total DROP NOT NULL;

ALTER TABLE conversations ALTER COLUMN budget_spent DROP NOT NULL;

ALTER TABLE conversations ALTER COLUMN budget_spent DROP DEFAULT;

-- An allowance is both halves or neither. A total with no count is a limit
-- nothing is measured against; a count with no total is spending with no
-- ceiling, which is what `conversations_spent_within_budget` cannot catch
-- because that predicate is unknown -- and therefore satisfied -- when the total
-- is NULL. This is the rule that keeps that one honest.
ALTER TABLE conversations ADD CONSTRAINT conversations_an_allowance_is_both_halves_or_neither
    CHECK ((budget_total IS NULL) = (budget_spent IS NULL));

-- And which origins own one. A turn's conversation owns the allowance its turns
-- spend -- that is what `POST /v1/conversations` asks for and has no default
-- for. A submission owns one built from the agent's own `max-model-calls`, which
-- is what `JobStore.submit` has always built for a run nobody will speak to
-- again. A delegation and a curator's ruling own nothing: the first spends its
-- parent's and the second spends the pass's, and neither object is this row's to
-- record.
--
-- WHY THIS IS TIED TO `origin` AND NOT TO `parent_id`, which was the first
-- shape and is wrong. A curator's ruling has no parent -- a pass is a job and
-- not a conversation, so there is nothing for it to point at -- and it shares an
-- allowance all the same. "Is a root" and "owns its allowance" are two different
-- questions and only one of them is answered by the parent column.
ALTER TABLE conversations ADD CONSTRAINT conversations_an_allowance_is_owned_or_shared
    CHECK ((origin IN ('turn', 'submission')) = (budget_total IS NOT NULL));

-- ---------------------------------------------------------------------------
-- 5. the listing's index
-- ---------------------------------------------------------------------------

-- `GET /v1/conversations` now reads one tier's PERSON'S conversations, so the
-- index leads with the two columns its WHERE names and then the one its ORDER BY
-- does -- V6's own rule for the index this replaces, with the new column in the
-- middle where the equality predicate is.
--
-- REPLACED RATHER THAN ADDED BESIDE. Two indexes whose leading column is the
-- same would be one index and a copy of its first level, paid for on every
-- insert, and the query that the old one served no longer exists: nothing reads
-- a home's conversations without also saying which kind it wants.
DROP INDEX conversations_home;

CREATE INDEX conversations_home ON conversations (project_id, origin, created_at);

-- ---------------------------------------------------------------------------
-- 6. which agent answered one turn
-- ---------------------------------------------------------------------------

-- The agent that answered this turn.
--
-- WHY IT BELONGS HERE AS WELL AS ON `conversations`, and the two are not a
-- duplication. A TURN has exactly one agent BY CONSTRUCTION -- it is in the path
-- of `POST /v1/agents/{name}/runs`, so there is no run of a turn that does not
-- know it -- while a CONVERSATION has one only when it is not a person's. A
-- root conversation may hold several agents' turns and a delegated child has
-- exactly one for its whole life, so:
--
--   * putting the column only on `conversations` would make it NULL for exactly
--     the conversations `resume` reads it for, since those are the person's;
--   * putting it only on `turns` would say nothing about a child that has not
--     had a turn yet, which is every child between the moment it is opened and
--     the moment its one run finishes -- and a run that dies mid-call never
--     writes a `turns` row at all (V11's header, and the case the entries log
--     exists for);
--   * so both, saying two different things: `conversations.agent` is what the
--     whole conversation is, `turns.agent` is who answered one question in it.
--
-- WHAT READS IT. `POST /v1/conversations/{id}/resume` continues a stopped run,
-- and the agent it must continue with is the one that ANSWERED that run -- a
-- per-turn fact, and the wrong one to take from a caller who can get it wrong
-- while a history written by another agent stands behind it.
--
-- NULLABLE, AND THE NULL IS EXACTLY "WRITTEN BEFORE THIS MIGRATION". There is no
-- honest backfill: `turns` has never held the agent, `entries` does not carry it
-- either, and `agents.JobStore` mints job ids from a per-process counter that
-- restarts at job_000001 on every boot -- V7's own argument for why there is no
-- job id column here -- so there is nothing anywhere to recover it from. A
-- backfill to the interlocutor's name would be a guess written down as a fact,
-- on precisely the rows a resumption would then act on. NOT NULL is not
-- available and inventing a value to make it available is worse than the null.
ALTER TABLE turns ADD COLUMN agent TEXT;

-- Blank is not an agent, on `conversations_agent_is_named`'s terms exactly.
ALTER TABLE turns ADD CONSTRAINT turns_agent_is_named
    CHECK (agent IS NULL OR agent <> '');

-- ---------------------------------------------------------------------------
-- 7. what the catalogue says
-- ---------------------------------------------------------------------------

COMMENT ON COLUMN conversations.origin IS
    'Which door this conversation came through: turn (a person, through POST '
    '/v1/conversations), delegation (a child a run started with agent_run), '
    'curator (one ruling of a curator pass), submission (a run started on its '
    'own behalf through POST /v1/agents/{name}/runs with no conversation). '
    'Written when the row is written and never derived later, because it '
    'decides which listing the row belongs in and which retention policy will '
    'apply to it.';

COMMENT ON COLUMN conversations.parent_id IS
    'The conversation that delegated to this one, or NULL for a root. Exactly '
    'the rows with origin delegation have one. A child''s entries are in its '
    'own log and never in its parent''s prompt -- that separation is what this '
    'column exists to make structural rather than a filter.';

COMMENT ON COLUMN conversations.agent IS
    'The one agent this conversation is, or NULL for a person''s conversation, '
    'which may hold several agents'' turns. Which agent answered a particular '
    'turn is turns.agent and is a different fact.';

COMMENT ON COLUMN conversations.budget_total IS
    'The allowance every turn in this conversation spends from, or NULL for a '
    'conversation that spends an allowance it does not own -- a delegated child '
    'spends its parent''s by reference, a curator''s ruling spends the pass''s. '
    'NULL is how this table refuses to hold a second copy of a shared budget, '
    'which would double the accounting the first time anything summed it.';

COMMENT ON COLUMN turns.agent IS
    'The agent that answered this turn, or NULL for a turn written before V17, '
    'when nothing recorded it and nothing can recover it. POST '
    '/v1/conversations/{id}/resume reads this rather than taking the agent from '
    'its caller.';
