-- V27: the vector of a document's own summary.
--
-- One column. It exists so that a caller can ask WHICH PAPER is about something
-- without touching a single chunk -- one vector comparison per document, where
-- `POST /v1/documents/search` compares against every chunk in the corpus and
-- then has to decide what a document's best passage says about the document.
--
-- Two reads want it and both are Anchor's:
--
--   * ranking documents by how close their summary is to a question;
--   * a vector-only stance score, cos(query, summary) - cos(not-query, summary),
--     which Anchor designed as a cheap pre-filter to run over a corpus before
--     spending a deliberation on any of it.
--
-- WHAT ANCHOR'S SPEC SAYS ABOUT THESE TWO, WHICH IS NOTHING. Recorded here
-- because a migration is the wrong place to discover it. Anchor's SPEC.md has a
-- 344-line API section, a data model and a list of decisions deferred to v1, and
-- the strings `documents/search`, `validate/quick` and `summary_embedding` appear
-- in none of them. Both routes and Anchor's own `V2` are post-spec additions
-- whose entire recorded argument is `V2`'s header comment. What the spec DOES
-- contain, under "open decisions deferred to v1", is:
--
--   Third API shape: /supports?document_id&claim -- does the document genuinely
--   back this claim.
--
-- The stance score is that shape, shipped early, under another name, with a
-- heuristic where the design was going to be. So this column is added for two
-- routes whose contract its own source never settled, and the honest thing is to
-- say so on the column rather than in a commit message nobody reads twice.
--
-- WHY NOT ON `paragraphs.summary` OR THE HIERARCHY'S SUMMARIES TOO. Those have a
-- reader already: `POST /v1/documents/retrieve` returns them beside the chunk
-- that matched, and the chunk's own vector is what found it. A vector per
-- paragraph would be a second embedding of the same passage in a different
-- register, with nothing to say which of the two a search should believe.

ALTER TABLE documents
    ADD COLUMN summary_embedding vector(768);

-- NULLABLE, AND NOT AS A CONVENIENCE FOR THE MIGRATION. It is the same shape
-- `chunks.embedding` has and for the same recorded reason (V18, and divergence 2
-- of the port spec): text is committed before any model is called, so a document
-- exists and is readable long before it has a summary at all, and a summary
-- exists before anything has embedded it. A NOT NULL column here would make an
-- ingest that finished its text and lost its embedding endpoint into an ingest
-- that failed.
--
-- The three states are therefore distinguishable and each means something:
--
--   summary IS NULL                          -- the cascade has not reached it
--   summary IS NOT NULL, embedding IS NULL   -- summarised, not yet rankable
--   both NOT NULL                            -- rankable
--
-- The middle one is what `DocumentStore.countUnranked` counts and what the two
-- new reads report beside an empty answer, on `Coverage`'s rule: an empty list
-- is a conclusion a caller acts on, so every way of producing one that is not
-- "nothing in the corpus is close" has to be distinguishable from it.

-- =============================================================================
-- THERE IS NO INDEX ON THIS COLUMN, AND THAT IS THE DECISION IN THIS FILE.
-- =============================================================================
--
-- Anchor's V2 creates one:
--
--   CREATE INDEX idx_documents_summary_embedding
--       ON documents USING hnsw (summary_embedding vector_cosine_ops);
--
-- with the comment "HNSW index for the search path. Same operator class as
-- chunks so the cosine semantics line up." That is a reason for the operator
-- class and it is not a reason for the index. This file declines it, on THIS
-- REPOSITORY'S OWN RECORDED RULE rather than on a preference.
--
-- V18 wrote the rule down while doing the opposite, and it is worth quoting
-- because it is the whole argument:
--
--   `memories` has no ANN index at all and `MemoryStore.searchByVector` is an
--   exact sequential scan, which is correct at memory scale (hundreds of rows)
--   and is not at corpus scale (one 30-page paper is ~200 chunks, so a hundred
--   documents is 20 000 vectors). `chunks_by_vector` below is that difference.
--
-- SO THE RULE IS ROW COUNT AND NOT "IT IS A VECTOR COLUMN". And this column
-- holds ONE ROW PER DOCUMENT. A hundred papers is a hundred vectors, which is
-- `memories` scale exactly -- the scale V18 says an exact scan is CORRECT at --
-- and it is two orders of magnitude below the scale that earned `chunks` its
-- index. An HNSW index here would be built, maintained on every ingest, and then
-- either ignored by the planner or chosen over a sort of a hundred rows that
-- costs nothing.
--
-- AND A SECOND REASON, WHICH IS CORRECTNESS AND NOT COST. Both reads filter
-- `summary_embedding IS NOT NULL`, so both are post-filtered ANN scans if the
-- index is taken. Port spec section 2.2a measured that case on this container:
-- with `hnsw.iterative_scan` off -- pgvector 0.8's default -- a post-filtered
-- HNSW scan CAN RETURN FEWER ROWS THAN THE LIMIT, an under-full answer with no
-- error. On the scoped chunk search that was a risk worth naming; here it would
-- be a corpus ranking that silently omitted papers, on a table where the exact
-- sort is a hundred comparisons.
--
-- WHAT WOULD CHANGE THIS. A corpus in the tens of thousands of documents, which
-- is four orders of magnitude past anything measured here and would arrive with
-- its own facts. `chunks_by_vector` is the precedent for adding one in a later
-- migration, and V18's own closing note is the precedent for saying so: it
-- shipped an index nothing in its slice read, because it knew which slice was
-- next. This file does not know that, so it does not guess.

COMMENT ON COLUMN documents.summary_embedding IS
    'The document summary as a vector, for ranking whole documents against a '
    'question without touching a chunk. NULL until something embeds the summary, '
    'which is a state the reads report rather than hide. Deliberately unindexed: '
    'one row per document is memories scale, and V18 records that an exact scan '
    'is correct there.';
