-- Why each write was filed the way it was, kept.
--
-- The spec's sentence, which nothing delivered until now: every scribe
-- fallback "says so in the reason ... So a year later the archive can
-- distinguish 'no scribe judged this' from 'the scribe was busy'."
-- `Archive.applyVerdict` put `verdict.reason()` into the returned WriteResult
-- and nowhere else, so the seventeen distinct sentences the scribe can produce
-- reached an HTTP response and the client's renderer and were gone the instant
-- that response was read.
--
-- A TABLE and not a column on `memories`, and the obstacle is a fact about
-- merges rather than a preference about effort. `Archive.merge` writes no new
-- row: it appends to the TARGET through `Lifecycle.mergeBody`, saves that, and
-- returns the target's own id. So a MERGED_INTO verdict has no new record for a
-- column to sit on, and putting it on the target would overwrite whatever that
-- memory's own write recorded, months earlier -- and "the scribe merged this
-- deliberately" against "no scribe judged this" is exactly the distinction the
-- spec cares about most.
--
-- Append-only, and that is what settles the first of the three questions 3a
-- left open. A MERGE ATTACHES TO THE TARGET: a second row against a memory
-- formed months ago ADDS to its history where a column would have replaced it,
-- so the objection that killed the column does not reach this shape. The
--
-- APPEND-ONLY IS NOT ENFORCED HERE, and since the merge decision rests on it
-- that is worth saying rather than implying. No constraint in this file stops
-- an UPDATE or a DELETE; what holds it is that `ReasonLog` has exactly two
-- statements -- an INSERT and a SELECT -- and its `record` is package-private
-- with `Archive.applyVerdict` as the only caller. A rule or a trigger could
-- hold it in the database, and is not worth a mechanism this schema uses
-- nowhere else for a table one class writes.
--
-- alternative -- attaching to "the proposal that never became a row" -- has no
-- id anything could join to, and minting one would create a durable identifier
-- for something the archive does not hold.
--
-- A DEMOTION EARNS NO ENTRY, which is the second question. Three reasons, and
-- the second is the one that would change if it stopped holding: this log's
-- subject is the judgement of a write, and a demotion is not one; a demotion's
-- reason is a constant -- the tier was over threshold and this record scored
-- lowest -- so the column would carry no information, while the state itself is
-- already on the memory row; and `demoteOverThreshold` runs on EVERY write, so
-- the table would grow with index churn rather than with judgements and bury
-- the rows it exists for. If a demotion ever gains a reason that varies, this
-- is where it goes.
--
-- IT READS BY MEMORY AND NOT PER TIER, which is the third. "Can this memory's
-- filing be explained a year later" is the question the spec asks and the only
-- one with a caller. A per-tier read is not foreclosed: `memories.project` is
-- one join away, exactly as `ProposalStore.pending` and `ruledOn` already do
-- it, which is why there is no denormalised project column here to get wrong.
CREATE TABLE memory_reasons (
    -- A surrogate key, because nothing references a row here by identity and
    -- (memory_id, filed_at) is not unique: the archive's clock is injected in
    -- tests, so two writes really can share an instant. It also gives
    -- `ORDER BY filed_at, id` a stable tiebreak, which a bare instant does not.
    id        BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    -- The memory this write landed in -- the id the caller was told. For a
    -- merge that is the TARGET, per the note above; for NEW and SUPERSEDES it is
    -- the record just minted. REFERENCES for the reason V2 gives about
    -- proposals: a reason about a memory that was never written is not a record,
    -- it is a bug, and nothing in this schema deletes a memory in any state.
    memory_id TEXT        NOT NULL REFERENCES memories (id),

    filed_at  TIMESTAMPTZ NOT NULL,

    kind      TEXT        NOT NULL,

    -- The memory the VERDICT named, and null for NEW, which names nothing. One
    -- rule in all three cases rather than a special case: for a merge this
    -- equals memory_id, and the redundancy is cheaper than a column that means
    -- different things depending on the row beside it. Both halves of that are
    -- CHECKed below; this comment used to assert the second "by construction",
    -- which is a claim about one Java method rather than about the column.
    target_id TEXT        REFERENCES memories (id),

    -- NOT NULL because `Verdict` already requires it -- the record's own
    -- constructor rejects a null reason, so a row without one could only come
    -- from outside Java. Deliberately no `<> ''` check, unlike every other
    -- non-blank TEXT column in this schema: `Verdict` permits a BLANK reason,
    -- and a constraint refusing one would make this log able to fail a write,
    -- which is the opposite of the rule the scribe is built on.
    reason    TEXT        NOT NULL,

    -- The three verdict kinds and nothing else, for the reason V1 gives about
    -- memory states: a fourth would be a row `VerdictKind.fromWireName` refuses
    -- to read back, so it would be written successfully and then be unreadable
    -- for ever. Adding a fourth kind therefore needs a migration, and that is
    -- correct rather than a cost.
    CONSTRAINT memory_reasons_kind_known
        CHECK (kind IN ('new', 'merged_into', 'supersedes')),

    -- The kind and the target agree, which the comment on target_id above
    -- stated twice in prose and nothing held. This is the rule V2 declined to
    -- leave to Java for the same reason: proposals_settlement_matches_state
    -- exists because "a half-written settlement reads to a human as an answered
    -- question with nobody's name on it", and a `merged_into` row with no
    -- target reads to that same human as a merge into nothing. Only
    -- Archive.applyVerdict's ternary kept these true, and a second writer --
    -- or a psql session -- would not go through it.
    CONSTRAINT memory_reasons_target_matches_kind
        CHECK ((kind = 'new') = (target_id IS NULL)),

    -- And the half the target_id comment claimed held "by construction": a
    -- merge writes no new row, so the row it lands in IS the target. Separate
    -- from the constraint above rather than folded into it, so that Postgres
    -- names which of the two a bad row broke -- one predicate reporting either
    -- fault is the shape V2's own state checks avoid.
    CONSTRAINT memory_reasons_merge_lands_on_its_target
        CHECK (kind <> 'merged_into' OR target_id = memory_id)
);

-- The one read there is: everything filed against one memory, oldest first.
CREATE INDEX memory_reasons_memory ON memory_reasons (memory_id, filed_at);
