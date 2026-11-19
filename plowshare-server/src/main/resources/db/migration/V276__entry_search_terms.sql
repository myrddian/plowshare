-- Stored term frequencies for conversation search: an inverted index PostgreSQL can read
-- best-first, and the per-conversation counts BM25 needs.
--
-- WHY. BM25 needs, for each matching entry, how often each question word occurs and how long the
-- entry is. `entries.text_search` holds that, but only as one value per entry: reading two
-- frequencies means unpacking every lexeme the entry has (thousands for a tool result). And GIN
-- returns matches in no order, so ranking has meant scoring every match. This table stores one
-- row per (entry, lexeme) so that
--   * scoring reads the question's own lexemes and nothing else, and
--   * a search can read one word's postings best-first and stop, instead of scoring them all.
--
-- THE SAME NUMBERS. `tf` and `doc_length` are exactly what `EntryStore.SEARCH_SQL` derives per
-- search: `greatest(cardinality(positions), 1)` and `search_length`, both over the evidence
-- lexemes. Scores computed from this table are therefore the scores computed from the entry.
--
-- NO SCORE IS STORED, ON PURPOSE. A BM25 score depends on the reader: document frequency and
-- average length are computed over the conversations that reader may see, at search time. What
-- does not depend on the reader is that, for one word, the score rises with `tf` and falls with
-- `doc_length`. So the index orders one word's postings by `tf`, then `doc_length`, and a search
-- turns "score at least S" into "for each tf, length at most L(tf, S)" using its own statistics.
-- That bound is exact for every reader and cannot go stale. Lucene does the same thing for the
-- same reason: it stores (frequency, length) pairs per block and computes maximum scores at
-- query time (org.apache.lucene.codecs.Impact, org.apache.lucene.search.MaxScoreCache).
-- `tf` never exceeds 256 (PostgreSQL keeps at most 256 positions per lexeme), so a search makes
-- 256 index range scans each time it reads a word this way (twice per word in one search: once
-- to estimate a threshold, once to read what can reach it), most of them empty. A range holds the postings of every conversation, visible or
-- not; hidden ones are read and discarded, so a reader scoped to few conversations of many
-- pays for the others' postings in the ranges it reads. That is a cost, not a disclosure.
--
-- COMPACT KEYS. A posting names its entry as (conversation_no, ordinal). Conversation ids are
-- long text and would otherwise be repeated in every posting and both indexes; the first
-- measurement with text keys was 6.6 times the size of `entries`.
--
-- RELIED UPON, UNLIKE THE PASSAGES. A search reads matches from this table, so a missing row is
-- a missing hit. It is therefore written and removed by triggers in the entry's own
-- transaction, as the generated lexeme column is maintained by the database: there is no writer
-- that can forget it.
--
-- COST. One row per distinct lexeme per searchable entry, with two indexes. This is the price of
-- an inverted index in a row store and is the largest object this search owns. Each posting
-- written or removed also adjusts one stored count (`entry_search_term_counts`, below) when the
-- transaction commits. Measure both on a restored copy of the target database before applying.
--
-- TWO INDEXES, EACH THE ONLY ONE THAT ANSWERS ITS QUESTION. The table is read in two ways: one
-- entry's posting for one word (the primary key), and one word's postings best-first (the second
-- index). Built plainly, each index could answer the other's question by reading far too much,
-- and the planner chose exactly that whenever its statistics were stale: a probe for one posting
-- walked every posting of the word, 8,544 times in one search (4 to 15 seconds, measured in
-- review). Statistics are not relied on here, so each read is given one index that fits it:
--   * the best-first index is partial (`WHERE tf >= 1`, true of every row). The planner uses a
--     partial index only for a query whose own conditions imply its predicate, and the
--     per-entry probes do not say `tf >= 1`;
--   * its leading column is the lexeme under collation "POSIX", which orders exactly as "C"
--     does but is a different collation. The best-first reads compare under "POSIX", which is
--     not an index condition on the primary key (collation "C"); the probes compare under "C",
--     which is not one on the best-first index.
-- What this enforces is that no other index can answer either read through an index condition.
-- A sequential scan or a bitmap scan of this table remains legal for both reads; neither was
-- observed in any planner state tested (stale, absent and fresh statistics, the tables believed
-- empty, a generic plan). See EntryStore.PASSAGE_SEARCH_SQL for the reads. Nothing here changes
-- what a query returns.
--
-- THE BACKFILL parses nothing (it reads the stored lexemes) but writes every posting and then
-- every count. Tables are filled bare, and their keys, indexes and foreign keys are built
-- afterwards: a foreign key present during the load would queue one check per row in memory.
-- `statement_timeout` bounds each statement separately, not the migration. Nine statements
-- grow with the archive: the pass over `entries` for the conversation statistics, the loads of
-- postings and of counts, four index builds and two foreign-key validations. The worst case
-- this file permits is therefore nine times the limit below (four and a half hours), not the
-- limit. An archive that needs anything near the limit for one statement must be rehearsed and
-- scheduled as an outage of that length: writes to `entries` and new conversations wait for
-- the whole migration, because the lock is taken first so that no entry can slip between the
-- load and the triggers.
SET LOCAL lock_timeout = '2s';
SET LOCAL statement_timeout = '30min';
LOCK TABLE conversations, entries IN SHARE ROW EXCLUSIVE MODE;

-- One row per conversation: a short number for its postings, and the counts a search needs
-- about it.
--
-- THE COUNTS REPLACE A SCAN. BM25 needs the number of searchable entries and their average
-- length over the conversations the reader may see, and every search reports what it could not
-- reach. Reading one narrow row per visible entry for that was a cost that grew with the
-- archive rather than with the answer. Summing these rows over the visible conversations gives
-- the same numbers from one row per conversation. They are per conversation, never global,
-- because visibility is decided per conversation.
--   searched      entries with a role and retained text (the BM25 corpus)
--   total_length  the sum of their `search_length`
--   ejected       entries whose payload was ejected
--   recorded_only entries of a kind that is recorded and never said
--
-- THE ROW IS CREATED WITH THE CONVERSATION, by the trigger below, not by its first entry. An
-- entry write therefore only ever reads this table until its transaction commits, and two
-- transactions first writing to one conversation have nothing to race for. It costs every new
-- conversation one more small row, whether or not it ever holds a searchable entry.
--
-- THE NUMBER IS A POSITION. A search marks the conversations its reader may see as bits at
-- these numbers (EntryStore.PASSAGE_SEARCH_SQL, `seen`), so a number is never negative. The
-- identity already starts at one and cannot be supplied by a writer; the CHECK states it.
CREATE TABLE entry_search_conversations (
 conversation_no integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY CHECK (conversation_no >= 1),
 conversation_id text NOT NULL UNIQUE
  REFERENCES conversations(id) ON UPDATE CASCADE ON DELETE CASCADE,
 searched integer NOT NULL DEFAULT 0 CHECK (searched >= 0),
 total_length bigint NOT NULL DEFAULT 0 CHECK (total_length >= 0),
 ejected integer NOT NULL DEFAULT 0 CHECK (ejected >= 0),
 recorded_only integer NOT NULL DEFAULT 0 CHECK (recorded_only >= 0)
 -- Updated by every commit that writes to the conversation. Half-empty pages keep those updates
 -- heap-only and let each page reclaim its own dead versions.
) WITH (fillfactor = 50);

CREATE FUNCTION entry_search_conversation_opened() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 INSERT INTO entry_search_conversations(conversation_id) VALUES (NEW.id)
  ON CONFLICT (conversation_id) DO NOTHING;
 RETURN NEW;
END $$;

CREATE TRIGGER entry_search_conversation AFTER INSERT ON conversations
 FOR EACH ROW EXECUTE FUNCTION entry_search_conversation_opened();

-- Keys, indexes and foreign keys follow the backfill at the end of this file.
CREATE TABLE entry_search_terms (
 conversation_no integer NOT NULL,
 ordinal integer NOT NULL,
 doc_length integer NOT NULL CHECK (doc_length >= 1),
 tf smallint NOT NULL CHECK (tf BETWEEN 1 AND 256),
 -- The entry's passage (V275) holding this lexeme most often, earliest first: the excerpt and
 -- duplicate group of a single-word hit, known without reading the passages at search time.
 -- NULL when no stored passage carries the lexeme whole. As wide as the passage position it
 -- copies: an entry of tens of millions of characters has more than 32,767 passages, and a
 -- narrower column refused the write of the entry itself.
 best_position integer,
 -- Byte order: lexemes are compared for equality only, and "C" keeps the index independent of
 -- the database's locale.
 lexeme text COLLATE "C" NOT NULL
 -- Autovacuum analyses a table after a tenth of it has changed. A young archive grows by more
 -- than that between passes, and the planner then reasons from the size it had. A fiftieth
 -- shortens that window five times for the price of a sampled read; the inserts-only vacuum
 -- threshold is lowered with it so index-only reads find their pages marked all-visible. The
 -- reads below do not depend on either, but the joins around them are better for it.
) WITH (autovacuum_analyze_scale_factor = 0.02, autovacuum_vacuum_insert_scale_factor = 0.05);

-- How many of one conversation's searchable entries contain one lexeme: the number of that
-- conversation's postings for it, kept so that nobody has to count them.
--
-- THE COUNTS REPLACE A SCAN. BM25 weighs a question word by its document frequency among the
-- entries the reader may see. Counting the word's visible postings is exact but linear in them:
-- a word in half the archive cost a read of half the archive's postings to rank one page.
-- Summing these rows over the visible conversations gives the same number. They are per
-- conversation, never global, for the reason the conversation counts are: visibility is decided
-- per conversation, and a statistic may cover only what the reader may see.
--
-- WHAT A SEARCH READS. One index range per question word: the rows of every conversation that
-- holds the word, visible or not, with hidden ones discarded after they are read. That is at
-- most one row per conversation in the archive per word, not per visible conversation: a reader
-- scoped to a few conversations of very many still pays for the word's spread across all of
-- them. Nothing hidden reaches a statistic; this is a cost, not a disclosure.
--
-- A SUM OF CURRENT POSTINGS, NEVER AN EDIT HISTORY. A row exists exactly when the conversation
-- has a posting for the lexeme and `entries` is how many. The CHECK keeps a zero from being
-- stored, but it is not what detects drift: the trigger removes a count at one instead of
-- decrementing it, so the CHECK cannot fire there. Drift is detected by the trigger counting
-- the rows it changed (see `entry_search_counts_settled`).
--
-- `entries` IS DELIBERATELY IN NO INDEX. A commit increments one row per distinct lexeme of
-- each entry it added. With the count outside every index those are heap-only updates, which
-- write no index entry; the fillfactor leaves each page room for them. The price is that the
-- search's sum reads the heap row behind each index entry.
CREATE TABLE entry_search_term_counts (
 lexeme text COLLATE "C" NOT NULL,
 conversation_no integer NOT NULL,
 entries integer NOT NULL CHECK (entries > 0)
) WITH (fillfactor = 80, autovacuum_analyze_scale_factor = 0.02);

-- Changes to a conversation's statistics and lexeme counts that a transaction has made and not
-- yet applied: one row per statement per conversation, written when the statement ends and
-- applied and removed when the transaction commits. It is empty between transactions.
--
-- WHY A QUEUE. The statistics must be applied at commit (see the lock order below), and applied
-- once per conversation, not once per entry. Applying one entry at a time made a bulk load
-- quadratic: every entry updated the same statistics row and the same count rows again, each
-- update stepping over the versions the transaction had already left behind (17.7 seconds to
-- commit 20,000 entries of one conversation, 68.6 for 40,000, measured in review). A statement
-- now adds up its own changes, and the commit adds up the statements.
--
-- `lexemes` and `changes` are parallel arrays: how many of the statement's entries gained
-- (positive) or lost (negative) each lexeme, lexemes that net to nothing left out.
--
-- A row belongs to the conversation's statistics row, not to its id. Deleting a conversation
-- removes what was pending for it, so a conversation deleted and created again under the same
-- id in one transaction starts from nothing instead of inheriting the old one's removals.
CREATE TABLE entry_search_pending (
 pending_no bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 conversation_no integer NOT NULL
  REFERENCES entry_search_conversations(conversation_no) ON DELETE CASCADE,
 searched integer NOT NULL,
 total_length bigint NOT NULL,
 ejected integer NOT NULL,
 recorded_only integer NOT NULL,
 lexemes text[] NOT NULL,
 changes integer[] NOT NULL
);

CREATE INDEX entry_search_pending_of_conversation ON entry_search_pending (conversation_no);

-- One entry's postings, from its stored evidence lexemes and its stored passages.
CREATE FUNCTION entry_search_terms_of(
 entry_conversation text, entry_ordinal integer, vector tsvector, evidence_length integer)
RETURNS TABLE(lexeme text, tf smallint, doc_length integer, best_position integer)
LANGUAGE sql STABLE AS $$
 SELECT t.lexeme, greatest(cardinality(t.positions), 1)::smallint, evidence_length,
        held.position
   FROM unnest(vector) AS t
   LEFT JOIN (
    SELECT DISTINCT ON (u.lexeme) u.lexeme, p.position
      FROM entry_search_passages p CROSS JOIN LATERAL unnest(p.text_search) AS u
     WHERE p.conversation_id = entry_conversation AND p.ordinal = entry_ordinal
     ORDER BY u.lexeme, cardinality(u.positions) DESC, p.position
   ) AS held ON held.lexeme = t.lexeme
$$;

-- HOW THE TABLES ARE KEPT, AND IN WHAT LOCK ORDER.
--
-- Three steps, because the rows have different owners.
--
-- POSTINGS BELONG TO ONE ENTRY and are rewritten at once, by `entry_search_terms_changed`, a row
-- trigger in the statement that writes the entry. Whoever writes an entry holds that entry's
-- row lock, and nobody else writes its postings, so this takes no lock that the statement did
-- not already hold. It only reads the conversation's number.
--
-- STATISTICS BELONG TO ONE CONVERSATION and are shared by every writer to it. When a statement
-- ends, `entry_search_counts_staged` adds up what the statement changed, per conversation, and
-- leaves one row in `entry_search_pending`. That writes a new row and locks nothing shared.
--
-- AT COMMIT, `entry_search_counts_settled` (a deferred constraint trigger on the pending rows)
-- settles the whole transaction on its first event: it takes everything pending, conversation
-- by conversation in ascending conversation number, adds each conversation's changes up,
-- applies them once and removes them. Later events find nothing left. This is what makes the
-- lock order safe. An earlier version updated the conversation's row inside each entry write. A fold
-- inserts a summary and then updates older entries; an ejection updates one of those entries.
-- The fold then held the conversation's row and wanted the entry, the ejection held the entry
-- and wanted the conversation's row, and PostgreSQL killed one of them. Deferred to commit, a
-- transaction takes every entry row lock it will ever take before its first statistics row
-- lock, and waits for nothing but statistics rows from then on.
--   * No lost update. A commit that finds the row locked waits for the other commit and then
--     reads its result: under READ COMMITTED each statement takes a new snapshot. Under
--     REPEATABLE READ or SERIALIZABLE the update fails with a serialization error instead, at
--     commit, and the whole transaction is retried by its caller as for any such failure.
--   * Transactions that write to several conversations. Before this migration two of them did
--     not block each other at all: an entry write takes no lock that another conversation's
--     writer wants, only a shared key lock on its conversation. Statistics rows are exclusive,
--     so two such transactions that settled in the order their statements ran (which is the
--     order the events fire) deadlocked at commit whenever they had written the same two
--     conversations in opposite orders; a stress run of multi-conversation transactions in
--     review produced hundreds of such deadlocks. Hence the ascending order above: every
--     commit takes its statistics rows lowest number first, and within a conversation its
--     lexeme counts in byte order, so no two commits wait for each other in a cycle. No
--     application path writes entries to two conversations in one transaction today.
--   * `SET CONSTRAINTS ALL IMMEDIATE` and the deletion of a conversation (next point) take
--     statistics rows when their statement runs, not in that order, and can still deadlock
--     against a commit or each other.
--   * Deleting a conversation locks its statistics row at once, by cascade, not at commit. A
--     transaction that deletes a conversation therefore still takes that row before later
--     entry locks. Nothing deletes conversations today.
--   * Lexeme counts are only written after the conversation's statistics row is locked, so
--     they add no lock order of their own.
--
-- COST IS PER CONVERSATION AND PER DISTINCT LEXEME, NOT PER ENTRY. One statement inserting
-- forty thousand entries into one conversation leaves one pending row, and the commit updates
-- the statistics row once and each lexeme's count once. A transaction of many statements
-- leaves one pending row per statement and still settles each conversation once.
--
-- CONSEQUENCES OF DEFERRING.
--   * Inside the writing transaction the postings are current and the counts are not. A search
--     run there before commit would rank with the old document frequencies and corpus size.
--     Searches run in their own transactions, and at commit both become visible together.
--   * `SET CONSTRAINTS ALL IMMEDIATE` settles what is pending at once and each later statement
--     as it ends. The numbers stay right; that transaction gives up the lock order above.
--   * The numbers are deltas between old and new rows, so several changes to one entry in a
--     transaction add up, and a failure anywhere rolls back entry, postings and counts together.
--   * The posting trigger is fired only by an UPDATE that names the key, role, content or
--     provenance in its SET list (`UPDATE OF`), so a supersession or a learning mark does not
--     run it and does not read the entry's text. That is every way those columns change: the
--     lexeme columns are generated from content and provenance, and the one BEFORE UPDATE
--     trigger on `entries` (`trajectory_image_ejection`) assigns only `tool_observation`. A
--     BEFORE trigger added later that assigned content, role or provenance without the
--     statement naming them would change an entry without firing this trigger; a test pins the
--     list of BEFORE triggers for that reason.
--   * Every UPDATE of `entries` still runs the statement trigger: a trigger with transition
--     tables may not have a column list. It compares what the statistics are computed from in
--     the old and new rows it was handed (role, whether there is content, the evidence
--     lexemes and their length, ejection), never the text itself. An update that changes none
--     of those stages nothing; it does read each updated row's lexemes to find that out.
--
-- On INSERT/UPDATE the posting trigger must run after V275's so the passages it reads exist:
-- PostgreSQL fires same-event triggers in name order, and "entry_search_passages" sorts before
-- "entry_search_terms". The foreign key that carries a changed key to the passages fires before
-- both for the same reason ("RI_..." sorts first).
CREATE FUNCTION entry_search_terms_changed() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
 held integer;
BEGIN
 IF TG_OP = 'UPDATE'
    AND ROW(OLD.conversation_id, OLD.ordinal, OLD.content, OLD.role, OLD.input_provenance)
        IS NOT DISTINCT FROM
        ROW(NEW.conversation_id, NEW.ordinal, NEW.content, NEW.role, NEW.input_provenance) THEN
  RETURN NEW;
 END IF;
 IF TG_OP <> 'INSERT' THEN
  SELECT conversation_no INTO held FROM entry_search_conversations
   WHERE conversation_id = OLD.conversation_id;
  DELETE FROM entry_search_terms WHERE conversation_no = held AND ordinal = OLD.ordinal;
  IF TG_OP = 'DELETE' THEN
   RETURN OLD;
  END IF;
 END IF;
 IF NEW.role IS NOT NULL AND NEW.content IS NOT NULL THEN
  SELECT conversation_no INTO held FROM entry_search_conversations
   WHERE conversation_id = NEW.conversation_id;
  IF held IS NULL THEN
   -- Cannot happen while the conversation trigger exists. Repaired rather than refused, because
   -- an entry must not become unwritable over a row this projection can recreate.
   INSERT INTO entry_search_conversations(conversation_id) VALUES (NEW.conversation_id)
    ON CONFLICT (conversation_id) DO NOTHING;
   SELECT conversation_no INTO STRICT held FROM entry_search_conversations
    WHERE conversation_id = NEW.conversation_id;
  END IF;
  INSERT INTO entry_search_terms(conversation_no, ordinal, doc_length, tf, best_position, lexeme)
  SELECT held, NEW.ordinal, t.doc_length, t.tf, t.best_position, t.lexeme
    FROM entry_search_terms_of(
           NEW.conversation_id, NEW.ordinal,
           coalesce(NEW.evidence_text_search, NEW.text_search),
           coalesce(NEW.evidence_search_length, NEW.search_length)) AS t;
 END IF;
 RETURN NEW;
END $$;

-- What one statement did to each conversation's statistics, from the rows it removed (counted
-- negatively) and the rows it wrote (counted positively). The three branches differ only in
-- which rows those are; an UPDATE counts a row out and in again only if what the statistics are
-- computed from changed, so an entry that keeps its conversation changes only the lexemes it
-- lost or gained, and one that moves leaves the old conversation whole and arrives whole in the
-- new one.
--
-- THE UPDATE BRANCH COMPARES THE INPUTS OF THE SUMS, NOT THE TEXT: role, whether there is
-- content, whether the payload was ejected, the evidence length and the evidence lexemes. Those
-- five are everything `sums` and `words` read of a row, so a row in which they are equal would
-- be counted out and in again for the same amounts and change nothing; leaving it out is the
-- same result without unpacking its lexemes twice. Comparing `content` instead fetched the
-- whole stored text of every updated row, old and new, for updates that had not touched it.
--
-- A conversation without a statistics row cannot occur while the conversation trigger exists.
-- If it did, the pending row's NOT NULL conversation number refuses the statement.
CREATE FUNCTION entry_search_counts_staged() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP = 'INSERT' THEN
  WITH moves AS MATERIALIZED (
   SELECT 1 AS sign, n.conversation_id, n.role, n.content IS NOT NULL AS said,
          coalesce(n.evidence_text_search, n.text_search) AS vector,
          coalesce(n.evidence_search_length, n.search_length) AS length,
          n.ejected_at IS NOT NULL AS ejected
     FROM new_rows n
  ), sums AS (
   SELECT m.conversation_id,
          coalesce(sum(m.sign) FILTER (WHERE m.role IS NOT NULL AND m.length IS NOT NULL), 0)
            AS searched,
          coalesce(sum(m.sign * m.length::bigint) FILTER (WHERE m.role IS NOT NULL), 0)
            AS total_length,
          coalesce(sum(m.sign) FILTER (WHERE m.ejected), 0) AS ejected,
          coalesce(sum(m.sign) FILTER (WHERE m.role IS NULL), 0) AS recorded_only
     FROM moves m GROUP BY m.conversation_id
  ), words AS (
   SELECT x.conversation_id, array_agg(x.lexeme) AS lexemes, array_agg(x.change) AS changes
     FROM (SELECT m.conversation_id, u.lexeme, sum(m.sign)::integer AS change
             FROM moves m CROSS JOIN LATERAL unnest(tsvector_to_array(m.vector)) AS u(lexeme)
            WHERE m.role IS NOT NULL AND m.said
            GROUP BY m.conversation_id, u.lexeme
           HAVING sum(m.sign) <> 0) x
    GROUP BY x.conversation_id
  )
  INSERT INTO entry_search_pending(
   conversation_no, searched, total_length, ejected, recorded_only, lexemes, changes)
  SELECT c.conversation_no, s.searched, s.total_length, s.ejected, s.recorded_only,
         coalesce(w.lexemes, '{}'), coalesce(w.changes, '{}')
    FROM sums s
    LEFT JOIN entry_search_conversations c ON c.conversation_id = s.conversation_id
    LEFT JOIN words w ON w.conversation_id = s.conversation_id
   WHERE s.searched <> 0 OR s.total_length <> 0 OR s.ejected <> 0 OR s.recorded_only <> 0
      OR w.lexemes IS NOT NULL;
 ELSIF TG_OP = 'DELETE' THEN
  WITH moves AS MATERIALIZED (
   SELECT -1 AS sign, o.conversation_id, o.role, o.content IS NOT NULL AS said,
          coalesce(o.evidence_text_search, o.text_search) AS vector,
          coalesce(o.evidence_search_length, o.search_length) AS length,
          o.ejected_at IS NOT NULL AS ejected
     FROM old_rows o
  ), sums AS (
   SELECT m.conversation_id,
          coalesce(sum(m.sign) FILTER (WHERE m.role IS NOT NULL AND m.length IS NOT NULL), 0)
            AS searched,
          coalesce(sum(m.sign * m.length::bigint) FILTER (WHERE m.role IS NOT NULL), 0)
            AS total_length,
          coalesce(sum(m.sign) FILTER (WHERE m.ejected), 0) AS ejected,
          coalesce(sum(m.sign) FILTER (WHERE m.role IS NULL), 0) AS recorded_only
     FROM moves m GROUP BY m.conversation_id
  ), words AS (
   SELECT x.conversation_id, array_agg(x.lexeme) AS lexemes, array_agg(x.change) AS changes
     FROM (SELECT m.conversation_id, u.lexeme, sum(m.sign)::integer AS change
             FROM moves m CROSS JOIN LATERAL unnest(tsvector_to_array(m.vector)) AS u(lexeme)
            WHERE m.role IS NOT NULL AND m.said
            GROUP BY m.conversation_id, u.lexeme
           HAVING sum(m.sign) <> 0) x
    GROUP BY x.conversation_id
  )
  INSERT INTO entry_search_pending(
   conversation_no, searched, total_length, ejected, recorded_only, lexemes, changes)
  SELECT c.conversation_no, s.searched, s.total_length, s.ejected, s.recorded_only,
         coalesce(w.lexemes, '{}'), coalesce(w.changes, '{}')
    FROM sums s
    LEFT JOIN entry_search_conversations c ON c.conversation_id = s.conversation_id
    LEFT JOIN words w ON w.conversation_id = s.conversation_id
   WHERE s.searched <> 0 OR s.total_length <> 0 OR s.ejected <> 0 OR s.recorded_only <> 0
      OR w.lexemes IS NOT NULL;
 ELSE
  WITH moves AS MATERIALIZED (
   SELECT -1 AS sign, o.conversation_id, o.role, o.content IS NOT NULL AS said,
          coalesce(o.evidence_text_search, o.text_search) AS vector,
          coalesce(o.evidence_search_length, o.search_length) AS length,
          o.ejected_at IS NOT NULL AS ejected
     FROM old_rows o
    WHERE NOT EXISTS (SELECT 1 FROM new_rows n
                       WHERE n.conversation_id = o.conversation_id AND n.ordinal = o.ordinal
                         AND ROW(n.role, n.content IS NULL, n.ejected_at IS NULL,
                                 coalesce(n.evidence_search_length, n.search_length),
                                 coalesce(n.evidence_text_search, n.text_search))
                          IS NOT DISTINCT FROM
                          ROW(o.role, o.content IS NULL, o.ejected_at IS NULL,
                              coalesce(o.evidence_search_length, o.search_length),
                              coalesce(o.evidence_text_search, o.text_search)))
   UNION ALL
   SELECT 1, n.conversation_id, n.role, n.content IS NOT NULL AS said,
          coalesce(n.evidence_text_search, n.text_search) AS vector,
          coalesce(n.evidence_search_length, n.search_length) AS length,
          n.ejected_at IS NOT NULL AS ejected
     FROM new_rows n
    WHERE NOT EXISTS (SELECT 1 FROM old_rows o
                       WHERE o.conversation_id = n.conversation_id AND o.ordinal = n.ordinal
                         AND ROW(o.role, o.content IS NULL, o.ejected_at IS NULL,
                                 coalesce(o.evidence_search_length, o.search_length),
                                 coalesce(o.evidence_text_search, o.text_search))
                          IS NOT DISTINCT FROM
                          ROW(n.role, n.content IS NULL, n.ejected_at IS NULL,
                              coalesce(n.evidence_search_length, n.search_length),
                              coalesce(n.evidence_text_search, n.text_search)))
  ), sums AS (
   SELECT m.conversation_id,
          coalesce(sum(m.sign) FILTER (WHERE m.role IS NOT NULL AND m.length IS NOT NULL), 0)
            AS searched,
          coalesce(sum(m.sign * m.length::bigint) FILTER (WHERE m.role IS NOT NULL), 0)
            AS total_length,
          coalesce(sum(m.sign) FILTER (WHERE m.ejected), 0) AS ejected,
          coalesce(sum(m.sign) FILTER (WHERE m.role IS NULL), 0) AS recorded_only
     FROM moves m GROUP BY m.conversation_id
  ), words AS (
   SELECT x.conversation_id, array_agg(x.lexeme) AS lexemes, array_agg(x.change) AS changes
     FROM (SELECT m.conversation_id, u.lexeme, sum(m.sign)::integer AS change
             FROM moves m CROSS JOIN LATERAL unnest(tsvector_to_array(m.vector)) AS u(lexeme)
            WHERE m.role IS NOT NULL AND m.said
            GROUP BY m.conversation_id, u.lexeme
           HAVING sum(m.sign) <> 0) x
    GROUP BY x.conversation_id
  )
  INSERT INTO entry_search_pending(
   conversation_no, searched, total_length, ejected, recorded_only, lexemes, changes)
  SELECT c.conversation_no, s.searched, s.total_length, s.ejected, s.recorded_only,
         coalesce(w.lexemes, '{}'), coalesce(w.changes, '{}')
    FROM sums s
    LEFT JOIN entry_search_conversations c ON c.conversation_id = s.conversation_id
    LEFT JOIN words w ON w.conversation_id = s.conversation_id
   WHERE s.searched <> 0 OR s.total_length <> 0 OR s.ejected <> 0 OR s.recorded_only <> 0
      OR w.lexemes IS NOT NULL;
 END IF;
 RETURN NULL;
END $$;

-- Applies everything the transaction has pending, once per conversation, at commit.
--
-- THE FIRST EVENT SETTLES EVERY CONVERSATION, LOWEST NUMBER FIRST. Events fire in the order the
-- statements ran, which is the order the writer chose; settling one conversation per event took
-- statistics rows in that order, and two transactions that had written the same conversations
-- in opposite orders deadlocked at commit. The pending rows this function can see are its own
-- transaction's (another transaction's are uncommitted until it has settled and removed them),
-- so the first event reads which conversations they name, sorts the numbers and settles each in
-- turn. Every commit therefore locks statistics rows in ascending conversation number, and the
-- events that follow find their rows gone.
--
-- AN IMPOSSIBLE STATE REFUSES THE COMMIT. Every lexeme that lost postings must have a count row
-- holding at least as many, so the rows deleted (the count was exactly what was lost) and
-- lowered (it was more) must number exactly the lexemes that lost postings; if not, the counts
-- no longer describe the postings and the transaction fails rather than carrying the error
-- forward. A statistic that would go negative is refused by its CHECK. What cannot be seen
-- here is a count that is too high, or a missing row for a lexeme that gained postings (it is
-- recreated): only a recount against the postings finds those.
--
-- Within a conversation, lexemes are applied in byte order. Two commits never take the same
-- count rows in opposite orders in any case, since both hold the conversation's statistics row.
CREATE FUNCTION entry_search_counts_settled() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
 pending integer[];
 held integer;
 staged bigint;
 searched_delta bigint;
 length_delta numeric;
 ejected_delta bigint;
 recorded_delta bigint;
 lost text[];
 lost_by integer[];
 gained text[];
 gained_by integer[];
 emptied integer;
 lowered integer;
BEGIN
 -- This event's own row is gone when an earlier event of the commit settled the transaction, or
 -- when the conversation was deleted. Looked up by key first: gathering the pending rows would
 -- step over every row the earlier event removed, once per event.
 IF NOT EXISTS (SELECT 1 FROM entry_search_pending WHERE pending_no = NEW.pending_no) THEN
  RETURN NULL;
 END IF;
 -- The numbers are read before anything is removed, so the loop does not depend on what a
 -- query still open would see of rows this function deletes.
 SELECT array_agg(DISTINCT p.conversation_no ORDER BY p.conversation_no) INTO pending
   FROM entry_search_pending p;
 FOREACH held IN ARRAY pending LOOP
  WITH taken AS (
   DELETE FROM entry_search_pending WHERE conversation_no = held
   RETURNING searched, total_length, ejected, recorded_only, lexemes, changes
  ), words AS (
   SELECT u.lexeme, sum(u.change)::integer AS change
     FROM taken t CROSS JOIN LATERAL unnest(t.lexemes, t.changes) AS u(lexeme, change)
    GROUP BY u.lexeme HAVING sum(u.change) <> 0
  )
  SELECT (SELECT count(*) FROM taken),
         (SELECT coalesce(sum(searched), 0) FROM taken),
         (SELECT coalesce(sum(total_length), 0) FROM taken),
         (SELECT coalesce(sum(ejected), 0) FROM taken),
         (SELECT coalesce(sum(recorded_only), 0) FROM taken),
         (SELECT array_agg(lexeme ORDER BY lexeme COLLATE "C") FROM words WHERE change < 0),
         (SELECT array_agg(-change ORDER BY lexeme COLLATE "C") FROM words WHERE change < 0),
         (SELECT array_agg(lexeme ORDER BY lexeme COLLATE "C") FROM words WHERE change > 0),
         (SELECT array_agg(change ORDER BY lexeme COLLATE "C") FROM words WHERE change > 0)
    INTO staged, searched_delta, length_delta, ejected_delta, recorded_delta,
         lost, lost_by, gained, gained_by;
  -- Nothing left for this number: its conversation was deleted since the numbers were read.
  CONTINUE WHEN staged = 0;
  UPDATE entry_search_conversations SET
    searched = searched + searched_delta,
    total_length = total_length + length_delta,
    ejected = ejected + ejected_delta,
    recorded_only = recorded_only + recorded_delta
   WHERE conversation_no = held;
  IF NOT FOUND THEN
   RAISE EXCEPTION 'search statistics row % is gone while changes to it are pending', held;
  END IF;
  IF lost IS NOT NULL THEN
   DELETE FROM entry_search_term_counts k USING unnest(lost, lost_by) AS g(lexeme, by)
    WHERE k.lexeme = g.lexeme AND k.conversation_no = held AND k.entries = g.by;
   GET DIAGNOSTICS emptied = ROW_COUNT;
   UPDATE entry_search_term_counts k SET entries = k.entries - g.by
     FROM unnest(lost, lost_by) AS g(lexeme, by)
    WHERE k.lexeme = g.lexeme AND k.conversation_no = held AND k.entries > g.by;
   GET DIAGNOSTICS lowered = ROW_COUNT;
   IF emptied + lowered <> cardinality(lost) THEN
    RAISE EXCEPTION
     'search lexeme counts of conversation number % disagree with its postings: % of % counts found',
     held, emptied + lowered, cardinality(lost);
   END IF;
  END IF;
  IF gained IS NOT NULL THEN
   INSERT INTO entry_search_term_counts AS k (lexeme, conversation_no, entries)
   SELECT g.lexeme, held, g.by FROM unnest(gained, gained_by) AS g(lexeme, by)
   ON CONFLICT (lexeme, conversation_no) DO UPDATE SET entries = k.entries + EXCLUDED.entries;
  END IF;
 END LOOP;
 RETURN NULL;
END $$;

-- TRUNCATE fires no row or statement trigger of the kinds above and no foreign key reaches
-- these tables from `entries`, so this statement trigger empties them, with whatever the
-- truncating transaction itself had pending. The conversations remain, with nothing counted.
--
-- WHAT A TRANSACTION THAT HAS WRITTEN ENTRIES MAY TRUNCATE. `TRUNCATE entries CASCADE` works:
-- it does not reach these tables except through this trigger, which uses DELETE. A TRUNCATE
-- that names or cascades to `conversations` does not: it cascades through
-- `entry_search_conversations` to `entry_search_pending`, whose rows written by the same
-- transaction still have their deferred event queued, and PostgreSQL refuses with
-- `cannot TRUNCATE "entry_search_pending" because it has pending trigger events`. Such a
-- transaction must truncate before it writes entries, or settle first with
-- `SET CONSTRAINTS ALL IMMEDIATE`. A transaction that has written no entries truncates either.
CREATE FUNCTION entry_search_terms_truncated() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 DELETE FROM entry_search_pending;
 DELETE FROM entry_search_terms;
 DELETE FROM entry_search_term_counts;
 UPDATE entry_search_conversations
    SET searched = 0, total_length = 0, ejected = 0, recorded_only = 0
  WHERE searched <> 0 OR total_length <> 0 OR ejected <> 0 OR recorded_only <> 0;
 RETURN NULL;
END $$;

-- The backfill. Every conversation gets its row, with or without entries.
INSERT INTO entry_search_conversations(conversation_id, searched, total_length, ejected, recorded_only)
SELECT c.id, coalesce(x.searched, 0), coalesce(x.total_length, 0), coalesce(x.ejected, 0),
       coalesce(x.recorded_only, 0)
  FROM conversations c
  LEFT JOIN (
   SELECT e.conversation_id,
          count(*) FILTER (WHERE e.role IS NOT NULL
                             AND coalesce(e.evidence_search_length, e.search_length) IS NOT NULL)
            AS searched,
          coalesce(sum(coalesce(e.evidence_search_length, e.search_length))
                     FILTER (WHERE e.role IS NOT NULL), 0) AS total_length,
          count(*) FILTER (WHERE e.ejected_at IS NOT NULL) AS ejected,
          count(*) FILTER (WHERE e.role IS NULL) AS recorded_only
     FROM entries e GROUP BY e.conversation_id
  ) x ON x.conversation_id = c.id;

INSERT INTO entry_search_terms(conversation_no, ordinal, doc_length, tf, best_position, lexeme)
SELECT c.conversation_no, e.ordinal, t.doc_length, t.tf, t.best_position, t.lexeme
  FROM entries e
  JOIN entry_search_conversations c ON c.conversation_id = e.conversation_id
 CROSS JOIN LATERAL entry_search_terms_of(
   e.conversation_id, e.ordinal,
   coalesce(e.evidence_text_search, e.text_search),
   coalesce(e.evidence_search_length, e.search_length)) AS t
 WHERE e.role IS NOT NULL AND e.content IS NOT NULL;

-- Entry first: scoring probes by (entry, lexeme), and removing an entry's postings must not
-- scan the table.
ALTER TABLE entry_search_terms
 ADD PRIMARY KEY (conversation_no, ordinal, lexeme) INCLUDE (tf, doc_length);

-- One lexeme's postings, best first within each frequency. Covers everything a best-first read
-- needs, so it does not touch the table. Partial and under "POSIX" for the reason given at the
-- top of this file: only a query that says `tf >= 1` and compares the lexeme under "POSIX" can
-- use it, and for such a query the primary key offers no index condition.
CREATE INDEX entry_search_terms_best_first
 ON entry_search_terms (lexeme COLLATE "POSIX", tf, doc_length, conversation_no, ordinal)
 INCLUDE (best_position)
 WHERE tf >= 1;

ALTER TABLE entry_search_terms
 ADD FOREIGN KEY (conversation_no)
  REFERENCES entry_search_conversations(conversation_no) ON DELETE CASCADE;

INSERT INTO entry_search_term_counts(lexeme, conversation_no, entries)
SELECT t.lexeme, t.conversation_no, count(*)
  FROM entry_search_terms t GROUP BY t.lexeme, t.conversation_no;

-- Lexeme first: a search reads one word's counts across conversations as one index range.
ALTER TABLE entry_search_term_counts ADD PRIMARY KEY (lexeme, conversation_no);

-- Removing a conversation removes its counts by cascade, which looks them up by conversation;
-- the primary key leads with the lexeme and cannot serve that. Untouched by increments because
-- `entries` is not in it.
CREATE INDEX entry_search_term_counts_of_conversation
 ON entry_search_term_counts (conversation_no);

ALTER TABLE entry_search_term_counts
 ADD FOREIGN KEY (conversation_no)
  REFERENCES entry_search_conversations(conversation_no) ON DELETE CASCADE;

-- The column list is what keeps a metadata update from running this trigger at all; the
-- function's own comparison remains for an UPDATE that names a column without changing it.
CREATE TRIGGER entry_search_terms
 AFTER INSERT OR DELETE OR UPDATE OF conversation_id, ordinal, content, role, input_provenance
 ON entries
 FOR EACH ROW EXECUTE FUNCTION entry_search_terms_changed();

-- A transition table may serve one event only, hence three triggers on one function. A trigger
-- with transition tables may not have a column list, so the UPDATE one runs for every UPDATE.
CREATE TRIGGER entry_search_counts_inserted AFTER INSERT ON entries
 REFERENCING NEW TABLE AS new_rows
 FOR EACH STATEMENT EXECUTE FUNCTION entry_search_counts_staged();

CREATE TRIGGER entry_search_counts_updated AFTER UPDATE ON entries
 REFERENCING OLD TABLE AS old_rows NEW TABLE AS new_rows
 FOR EACH STATEMENT EXECUTE FUNCTION entry_search_counts_staged();

CREATE TRIGGER entry_search_counts_deleted AFTER DELETE ON entries
 REFERENCING OLD TABLE AS old_rows
 FOR EACH STATEMENT EXECUTE FUNCTION entry_search_counts_staged();

CREATE CONSTRAINT TRIGGER entry_search_counts_settled AFTER INSERT ON entry_search_pending
 DEFERRABLE INITIALLY DEFERRED
 FOR EACH ROW EXECUTE FUNCTION entry_search_counts_settled();

CREATE TRIGGER entry_search_terms_truncated AFTER TRUNCATE ON entries
 FOR EACH STATEMENT EXECUTE FUNCTION entry_search_terms_truncated();

-- A search planned before these tables had statistics was measured in review at 9.7 seconds: the
-- planner priced every read at one row and chose to scan all of a word's postings once per
-- frequency. Autovacuum would analyse them eventually; a search must not depend on when. The
-- query is fenced so its index reads do not depend on estimates (see
-- EntryStore.PASSAGE_SEARCH_SQL), and this removes the state in which that was observed.
ANALYZE entry_search_conversations;
ANALYZE entry_search_terms;
ANALYZE entry_search_term_counts;
