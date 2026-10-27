-- Immutable audit/report projections share durable observation versions across feeds and sockets.
-- Counters are allocation sequences, not a contended row or an authorization decision. Preserve
-- the completed-probe/pending-XID protocol so late commits and rollback remain distinguishable.
CREATE SEQUENCE snapshot_cache_observations MINVALUE 1 START 1 CACHE 1 NO CYCLE;
SELECT nextval('snapshot_cache_observations');

DROP FUNCTION snapshot_cache_capture_counters();
CREATE FUNCTION snapshot_cache_capture_counters()
 RETURNS TABLE(database_id UUID,documents BIGINT,search BIGINT,schedules BIGINT,relay BIGINT,transcripts BIGINT,authority BIGINT,observations BIGINT)
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
  (SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM snapshot_cache_observations)
 INTO database_id,documents,search,schedules,relay,transcripts,authority,observations
 FROM snapshot_cache_identity identity WHERE identity.singleton;
 IF NOT FOUND THEN RAISE EXCEPTION 'Projection cache database identity is missing'; END IF;
 PERFORM pg_current_xact_id();
 RETURN NEXT;
END $$;

CREATE FUNCTION snapshot_cache_observation_changed() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
 -- TRUNCATE has no transition relation. Keep a separate PL/pgSQL branch so its statement never
 -- plans a query referencing the absent relation, even before boolean short-circuit evaluation.
 IF TG_OP<>'TRUNCATE' THEN
  IF NOT EXISTS(SELECT 1 FROM changed_rows) THEN RETURN NULL; END IF;
 END IF;
 PERFORM pg_current_xact_id();
 PERFORM set_config('plowshare.cache_dirty','on',true);
 PERFORM nextval('snapshot_cache_observations');
 PERFORM pg_notify('plowshare_projection_changes','observations');
 RETURN NULL;
END $$;

DO $$
DECLARE relation TEXT; operation TEXT; transition TEXT;
BEGIN
 FOREACH relation IN ARRAY ARRAY['inference_calls','inference_attempts','inference_run_ownership',
   'inference_accounting_instances','inference_accounting_journals','inference_pricing_versions',
   'jobs','acting_work_conversations','information_acquisitions','sections','chapters'] LOOP
  FOREACH operation IN ARRAY ARRAY['INSERT','UPDATE','DELETE'] LOOP
   transition := CASE WHEN operation='DELETE' THEN 'OLD' ELSE 'NEW' END;
   EXECUTE format('CREATE TRIGGER snapshot_cache_observation_%s AFTER %s ON %I REFERENCING %s TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION snapshot_cache_observation_changed()',
    lower(operation),operation,relation,transition);
  END LOOP;
  EXECUTE format('CREATE TRIGGER snapshot_cache_observation_truncate AFTER TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION snapshot_cache_observation_changed()',relation);
 END LOOP;
END $$;

-- Clock-only lease expiry changes PROC running/pending counts without a write. Version capture
-- includes this boundary for observation projections; ordinary corpus caches keep their old window.
CREATE INDEX information_steps_running_lease_window ON information_steps(lease_until)
 WHERE state='running' AND lease_until IS NOT NULL;
