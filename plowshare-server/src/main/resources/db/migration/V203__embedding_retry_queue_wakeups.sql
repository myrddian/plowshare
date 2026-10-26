-- Retained failures are durable scheduling facts, including those written during a mixed-version
-- rollout. Queue discovery must not postpone their deadline until the next recovery sweep.
-- Drain old trigger invocations before replacing them, so a rolling writer cannot commit an
-- UPDATE-only wakeup after a new server has begun queue enumeration.
LOCK TABLE memories_embedding_failures,digests_embedding_failures,documents_embedding_failures,
 chunks_embedding_failures,retrieval_passages_embedding_failures IN SHARE ROW EXCLUSIVE MODE;

CREATE OR REPLACE FUNCTION embedding_repair_progress() RETURNS TRIGGER LANGUAGE plpgsql AS $$
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
  -- A failure can arrive before initial queue enumeration (including on an older server in a
  -- rolling upgrade). UPDATE-only loses that wakeup if no key exists yet. The source guard has
  -- already checked its current revision; preserve its exact retry/infinite submission fence.
  INSERT INTO embedding_repair_work(slot,space_id,store,source_type,source_id,position,source_revision,available_at)
   SELECT NEW.slot,NEW.space_id,owner_store,source_kind,NEW.source_id::text,source_position,NEW.source_revision,NEW.retry_at
   FROM embedding_slots configured WHERE configured.slot=NEW.slot
    AND (configured.active_space_id=NEW.space_id OR configured.target_space_id=NEW.space_id)
   ON CONFLICT(slot,space_id,store,source_type,source_id,position) DO UPDATE
    SET source_revision=excluded.source_revision,available_at=excluded.available_at
    WHERE embedding_repair_work.source_revision<=excluded.source_revision;
 END IF;
 RETURN NULL;
END $$;
