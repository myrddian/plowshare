-- What an answer said it took from the corpus.
--
-- V18 ends its comment on `paragraphs` with "nothing points at `paragraphs`
-- yet. Citation is a later slice." This is that slice, and this is the first
-- table that points at it.
--
-- This file is written once. `MigrationsAreImmutableTest` freezes every
-- migration that is on `master` by asking git for `master`'s blobs, so what is
-- below cannot be edited afterwards and can only be added to. Everything here
-- is therefore either a fact or a decision whose alternative was worse; the
-- policies are in `documents.Citations` and `documents.CitationStore`, where
-- they can be revised, on the same split V18 made between the identity RULE and
-- the columns it is written over.
--
--
-- WHAT A CITATION IS, AND WHERE IT COMES FROM
--
-- `librarian`'s body already instructs the agent to name the paragraph beside
-- the claim it took from it -- "a sentence you cannot attach a paragraph to is
-- a sentence the corpus did not give you" -- and every hit of `document_search`
-- already carries the paragraph id to cite it by. So a citation was already IN
-- an answer, as prose, before this table existed; what did not exist was
-- anywhere to put one.
--
-- A row here is written by the server reading a finished answer, never by a
-- model calling a tool. That is the whole of the recording decision and it is
-- argued at length in `documents.Citations`; the short form is that a guardrail
-- which depends on a model remembering to call a second tool is the shape this
-- repository refuses everywhere else, and a citation the model forgot to record
-- is worse than none because the gap is invisible.
--
--
-- WHAT IS NOT IN THIS FILE, so that its absence reads as a decision.
--
--   * No claim text, and no quotation of the sentence the citation was attached
--     to. A citation says WHICH PARAGRAPH an answer drew on; the answer itself
--     is already in `entries` and in `turns`, verbatim, and copying a sentence
--     of it here would be a second, diverging record of one utterance. It would
--     also be the only place in this schema where prose derived from an
--     uploaded document sits in a column no renderer owns.
--
--   * No confidence, no rank, no similarity. Those are facts about the
--     retrieval that produced the hit, they are already in the tool result the
--     log holds, and none of them is a fact about the citation. A number here
--     would be read as the server's judgement of an answer it did not make.
--
--   * No unique constraint over (conversation, turn, paragraph). One answer
--     naming one paragraph twice is one citation and the writer deduplicates,
--     but that is a property of the writer: an answer naming a paragraph twice
--     for two different claims is a shape somebody may later want two rows for,
--     and a constraint here would have frozen the choice. `citations_made_in`
--     is the index that read wants either way.
--
--   * No lifecycle and no tombstone column. A citation is not contradicted, it
--     is only made stale, and staleness is derived rather than stored: it is
--     exactly `paragraph_id IS NULL`, which the foreign key below maintains. A
--     stored flag would be a second answer to a question the key already
--     answers, and the two would drift.


CREATE TABLE citations (
    id UUID PRIMARY KEY,

    -- WHAT WAS CITED, AND THE ONE COLUMN THE WHOLE TABLE IS ABOUT.
    --
    -- ON DELETE SET NULL, and this is the decision V18's own reasoning about
    -- surrogate keys forces. The three candidates:
    --
    --   CASCADE      -- a citation to a paragraph a re-ingest edited, or to a
    --                   document somebody removed, would simply cease to exist.
    --                   That is the failure V18 wrote the surrogate key to avoid
    --                   turned inside out: it says a dangling citation must be
    --                   "a staleness signal and never a silent repoint at
    --                   different text", and a row that deletes itself is a
    --                   silence, which is worse than either.
    --   no key       -- would keep the id and lose the ability to tell "stale"
    --                   from "someone wrote a uuid down wrong", and would make
    --                   V18's stated reason for putting documents in the SAME
    --                   database -- "a citation from `memories` to `paragraphs`
    --                   that could not be a foreign key" -- false on its first
    --                   use.
    --   SET NULL     -- the row survives, and the citation resolves to "the
    --                   paragraph this named is no longer in the corpus" rather
    --                   than to nothing or to different words.
    --
    -- What is lost with the id is nothing a reader could have used: the row it
    -- addressed is gone and its uuid is random, so it can never come back and
    -- can never be re-pointed. What a reader needs from a stale citation is the
    -- human coordinates, and those are `source_name` and `paragraph_ordinal`
    -- below, which is why they are stored rather than joined for.
    --
    -- NOTHING DELETES A DOCUMENT TODAY. There is no DELETE /v1/documents, no
    -- `DELETE FROM documents` in the server, and the retention sweep does not
    -- touch the corpus -- so the only reachable way to null this column is the
    -- one that matters most anyway: a re-ingest that edits the paragraph.
    -- `paragraphs.document_id` is already ON DELETE CASCADE, so the day a delete
    -- endpoint exists this key is what stops that cascade from reaching a
    -- citation.
    paragraph_id UUID REFERENCES paragraphs(id) ON DELETE SET NULL,

    -- WHICH DOCUMENT IT WAS IN, kept beside the paragraph rather than joined
    -- through it, and nulled by the same rule for the same reason. The two
    -- together are what make the three states of a citation distinguishable
    -- without a stored flag:
    --
    --   both set        -- resolves to the words, unchanged.
    --   document only   -- the paragraph was edited or removed by a later
    --                      ingest; the document is still here.
    --   neither         -- the document is no longer in the corpus.
    --
    -- A re-ingest never nulls this: `DocumentStore.write` keeps the document row
    -- and rewrites its derivation, which is the distinction V18 draws between a
    -- document's identity and its content.
    document_id UUID REFERENCES documents(id) ON DELETE SET NULL,

    -- WHAT THE CORPUS FILED IT AS WHEN IT WAS CITED, and WHERE IN IT.
    --
    -- Denormalised on purpose and NOT a cache: these are what the citation meant
    -- at the moment it was made, and they are the only thing left that can say
    -- so once the keys above are null. `documents.source_name` is mutable in
    -- principle -- a re-ingest under a new name is a new document, but nothing
    -- stops a later slice renaming one -- and a citation that silently followed
    -- a rename would be reporting a name the answer never said.
    --
    -- `paragraph_ordinal` is V18's mutable position column read at citation
    -- time, so it is "paragraph 12 as the document then stood" and is not an
    -- identity. Said out loud because the pair reads like a natural key and is
    -- not one: two citations may carry the same pair and mean different text.
    source_name       TEXT NOT NULL,
    paragraph_ordinal INT  NOT NULL,

    -- WHERE THE CITATION WAS MADE.
    --
    -- The conversation and the turn within it, which together address the
    -- `turns` row and the `entries` rows of one answer. No ON DELETE clause,
    -- exactly as `entries.conversation_id` and `turns.conversation_id` have
    -- none, and V11 gives the reason in one line: nothing in this schema deletes
    -- a conversation. Retention DEMOTES -- it nulls `entries.content` for a
    -- `tool_result` and moves a root to `ejected` -- and V19's constraint
    -- `entries_only_a_tool_result_is_ejected` is what guarantees the answer this
    -- citation was read out of is never one of them.
    --
    -- SO A CITATION DOES NOT DEGRADE UNDER EJECTION, and that is a property of
    -- pointing HERE rather than into the log. What ejection takes away is the
    -- search result the agent was shown -- the evidence -- and `result_read`
    -- already answers for that in its own words. The citation, the cited
    -- paragraph and the answer that made it are all somewhere ejection does not
    -- reach.
    --
    -- NULLABLE, because a run started on its own behalf -- POST
    -- /v1/agents/librarian/runs, and the MCP agent_run over it -- has no
    -- conversation. Such a run's answer can cite exactly as a turn's can, and a
    -- table that could not hold its citations would be recording the corpus's
    -- use only from the door a person happened to come in by. The two columns
    -- are null together or set together.
    conversation_id TEXT REFERENCES conversations(id),
    turn_ordinal    INT,

    -- WHICH AGENT SAID IT. A name and not a reference: agent definitions are
    -- files on disk that an operator may rename or remove, `turns.agent` is
    -- already a plain name for that reason, and a citation must outlive the
    -- definition that made it.
    agent    TEXT        NOT NULL,

    cited_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT citations_named
        CHECK (source_name <> '' AND agent <> ''),
    CONSTRAINT citations_counted_from_one
        CHECK (paragraph_ordinal >= 1),
    -- A turn is a position in a conversation, so neither half means anything
    -- without the other. `entries` says the same about its own pair by making
    -- both NOT NULL; here the pair is optional as a pair.
    CONSTRAINT citations_a_turn_is_in_a_conversation
        CHECK ((conversation_id IS NULL) = (turn_ordinal IS NULL)),
    CONSTRAINT citations_a_turn_is_counted_from_one
        CHECK (turn_ordinal IS NULL OR turn_ordinal >= 1)

    -- AND THE ONE THAT IS NOT HERE, because it cannot be. "A live paragraph key
    -- with a dead document key is impossible" is true of every state anything
    -- writes, and `CHECK (paragraph_id IS NULL OR document_id IS NOT NULL)`
    -- would still be wrong: deleting a document fires TWO referential actions --
    -- this table's own SET NULL on `document_id`, and the SET NULL above,
    -- reached through `paragraphs.document_id`'s ON DELETE CASCADE -- and
    -- nothing orders them. A CHECK in Postgres is immediate and cannot be
    -- deferred, so the delete fails outright whenever the document's key is
    -- nulled first. Measured, not reasoned about: the constraint was written,
    -- and `a_citation_to_a_deleted_document_is_kept_and_says_the_document_is
    -- _gone` failed with `new row for relation "citations" violates check
    -- constraint`.
    --
    -- So the invariant is a fact about `CitationStore.record`, which writes both
    -- keys from one join or writes nothing, and `Cited.standing()` reads the
    -- pair without relying on it: paragraph set means RESOLVES whatever the
    -- document says.
);

-- The backlink read: what has cited this document, and what has cited this
-- paragraph. `document_id` leads because "who has used this paper" is the
-- question a person asks about a corpus, and the second column serves the
-- narrower one from the same index.
CREATE INDEX citations_of ON citations (document_id, paragraph_id);

-- The provenance read: what did this answer cite. Ordered as the conversation
-- reads, so the listing needs no sort.
CREATE INDEX citations_made_in ON citations (conversation_id, turn_ordinal);

-- The listing read, newest first, which is what a person with no id in hand
-- gets. `id` second so the order is total: two citations out of one answer are
-- written from one instant.
CREATE INDEX citations_by_time ON citations (cited_at DESC, id);

COMMENT ON TABLE citations IS
    'What an answer said it took from the corpus. Written by the server reading'
    ' a finished answer, never by a model calling a tool. A row holds'
    ' coordinates and no document text: the words are in `paragraphs`, and'
    ' anything that renders them is putting an uploaded document in front of a'
    ' reader and owes the quoting rule `agents.DocumentTools` states.';
