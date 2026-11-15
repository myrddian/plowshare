-- Preserve complete inputs, but derive evidence text only from explicitly classified spans.
-- Legacy rows have no classification and keep their existing searchable content. No text-pattern
-- backfill can prove whether a historical input was copied, so none is attempted.
-- Table rewrites/index construction need a finite maintenance budget, not the interactive budget.
SET LOCAL statement_timeout = '5min';
SET LOCAL lock_timeout = '2s';

ALTER TABLE entries ADD COLUMN input_provenance jsonb;
ALTER TABLE entries ADD CONSTRAINT entries_input_provenance_is_a_span CHECK (
 input_provenance IS NULL OR (
  kind='utterance' AND jsonb_typeof(input_provenance)='object'
  AND input_provenance ?& ARRAY['start','end','sources']
  AND jsonb_typeof(input_provenance->'start')='number'
  AND jsonb_typeof(input_provenance->'end')='number'
  AND (input_provenance->>'start') ~ '^[0-9]+$'
  AND (input_provenance->>'end') ~ '^[0-9]+$'
  AND (input_provenance->>'start')::integer < (input_provenance->>'end')::integer
  AND (content IS NULL OR (input_provenance->>'end')::integer <= length(content))
  AND jsonb_typeof(input_provenance->'sources')='array'
  AND jsonb_array_length(input_provenance->'sources') <= 1024
 ));

CREATE FUNCTION conversation_evidence_text(value text, provenance jsonb) RETURNS text
LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
 SELECT CASE WHEN provenance IS NULL THEN value ELSE
  left(value,(provenance->>'start')::integer) || E'\n\n' ||
  substring(value FROM (provenance->>'end')::integer+1) END
$$;
-- A provenance edit invalidates both retained snapshots and in-flight passage publication.
-- Unclassified historical revisions remain byte-for-byte compatible with the old hashes.
-- The prefix and canonical pair encoding keep classified revisions distinct from raw text
-- containing serialized metadata, without relying on ambiguous text concatenation.
CREATE FUNCTION conversation_source_revision(value text, provenance jsonb) RETURNS text
LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
 SELECT CASE WHEN provenance IS NULL THEN md5(value) ELSE
  'input:' || md5(jsonb_build_array(value,provenance)::text) END
$$;

-- Search replies expose one navigation reference plus the total, never thousands per occurrence.
CREATE FUNCTION conversation_input_provenance_view(provenance jsonb) RETURNS jsonb
LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE AS $$
 SELECT jsonb_build_object('start',provenance->'start','end',provenance->'end',
  'sources',jsonb_path_query_array(provenance,'$.sources[0 to 0]'),
  'sourceCount',jsonb_array_length(provenance->'sources'))
$$;

-- Sparse projections reuse the original text/vector/length for unclassified rows rather than
-- duplicating every historical transcript payload. The expression index matches the read.
ALTER TABLE entries ADD COLUMN evidence_content text GENERATED ALWAYS AS
 (CASE WHEN input_provenance IS NOT NULL THEN conversation_evidence_text(content,input_provenance) END) STORED;
ALTER TABLE entries ADD COLUMN evidence_text_search tsvector GENERATED ALWAYS AS
 (CASE WHEN input_provenance IS NOT NULL THEN to_tsvector('english',conversation_evidence_text(content,input_provenance)) END) STORED;
ALTER TABLE entries ADD COLUMN evidence_search_length integer GENERATED ALWAYS AS
 (CASE WHEN input_provenance IS NOT NULL THEN conversation_search_length(to_tsvector('english',conversation_evidence_text(content,input_provenance))) END) STORED;
CREATE INDEX entries_evidence_text_search ON entries USING gin((coalesce(evidence_text_search,text_search)));

-- Chunk context separately so no passage crosses its provenance boundary. Full-input search can
-- still inspect these passages, but normal retrieval removes them before distance/grouping/limits.
ALTER TABLE retrieval_passages ADD COLUMN supplied_context boolean NOT NULL DEFAULT false;
CREATE OR REPLACE FUNCTION retrieval_entry_changed() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE key text;
BEGIN
 IF TG_OP='DELETE' THEN
  DELETE FROM retrieval_sources WHERE source_type='entry' AND source_id=OLD.conversation_id || ':' || OLD.ordinal;
  RETURN OLD;
 END IF;
 key := NEW.conversation_id || ':' || NEW.ordinal;
 IF TG_OP='UPDATE' AND ROW(OLD.content,OLD.role,OLD.ejected_at,OLD.input_provenance)
  IS NOT DISTINCT FROM ROW(NEW.content,NEW.role,NEW.ejected_at,NEW.input_provenance) THEN
  RETURN NEW;
 END IF;
 DELETE FROM retrieval_sources WHERE source_type='entry' AND source_id=key;
 IF NEW.role IS NOT NULL AND NEW.content IS NOT NULL AND btrim(NEW.content)<>'' AND NEW.ejected_at IS NULL THEN
  INSERT INTO retrieval_sources(source_type,source_id,conversation_id,ordinal,source_hash)
  VALUES('entry',key,NEW.conversation_id,NEW.ordinal,conversation_source_revision(NEW.content,NEW.input_provenance));
 END IF;
 RETURN NEW;
END $$;
