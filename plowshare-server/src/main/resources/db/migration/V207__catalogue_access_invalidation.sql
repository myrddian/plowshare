-- Catalogue access is independent of retained conversation/worker log visibility. Keep every
-- READABILITY trigger: log reads still fence on that wider domain. New catalogue readers and
-- socket invalidations watch KNOWLEDGE_ACCESS plus AUTHORITY instead. Restart server binaries
-- together; hints never replace current policy or the pending/future transaction commit fence.
CREATE SEQUENCE snapshot_cache_knowledge_access MINVALUE 1 START 1 CACHE 1 NO CYCLE;
SELECT nextval('snapshot_cache_knowledge_access');
ALTER TABLE snapshot_cache_writers DROP CONSTRAINT snapshot_cache_writers_domain_check;
ALTER TABLE snapshot_cache_writers ADD CONSTRAINT snapshot_cache_writers_domain_check CHECK(domain IN
 ('documents','search','schedules','relay','transcripts','authority','observations','readability',
 'queues','progress','types','extractions','knowledge_access'));
-- Thirteen domains * 64 first-writer allocations = at most 832 new rows between ordinary
-- maintenance attempts, still below the existing 1024-row pruning budget. Retention is unchanged.
CREATE OR REPLACE FUNCTION snapshot_cache_record_writer(selected TEXT[]) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE seen TEXT[]; added TEXT[]; first_write BOOLEAN;
BEGIN
 IF selected IS NULL OR cardinality(selected)=0 OR NOT selected <@ ARRAY[
  'documents','search','schedules','relay','transcripts','authority','observations','readability','queues','progress','types','extractions','knowledge_access'] THEN
  RAISE EXCEPTION 'Unknown projection writer domains';
 END IF;
 seen:=string_to_array(nullif(current_setting('plowshare.cache_writer_domains',true),''),',');
 IF selected <@ coalesce(seen,ARRAY[]::text[]) THEN RETURN; END IF;
 first_write:=seen IS NULL;
 SELECT array_agg(DISTINCT domain ORDER BY domain) INTO added FROM unnest(selected) domain
  WHERE NOT domain=ANY(coalesce(seen,ARRAY[]::text[]));
 seen:=coalesce(seen,ARRAY[]::text[])||added;
 INSERT INTO snapshot_cache_writers(xid,domain)
  SELECT pg_current_xact_id(),domain FROM unnest(added) domain
  ON CONFLICT(domain,xid) DO NOTHING;
 PERFORM set_config('plowshare.cache_writer_domains',array_to_string(seen,','),true);
 PERFORM set_config('plowshare.cache_dirty','on',true);
 IF first_write AND nextval('snapshot_cache_writer_allocations')%64=0 THEN
  PERFORM snapshot_cache_prune_writers();
 END IF;
END $$;
DROP FUNCTION snapshot_cache_capture_counters();
CREATE FUNCTION snapshot_cache_capture_counters()
 RETURNS TABLE(database_id UUID,documents BIGINT,search BIGINT,schedules BIGINT,relay BIGINT,transcripts BIGINT,authority BIGINT,observations BIGINT,readability BIGINT,queues BIGINT,progress BIGINT,types BIGINT,extractions BIGINT,knowledge_access BIGINT)
 LANGUAGE plpgsql VOLATILE PARALLEL UNSAFE AS $$
BEGIN
 IF pg_current_xact_id_if_assigned() IS NOT NULL THEN
  RAISE EXCEPTION 'Projection counter capture requires a fresh autocommit statement';
 END IF;
 SELECT identity.database_id,
  (SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM snapshot_cache_documents),
  (SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM snapshot_cache_search),
  (SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM snapshot_cache_schedules),
  (SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM snapshot_cache_relay),
  (SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM snapshot_cache_transcripts),
  (SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM snapshot_cache_authority),
  (SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM snapshot_cache_observations),
  (SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM snapshot_cache_readability),
  (SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM snapshot_cache_queues),
  (SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM snapshot_cache_progress),
  (SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM snapshot_cache_types),
  (SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM snapshot_cache_extractions),
  (SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM snapshot_cache_knowledge_access)
 INTO database_id,documents,search,schedules,relay,transcripts,authority,observations,readability,queues,progress,types,extractions,knowledge_access
 FROM snapshot_cache_identity identity WHERE identity.singleton;
 IF NOT FOUND THEN RAISE EXCEPTION 'Projection cache database identity is missing'; END IF;
 RETURN NEXT;
END $$;


CREATE FUNCTION snapshot_cache_knowledge_access_changed() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP<>'TRUNCATE' THEN
  IF NOT EXISTS(SELECT 1 FROM changed_rows) THEN RETURN NULL; END IF;
  IF TG_OP='UPDATE' THEN
   -- Transition comparisons project only policy/selection fields for the large source tables.
   -- Small policy/edge tables compare complete rows, conservatively covering future fields.
   IF TG_TABLE_NAME='information_revisions' THEN
    IF NOT EXISTS(SELECT id,resource_id,ordinal,availability,excluded FROM changed_rows
      EXCEPT SELECT id,resource_id,ordinal,availability,excluded FROM previous_rows) THEN RETURN NULL; END IF;
   ELSIF TG_TABLE_NAME='information_resources' THEN
    IF NOT EXISTS(SELECT id,home_scope_id,project_id FROM changed_rows
      EXCEPT SELECT id,home_scope_id,project_id FROM previous_rows) THEN RETURN NULL; END IF;
   ELSIF NOT EXISTS(SELECT to_jsonb(n) FROM changed_rows n EXCEPT SELECT to_jsonb(o) FROM previous_rows o) THEN RETURN NULL;
   END IF;
  END IF;
 END IF;
 PERFORM snapshot_cache_record_writer(ARRAY['knowledge_access']);
 PERFORM nextval('snapshot_cache_knowledge_access');
 -- Old listeners fail closed on unknown domains; do not send new vocabulary to their channels.
 PERFORM pg_notify('plowshare_knowledge_access_changes','knowledge_access');
 RETURN NULL;
END $$;
DO $$
DECLARE relation TEXT; operation TEXT; transition TEXT;
BEGIN
 FOREACH relation IN ARRAY ARRAY['information_revisions','information_resources',
  'information_links','information_inputs','information_resource_policies',
  'information_navigation_policies','information_reference_obligations','information_document_policies'] LOOP
  FOREACH operation IN ARRAY ARRAY['INSERT','UPDATE','DELETE'] LOOP
   transition:=CASE operation WHEN 'UPDATE' THEN 'OLD TABLE AS previous_rows NEW' WHEN 'DELETE' THEN 'OLD' ELSE 'NEW' END;
   EXECUTE format('CREATE TRIGGER snapshot_cache_knowledge_access_%s AFTER %s ON %I REFERENCING %s TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION snapshot_cache_knowledge_access_changed()',lower(operation),operation,relation,transition);
  END LOOP;
  EXECUTE format('CREATE TRIGGER snapshot_cache_knowledge_access_truncate AFTER TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION snapshot_cache_knowledge_access_changed()',relation);
 END LOOP;
END $$;

-- The old per-event identified socket fan-out is redundant with opaque projection hints. History
-- alone must still refresh status/events, even when no stage row changed. These triggers replace
-- that live wakeup without changing any Relay publication, recipient authority or durable cursor.
CREATE FUNCTION snapshot_cache_history_changed() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP<>'TRUNCATE' THEN
  IF NOT EXISTS(SELECT 1 FROM changed_rows) THEN RETURN NULL; END IF;
  IF TG_OP='UPDATE' THEN
   IF NOT EXISTS(SELECT 1 FROM changed_rows n FULL JOIN previous_rows o
     USING(revision_id,relay_project,relay_position) WHERE n IS DISTINCT FROM o) THEN RETURN NULL; END IF;
  END IF;
 END IF;
 PERFORM snapshot_cache_bump(ARRAY['progress']);
 RETURN NULL;
END $$;
CREATE TRIGGER snapshot_cache_history_insert AFTER INSERT ON information_revision_events REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION snapshot_cache_history_changed();
CREATE TRIGGER snapshot_cache_history_update AFTER UPDATE ON information_revision_events REFERENCING OLD TABLE AS previous_rows NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION snapshot_cache_history_changed();
CREATE TRIGGER snapshot_cache_history_delete AFTER DELETE ON information_revision_events REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION snapshot_cache_history_changed();
CREATE TRIGGER snapshot_cache_history_truncate AFTER TRUNCATE ON information_revision_events FOR EACH STATEMENT EXECUTE FUNCTION snapshot_cache_history_changed();
