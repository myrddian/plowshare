-- The hierarchy a document has: chapters, the sections in them, and the edge
-- that hangs an existing paragraph off a section.
--
-- This file is written once. `MigrationsAreImmutableTest` freezes every
-- migration that is on `master` by asking git for `master`'s blobs, so what is
-- below cannot be edited afterwards and can only be added to.
--
--
-- WHY THIS EXISTS, AND WHY V18 DECLINED IT
--
-- V18 declined chapters and sections on a judgement it stated plainly: Anchor's
-- detectors are "~40 hand-curated strings and three regexes fitted to English
-- chemistry and optimisation PDFs -- each constant a specific paper that broke a
-- specific prompt", and "two tables nothing writes to would be frozen here for
-- ever". The first half is an accurate description. The conclusion is the part
-- that changed: those constants are a REGRESSION LOG, which is the argument for
-- carrying them rather than against, and the per-document deliberation this
-- corpus is being built toward is not a retrieval feature that happens to want
-- structure -- the structure IS the mechanism. A critic that holds a proposed
-- answer against "what this document argues as a whole" needs a whole to hold it
-- against.
--
-- The second half of V18's objection is respected literally rather than argued
-- with: everything created here has a writer in the same change. `chapters`,
-- `sections` and `paragraphs.section_id` are written by `DocumentStore.write`
-- from `Derivation`; `documents.top_level_label` by `Vocabulary`;
-- `document_references` by `ReferencesExtractor`. What has NO writer yet is left
-- out -- see the note on authors at the end.
--
-- V18 also pre-specified this migration, under a heading reading "WHAT IS NOT IN
-- THIS FILE, so that its absence reads as a decision":
--
--     A later slice that has a use for sections adds a `sections` table and a
--     NULLABLE `section_id` on `paragraphs`; both are additive, which is why
--     paragraphs hang off the document directly rather than off a section
--     placeholder.
--
-- That is exactly what this is, and it is why a paragraph now reaches its
-- document by two paths. See `paragraphs_are_in_their_sections_document` below,
-- which is the whole of what that redundancy costs.
--
--
-- FOUR DEPARTURES FROM ANCHOR'S OWN DDL, each forced or already decided.
--
--   1. SUMMARIES ARE NULLABLE. Anchor's four summary columns are NOT NULL
--      because its whole cascade runs inside one transaction before any row is
--      written. Plowshare persists text first and summarises after -- V22's
--      `paragraphs_awaiting_a_summary` is the work queue for exactly that -- so
--      a NOT NULL summary is unwritable here. Blank is refused instead, which is
--      the half of NOT NULL that survives the ordering.
--
--   2. ORDINALS ARE 1-BASED. Anchor's are 0-based. `paragraphs.ordinal` and
--      `entries.ordinal` are 1-based and mixing bases inside one schema is a bug
--      generator, so the port renumbers rather than importing a second
--      convention.
--
--   3. UNIQUENESS IS DEFERRABLE, for V18's reason: a re-ingest that inserts a
--      unit in the middle reshuffles ordinals and passes through states where
--      two rows share one. Anchor never needs this because it deletes and
--      re-inserts everything.
--
--   4. A NAMED UNIT MUST CARRY ITS TITLE. Anchor allows a null title on a
--      non-synthetic chapter. Nothing writes one; the constraint records the
--      intent.
--
-- And one that is not a departure: Anchor has zero CHECK constraints anywhere.
-- Reproducing that absence would give up guarantees for nothing. Constraints are
-- not structure.


-- The top-level groupings of one document, in the order the document makes
-- them.
--
-- `is_synthetic` IS THE WHOLE OF WHAT A PARSER MAY INVENT. A document with no
-- detectable heading at this level gets exactly one chapter covering all of it,
-- flagged, and carrying a sentinel title (`documents.SyntheticTitles`) rather
-- than a plausible-looking name. Anchor's V5 says why in its own words: the
-- render boundary drops synthetic units from everything user- or model-facing,
-- and "if anyone bypasses the helper these obviously-internal strings make the
-- bug visible instantly rather than blending in as a plausible-looking section
-- name like the previous 'Body' / 'Document' fallbacks did".
CREATE TABLE chapters (
    id           UUID    PRIMARY KEY,

    document_id  UUID    NOT NULL REFERENCES documents(id) ON DELETE CASCADE,

    -- Where in the document, from 1. Dense over what SURVIVED detection: an
    -- excluded heading -- a bibliography, a table of contents -- still ends the
    -- previous chapter's range and is then dropped without consuming an ordinal.
    ordinal      INT     NOT NULL,

    -- The document's own words, or NULL when this unit is the parser's
    -- invention. Never a name the parser made up: see `is_synthetic`.
    title        TEXT,

    -- What the tier above reads. NULL until the cascade reaches it, which on
    -- this server is a normal state for minutes at a time and after a re-ingest
    -- is a normal state again.
    summary      TEXT,

    is_synthetic BOOLEAN NOT NULL DEFAULT FALSE,

    CONSTRAINT chapters_counted_from_one CHECK (ordinal >= 1),
    CONSTRAINT chapters_summary_is_not_blank CHECK (summary IS NULL OR summary <> ''),
    CONSTRAINT chapters_a_named_chapter_has_a_title
        CHECK (is_synthetic OR (title IS NOT NULL AND title <> '')),

    -- Deferred for `paragraphs_one_per_position`'s reason, stated in V18.
    CONSTRAINT chapters_one_per_position
        UNIQUE (document_id, ordinal) DEFERRABLE INITIALLY DEFERRED,

    -- NOT A UNIQUENESS CLAIM -- `id` is already the primary key and this adds
    -- nothing to what a row may be. It is a FOREIGN KEY TARGET, and it is the
    -- first half of the mechanism described at `sections.document_id`: a
    -- composite key can only reference a uniquely-constrained column list, so
    -- the list has to be declared even when one of its columns already implies
    -- it.
    CONSTRAINT chapters_addressed_with_their_document UNIQUE (id, document_id)
);

-- The sections of one chapter.
CREATE TABLE sections (
    id           UUID    PRIMARY KEY,

    chapter_id   UUID    NOT NULL,

    -- WHICH DOCUMENT THIS SECTION IS IN, WHICH ITS CHAPTER ALREADY ANSWERS.
    --
    -- Denormalised deliberately and held in step by
    -- `sections_are_in_their_chapters_document` below, so that it is never a
    -- second answer to the question -- it is the same answer, copied down one
    -- level, so that the level below can be constrained against it. That is the
    -- only reason it exists; nothing reads it.
    document_id  UUID    NOT NULL,

    ordinal      INT     NOT NULL,
    title        TEXT,
    summary      TEXT,
    is_synthetic BOOLEAN NOT NULL DEFAULT FALSE,

    CONSTRAINT sections_counted_from_one CHECK (ordinal >= 1),
    CONSTRAINT sections_summary_is_not_blank CHECK (summary IS NULL OR summary <> ''),
    CONSTRAINT sections_a_named_section_has_a_title
        CHECK (is_synthetic OR (title IS NOT NULL AND title <> '')),
    CONSTRAINT sections_one_per_position
        UNIQUE (chapter_id, ordinal) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT sections_addressed_with_their_document UNIQUE (id, document_id),

    -- A section is in its chapter's document, said as a key rather than as a
    -- rule somebody has to obey. ON DELETE CASCADE: a chapter that goes takes
    -- its sections, exactly as Anchor's V1 has it.
    CONSTRAINT sections_are_in_their_chapters_document
        FOREIGN KEY (chapter_id, document_id) REFERENCES chapters (id, document_id)
        ON DELETE CASCADE
);


-- THE SHORTCUT EDGE, AND THE CONSTRAINT THAT MAKES IT SAFE.
--
-- `paragraphs.document_id` is NOT NULL and frozen in V18, so a paragraph cannot
-- be re-parented onto a section the way Anchor's is: it now reaches its document
-- BOTH directly and through `section -> chapter -> document`. The failure that
-- redundancy creates is the two paths disagreeing, and it is the one thing that
-- has to be impossible rather than merely unwritten.
--
-- WHY A COMPOSITE FOREIGN KEY, AND NOT A CHECK OR A TRIGGER. Three candidates
-- and only one of them is even expressible:
--
--   CHECK    -- cannot be written at all. The invariant is a join, and Postgres
--              forbids a subquery in a CHECK constraint. And if it could be
--              written it would be the wrong shape for a second reason V25
--              measured rather than reasoned about: a CHECK is IMMEDIATE and
--              cannot be deferred, and V25 records a delete that fired two
--              referential actions in unspecified order and failed outright
--              because the CHECK ran between them. Deleting a document here
--              fires more actions than that one did.
--
--   TRIGGER  -- expressible, as a CONSTRAINT TRIGGER DEFERRABLE INITIALLY
--              DEFERRED running the join at commit. It would work, and it would
--              put a dozen lines of PL/pgSQL into a file that can never be
--              edited, to say something the engine can already enforce.
--
--   COMPOSITE KEY -- what is below. `sections` carries its chapter's
--              `document_id` (held in step by its own composite key one table
--              up), and a paragraph's `(section_id, document_id)` must be a
--              pair that exists in `sections`. Transitively:
--              paragraph.document_id = section.document_id = chapter.document_id,
--              which is the invariant, checked by the engine against every
--              writer including a psql session, and insensitive to the order
--              referential actions happen to fire in.
--
-- MATCH SIMPLE -- Postgres's default -- is what makes the nullable half work: a
-- composite key with any column NULL is satisfied without a lookup, so
-- `section_id IS NULL` is legal and means "derived before the hierarchy
-- existed", which is precisely V18's clause. `document_id` alone is never
-- checked against `sections` and cannot be, which is correct: a document has
-- paragraphs whether or not it has a hierarchy.
--
-- ON DELETE SET NULL (section_id), AND THIS IS THE ONE PLACE THIS MIGRATION
-- DEPARTS FROM THE DESIGN DOCUMENT'S OWN DDL, which says CASCADE.
--
-- CASCADE here would be a data-loss bug against the decision the corpus is built
-- on. A re-ingest REPLACES the hierarchy -- the detectors run again over new
-- text -- while V18's identity rule keeps an unchanged paragraph's id, V25's
-- citations resolve through that id, and three tests in `IngestServiceTest` pin
-- it. Under CASCADE, dropping the old chapters would delete every paragraph in
-- the document, so re-ingesting a file that had not changed would move every id
-- and stale every citation into it. SET NULL detaches instead: the paragraph
-- survives, briefly holding the legal null above, and the writer re-points it at
-- its new section in the same transaction. The column list form -- SET NULL
-- (section_id) -- is what keeps `document_id` NOT NULL intact, and it is why
-- this migration needs Postgres 15 or later.
ALTER TABLE paragraphs
    ADD COLUMN section_id UUID;

ALTER TABLE paragraphs
    ADD CONSTRAINT paragraphs_are_in_their_sections_document
    FOREIGN KEY (section_id, document_id) REFERENCES sections (id, document_id)
    ON DELETE SET NULL (section_id);

-- The read the ask path makes: a section's paragraphs, in order. Also what backs
-- the SET NULL above, which without an index would scan `paragraphs` once per
-- deleted section.
CREATE INDEX paragraphs_in_section ON paragraphs (section_id, ordinal);

-- No separate index on `chapters (document_id)` or `sections (chapter_id)`:
-- `chapters_one_per_position` and `sections_one_per_position` are unique
-- constraints with those columns leading, so they already back both the
-- cascades and every read that fetches a unit's children in order. Said out loud
-- because a redundant index here would be invisible and would cost every insert
-- -- V18 says the same about `chunks (paragraph_id)`.


-- WHAT THE DOCUMENT CALLS ITS OWN TOP-LEVEL GROUPINGS.
--
-- Anchor's `ChapterDetector.detectVocabulary`, counting heading shapes and
-- picking the format that wins. It exists because of a specific failure:
--
--     Without this, prompts always said "YOUR CHAPTERS" -- a paper that uses
--     "Section" throughout would have the model dutifully cite our label as
--     "chapter," which contradicts the document's self-references and reads as
--     fabrication to a reader.
--
-- A REAL COLUMN AND NOT A JSONB BLOB. Anchor keeps this in
-- `documents.metadata`. Plowshare's `documents` has no metadata column at all --
-- V18 gave `content_hash` and `text_hash` real columns instead -- and a JSONB
-- blob is where schema decisions go to stop being reviewable.
--
-- NULLABLE, because rows written before this migration have no answer and
-- inventing one for them would be recording a detection that never ran. The
-- CHECK is over the three values the enum has, so a fourth arriving is a
-- migration and a prompt change together rather than a string nothing rejects.
ALTER TABLE documents
    ADD COLUMN top_level_label TEXT;

ALTER TABLE documents
    ADD CONSTRAINT documents_top_level_label_is_one_of_three
    CHECK (top_level_label IS NULL OR top_level_label IN ('CHAPTER', 'SECTION', 'PART'));


-- THE DOCUMENT'S OWN BIBLIOGRAPHY, WHICH IS NOT `citations` AND MUST NOT BE
-- RECONCILED WITH IT.
--
-- V25's `citations` records what an ANSWER took from the corpus. A row here is a
-- reference the DOCUMENT ITSELF lists: Anchor's `Citation(refNum, raw)`, fed to
-- the deliberation as `{document_citations}` so a model can tell "an author of
-- this document" from "a third party this document cites". Two different
-- concepts sharing a word; the tables are named apart so the word cannot do the
-- reconciling on its own.
--
-- It is also what pays the exclusions back. The detectors drop a references
-- section wholesale -- it is not claim-bearing, and promoting it to a chapter
-- makes the summariser write a "References summary" that the deliberation then
-- surfaces as authoritative content. `ReferencesExtractor` re-walks the raw text
-- for that block so the list survives being dropped from the hierarchy.
--
-- `raw` AND NOT PARSED FIELDS. Anchor stores the entry as the paper printed it
-- and parses nothing; author/year/venue would be a citation-format parser this
-- repository has no reason to own, and a wrong one is worse than the string.
CREATE TABLE document_references (
    id          UUID PRIMARY KEY,

    document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,

    -- The number the document itself prints, from 1. It is how the paper's own
    -- prose points at the entry -- "[16]" -- so it is the entry's name and not
    -- its position.
    ref_num     INT  NOT NULL,

    -- The entry as it appears. NOT this server's prose: like `chunks.text` it is
    -- content the server did not write and cannot vouch for.
    raw         TEXT NOT NULL,

    CONSTRAINT document_references_numbered_from_one CHECK (ref_num >= 1),
    CONSTRAINT document_references_not_blank CHECK (raw <> ''),

    -- Deferred for the same reason as everything else here: a re-ingest rewrites
    -- the whole list.
    CONSTRAINT document_references_one_per_number
        UNIQUE (document_id, ref_num) DEFERRABLE INITIALLY DEFERRED
);

COMMENT ON TABLE document_references IS
    'What a document cites, scraped from its own reference list. NOT `citations`,'
    ' which is what an answer took from the corpus. Like `chunks`, this table'
    ' holds content the server did not write and cannot vouch for.';


-- WHAT IS NOT IN THIS FILE, so that its absence reads as a decision.
--
--   * No `documents.authors`. The design document asks for it here. Nothing on
--     this server can extract an author: Anchor's `DocumentMetadataExtractor`
--     does it with a model call against the first 2000 characters, and that
--     extractor belongs to the cascade slice. A JSONB column frozen one slice
--     ahead of the code that would shape it is the "two tables nothing writes
--     to" V18 refused, one column smaller. The slice that extracts authors adds
--     the column, and by then it knows whether the answer is an array of names
--     or names with affiliations.
--
--   * No `sections.is_abstract`. Anchor computes it, threads it through
--     `ParsedSection`, and persists it nowhere -- there is no column and no
--     consumer in Anchor either. Porting the column would be porting the
--     intention rather than the mechanism.
--
--   * No chunk- or paragraph-level pointer at a chapter. A paragraph's section
--     answers it in one join, and a second stored edge would be a third path to
--     the document to keep in agreement with the other two.
