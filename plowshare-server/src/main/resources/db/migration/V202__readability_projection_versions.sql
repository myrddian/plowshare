-- Access topology is distinct from transcript display and processing progress. Ordinary entry
-- appends, leases, code derivation and accounting must not flush every actor's READ graph.
-- This domain is combined with AUTHORITY, never used as an allow lease by itself.
CREATE SEQUENCE snapshot_cache_readability MINVALUE 1 START 1 CACHE 1 NO CYCLE;
SELECT nextval('snapshot_cache_readability');

DROP FUNCTION snapshot_cache_capture_counters();
CREATE FUNCTION snapshot_cache_capture_counters()
 RETURNS TABLE(database_id UUID,documents BIGINT,search BIGINT,schedules BIGINT,relay BIGINT,transcripts BIGINT,authority BIGINT,observations BIGINT,readability BIGINT)
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
  (SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM snapshot_cache_readability)
 INTO database_id,documents,search,schedules,relay,transcripts,authority,observations,readability
 FROM snapshot_cache_identity identity WHERE identity.singleton;
 IF NOT FOUND THEN RAISE EXCEPTION 'Projection cache database identity is missing'; END IF;
 PERFORM pg_current_xact_id();
 RETURN NEXT;
END $$;


CREATE FUNCTION snapshot_cache_readability_changed() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP<>'TRUNCATE' AND TG_LEVEL='STATEMENT' THEN
  IF NOT EXISTS(SELECT 1 FROM changed_rows) THEN RETURN NULL; END IF;
 END IF;
 PERFORM pg_current_xact_id();
 PERFORM set_config('plowshare.cache_dirty','on',true);
 PERFORM nextval('snapshot_cache_readability');
 PERFORM pg_notify('plowshare_readability_changes','readability');
 RETURN NULL;
END $$;
DO $$
DECLARE relation TEXT; operation TEXT; transition TEXT; columns TEXT;
BEGIN
 -- Every writer, cascade and TRUNCATE participates. Tables whose ordinary updates are unrelated
 -- use column-sensitive row UPDATE triggers; their INSERT/DELETE/TRUNCATE remain statement-level.
 FOR relation,columns IN VALUES
  ('information_revisions','id,resource_id,ordinal,availability,excluded'),
  ('information_resources','id,home_scope_id,project_id'),
  ('conversations','id,parent_id'),
  ('orchestrations','id,parent,caller_conversation,conductor_conversation'),
  ('information_job_inputs',NULL),('information_quarantined_payloads',NULL),
  ('information_links',NULL),('information_inputs',NULL),
  ('information_resource_policies',NULL),('information_navigation_policies',NULL),
  ('information_reference_obligations',NULL),('information_document_policies',NULL)
 LOOP
  FOREACH operation IN ARRAY ARRAY['INSERT','UPDATE','DELETE'] LOOP
   IF operation='UPDATE' AND columns IS NOT NULL THEN
    EXECUTE format('CREATE TRIGGER snapshot_cache_readability_update AFTER UPDATE ON %I FOR EACH ROW WHEN (ROW(%s) IS DISTINCT FROM ROW(%s)) EXECUTE FUNCTION snapshot_cache_readability_changed()',
      relation,'OLD.'||replace(columns,',',',OLD.'),'NEW.'||replace(columns,',',',NEW.'));
   ELSE
    transition:=CASE WHEN operation='DELETE' THEN 'OLD' ELSE 'NEW' END;
    EXECUTE format('CREATE TRIGGER snapshot_cache_readability_%s AFTER %s ON %I REFERENCING %s TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION snapshot_cache_readability_changed()',lower(operation),operation,relation,transition);
   END IF;
  END LOOP;
  EXECUTE format('CREATE TRIGGER snapshot_cache_readability_truncate AFTER TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION snapshot_cache_readability_changed()',relation);
 END LOOP;
END $$;
