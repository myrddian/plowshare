-- Stored lexical passages for conversation search, so a hit can be grouped and paginated
-- without reparsing every matching entry.
--
-- WHY. `EntryStore.SEARCH_SQL` groups hits by their `ts_headline` excerpt, and `ts_headline`
-- reparses the whole source text. The cost of one search is therefore the total size of every
-- matching entry, whatever the page size. This table moves the expensive half to write time: each
-- searchable entry is cut once into bounded passages, and each passage keeps its lexemes and a
-- whitespace-insensitive key. A search can then choose the best passage per matched entry, group
-- by the stored key, paginate, and highlight only the passages on the page.
--
-- WHAT IS STORED, AND WHAT IS NOT. Offsets, lexemes and a key. The passage text is NOT copied:
-- `start_at`/`span` are character offsets into the entry's evidence text, read back from
-- `entries` at page time. The excerpt therefore always comes from the current revision of the
-- entry, and the archive's text is not stored twice.
--
-- NOT AN AUTHORITY. Nothing here decides a match, visibility, a corpus statistic or a score:
-- those come from `entries` and from the postings and counts of V276. A passage row only chooses
-- which part of an already authorized, already matched entry is shown and which duplicates
-- collapse together. A missing passage row degrades to the whole-entry excerpt for that one hit;
-- it never hides or admits a hit.
--
-- NO PROVIDER, NO QUEUE. `retrieval_passages` is published after embedding and is unsearchable
-- until then. Lexical search must not depend on an inference service, so this projection is
-- written by a trigger in the transaction that writes the entry.
--
-- The backfill and trigger creation need a maintenance budget, not the interactive one. The
-- trigger takes a lock on `entries` that is held until this migration commits, so no entry can be
-- written between the backfill and the trigger becoming active. Rehearse the backfill on a
-- restored copy of the target database before applying: it parses every retained entry once.
-- `statement_timeout` bounds each statement, not the migration: the backfill and the validation
-- of the foreign key added after it are the two long ones, so the worst case is twice the limit.
SET LOCAL lock_timeout = '2s';
SET LOCAL statement_timeout = '15min';

-- Cuts one unbroken run longer than a passage into pieces of at most 1000 bytes.
--
-- Procedural on purpose. A set-based version carries the run through every output row, and
-- `substr` on multibyte text is linear in the offset, so both are quadratic for a
-- 600 000-character minified bundle. Byte slicing is constant time per piece. A cut never lands
-- inside a UTF-8 sequence: continuation bytes are 10xxxxxx, so the edge backs off at most three.
CREATE FUNCTION conversation_search_long_run(run text)
RETURNS TABLE(cut integer, piece text)
LANGUAGE plpgsql IMMUTABLE STRICT PARALLEL SAFE AS $$
DECLARE
 bytes bytea := convert_to(run, 'UTF8');
 total integer := length(bytes);
 edge integer := 0;
 upto integer;
BEGIN
 cut := 0;
 WHILE edge < total LOOP
  upto := least(edge + 1000, total);
  WHILE upto < total AND get_byte(bytes, upto) & 192 = 128 LOOP
   upto := upto - 1;
  END LOOP;
  piece := convert_from(substring(bytes FROM edge + 1 FOR upto - edge), 'UTF8');
  RETURN NEXT;
  cut := cut + 1;
  edge := upto;
 END LOOP;
END $$;

-- Cuts text into consecutive passages of roughly 900 characters, at whitespace where there is
-- any. Concatenating the passages in position order reproduces the input exactly, and
-- `substr(value, start_at, length(passage))` is the passage, so only offsets need storing.
--
-- A passage is the pieces that START inside one 900-character window, so it is at most 900 plus
-- one piece (1000) long. Boundaries depend only on the text before them: two entries sharing a
-- prefix share its passages, which is what lets a copied block collapse into one group.
-- Passages do not overlap; a phrase straddling a boundary is still matched (matching is
-- entry-level) but its excerpt shows the better half.
CREATE FUNCTION conversation_search_passages(value text)
RETURNS TABLE("position" integer, start_at integer, passage text)
LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE AS $$
 WITH runs AS (
  SELECT found[1] AS run, n
    FROM regexp_matches(value, '^\s+|\S+\s*', 'g') WITH ORDINALITY AS matched(found, n)
 ), pieces AS (
  SELECT run AS piece, n, 0 AS cut FROM runs WHERE length(run) <= 1000
  UNION ALL
  SELECT long.piece, r.n, long.cut
    FROM runs r CROSS JOIN LATERAL conversation_search_long_run(r.run) AS long
   WHERE length(r.run) > 1000
 ), placed AS (
  SELECT piece, n, cut,
         coalesce(sum(length(piece)) OVER (
           ORDER BY n, cut ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING), 0) AS before
    FROM pieces
 )
 SELECT (before / 900)::integer, (min(before) + 1)::integer,
        string_agg(piece, '' ORDER BY n, cut)
   FROM placed GROUP BY before / 900
$$;

-- The key two excerpts share when they read the same: `EntryStore.SEARCH_SQL`'s normalisation of
-- a snippet, applied to the stored passage instead of to a headline computed per search.
CREATE FUNCTION conversation_search_passage_key(passage text) RETURNS text
LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE AS $$
 SELECT md5(regexp_replace(btrim(passage), '\s+', ' ', 'g'))
$$;

CREATE TABLE entry_search_passages (
 conversation_id text NOT NULL,
 ordinal integer NOT NULL,
 position integer NOT NULL,
 -- One-based character offset and length within conversation_evidence_text(content, provenance).
 start_at integer NOT NULL CHECK (start_at >= 1),
 span integer NOT NULL CHECK (span >= 1),
 passage_key text NOT NULL,
 -- 'english' is welded in for V23's reason: the query side spells the same configuration, and a
 -- mismatch does not fail, it quietly stops matching.
 text_search tsvector NOT NULL,
 PRIMARY KEY (conversation_id, ordinal, position)
 -- The foreign key to the entry follows the backfill at the end of this file.
 -- Analysed after a fiftieth of the table has changed, not a tenth: see V276.
) WITH (autovacuum_analyze_scale_factor = 0.02);

-- Rewrites one entry's passages when what search reads from it changes.
--
-- The searchable set is SEARCH_SQL's: a role and retained content. Ejection sets content to NULL
-- and so removes the passages with the lexemes. Supersession, learning marks and other metadata
-- updates do not name content, role or provenance in their SET list and so do not fire the
-- trigger (`UPDATE OF` below): comparing the old and new text to find it unchanged meant
-- fetching both from storage, about a fifth of a millisecond per row for a 495 kB entry. The
-- comparison in the function remains for an UPDATE that names a column without changing it.
--
-- `UPDATE OF` looks at the statement's SET list, not at the row. It misses nothing here because
-- these three columns change in no other way: no BEFORE UPDATE trigger on `entries` assigns
-- them (the only one, `trajectory_image_ejection`, assigns `tool_observation`), and the
-- generated lexeme columns follow content and provenance. A BEFORE trigger added later that
-- assigned one of them would have to be named here; a test pins the list.
--
-- An entry whose key changes keeps its passages: the foreign key below carries the new key to
-- them. When the same update also rewrites the text, the old passages may already sit under the
-- new key (the foreign key's trigger fires first), so both keys are cleared before the insert.
CREATE FUNCTION entry_search_passages_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP = 'UPDATE' THEN
  IF ROW(OLD.content, OLD.role, OLD.input_provenance)
     IS NOT DISTINCT FROM ROW(NEW.content, NEW.role, NEW.input_provenance) THEN
   RETURN NEW;
  END IF;
  DELETE FROM entry_search_passages
   WHERE conversation_id = OLD.conversation_id AND ordinal = OLD.ordinal;
  DELETE FROM entry_search_passages
   WHERE conversation_id = NEW.conversation_id AND ordinal = NEW.ordinal;
 END IF;
 IF NEW.role IS NOT NULL AND NEW.content IS NOT NULL THEN
  INSERT INTO entry_search_passages(
   conversation_id, ordinal, position, start_at, span, passage_key, text_search)
  SELECT NEW.conversation_id, NEW.ordinal, cut.position, cut.start_at, length(cut.passage),
         conversation_search_passage_key(cut.passage), to_tsvector('english', cut.passage)
    FROM conversation_search_passages(
           conversation_evidence_text(NEW.content, NEW.input_provenance)) AS cut
   WHERE btrim(cut.passage) <> '';
 END IF;
 RETURN NEW;
END $$;

CREATE TRIGGER entry_search_passages
 AFTER INSERT OR UPDATE OF content, role, input_provenance ON entries
 FOR EACH ROW EXECUTE FUNCTION entry_search_passages_changed();

INSERT INTO entry_search_passages(
 conversation_id, ordinal, position, start_at, span, passage_key, text_search)
SELECT e.conversation_id, e.ordinal, cut.position, cut.start_at, length(cut.passage),
       conversation_search_passage_key(cut.passage), to_tsvector('english', cut.passage)
  FROM entries e
 CROSS JOIN LATERAL conversation_search_passages(
   conversation_evidence_text(e.content, e.input_provenance)) AS cut
 WHERE e.role IS NOT NULL AND e.content IS NOT NULL AND btrim(cut.passage) <> '';

-- Deleting an entry removes its passages, renumbering or moving it takes them along, and
-- TRUNCATE of entries must name CASCADE; a passage may not outlive its source. Added after the
-- backfill so the load does not queue one check per passage in memory.
ALTER TABLE entry_search_passages
 ADD FOREIGN KEY (conversation_id, ordinal)
  REFERENCES entries(conversation_id, ordinal) ON UPDATE CASCADE ON DELETE CASCADE;

-- A search must not wait for autovacuum to learn the size of this table (see V276).
ANALYZE entry_search_passages;
