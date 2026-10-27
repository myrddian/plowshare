-- Narrow dependency ownership without turning hints into authorization. Older client binaries
-- still treat an unknown optional progress flag as an ordinary full refresh. Server binaries must
-- be restarted together for this dependency change; old DOCUMENTS-only metadata caches do not
-- understand PROGRESS. Notifications for new domains use a separate channel so old listeners do
-- not reject unknown payloads while the coordinated restart drains.
CREATE SEQUENCE snapshot_cache_queues MINVALUE 1 START 1 CACHE 1 NO CYCLE;
CREATE SEQUENCE snapshot_cache_progress MINVALUE 1 START 1 CACHE 1 NO CYCLE;
CREATE SEQUENCE snapshot_cache_types MINVALUE 1 START 1 CACHE 1 NO CYCLE;
CREATE SEQUENCE snapshot_cache_extractions MINVALUE 1 START 1 CACHE 1 NO CYCLE;
SELECT nextval('snapshot_cache_queues'),nextval('snapshot_cache_progress'),
 nextval('snapshot_cache_types'),nextval('snapshot_cache_extractions');

-- Shared trigger helper, still owned by schema/persistence. The writer record precedes allocation
-- and commits atomically with facts; each domain is allowlisted before identifier construction.
CREATE FUNCTION snapshot_cache_bump(selected TEXT[]) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE domain TEXT; channel TEXT;
BEGIN
 PERFORM snapshot_cache_record_writer(selected);
 FOREACH domain IN ARRAY selected LOOP
  PERFORM nextval(('snapshot_cache_'||domain)::regclass);
  channel:=CASE WHEN domain IN ('queues','progress','types','extractions') THEN 'plowshare_projection_changes_v2'
    WHEN domain='readability' THEN 'plowshare_readability_changes' ELSE 'plowshare_projection_changes' END;
  PERFORM pg_notify(channel,domain);
 END LOOP;
END $$;

DROP FUNCTION snapshot_cache_capture_counters();
CREATE FUNCTION snapshot_cache_capture_counters()
 RETURNS TABLE(database_id UUID,documents BIGINT,search BIGINT,schedules BIGINT,relay BIGINT,transcripts BIGINT,authority BIGINT,observations BIGINT,readability BIGINT,queues BIGINT,progress BIGINT,types BIGINT,extractions BIGINT)
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
  (SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM snapshot_cache_extractions)
 INTO database_id,documents,search,schedules,relay,transcripts,authority,observations,readability,queues,progress,types,extractions
 FROM snapshot_cache_identity identity WHERE identity.singleton;
 IF NOT FOUND THEN RAISE EXCEPTION 'Projection cache database identity is missing'; END IF;
 RETURN NEXT;
END $$;

-- Atomic no-op upserts may still be necessary for ownership validation or RETURNING. Do not
-- confuse their touched-row count with a changed projection. Compare narrow source tables only;
-- do not serialize entire extracted documents or payload bodies merely to detect a no-op.
CREATE OR REPLACE FUNCTION snapshot_cache_source_changed() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP<>'TRUNCATE' THEN
  IF NOT EXISTS(SELECT 1 FROM changed_rows) THEN RETURN NULL; END IF;
  IF TG_OP='UPDATE' AND TG_TABLE_NAME IN ('schedule_sources','schedule_files') THEN
   IF NOT EXISTS(SELECT to_jsonb(n) FROM changed_rows n EXCEPT SELECT to_jsonb(o) FROM previous_rows o) THEN RETURN NULL; END IF;
  END IF;
  IF TG_TABLE_NAME='execution_allowances' THEN
   IF NOT EXISTS(SELECT 1 FROM changed_rows changed JOIN information_revisions revision
     ON revision.processing_budget_id=changed.id) THEN RETURN NULL; END IF;
  END IF;
 END IF;
 PERFORM snapshot_cache_bump(TG_ARGV);
 RETURN NULL;
END $$;
DO $$
DECLARE relation TEXT;
BEGIN
 FOREACH relation IN ARRAY ARRAY['schedule_sources','schedule_files'] LOOP
  EXECUTE format('DROP TRIGGER snapshot_cache_changed_update ON %I',relation);
  EXECUTE format('CREATE TRIGGER snapshot_cache_changed_update AFTER UPDATE ON %I REFERENCING OLD TABLE AS previous_rows NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION snapshot_cache_source_changed(''schedules'')',relation);
 END LOOP;
END $$;
-- A processing budget is progress, not a change to source text, type definitions or navigation.
DROP TRIGGER snapshot_cache_changed_insert ON execution_allowances;
DROP TRIGGER snapshot_cache_changed_update ON execution_allowances;
DROP TRIGGER snapshot_cache_changed_delete ON execution_allowances;
DROP TRIGGER snapshot_cache_changed_truncate ON execution_allowances;
CREATE TRIGGER snapshot_cache_changed_insert AFTER INSERT ON execution_allowances REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION snapshot_cache_source_changed('progress');
CREATE TRIGGER snapshot_cache_changed_update AFTER UPDATE ON execution_allowances REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION snapshot_cache_source_changed('progress');
CREATE TRIGGER snapshot_cache_changed_delete AFTER DELETE ON execution_allowances REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION snapshot_cache_source_changed('progress');
CREATE TRIGGER snapshot_cache_changed_truncate AFTER TRUNCATE ON execution_allowances FOR EACH STATEMENT EXECUTE FUNCTION snapshot_cache_source_changed('progress');

-- Stage readiness changes search eligibility and generated facets; errors/attempts/timestamps do
-- not. Lease-only changes affect queues and their clock window, not content or progress DTOs.
CREATE FUNCTION snapshot_cache_information_steps_changed() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE selected TEXT[]:=ARRAY['queues','observations'];
BEGIN
 IF TG_OP='TRUNCATE' THEN
  PERFORM snapshot_cache_bump(selected||ARRAY['documents','search','progress']); RETURN NULL;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM changed_rows) THEN RETURN NULL; END IF;
 IF TG_OP='UPDATE' THEN
  IF NOT EXISTS(SELECT to_jsonb(n) FROM changed_rows n EXCEPT SELECT to_jsonb(o) FROM previous_rows o) THEN RETURN NULL; END IF;
  IF EXISTS(SELECT to_jsonb(n)-'lease_until' FROM changed_rows n EXCEPT SELECT to_jsonb(o)-'lease_until' FROM previous_rows o) THEN selected:=array_append(selected,'progress'); END IF;
  IF EXISTS(SELECT 1 FROM changed_rows n FULL JOIN previous_rows o USING(revision_id,generation,stage)
    WHERE ROW(n.revision_id,n.generation,n.stage,n.state,n.fingerprint) IS DISTINCT FROM ROW(o.revision_id,o.generation,o.stage,o.state,o.fingerprint)) THEN selected:=array_append(selected,'search'); END IF;
  IF EXISTS(SELECT 1 FROM changed_rows n FULL JOIN previous_rows o USING(revision_id,generation,stage)
    WHERE coalesce(n.stage,o.stage) IN ('autoTag','tagGroups')
     AND ROW(n.revision_id,n.generation,n.stage,n.state) IS DISTINCT FROM ROW(o.revision_id,o.generation,o.stage,o.state)) THEN selected:=array_append(selected,'documents'); END IF;
 ELSE
  selected:=selected||ARRAY['progress','search'];
  IF EXISTS(SELECT 1 FROM changed_rows WHERE stage IN ('autoTag','tagGroups')) THEN selected:=array_append(selected,'documents'); END IF;
 END IF;
 PERFORM snapshot_cache_bump(selected);
 RETURN NULL;
END $$;
DROP TRIGGER snapshot_cache_changed_insert ON information_steps;
DROP TRIGGER snapshot_cache_changed_update ON information_steps;
DROP TRIGGER snapshot_cache_changed_delete ON information_steps;
DROP TRIGGER snapshot_cache_changed_truncate ON information_steps;
DROP TRIGGER snapshot_cache_observation_update ON information_steps;
CREATE TRIGGER snapshot_cache_steps_insert AFTER INSERT ON information_steps REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION snapshot_cache_information_steps_changed();
CREATE TRIGGER snapshot_cache_steps_update AFTER UPDATE ON information_steps REFERENCING OLD TABLE AS previous_rows NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION snapshot_cache_information_steps_changed();
CREATE TRIGGER snapshot_cache_steps_delete AFTER DELETE ON information_steps REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION snapshot_cache_information_steps_changed();
CREATE TRIGGER snapshot_cache_steps_truncate AFTER TRUNCATE ON information_steps FOR EACH STATEMENT EXECUTE FUNCTION snapshot_cache_information_steps_changed();

-- Registry reads no longer depend on ordinary document/progress churn. Existing document/search
-- fences still cover places that project labels/schema metadata; current caller admission stays.
DO $$
DECLARE relation TEXT; operation TEXT; transition TEXT;
BEGIN
 FOREACH relation IN ARRAY ARRAY['information_content_types','information_content_type_versions'] LOOP
  FOREACH operation IN ARRAY ARRAY['INSERT','UPDATE','DELETE'] LOOP
   transition:=CASE WHEN operation='DELETE' THEN 'OLD' ELSE 'NEW' END;
   EXECUTE format('CREATE TRIGGER snapshot_cache_types_%s AFTER %s ON %I REFERENCING %s TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION snapshot_cache_source_changed(''types'')',lower(operation),operation,relation,transition);
  END LOOP;
  EXECUTE format('CREATE TRIGGER snapshot_cache_types_truncate AFTER TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION snapshot_cache_source_changed(''types'')',relation);
 END LOOP;
END $$;

-- Exact text DTO columns only. READABILITY and AUTHORITY independently cover current selection,
-- topology, privacy, project membership, ceilings and revocation; these are not content leases.
CREATE FUNCTION snapshot_cache_extractions_changed() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP<>'TRUNCATE' THEN
  IF NOT EXISTS(SELECT 1 FROM changed_rows) THEN RETURN NULL; END IF;
  IF TG_OP='UPDATE' THEN
   IF NOT EXISTS(SELECT 1 FROM changed_rows n FULL JOIN previous_rows o USING(id)
    WHERE ROW(n.id,n.resource_id,n.document_type,n.document_subtype,n.content_hash,n.text_hash,n.generation,n.extracted_text)
     IS DISTINCT FROM ROW(o.id,o.resource_id,o.document_type,o.document_subtype,o.content_hash,o.text_hash,o.generation,o.extracted_text)) THEN RETURN NULL; END IF;
  END IF;
 END IF;
 PERFORM snapshot_cache_bump(ARRAY['extractions']);
 RETURN NULL;
END $$;
CREATE TRIGGER snapshot_cache_extractions_insert AFTER INSERT ON information_revisions REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION snapshot_cache_extractions_changed();
CREATE TRIGGER snapshot_cache_extractions_update AFTER UPDATE ON information_revisions REFERENCING OLD TABLE AS previous_rows NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION snapshot_cache_extractions_changed();
CREATE TRIGGER snapshot_cache_extractions_delete AFTER DELETE ON information_revisions REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION snapshot_cache_extractions_changed();
CREATE TRIGGER snapshot_cache_extractions_truncate AFTER TRUNCATE ON information_revisions FOR EACH STATEMENT EXECUTE FUNCTION snapshot_cache_extractions_changed();

CREATE OR REPLACE FUNCTION snapshot_cache_relay_changed() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP<>'TRUNCATE' THEN
  IF NOT EXISTS(SELECT 1 FROM changed_rows) THEN RETURN NULL; END IF;
  IF TG_OP='UPDATE' AND TG_TABLE_NAME<>'conversations' THEN
   IF NOT EXISTS(SELECT to_jsonb(n) FROM changed_rows n EXCEPT SELECT to_jsonb(o) FROM old_rows o) THEN RETURN NULL; END IF;
  END IF;
  -- Only conversation identity/project fields enter branch diagnostics. Token, transcript and
  -- compaction updates must not flush every Relay page or wake every worker during inference.
  IF TG_TABLE_NAME='conversations' THEN
   IF TG_OP='UPDATE' THEN
    IF NOT EXISTS(SELECT 1 FROM changed_rows n FULL JOIN old_rows o USING(id)
      WHERE n.id IS NULL OR o.id IS NULL OR n.project_id IS DISTINCT FROM o.project_id) THEN
     RETURN NULL;
    END IF;
    IF NOT EXISTS(SELECT 1 FROM relay_executions e WHERE e.conversation_id IN
      (SELECT id FROM changed_rows UNION SELECT id FROM old_rows)) THEN RETURN NULL; END IF;
   ELSIF NOT EXISTS(SELECT 1 FROM changed_rows c JOIN relay_executions e ON e.conversation_id=c.id) THEN
    RETURN NULL;
   END IF;
  END IF;
 END IF;
 PERFORM snapshot_cache_record_writer(ARRAY['relay']);
 PERFORM nextval('snapshot_cache_relay');
 PERFORM pg_notify('plowshare_projection_changes','relay');
 RETURN NULL;
END $$;
DO $$
DECLARE relation TEXT;
BEGIN
 FOREACH relation IN ARRAY ARRAY['relay_topics','relay_publications','relay_subscriptions',
   'relay_admissions','relay_deliveries','relay_executions','relay_native_gap_recoveries','conversations','projects'] LOOP
  EXECUTE format('DROP TRIGGER snapshot_cache_relay_update ON %I',relation);
  EXECUTE format('CREATE TRIGGER snapshot_cache_relay_update AFTER UPDATE ON %I REFERENCING OLD TABLE AS old_rows NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION snapshot_cache_relay_changed()',relation);
 END LOOP;
END $$;
