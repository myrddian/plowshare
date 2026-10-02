-- The promotion queue: what a curator thinks belongs in the global tier, and
-- what a human decided about it.
--
-- Nothing is ever deleted here either. A settled proposal is the record that
-- somebody was already asked this question, which is the whole of what stops a
-- nightly curator pass proposing the same memory every night forever.
-- OPEN, for whoever adds a column here: there is no `proposed_by`. A person
-- reading a pending row cannot tell a curator's question from anyone else's —
-- the reason text is all they get. Deferred by Task 9 and left open by Task 10;
-- `resolved_by` covers who ANSWERED, and the gap is only who ASKED. One column,
-- one ALTER TABLE, no data migration, because every existing row was filed by
-- the curator.
CREATE TABLE proposals (
    id          TEXT PRIMARY KEY,

    -- REFERENCES, because a proposal about a memory that was never written is
    -- not a queue entry, it is a curator with a bug — and the queue is read by
    -- a human who would have no way to tell the two apart. Safe as a hard
    -- constraint only because nothing in this schema deletes a memory in any
    -- state: a tombstone stays, so a proposal can outlive the claim it names.
    memory_id   TEXT        NOT NULL REFERENCES memories (id),

    -- Deliberately no `project` column. The tier is the memory's own and is
    -- read back through the join; a copy here would be a second spelling of one
    -- fact, and the one a caller could get wrong. `pending` and `ruledOn`
    -- filter on `memories.project` for that reason.

    -- What is being proposed. An open string rather than an enum, so a second
    -- kind of proposal does not need a migration — and the unique index below
    -- is on the pair, so two different questions about one memory can both be
    -- waiting.
    action      TEXT        NOT NULL,

    -- The curator's account of why. Required, unlike the human's account of
    -- their answer: a proposal nobody can justify is a question a human cannot
    -- act on, while a rejection with no comment is still a rejection.
    reason      TEXT        NOT NULL,

    state       TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,

    resolved_at TIMESTAMPTZ,
    resolved_by TEXT,
    resolution  TEXT,

    -- The three states and nothing else, for the reason V1 gives about
    -- memories: a fourth state is a row that is neither waiting nor settled,
    -- and so invisible to both reads.
    CONSTRAINT proposals_state_known
        CHECK (state IN ('pending', 'accepted', 'rejected')),

    -- A settled proposal says who settled it and when; a pending one cannot.
    -- Without this a half-written settlement — the state moved, the columns not
    -- — reads to a human as an answered question with nobody's name on it,
    -- which is exactly the thing the queue exists to record.
    CONSTRAINT proposals_settlement_matches_state
        CHECK ((state = 'pending'
                    AND resolved_at IS NULL AND resolved_by IS NULL AND resolution IS NULL)
            OR (state <> 'pending'
                    AND resolved_at IS NOT NULL AND resolved_by IS NOT NULL)),

    CONSTRAINT proposals_action_named CHECK (action <> ''),
    CONSTRAINT proposals_reason_given CHECK (reason <> ''),
    CONSTRAINT proposals_resolver_named CHECK (resolved_by IS NULL OR resolved_by <> '')
);

-- One waiting proposal per memory and action, held by Postgres and not by
-- Java. Two curator passes are two transactions, and a `SELECT ... WHERE state
-- = 'pending'` before the INSERT passes in both of them: neither can see the
-- other's uncommitted row, so both file one and the queue fills up with the
-- same question. Measured against pgvector/pgvector:pg16 on 2026-08-29 by
-- ProposalStoreTest.two_concurrent_proposals_for_one_memory_leave_one_row: with
-- this index the second transaction's insert blocks until the first commits,
-- and only then finds the conflict. Without it that test files two rows.
--
-- ProposalStore inserts with ON CONFLICT DO NOTHING rather than letting the
-- violation be raised, and the conflict target there has to repeat the WHERE
-- below verbatim — a partial index is only inferable from a predicate that
-- matches it. Changing this predicate means changing that one.
--
-- Partial, on `pending` alone, so a settled proposal does not make its question
-- unaskable forever. A rejection is *remembered* — that is `ruledOn`, which the
-- curator's triage applies — but a human who has seen three more projects hit
-- the same wall must be able to raise it again.
CREATE UNIQUE INDEX proposals_one_pending
    ON proposals (memory_id, action) WHERE state = 'pending';

-- `ruledOn` and `pending` both join memories on this column, and a proposal for
-- a memory is looked up by it whenever the queue is read.
CREATE INDEX proposals_memory ON proposals (memory_id);
