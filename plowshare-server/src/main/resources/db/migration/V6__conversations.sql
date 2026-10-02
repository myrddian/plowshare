-- A conversation is the long-lived thing a person talks to: it owns a budget
-- that every turn spends from, and it outlives each of them. A job is one turn;
-- this row is what several turns share.
--
-- WHY THE BUDGET IS TWO COLUMNS AND NOT A TABLE. `agents.Budget` is a limit and
-- a count, shared by reference down a delegation tree -- `Curator.pass` shares
-- one across a whole pass of many rulings -- and a conversation's is the same
-- object one level up, shared across turns instead of across delegations. Two
-- integers are the whole of its state, so a second table would be a join for a
-- pair of ints and a second place for them to disagree. What this row does NOT
-- hold is the delegation tree's copy of it: a turn's children spend the object,
-- and the object is written back here when the turn ends.
--
-- WHAT IS NOT HERE. Turns. They are jobs, and a job is an in-memory handle
-- (`agents.JobStore` holds them in a map) -- so the foreign key would point at
-- something no table has. The turn table, if there is one, belongs to the task
-- that builds turns rather than to this one, and putting a column here for it
-- now would be a shape guessed ahead of its caller.
CREATE TABLE conversations (
    -- `cnv_` under `protocol.MemoryIds.mint`, which is the third caller of that
    -- scheme after `mem_` and `prp_` and is what its javadoc says the split
    -- exists for. Ten hex digits of millis from a 2020 epoch, zero-padded, so
    -- string order is minting order; the reads below still order by created_at
    -- first, because the clock is injected in tests and two conversations really
    -- can share an instant, at which point only the three random bytes separate
    -- their ids and that order means nothing.
    id            TEXT        PRIMARY KEY,

    -- The project, and NULL is the global tier -- `memories.project` exactly,
    -- for the same reason V1 gives: global is the absence of a project rather
    -- than a project named 'global', so no project can be named into becoming
    -- it.
    --
    -- NULLABLE IS A DECISION AND IT WENT AGAINST THE PLAN, which asked for "a
    -- conversation always has a project" as a CHECK. It is not true of this
    -- system. `api.AgentController.resolveHome` returns `Home.global()` for
    -- every submission that names no project, and `agents.JobStore.submit`
    -- accepts it -- so a global run is the ordinary shape of a caller who did
    -- not say which project, not a degenerate one. A NOT NULL here would make a
    -- conversation the only thing in this server that cannot be global, and the
    -- first person to open a REPL without naming a project would meet a refusal
    -- with no rule behind it. `ProposalStore.HOME_MATCHES` already carries the
    -- SQL for reading a nullable home (`IS NOT DISTINCT FROM CAST(? AS TEXT)`),
    -- which is the other half of the evidence that this tier is meant to be
    -- reachable by a query.
    --
    -- NO FOREIGN KEY to `projects (name)`, for the reason V3 gives about
    -- `memories.project` one column over: a project may hold conversations
    -- before it has a workspace -- most will, since a workspace is defined by an
    -- operator and a conversation by whoever starts talking -- and tying them
    -- would make talking depend on somebody having already run `define`.
    project       TEXT,

    -- Written by the store from an injected clock rather than DEFAULT now(),
    -- unlike `projects.defined_at`: that column is never read back, and these
    -- two are on `ConversationRecord`, so a test that asserts on them has to be
    -- able to choose them. Same shape as `memory_reasons.filed_at`.
    created_at    TIMESTAMPTZ NOT NULL,

    -- When the last turn ended, and NULL means there has not been one.
    --
    -- NULL rather than defaulting to created_at, for the reason V4 gives about
    -- `proposals.proposed_by`: "no turn yet" and "one turn, at the moment the
    -- conversation was opened" are different facts, and a default would destroy
    -- the distinction on exactly the rows where it is interesting -- a
    -- conversation somebody opened and never spoke into.
    last_turn_at  TIMESTAMPTZ,

    -- The two halves of `agents.Budget`: what it was built with, and what has
    -- been spent against it by every turn and everything those turns delegated
    -- to.
    budget_total  INT         NOT NULL,
    budget_spent  INT         NOT NULL DEFAULT 0,

    -- An id nothing can name is a conversation no turn can be submitted
    -- against. The same sentence `projects_name_named` and
    -- `memories_project_named` carry; the store refuses it first, and this stops
    -- anything that bypasses the store.
    CONSTRAINT conversations_id_named CHECK (id <> ''),

    -- Blank is not global and it is not a project either -- V1's
    -- `memories_project_named`, restated because the empty string is what an
    -- omitted request field arrives as and a row holding one would belong to a
    -- tier nobody can name.
    CONSTRAINT conversations_project_named
        CHECK (project IS NULL OR project <> ''),

    -- `Budget.of` refuses a non-positive limit, naming it: "a budget that may
    -- make no model calls is a job that can only end at its budget". A row
    -- holding zero would be a conversation whose every turn ends at
    -- CALL_BUDGET before it says anything, which reads to a person as a model
    -- that has stopped answering.
    CONSTRAINT conversations_budget_is_spendable CHECK (budget_total > 0),

    -- Separate from the ceiling below rather than folded into a single
    -- `budget_spent BETWEEN 0 AND budget_total`, for the reason V5 gives about
    -- splitting `memory_reasons_target_matches_kind` from
    -- `..._merge_lands_on_its_target`: one predicate reporting either fault
    -- makes Postgres name a rule without naming which half broke, and "spent
    -- went negative" and "spent ran past the limit" send a reader to opposite
    -- ends of the accounting.
    CONSTRAINT conversations_spending_is_not_negative CHECK (budget_spent >= 0),

    -- THE INVARIANT THE WHOLE TABLE IS FOR. `Budget.trySpend` is a
    -- compare-and-set that refuses at the limit, so Java cannot produce a row
    -- that breaks this -- which is precisely V2's and V5's argument for writing
    -- it down anyway: what holds it today is one method, and a second writer, a
    -- migration or a psql session does not go through it. A conversation whose
    -- spent exceeds its total is one that has already had more model calls than
    -- anybody granted it, and there is no reading of that row that is merely
    -- cosmetic.
    CONSTRAINT conversations_spent_within_budget CHECK (budget_spent <= budget_total),

    -- A turn cannot end before the conversation it belongs to was opened.
    -- Equality is allowed: the clock is injected, so a test that opens a
    -- conversation and ends a turn without moving it shares one instant, which
    -- is a fixture and not a fault -- the same allowance `memory_reasons` makes
    -- for two writes sharing `filed_at`.
    CONSTRAINT conversations_turn_is_not_before_the_conversation
        CHECK (last_turn_at IS NULL OR last_turn_at >= created_at)
);

-- The one read that filters: every conversation in one home, oldest first.
-- Leads with the column the WHERE names, then the one the ORDER BY does.
--
-- It indexes NULL projects along with the rest, which is the point -- a btree
-- holds NULLs, and the global tier is a real tier that this query really reads.
CREATE INDEX conversations_home ON conversations (project, created_at);

COMMENT ON COLUMN conversations.budget_spent IS
    'Model calls made against this conversation by every turn and everything '
    'those turns delegated to. Only ever increases; ConversationStore.turnEnded '
    'refuses a write that would lower it.';
