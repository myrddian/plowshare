-- Transcript pages include opened-child metadata and depend on the durable log-input ancestry.
-- Keep display invalidation separate from worker claims, leases and mutation authority.
CREATE SEQUENCE snapshot_cache_transcripts MINVALUE 1 START 1 CACHE 1 NO CYCLE;
SELECT nextval('snapshot_cache_transcripts');

-- Preserve the completed-probe and pending-XID protocol; old binaries read named fields.
DROP FUNCTION snapshot_cache_capture_counters();
CREATE FUNCTION snapshot_cache_capture_counters()
 RETURNS TABLE(database_id UUID,documents BIGINT,search BIGINT,schedules BIGINT,relay BIGINT,transcripts BIGINT,authority BIGINT)
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
  (SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM snapshot_cache_authority)
 INTO database_id,documents,search,schedules,relay,transcripts,authority
 FROM snapshot_cache_identity identity WHERE identity.singleton;
 IF NOT FOUND THEN RAISE EXCEPTION 'Projection cache database identity is missing'; END IF;
 PERFORM pg_current_xact_id();
 RETURN NEXT;
END $$;

CREATE FUNCTION snapshot_cache_transcript_changed() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
 -- TRUNCATE has no transition relation. Keep a separate PL/pgSQL branch so its statement never
 -- plans a query referencing the absent relation, even before boolean short-circuit evaluation.
 IF TG_OP<>'TRUNCATE' THEN
  IF NOT EXISTS(SELECT 1 FROM changed_rows) THEN RETURN NULL; END IF;
 END IF;
 PERFORM pg_current_xact_id();
 PERFORM set_config('plowshare.cache_dirty','on',true);
 PERFORM nextval('snapshot_cache_transcripts');
 PERFORM pg_notify('plowshare_projection_changes','transcripts');
 RETURN NULL;
END $$;

DO $$
DECLARE relation TEXT; operation TEXT; transition TEXT;
BEGIN
 FOREACH relation IN ARRAY ARRAY['entries','conversations','orchestrations',
   'information_job_inputs','information_quarantined_payloads'] LOOP
  FOREACH operation IN ARRAY ARRAY['INSERT','UPDATE','DELETE'] LOOP
   transition := CASE WHEN operation='DELETE' THEN 'OLD' ELSE 'NEW' END;
   EXECUTE format('CREATE TRIGGER snapshot_cache_transcript_%s AFTER %s ON %I REFERENCING %s TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION snapshot_cache_transcript_changed()',
    lower(operation),operation,relation,transition);
  END LOOP;
  EXECUTE format('CREATE TRIGGER snapshot_cache_transcript_truncate AFTER TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION snapshot_cache_transcript_changed()',relation);
 END LOOP;
END $$;
