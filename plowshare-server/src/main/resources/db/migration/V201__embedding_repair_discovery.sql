-- Pending keys are facts about work, never permission grants. The worker still checks current
-- eligibility, source/target versions and the retained submission fence before any provider I/O.
CREATE TABLE embedding_repair_work (
 slot TEXT NOT NULL REFERENCES embedding_slots(slot),
 space_id TEXT NOT NULL REFERENCES embedding_spaces(id),
 store TEXT NOT NULL CHECK(store IN ('memories','digests','documents','chunks','retrieval_passages')),
 source_type TEXT NOT NULL DEFAULT '', source_id TEXT NOT NULL, position INTEGER NOT NULL DEFAULT 0 CHECK(position>=0),
 source_revision BIGINT NOT NULL CHECK(source_revision>=0),
 available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(slot,space_id,store,source_type,source_id,position)
);
CREATE INDEX embedding_repair_due ON embedding_repair_work(slot,space_id,store,available_at);
CREATE INDEX embedding_repair_available ON embedding_repair_work(available_at);

-- Keyset reconciliation has one durable owner per profile/store/page, including across replicas.
-- No sequence watermark is used: late commits are captured by source triggers in their own TX.
CREATE TABLE embedding_repair_scans (
 slot TEXT NOT NULL REFERENCES embedding_slots(slot),
 space_id TEXT NOT NULL REFERENCES embedding_spaces(id),
 store TEXT NOT NULL CHECK(store IN ('memories','digests','documents','chunks','retrieval_passages')),
 source_type TEXT, source_id TEXT, position INTEGER,
 enumerated BOOLEAN NOT NULL DEFAULT false,
 next_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(slot,space_id,store)
);
CREATE INDEX embedding_repair_scans_due ON embedding_repair_scans(next_at);
CREATE FUNCTION embedding_repair_profiles() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
 INSERT INTO embedding_repair_scans(slot,space_id,store)
 SELECT NEW.slot,p.space_id,s.store FROM
  (SELECT NEW.active_space_id AS space_id UNION SELECT NEW.target_space_id) p
 CROSS JOIN unnest(ARRAY['memories','digests','documents','chunks','retrieval_passages']) s(store)
 WHERE p.space_id IS NOT NULL ON CONFLICT DO NOTHING;
 IF ROW(NEW.target_space_id,NEW.target_mode,NEW.target_distance) IS DISTINCT FROM ROW(OLD.target_space_id,OLD.target_mode,OLD.target_distance) THEN
  UPDATE embedding_repair_scans SET next_at=now() WHERE slot=NEW.slot AND space_id=NEW.target_space_id;
 END IF;
 DELETE FROM embedding_repair_scans WHERE slot=NEW.slot
  AND space_id IS DISTINCT FROM NEW.active_space_id AND space_id IS DISTINCT FROM NEW.target_space_id;
 DELETE FROM embedding_repair_work WHERE slot=NEW.slot
  AND space_id IS DISTINCT FROM NEW.active_space_id AND space_id IS DISTINCT FROM NEW.target_space_id;
 RETURN NULL;
END $$;
CREATE TRIGGER embedding_repair_profiles AFTER UPDATE ON embedding_slots
 FOR EACH ROW EXECUTE FUNCTION embedding_repair_profiles();
INSERT INTO embedding_repair_scans(slot,space_id,store)
 SELECT p.slot,p.space_id,s.store FROM
  (SELECT slot,active_space_id AS space_id FROM embedding_slots UNION
   SELECT slot,target_space_id FROM embedding_slots) p
 CROSS JOIN unnest(ARRAY['memories','digests','documents','chunks','retrieval_passages']) s(store)
 WHERE p.space_id IS NOT NULL;

CREATE FUNCTION embedding_repair_source_changed() RETURNS TRIGGER LANGUAGE plpgsql AS $$
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
 ON CONFLICT(slot,space_id,store,source_type,source_id,position) DO UPDATE
  SET source_revision=excluded.source_revision,available_at=now();
 RETURN NULL;
END $$;

-- Publication and failure timestamps update discovery atomically, including 'infinity' for an
-- uncertain retained submission. An expired discovery reservation never overrides that fence.
CREATE FUNCTION embedding_repair_progress() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE source_kind TEXT := ''; source_position INTEGER := 0; owner_store TEXT;
BEGIN
 owner_store:=replace(replace(TG_TABLE_NAME,'_embedding_staging',''),'_embedding_failures','');
 IF TG_OP='DELETE' THEN
  IF owner_store='retrieval_passages' THEN source_kind:=OLD.source_type; source_position:=OLD.position; END IF;
  UPDATE embedding_repair_work SET available_at=now()
   WHERE slot=OLD.slot AND space_id=OLD.space_id AND store=owner_store
    AND source_type=source_kind AND source_id=OLD.source_id::text AND position=source_position
    AND source_revision=OLD.source_revision;
  RETURN NULL;
 END IF;
 IF owner_store='retrieval_passages' THEN source_kind:=NEW.source_type; source_position:=NEW.position; END IF;
 IF TG_TABLE_NAME LIKE '%_embedding_staging' THEN
  -- Only a rebuilding slot needs an activation wakeup. Ordinary active-space publication must
  -- not turn a steady stream of source edits into repeated recovery scans of unrelated content.
  UPDATE embedding_repair_scans scan SET next_at=least(scan.next_at,now())
   FROM embedding_slots configured WHERE configured.slot=NEW.slot
    AND scan.slot=NEW.slot AND scan.space_id=NEW.space_id
    AND ROW(configured.active_space_id,configured.active_mode,configured.active_distance)
     IS DISTINCT FROM ROW(configured.target_space_id,configured.target_mode,configured.target_distance);
  DELETE FROM embedding_repair_work WHERE slot=NEW.slot AND space_id=NEW.space_id
   AND store=owner_store AND source_type=source_kind AND source_id=NEW.source_id::text
   AND position=source_position AND source_revision=NEW.source_revision;
 ELSE
  UPDATE embedding_repair_work SET available_at=NEW.retry_at
   WHERE slot=NEW.slot AND space_id=NEW.space_id AND store=owner_store
    AND source_type=source_kind AND source_id=NEW.source_id::text AND position=source_position
    AND source_revision=NEW.source_revision;
 END IF;
 RETURN NULL;
END $$;
DO $$
DECLARE owner_store TEXT;
BEGIN
 FOREACH owner_store IN ARRAY ARRAY['memories','digests','documents','chunks','retrieval_passages'] LOOP
  EXECUTE format('CREATE TRIGGER embedding_repair_source AFTER INSERT OR UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION embedding_repair_source_changed()',owner_store);
  EXECUTE format('CREATE TRIGGER embedding_repair_truncate AFTER TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION embedding_repair_source_changed()',owner_store);
  EXECUTE format('CREATE TRIGGER embedding_repair_progress AFTER INSERT OR UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION embedding_repair_progress()',owner_store||'_embedding_staging');
  EXECUTE format('CREATE TRIGGER embedding_repair_progress AFTER INSERT OR UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION embedding_repair_progress()',owner_store||'_embedding_failures');
 END LOOP;
END $$;
