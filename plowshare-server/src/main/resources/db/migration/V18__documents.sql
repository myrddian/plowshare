-- The corpus: documents, the paragraphs they are made of, and the chunks that
-- get embedded.
--
-- This file is written once. `MigrationsAreImmutableTest` freezes every
-- migration that is on `master`, by asking git for `master`'s blobs, so the
-- decisions below cannot be edited afterwards -- they can only be added to by a
-- later migration. Four of them were settled by the owner before any of this was
-- written, in implementation rationale,
-- and the two that shape this DDL are recorded again here because the file has
-- to stand on its own when the spec is three years stale.
--
-- WHY THIS IS IN THE SAME DATABASE AS `memories`, decided rather than defaulted.
-- Same Postgres, same pgvector, same 768-wide space from the same model. Two
-- databases would be two connection pools, two Flyway histories, and a citation
-- from `memories` to `paragraphs` that could not be a foreign key -- and that
-- citation is the point of having documents inside Plowshare rather than beside
-- it. What is NOT inherited is the memory tables' indexing: `memories` has no
-- ANN index at all and `MemoryStore.searchByVector` is an exact sequential scan,
-- which is correct at memory scale (hundreds of rows) and is not at corpus scale
-- (one 30-page paper is ~200 chunks, so a hundred documents is 20 000 vectors).
-- `chunks_by_vector` below is that difference. Adding an HNSW index to
-- `memories.embedding` is a separate improvement with its own argument and is
-- deliberately not bundled here.
--
-- WHAT IS NOT IN THIS FILE, so that its absence reads as a decision.
--
--   * No chapters and no sections. Anchor derives document -> chapter -> section
--     -> paragraph -> chunk, and its chapter and section detectors are ~40
--     hand-curated strings and three regexes fitted to English chemistry and
--     optimisation PDFs -- each constant a specific paper that broke a specific
--     prompt. Declining them means there is no hierarchy to store, and two
--     tables nothing writes to would be frozen here for ever. A later slice that
--     has a use for sections adds a `sections` table and a NULLABLE `section_id`
--     on `paragraphs`; both are additive, which is why paragraphs hang off the
--     document directly rather than off a section placeholder.
--
--   * No summaries. The four-level summariser cascade is what the 242 model
--     calls are, and it is a later slice. Adding the columns now would be
--     guessing at their shape a whole slice early.
--
--   * No `tsvector` and no GIN index. Hybrid retrieval is described in
--     implementation rationale and in the v1 design as inherited from Anchor, and it is
--     not: grepping Anchor's whole server tree for `tsvector`, `to_tsquery`,
--     `ts_rank`, `BM25` and `rrf` returns zero hits. It is new work in both
--     repositories, retrieval is not in this slice at all, and a column whose
--     shape is decided by a retrieval design nobody has written would be the
--     worst thing to freeze here.
--
--   * No project column. "A memory has exactly one home; a document has none.
--     Memory is scoped because contradiction needs an owner. Documents are not
--     because relevance does not." (v1 design.) A corpus reachable from every
--     project is the decision; a nullable project column would make it look
--     revisable while nothing read it.
--
--   * No lifecycle. `memories` has four states, a CHECK refusing a fifth,
--     tombstones and reasons, because a memory can be contradicted. A paragraph
--     cannot: it is in the current derivation of its document or it is not.
--     Importing those states would give every row three it can never reach.

-- One row per document in the corpus.
--
-- WHAT IDENTIFIES A DOCUMENT, and why it is not its content. Anchor derives its
-- document id from the content hash -- `UUID.nameUUIDFromBytes(NAMESPACE + ":" +
-- contentHash)` -- so re-ingesting the same bytes hits the same row. That is
-- exactly wrong for the rule this schema exists to support. If identity were
-- content-derived then EDITING a document would produce a different document,
-- every paragraph in it would be new, and the paragraph matching rule below
-- could never fire -- which is the one case it was built for. So a document is
-- identified by `source_name`, what the corpus files it as, and its content is a
-- fact about it rather than its name.
--
-- `source_name` is UNIQUE and that is a structural claim rather than a policy: a
-- corpus that can hold two documents under one name, with no way to tell a
-- reader which is which, is worse than one that refuses the second. Re-ingesting
-- an edited file under the same name is the SAME document with changed
-- paragraphs, which is what makes a citation survive an edit.
CREATE TABLE documents (
    id           UUID        PRIMARY KEY,

    -- What this document is called, and the natural key. Supplied by whoever
    -- uploads it, defaulting to the uploaded filename.
    source_name  TEXT        NOT NULL UNIQUE,

    -- For a human and for a prompt. Derived from the name when the format
    -- carries no title of its own, which for text-like formats is always.
    title        TEXT        NOT NULL,

    -- SHA-256 of the bytes as they arrived. THE DEDUP GATE: an upload whose
    -- content hash already matches the row is not a re-ingest, and the whole
    -- pipeline is skipped. Not unique -- two documents may legitimately be the
    -- same bytes under two names -- and not the identity, for the reason above.
    content_hash TEXT        NOT NULL,

    -- SHA-256 of the EXTRACTED TEXT, which is a different question from the one
    -- above and the second gate the v1 design asks for: a file re-saved by a
    -- tool that rewrote its metadata has new bytes and identical text, and this
    -- column is what makes that re-ingest free. Today extraction is a UTF-8
    -- decode, so the two hashes differ only over a byte-order mark or a line
    -- ending; the column earns itself the moment conversion arrives, where two
    -- exports of one document routinely differ in every byte.
    text_hash    TEXT        NOT NULL,

    -- How large the upload was. Kept because it is the one fact about the
    -- source that the extracted text cannot answer, and a corpus that cannot say
    -- how much was read cannot be reconciled against a disk.
    byte_size    BIGINT      NOT NULL,

    -- The last ingest that wrote this row. NOT an append-only log of ingests,
    -- and the distinction is worth stating: a corpus is not append-only and must
    -- not pretend to be. Re-ingest rewrites the derivation, which is the
    -- opposite of the `entries` rule one table over. If the history of ingests
    -- becomes worth keeping it is its own table, because it is a log of what was
    -- done and this is a description of what is.
    ingested_at  TIMESTAMPTZ NOT NULL,
    ingested_by  TEXT        NOT NULL,

    CONSTRAINT documents_named        CHECK (source_name <> '' AND title <> ''),
    CONSTRAINT documents_has_content  CHECK (byte_size > 0)
);

-- The paragraphs of a document, in the order they appear.
--
-- THE DECISION THIS TABLE EXISTS TO RECORD (spec decision 1, Enzo, 2026-09-03).
-- `id` is a surrogate. It is NOT the content hash, and it is not derived from
-- one, and the reason is particular to this repository: a content-hash primary
-- key would freeze the identity RULE into the schema, and this file is frozen.
-- If "document + content + occurrence" later proved wrong -- a format where
-- occurrence is unstable, a need for near-match rather than exact -- the rule
-- could not be revised without moving every foreign key that points at a
-- paragraph. The constraint that makes this file expensive to write is exactly
-- the one that argues against putting a policy in a primary key.
--
-- So the rule lives in `DocumentStore.matchToExisting`, where it can be revised,
-- and this table holds only the facts it is written over. As it stands today:
-- on re-ingest, a paragraph matching an existing row by document + content hash
-- + occurrence keeps that row's id, and anything else gets a new one. The
-- behaviour is what a content-hash key would have given -- unchanged text keeps
-- its id, changed text gets a new one, and a dangling citation is a staleness
-- signal and never a silent repoint at different text -- and what differs is
-- that the rule producing it is revisable and this file is not committed to it.
--
-- Nothing points at `paragraphs` yet. Citation is a later slice; this table is
-- shaped for it now because that is the half that cannot be added later.
CREATE TABLE paragraphs (
    id           UUID PRIMARY KEY,

    document_id  UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,

    -- The paragraph itself, re-flowed onto one line. Hashed below over this
    -- text and not over the source lines, so a document re-saved at a different
    -- line width is the same paragraph.
    text         TEXT NOT NULL,

    -- SHA-256 of `text`. An indexed column BESIDE the key, which is the whole
    -- shape of the decision above.
    content_hash TEXT NOT NULL,

    -- Which of the identically-hashed paragraphs in THIS document this is, from
    -- 1. A repeated heading, a refrain or a boilerplate footer appears more than
    -- once with one hash; without this term there is one identity for two rows
    -- and the second could never hold an id of its own. Counted within a hash
    -- and not across the document.
    --
    -- The known limitation, recorded rather than left to be found: inserting a
    -- duplicate above an existing one renumbers the later one and moves its id.
    -- That is a bug fixable in a code change now, rather than a property welded
    -- into this file, which is the entire benefit being bought here.
    occurrence   INT  NOT NULL,

    -- WHERE IT SITS, AND NOT WHAT IT IS. A separate mutable column, on purpose:
    -- inserting a paragraph moves every ordinal after it and must not move a
    -- single id, because a citation has to keep meaning the same text. 1-based
    -- and dense, like `entries.ordinal`.
    ordinal      INT  NOT NULL,

    CONSTRAINT paragraphs_not_blank  CHECK (text <> ''),
    CONSTRAINT paragraphs_counted_from_one CHECK (ordinal >= 1 AND occurrence >= 1),

    -- DEFERRABLE, and this is not decoration. A re-ingest that inserts a
    -- paragraph in the middle rewrites the ordinals of everything after it, and
    -- any order of those updates passes through a state where two rows share an
    -- ordinal. An immediate constraint would refuse the transaction for a
    -- collision that does not exist at commit; a plain non-unique index would
    -- let a genuine duplicate through for ever. Deferred is the only one of the
    -- three that says what is meant: within a transaction, reshuffle freely;
    -- at commit, a document has one paragraph per position.
    CONSTRAINT paragraphs_one_per_position
        UNIQUE (document_id, ordinal) DEFERRABLE INITIALLY DEFERRED
);

-- What the matching rule looks a paragraph up by, and the reason it is NOT
-- UNIQUE. Uniqueness on this triple would be the content-hash primary key
-- wearing a different hat: it would make "document + hash + occurrence
-- identifies a paragraph" a fact about the schema, which is the exact thing the
-- decision above declines to freeze. The derivation produces the triple
-- uniquely by construction; that is a property of the code, and it belongs
-- where it can be changed.
CREATE INDEX paragraphs_matched_by ON paragraphs (document_id, content_hash, occurrence);

-- The pieces that are embedded.
--
-- A chunk is an artefact of the chunker and nothing cites one: change the chunk
-- rule and every chunk id moves while the document has not. That is why a
-- memory will cite a PARAGRAPH, and why nothing here tries to make a chunk id
-- stable across a re-derivation.
CREATE TABLE chunks (
    id                 UUID    PRIMARY KEY,

    paragraph_id       UUID    NOT NULL REFERENCES paragraphs(id) ON DELETE CASCADE,

    -- Where in its paragraph, from 1.
    ordinal            INT     NOT NULL,

    text               TEXT    NOT NULL,

    -- THE CHUNK'S SIZE, IN UTF-8 BYTES, AND WHY THE CEILING IS NOT IN THIS FILE.
    --
    -- The quantity that matters is tokens: nomic-embed-text reports
    -- loaded_context_length 2048 and max_context_length 2048 -- measured against
    -- the reference node 2026-09-03 -- so there is no headroom to be bought by
    -- configuration. There is no tokenizer on this node (implementation rationale: both
    -- tokenize endpoints answer "Unexpected endpoint or method", one of them
    -- with HTTP 200), so the count is a stand-in, and bytes are the stand-in
    -- that is wrong in the safe direction: no tokenizer over a byte or character
    -- vocabulary emits more tokens than the input has bytes, so N bytes is at
    -- most N tokens in any script. Anchor's stand-in is whitespace words, which
    -- is wrong in the other direction -- a real tokenizer counts MORE than
    -- whitespace words, so its 300-token chunks are ~400 real tokens, and a CJK
    -- line is one word.
    --
    -- The CHECK is `> 0` and there is deliberately NO numeric ceiling here. The
    -- number is 2048 today and it is a property of the loaded embedding model
    -- and of a stand-in that a tokenizer will replace; welding it into a file
    -- that cannot be edited would be the same mistake as a content-hash primary
    -- key, one table up. It lives in plowshare.llm.embedding-max-input-bytes,
    -- it is enforced by the chunker at derivation time and refused again at the
    -- embedding boundary, and changing it is a re-chunk and a re-embed of the
    -- whole corpus rather than a setting -- application.yml says so beside the
    -- key, in the same words it already uses for the embedding model.
    byte_size          INT     NOT NULL,

    -- WHETHER THIS CHUNK'S BOUNDARIES ARE THE CHUNKER'S OR THE PROSE'S. False
    -- for a chunk packed out of whole sentences; true for a piece of a run that
    -- had to be cut at the ceiling -- a PDF table re-flowed onto one line, a
    -- reference list, an equation block, anything BreakIterator returns whole.
    --
    -- It is stored, rather than being a fact the chunker knew and forgot,
    -- because the failure this whole column guards against is a SILENT
    -- truncation: a vector for text that is not the text, where the row is
    -- perfect, the search misses it, and nothing goes red. A recorded cut is a
    -- different thing from a silent one, and this is where the recording is.
    split_mid_sentence BOOLEAN NOT NULL,

    -- 768, matching nomic-embed-text and `memories.embedding` in V1. Changing
    -- the embedding model changes this number and invalidates every stored
    -- vector, so it is a migration rather than a config change -- V1 says the
    -- same about the same number.
    --
    -- NULLABLE, for V1's reason and a second one. A chunk is written before it
    -- is embedded, and an embedding endpoint being down must lose the vector and
    -- never the text; and unlike a memory, a chunk that arrives unembedded can
    -- be embedded later from the row itself, because the text is right here.
    embedding          vector(768),

    CONSTRAINT chunks_not_blank      CHECK (text <> ''),
    CONSTRAINT chunks_has_size       CHECK (byte_size > 0),
    CONSTRAINT chunks_counted_from_one CHECK (ordinal >= 1),

    -- Deferred for the reason `paragraphs_one_per_position` is.
    CONSTRAINT chunks_one_per_ordinal
        UNIQUE (paragraph_id, ordinal) DEFERRABLE INITIALLY DEFERRED
);

-- THE INDEX `memories` DOES NOT HAVE, and the reason documents cannot inherit
-- that omission. An exact scan over a few hundred memories is fast and choosing
-- it was right; an exact scan over six figures of chunks is not. HNSW rather
-- than IVFFlat because it needs no training pass and no list count chosen
-- against a corpus size nobody knows yet -- an IVFFlat index built on an empty
-- table is worse than none, and this table starts empty.
--
-- `vector_cosine_ops` because `<=>` is what every read here will use, matching
-- `MemoryStore.searchByVector` and Anchor's own `idx_chunks_embedding`. An
-- operator class is not a default: an index built for one distance function
-- does not serve another, and a query using `<->` against this index silently
-- falls back to a sequential scan.
--
-- Nothing in this slice reads it. It is here because retrieval is the next slice
-- and this file cannot be edited then.
CREATE INDEX chunks_by_vector ON chunks USING hnsw (embedding vector_cosine_ops);

-- No separate index on `chunks (paragraph_id)`: `chunks_one_per_ordinal` is a
-- unique constraint with `paragraph_id` leading, so it already backs both the
-- foreign key's cascade and every read that fetches a paragraph's chunks in
-- order. Said out loud because a redundant index here would be invisible and
-- would cost every insert.

COMMENT ON TABLE chunks IS
    'Chunk text is whatever was uploaded: other people''s papers, notes and'
    ' source. Like `entries`, this table holds content the server did not write'
    ' and cannot vouch for, and unlike `entries` it is content a search will'
    ' surface out of context. Anything that puts a chunk into a prompt is'
    ' putting an uploaded document into a prompt.';
