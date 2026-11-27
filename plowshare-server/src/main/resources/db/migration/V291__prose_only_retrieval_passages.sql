SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '10min';

-- Freeze profile changes and source writes before retiring their derived code representation.
-- Use the same slot-before-source order as publication and activation.
SELECT slot FROM embedding_slots ORDER BY slot FOR UPDATE;
LOCK TABLE retrieval_passages IN ACCESS EXCLUSIVE MODE;

CREATE OR REPLACE FUNCTION embedding_repair_profiles() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
 INSERT INTO embedding_repair_scans(slot,space_id,store)
 SELECT NEW.slot,p.space_id,s.store FROM
  (SELECT NEW.active_space_id AS space_id UNION SELECT NEW.target_space_id) p
 CROSS JOIN unnest(ARRAY['memories','digests','documents','chunks','retrieval_passages']) s(store)
 WHERE p.space_id IS NOT NULL AND (NEW.slot='prose' OR s.store<>'retrieval_passages')
 ON CONFLICT DO NOTHING;
 IF ROW(NEW.target_space_id,NEW.target_mode,NEW.target_distance) IS DISTINCT FROM ROW(OLD.target_space_id,OLD.target_mode,OLD.target_distance) THEN
  UPDATE embedding_repair_scans SET next_at=now() WHERE slot=NEW.slot AND space_id=NEW.target_space_id;
 END IF;
 DELETE FROM embedding_repair_scans WHERE slot=NEW.slot
  AND space_id IS DISTINCT FROM NEW.active_space_id AND space_id IS DISTINCT FROM NEW.target_space_id;
 DELETE FROM embedding_repair_work WHERE slot=NEW.slot
  AND space_id IS DISTINCT FROM NEW.active_space_id AND space_id IS DISTINCT FROM NEW.target_space_id;
 RETURN NULL;
END $$;
CREATE OR REPLACE FUNCTION embedding_repair_source_changed() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE source_key TEXT; source_kind TEXT := ''; source_position INTEGER := 0;
BEGIN
 IF TG_OP='TRUNCATE' THEN
  DELETE FROM embedding_repair_work WHERE store=TG_TABLE_NAME;
  UPDATE embedding_repair_scans SET source_type=NULL,source_id=NULL,position=NULL,enumerated=false,next_at=now()
   WHERE store=TG_TABLE_NAME;
  RETURN NULL;
 END IF;
 IF TG_TABLE_NAME='retrieval_passages' THEN
  IF TG_OP='DELETE' THEN
   source_key:=OLD.source_id; source_kind:=OLD.source_type; source_position:=OLD.position;
  ELSE
   source_key:=NEW.source_id; source_kind:=NEW.source_type; source_position:=NEW.position;
  END IF;
 ELSE
  IF TG_OP='DELETE' THEN source_key:=OLD.id::text; ELSE source_key:=NEW.id::text; END IF;
 END IF;
 IF TG_OP='DELETE' THEN
  DELETE FROM embedding_repair_work WHERE store=TG_TABLE_NAME AND source_type=source_kind
   AND source_id=source_key AND position=source_position;
  RETURN NULL;
 END IF;
 -- Vector publication does not enqueue its own input again. Source revisions are guarded by V115.
 IF TG_OP='UPDATE' AND NEW.embedding_source_revision=OLD.embedding_source_revision THEN RETURN NULL; END IF;
 INSERT INTO embedding_repair_work(slot,space_id,store,source_type,source_id,position,source_revision)
 SELECT p.slot,p.space_id,TG_TABLE_NAME,source_kind,source_key,source_position,NEW.embedding_source_revision
 FROM (SELECT slot,active_space_id AS space_id FROM embedding_slots UNION
       SELECT slot,target_space_id FROM embedding_slots) p WHERE p.space_id IS NOT NULL
  AND (p.slot='prose' OR TG_TABLE_NAME<>'retrieval_passages')
 ON CONFLICT(slot,space_id,store,source_type,source_id,position) DO UPDATE
  SET source_revision=excluded.source_revision,available_at=now();
 RETURN NULL;
END $$;

-- The queue only schedules future work. Repair attempts and accounting keep their own history.
DELETE FROM embedding_repair_work WHERE slot='code' AND store='retrieval_passages';
DELETE FROM embedding_repair_scans WHERE slot='code' AND store='retrieval_passages';
ALTER TABLE embedding_repair_work ADD CONSTRAINT embedding_repair_work_prose_passages CHECK (slot='prose' OR store<>'retrieval_passages');
ALTER TABLE embedding_repair_scans ADD CONSTRAINT embedding_repair_scans_prose_passages CHECK (slot='prose' OR store<>'retrieval_passages');

DELETE FROM retrieval_passages_embedding_staging WHERE slot='code';
ALTER TABLE retrieval_passages_embedding_staging ADD CONSTRAINT retrieval_passage_staging_prose_only CHECK (slot='prose');

-- Only the generated code ANN indexes belong to this retirement. The legacy
-- and prose indexes keep serving. Index names include store 4 and slot 0.
DO $$
DECLARE code_index record;
BEGIN
    FOR code_index IN
        SELECT ns.nspname, c.relname
        FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid
        JOIN pg_namespace ns ON ns.oid=c.relnamespace
        WHERE i.indrelid='retrieval_passages'::regclass
          AND c.relname ~ '^emb_4_0_[0-9]+_[0-9]+_[0-9a-f]{32}$'
    LOOP
        EXECUTE format('DROP INDEX %I.%I', code_index.nspname, code_index.relname);
    END LOOP;
END $$;
DROP INDEX retrieval_passages_code_space;

-- Rewrite once to release TOAST space while retaining the common source-row
-- shape used by the embedding revision guard. Prose values and tokens survive.
ALTER TABLE retrieval_passages
    ALTER COLUMN code_embedding TYPE vector USING NULL::vector,
    ALTER COLUMN code_space_id TYPE text USING NULL::text,
    ALTER COLUMN code_source_revision TYPE bigint USING NULL::bigint,
    ADD CONSTRAINT retrieval_passages_prose_only CHECK (code_embedding IS NULL AND code_space_id IS NULL AND code_source_revision IS NULL);
