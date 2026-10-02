-- The conversation log by its WORDS, which is the one thing `entries` has never
-- been askable about.
--
-- This file is written once. `MigrationsAreImmutableTest` freezes every
-- migration that is on `master`, by asking git for `master`'s blobs, so the
-- decisions below cannot be edited afterwards -- they can only be added to by a
-- later migration.
--
-- WHAT WAS ACTUALLY MISSING, said plainly because the gap is easy to understate.
-- `entries` has carried a person's every utterance, a model's every answer, every
-- tool result and every fold's summary since V11, and the only ways in are the
-- primary key and a scan: `EntryStore.forConversation` reads one conversation
-- whole, `pageOfLog` reads a window of one in order. There is NO index of any
-- kind on `content` and never has been. A question of the form "where was this
-- said" has had exactly one answer available to it -- read every conversation
-- back and look -- which is why `implementation rationale` §3.3 has stood open since the log
-- was built.
--
-- V21 DID THIS JOB FOR `chunks` AND PROVED THE INFRASTRUCTURE. Everything below
-- that is not about `entries` specifically is that file's argument, deliberately
-- not repeated at length: the column is generated rather than written, the
-- configuration is a literal rather than a session setting, the index is GIN
-- rather than GiST, and the query side must spell the same configuration or
-- matching quietly stops. Read V21 for those; what follows is what is different
-- about a log.

-- ---------------------------------------------------------------------------
-- 1. the column
-- ---------------------------------------------------------------------------

-- The entry's text as lexemes: stemmed, stopped, and positioned.
--
-- GENERATED ALWAYS AS ... STORED, for V21's reason and one more that only
-- applies here. That file's argument is that a plain column filled by the store,
-- or a trigger, admits a state where the text says one thing and the index says
-- another -- a divergence with NO SYMPTOM. This table makes that argument
-- sharper: `EntryStore.append` is called from inside a running turn, on the
-- path that also writes tool results and answers, and it is the one write in this
-- server that must not acquire a second reason to fail or a second thing to
-- remember. A generated column adds neither. It also needs no backfill door and
-- no repair pass, which matters more here than it did for `chunks`, because
-- `chunks` can be re-ingested from a document that is still on disk and a
-- conversation cannot be re-said.
--
-- NULL CONTENT IS THE THING THIS COLUMN HAS THAT `chunks.text_search` DOES NOT,
-- and it is the whole of what had to be decided rather than copied. V19 dropped
-- `NOT NULL` from `content` so a retention sweep can eject a payload and keep the
-- row -- the state `result_read` reports as "this was ejected on <date>; it is in
-- <export>". `to_tsvector` of NULL is NULL, so an ejected row carries no lexemes,
-- is absent from the index below, and can never be a hit.
--
-- THAT IS CORRECT AND IT IS NOT SUFFICIENT. It is correct because the bytes are
-- genuinely gone: there is nothing to match on and nothing to rank, and a row
-- that came back as a hit with an empty snippet would be the harness telling a
-- reader that a hundred-thousand-character file read said nothing -- V19's own
-- sentence about why the absence is NULL and not the empty string. It is not
-- sufficient because a row that is present and unfindable, with nothing saying
-- why, is the confident-empty answer this project keeps deleting. So the read
-- COUNTS them: `EntryStore.search` answers with how many entries it searched,
-- how many were ejected, and how many are of a kind that is recorded and never
-- said -- `DocumentStore.coverage`'s pair, at log scale and with three numbers
-- because there are three ways to be out of reach. The three partition the tier
-- exactly, so a reader can check the arithmetic instead of trusting it, and
-- `LogSearchTest` asserts that they do.
--
-- THE COLUMN IS COMPUTED FOR EVERY KIND AND THE READ SEARCHES FOUR OF THEM,
-- which is deliberately the same shape V21 chose for `embedding IS NOT NULL`.
-- The kinds that carry a role -- utterance, answer, tool_result, summary -- are
-- the conversation; attempt_failed, runtime_note, plan and diagnostic are
-- recorded and never reach a model, and searching them answers a different
-- question in the same list. That decision lives in `EntryStore.SEARCH_SQL`,
-- which spells `role IS NOT NULL` -- half of the predicate `THAT_PROJECT`
-- already uses -- and NOT in this file, because it is a decision about a read.
-- Welding it into a generated column would make revisiting it a table rewrite
-- instead of an edit to one Java string, and the lexemes for every kind are
-- already sitting here for the day it is revisited.
--
-- WHAT THIS COSTS AT WRITE TIME, AND THE ONE WAY IT CAN FAIL. `to_tsvector`
-- raises "string is too long for tsvector" above 1 048 575 bytes of lexeme data,
-- and because this column is generated that error would surface as a REFUSED
-- INSERT -- a turn that could not record what a tool returned. Measured against
-- pgvector/pgvector:pg16 on 2026-09-04: ordinary prose costs about 0.06 bytes of
-- tsvector per character, and the worst case that could be constructed -- every
-- token distinct and short, so every lexeme new and every position stored --
-- costs 2.19, which puts the limit at roughly 478 000 characters of pathological
-- text. The largest thing this server writes into `content` is
-- `FileTools.MAX_DISPLAY_CHARS`, which is 100 000, so the headroom is about
-- 4.8x against text nobody could actually produce and about 180x against prose.
-- A single token longer than 2 047 bytes -- a minified bundle on one line -- is a
-- WARNING and is skipped, not an error. The number that makes this safe is a
-- fact about what this server writes today and not about this statement, which
-- is why it is recorded here; if a tool ever returns half a megabyte of
-- distinct tokens, the fix is one later migration wrapping `content` in a
-- `left(...)` and accepting that the tail of such an entry is unsearchable.
ALTER TABLE entries
    ADD COLUMN text_search tsvector
        GENERATED ALWAYS AS (to_tsvector('english', content)) STORED;

-- WHAT THE STATEMENT ABOVE COSTS, said out loud because it is not free and
-- because this table is larger than `chunks`. Adding a STORED generated column
-- rewrites the whole table under an ACCESS EXCLUSIVE lock: every existing entry
-- is read, its lexemes computed, and the row written again. A conversation is a
-- few hundred entries and a busy single-operator archive is tens of thousands,
-- so this is seconds; the number that makes it cheap is a fact about today's
-- archive and not about this statement. There is no concurrent reader to block
-- on a server that is single user by construction.

-- ---------------------------------------------------------------------------
-- 2. the index
-- ---------------------------------------------------------------------------

-- THE INDEX THAT MAKES A SEARCH OF THE WHOLE TIER POSSIBLE, and the first index
-- this table has ever had that is not about WHERE a row sits.
--
-- GIN AND NOT GiST, for V21's reason: GiST stores a lossy signature, every
-- candidate is rechecked against the heap, and it is the choice when writes
-- dominate reads. Writes here are one row per model call and one per tool
-- result, each already dominated by the model call itself.
--
-- WHAT IT IS FOR, PRECISELY, AND WHERE IT IS NOT USED -- measured, because the
-- honest version of this is more interesting than the slogan. Against 200 000
-- entries in 501 conversations on pgvector/pgvector:pg16:
--
--   * A search of a whole tier is a Bitmap Index Scan on this index, and the
--     ranking is a Sort over what survives it. 172 hits out of 200 000 rows,
--     200 heap blocks. This is the read the index exists for.
--
--   * A search NARROWED TO ONE CONVERSATION is served by
--     `entries_one_per_place_in_a_conversation` -- the primary key -- alone, and
--     the planner is right: one conversation is 400 rows in sixteen heap blocks,
--     and there is nothing for a GIN scan to improve on. The `@@` becomes a
--     filter over rows the key already fetched, which still WORKS and still uses
--     this column; it just does not use this index.
--
-- That second measurement is why `EntryStore.search` is scoped to a TIER and not
-- to a conversation. A per-conversation search would be a correct read that
-- never touched this index, which is an index built on a guess -- and the
-- question it would answer is one `conversation_trajectory` already answers by
-- paging a few hundred rows.
--
-- AND THE SECOND SORT KEY IS FREE, WHICH IS THE FINDING THAT TRANSFERS FROM V21
-- AND IS NOT TRUE ONE TABLE OVER. `DocumentStoreTest` records, measured, that
-- appending a second key to the VECTOR search's ORDER BY makes `chunks_by_vector`
-- vanish from the plan -- no error, same rows, sequential scan -- because an HNSW
-- index supplies the ORDER. A GIN index supplies no order at all: it supplies the
-- FILTER, and the ranking is already a Sort over the bitmap. So `SEARCH_SQL`
-- breaks its ties in SQL, on `(conversation_id, turn_ordinal, ordinal)`, and that
-- is not a style preference: a LIMIT over an unbroken tie takes an arbitrary
-- subset of the tied rows, so the CANDIDATE SET and not merely its order would
-- vary between two runs of one question.
CREATE INDEX entries_by_text ON entries USING gin (text_search);

-- NO PARTIAL PREDICATE, although the read that uses it always carries `role IS
-- NOT NULL` beside the `@@`. V21 declined the same thing for the same first
-- reason -- it would weld a decision that lives in a Java string into a file that
-- can never be edited -- and there is a second reason here that is about sizing.
-- The kinds this index would exclude are the SHORT ones: a diagnostic is a
-- sentence the harness composed, an attempt_failed is an ending and a reason, a
-- runtime_note is a nudge. What this index actually costs is tool results and
-- answers, and those are exactly the rows the read wants. A partial predicate
-- would trade an unrevisable decision for no measurable space.
--
-- ALSO NOT HERE: an index on `(conversation_id, text_search)` through btree_gin,
-- which would serve the narrowed read the paragraph above declines to build. It
-- needs an extension this schema does not install, and it would be an index
-- shaped for a read nothing makes. When something makes it, that is its own
-- migration with its own argument.

COMMENT ON COLUMN entries.text_search IS
    'The entry''s own text as english lexemes, computed by the database and '
    'writable by nothing -- so a lexical index that disagrees with the text it '
    'indexes is unreachable rather than merely unlikely. NULL for an ejected '
    'payload, which is therefore never a hit: the bytes are gone, and '
    'EntryStore.search counts those rows separately rather than letting them be '
    'silently missing. The query side must name the same text search '
    'configuration or it silently stops matching; EntryStore.SEARCH_SQL does, '
    'and a test compares the two spellings.';
