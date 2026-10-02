-- What the summariser cascade writes back.
--
-- V18 left these out and said why: "No summaries. The four-level summariser
-- cascade is what the 242 model calls are, and it is a later slice. Adding the
-- columns now would be guessing at their shape a whole slice early." This is
-- that slice, and the shape is now known rather than guessed, so the columns
-- can be written against a cascade that exists.
--
-- Like every file in this directory this one is frozen the moment it is on
-- `master` -- `MigrationsAreImmutableTest` asks git for `master`'s blobs -- so
-- what is NOT here matters as much as what is.
--
-- WHAT IS NOT HERE, AND THIS IS THE FINDING OF THE SLICE RATHER THAN AN
-- OMISSION: there are still no `sections` and no `chapters`.
--
-- Anchor summarises at four levels -- paragraph, section, chapter, document --
-- and V18 declined its section and chapter DETECTORS, which are ~40
-- hand-curated strings and three regexes fitted to English chemistry and
-- optimisation PDFs. What that leaves, read in Anchor's own code rather than in
-- its comments, is the degenerate path: `SectionDetector` with no boundaries
-- returns ONE synthetic section over the whole body, `ChapterDetector` with none
-- returns ONE synthetic chapter, and `IngestService` then makes a section call
-- over every paragraph summary, a chapter call over THAT ONE section summary,
-- and a document call over THAT ONE chapter summary. Two of Anchor's four levels
-- summarise a single input. With no detectors here, that degenerate path is the
-- only path this server would ever take, so porting four levels would buy two
-- model calls per document that paraphrase one sentence into another sentence,
-- and two frozen tables holding one row each.
--
-- So the middle level here is a FOLD and not a hierarchy: an ordered run of
-- summaries is folded into one summary, repeatedly, until what is left fits in
-- one call. That has no rows because it has no identity -- a span is an artefact
-- of a batch size, exactly as a chunk is an artefact of the chunker, and V18
-- already refuses to make a chunk id stable for the same reason. What records
-- the intermediate work is the CONVERSATION each fold runs in, which is what
-- running the cascade through the agent runtime buys and is the whole point of
-- building it as agents.
--
-- A later slice that has a real use for sections still adds `sections` and a
-- NULLABLE `paragraphs.section_id`, exactly as V18 says, and the span agent
-- reads a section's paragraph summaries with no change: the grouping rule is
-- the caller's and never the agent's.
--
-- WHAT ELSE IS NOT HERE. No `summary_embedding` on `documents`. Anchor's V2 adds
-- one, and it backs `GET /documents/search` and `/validate/quick`, neither of
-- which exists here -- retrieval on this server is chunk-level and hybrid
-- (V18 + V21) and nothing would read a summary vector. A vector column nothing
-- searches would be 768 floats per document kept in step with an embedding model
-- for no reader, and it is additive whenever a reader arrives.

-- The claim-bearing summary of this paragraph, or NULL for one nothing has
-- summarised yet.
--
-- NULLABLE, and the null is `chunks.embedding`'s null one table over rather than
-- a gap waiting to be filled. A paragraph is written before it is summarised --
-- the text is committed and only then is a model called, because a model call
-- inside a transaction has already once discarded every committed row a server
-- wrote -- so "stored and not yet summarised" is an ordinary state of a correct
-- ingest, and it is the state that makes a stopped cascade RESUMABLE: the next
-- ingest of the same document finds exactly the paragraphs that still hold no
-- summary, from their own rows, needing no state outside this database to have
-- survived a restart.
--
-- It also makes a re-ingest cheap in the way the identity rule was built for.
-- `DocumentStore.write` touches a kept paragraph's ORDINAL and nothing else, so
-- unchanged text keeps its id and therefore keeps this column: a document with
-- one edited paragraph costs one paragraph-level model call and not two hundred.
--
-- BLANK IS REFUSED, on `paragraphs_not_blank`'s terms exactly. An empty string
-- is what a model that answered nothing arrives as, and a row holding one says a
-- summary was written when none was -- which would then be folded upward as
-- though it were a claim. Anchor guards the same failure in Java, by retrying
-- once at temperature 0 and then throwing; this is the half that cannot be
-- forgotten at a later call site.
ALTER TABLE paragraphs ADD COLUMN summary TEXT;

ALTER TABLE paragraphs ADD CONSTRAINT paragraphs_summary_is_not_blank
    CHECK (summary IS NULL OR summary <> '');

-- The claim-bearing summary of the whole document, or NULL for one nothing has
-- summarised yet.
--
-- The same nullability for the same reason, plus one this column has and the one
-- above does not: it is DROPPED when the document's derivation changes.
-- `DocumentStore.write` nulls it in the transaction that added or removed a
-- paragraph, because a document summary is derived from every paragraph summary
-- under it and one that gained or lost a paragraph is a document the stored
-- sentence is no longer about. A stale document summary is the readable,
-- confident, wrong answer -- the failure `chunks.split_mid_sentence` is written
-- about, arriving one table up.
ALTER TABLE documents ADD COLUMN summary TEXT;

ALTER TABLE documents ADD CONSTRAINT documents_summary_is_not_blank
    CHECK (summary IS NULL OR summary <> '');

-- What the cascade asks for first, on every ingest: the paragraphs of one
-- document that still hold no summary, in document order.
--
-- A PARTIAL INDEX, and the reason it is worth one where `chunks.embedding IS
-- NULL` deliberately is not. That one is named in implementation rationale as a fix to make
-- when the corpus outgrows a sequential scan, and it is right to wait: it is
-- read once per ingest. This one is read once per ingest TOO, but the query it
-- answers is the resumption gate for a cascade whose full run is ~26 minutes of
-- model calls -- so it is also the query an operator runs to ask how far a
-- stopped ingest got, and the query a second ingest of the same document runs to
-- find out that it owes nothing. Partial rather than whole because a finished
-- document has no rows in it at all: the index holds only the work outstanding,
-- which on a settled corpus is empty.
CREATE INDEX paragraphs_awaiting_a_summary ON paragraphs (document_id, ordinal)
    WHERE summary IS NULL;

COMMENT ON COLUMN paragraphs.summary IS
    'One claim-bearing sentence about this paragraph, written by the '
    'paragraph_summariser agent, or NULL for a paragraph nothing has summarised '
    'yet. The only level of the cascade that reads raw text; every level above '
    'reads summaries, which is what gives the levels independent compression.';

COMMENT ON COLUMN documents.summary IS
    'What this document claims, written by the document_summariser agent from '
    'the summaries below it, or NULL for a document nothing has summarised yet. '
    'Dropped by DocumentStore.write when a re-ingest adds or removes a '
    'paragraph, because a summary of a derivation that has changed is a wrong '
    'answer rather than an old one.';
