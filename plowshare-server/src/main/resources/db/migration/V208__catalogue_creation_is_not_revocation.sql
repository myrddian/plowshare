-- Creation changes catalogue membership; it does not revoke already visible content. Keep
-- KNOWLEDGE_ACCESS for every existing cache/disclosure fence and add a separate hard-hint domain.
-- Drain old trigger writers before installing this classifier; restart server binaries together.
DO $$
DECLARE relation REGCLASS;
BEGIN
 FOR relation IN SELECT unnest(ARRAY['information_resources','information_revisions','documents',
  'information_links','information_inputs','information_resource_policies',
  'information_navigation_policies','information_reference_obligations','information_document_policies']::regclass[])
 LOOP EXECUTE format('LOCK TABLE %s IN SHARE ROW EXCLUSIVE MODE',relation); END LOOP;
END $$;
CREATE SEQUENCE snapshot_cache_knowledge_revocations MINVALUE 1 START 1 CACHE 1 NO CYCLE;
SELECT nextval('snapshot_cache_knowledge_revocations');
ALTER TABLE snapshot_cache_writers DROP CONSTRAINT snapshot_cache_writers_domain_check;
ALTER TABLE snapshot_cache_writers ADD CONSTRAINT snapshot_cache_writers_domain_check CHECK(domain IN
 ('documents','search','schedules','relay','transcripts','authority','observations','readability',
 'queues','progress','types','extractions','knowledge_access','knowledge_revocations'));
-- Fourteen domains * 64 first-writer allocations = at most 896 new rows between ordinary
-- maintenance attempts, still below the existing 1024-row pruning budget. Retention is unchanged.
CREATE OR REPLACE FUNCTION snapshot_cache_record_writer(selected TEXT[]) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE seen TEXT[]; added TEXT[]; first_write BOOLEAN;
BEGIN
 IF selected IS NULL OR cardinality(selected)=0 OR NOT selected <@ ARRAY[
  'documents','search','schedules','relay','transcripts','authority','observations','readability','queues','progress','types','extractions','knowledge_access','knowledge_revocations'] THEN
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
 RETURNS TABLE(database_id UUID,documents BIGINT,search BIGINT,schedules BIGINT,relay BIGINT,transcripts BIGINT,authority BIGINT,observations BIGINT,readability BIGINT,queues BIGINT,progress BIGINT,types BIGINT,extractions BIGINT,knowledge_access BIGINT,knowledge_revocations BIGINT)
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
  (SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM snapshot_cache_knowledge_access),
  (SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM snapshot_cache_knowledge_revocations)
 INTO database_id,documents,search,schedules,relay,transcripts,authority,observations,readability,queues,progress,types,extractions,knowledge_access,knowledge_revocations
 FROM snapshot_cache_identity identity WHERE identity.singleton;
 IF NOT FOUND THEN RAISE EXCEPTION 'Projection cache database identity is missing'; END IF;
 RETURN NEXT;
END $$;


-- Record only physical INSERTs, never BEFORE INSERT/ON CONFLICT attempts on existing rows.
-- The fixed GUC names hold at most 128 UUIDs each, roll back at savepoints and clear on commit.
-- Evicted/unknown creation evidence remains a hard hint. These markers never grant access, skip
-- source-version validation or enter cache keys; all authorization SQL remains unchanged.
CREATE FUNCTION snapshot_cache_knowledge_born() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE setting TEXT; born TEXT[];
BEGIN
 setting:=CASE TG_TABLE_NAME WHEN 'information_resources' THEN 'plowshare.cache_born_resources'
  WHEN 'information_revisions' THEN 'plowshare.cache_born_revisions'
  WHEN 'documents' THEN 'plowshare.cache_born_documents' END;
 IF setting IS NULL THEN RAISE EXCEPTION 'Unknown knowledge creation relation'; END IF;
 born:=array_append(coalesce(string_to_array(nullif(current_setting(setting,true),''),','),ARRAY[]::text[]),NEW.id::text);
 IF cardinality(born)>128 THEN born:=born[cardinality(born)-127:cardinality(born)]; END IF;
 PERFORM set_config(setting,array_to_string(born,','),true);
 RETURN NULL;
END $$;
-- PostgreSQL orders same-kind row triggers by name. This marker must precede the existing
-- default-policy row triggers, including their nested INSERTs, for every row in a bulk intake.
CREATE TRIGGER "000_snapshot_cache_knowledge_born" AFTER INSERT ON information_resources
 FOR EACH ROW EXECUTE FUNCTION snapshot_cache_knowledge_born();
CREATE TRIGGER "000_snapshot_cache_knowledge_born" AFTER INSERT ON information_revisions
 FOR EACH ROW EXECUTE FUNCTION snapshot_cache_knowledge_born();
CREATE TRIGGER "000_snapshot_cache_knowledge_born" AFTER INSERT ON documents
 FOR EACH ROW EXECUTE FUNCTION snapshot_cache_knowledge_born();

CREATE OR REPLACE FUNCTION snapshot_cache_bump(selected TEXT[]) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE domain TEXT; channel TEXT;
BEGIN
 PERFORM snapshot_cache_record_writer(selected);
 FOREACH domain IN ARRAY selected LOOP
  PERFORM nextval(('snapshot_cache_'||domain)::regclass);
  channel:=CASE WHEN domain='knowledge_revocations' THEN 'plowshare_knowledge_revocations'
   WHEN domain='knowledge_access' THEN 'plowshare_knowledge_access_changes'
   WHEN domain IN ('queues','progress','types','extractions') THEN 'plowshare_projection_changes_v2'
   WHEN domain='readability' THEN 'plowshare_readability_changes' ELSE 'plowshare_projection_changes' END;
  PERFORM pg_notify(channel,domain);
 END LOOP;
END $$;

CREATE OR REPLACE FUNCTION snapshot_cache_knowledge_access_changed() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE born TEXT[]; revokes BOOLEAN:=true;
BEGIN
 IF TG_OP<>'TRUNCATE' THEN
  IF NOT EXISTS(SELECT 1 FROM changed_rows) THEN RETURN NULL; END IF;
  IF TG_OP='UPDATE' THEN
   IF TG_TABLE_NAME='information_revisions' THEN
    IF NOT EXISTS(SELECT id,resource_id,ordinal,availability,excluded FROM changed_rows
      EXCEPT SELECT id,resource_id,ordinal,availability,excluded FROM previous_rows) THEN RETURN NULL; END IF;
   ELSIF TG_TABLE_NAME='information_resources' THEN
    IF NOT EXISTS(SELECT id,home_scope_id,project_id FROM changed_rows
      EXCEPT SELECT id,home_scope_id,project_id FROM previous_rows) THEN RETURN NULL; END IF;
   ELSIF NOT EXISTS(SELECT to_jsonb(n) FROM changed_rows n EXCEPT SELECT to_jsonb(o) FROM previous_rows o) THEN RETURN NULL;
   END IF;
  END IF;
  -- Initial policies/inputs for rows born in this transaction cannot revoke a previously
  -- delivered snapshot. Both sides of UPDATE must be new; a moved old edge stays hard.
  IF TG_TABLE_NAME IN ('information_resources','information_resource_policies','information_reference_obligations') THEN
   born:=coalesce(string_to_array(nullif(current_setting('plowshare.cache_born_resources',true),''),','),ARRAY[]::text[]);
   IF TG_TABLE_NAME='information_resources' THEN
    revokes:=EXISTS(SELECT 1 FROM changed_rows WHERE NOT id::text=ANY(born));
    IF TG_OP='UPDATE' THEN revokes:=revokes OR EXISTS(SELECT 1 FROM previous_rows WHERE NOT id::text=ANY(born)); END IF;
   ELSIF TG_TABLE_NAME='information_resource_policies' THEN
    revokes:=EXISTS(SELECT 1 FROM changed_rows WHERE NOT resource_id::text=ANY(born));
    IF TG_OP='UPDATE' THEN revokes:=revokes OR EXISTS(SELECT 1 FROM previous_rows WHERE NOT resource_id::text=ANY(born)); END IF;
   ELSE
    revokes:=EXISTS(SELECT 1 FROM changed_rows WHERE NOT source::text=ANY(born));
    IF TG_OP='UPDATE' THEN revokes:=revokes OR EXISTS(SELECT 1 FROM previous_rows WHERE NOT source::text=ANY(born)); END IF;
   END IF;
  ELSIF TG_TABLE_NAME IN ('information_revisions','information_inputs','information_links') THEN
   born:=coalesce(string_to_array(nullif(current_setting('plowshare.cache_born_revisions',true),''),','),ARRAY[]::text[]);
   IF TG_TABLE_NAME='information_revisions' THEN
    -- A new head of an existing resource can redirect unpinned dependencies of old reports.
    -- Only a resource created here proves that no previously delivered graph can depend on it.
    IF TG_OP='INSERT' THEN
     revokes:=EXISTS(SELECT 1 FROM changed_rows WHERE NOT resource_id::text=ANY(
       coalesce(string_to_array(nullif(current_setting('plowshare.cache_born_resources',true),''),','),ARRAY[]::text[])));

    ELSE
     revokes:=EXISTS(SELECT 1 FROM changed_rows WHERE NOT id::text=ANY(born));
     IF TG_OP='UPDATE' THEN revokes:=revokes OR EXISTS(SELECT 1 FROM previous_rows WHERE NOT id::text=ANY(born)); END IF;
    END IF;
   ELSIF TG_TABLE_NAME='information_inputs' THEN
    revokes:=EXISTS(SELECT 1 FROM changed_rows WHERE NOT derived_revision::text=ANY(born));
    IF TG_OP='UPDATE' THEN revokes:=revokes OR EXISTS(SELECT 1 FROM previous_rows WHERE NOT derived_revision::text=ANY(born)); END IF;
   ELSE
    revokes:=EXISTS(SELECT 1 FROM changed_rows WHERE NOT revision_id::text=ANY(born));
    IF TG_OP='UPDATE' THEN revokes:=revokes OR EXISTS(SELECT 1 FROM previous_rows WHERE NOT revision_id::text=ANY(born)); END IF;
   END IF;
  ELSIF TG_TABLE_NAME='information_document_policies' THEN
   born:=coalesce(string_to_array(nullif(current_setting('plowshare.cache_born_documents',true),''),','),ARRAY[]::text[]);
   revokes:=EXISTS(SELECT 1 FROM changed_rows WHERE NOT document_id::text=ANY(born));
   IF TG_OP='UPDATE' THEN revokes:=revokes OR EXISTS(SELECT 1 FROM previous_rows WHERE NOT document_id::text=ANY(born)); END IF;
  END IF;
  IF TG_TABLE_NAME='information_resources' AND TG_OP='INSERT' THEN revokes:=false; END IF;
 END IF;
 PERFORM snapshot_cache_bump(ARRAY['knowledge_access']);
 IF revokes THEN PERFORM snapshot_cache_bump(ARRAY['knowledge_revocations']); END IF;
 RETURN NULL;
END $$;
