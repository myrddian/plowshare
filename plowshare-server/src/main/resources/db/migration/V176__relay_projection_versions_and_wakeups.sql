-- Relay inspection is a separate projection domain. Its counter is an allocation hint;
-- the existing completed-XID/pending-transaction protocol remains required for late commits.
CREATE SEQUENCE snapshot_cache_relay MINVALUE 1 START 1 CACHE 1 NO CYCLE;
SELECT nextval('snapshot_cache_relay');

-- Existing binaries select the original four named fields, so the extra output is compatible.
-- Replacement is atomic with this migration; no application observes an absent function.
DROP FUNCTION snapshot_cache_capture_counters();
CREATE FUNCTION snapshot_cache_capture_counters()
 RETURNS TABLE(database_id UUID,documents BIGINT,search BIGINT,schedules BIGINT,relay BIGINT,authority BIGINT)
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
  (SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM snapshot_cache_authority)
 INTO database_id,documents,search,schedules,relay,authority
 FROM snapshot_cache_identity identity WHERE identity.singleton;
 IF NOT FOUND THEN RAISE EXCEPTION 'Projection cache database identity is missing'; END IF;
 PERFORM pg_current_xact_id();
 RETURN NEXT;
END $$;

-- Ordered keys matching the actual inspection/discovery predicates. Handler source
-- and admitted payloads are deliberately excluded: UI inspection does not hydrate either.
CREATE INDEX relay_deliveries_inspection ON relay_deliveries
 (scope_key,topic,publication_position DESC,branch_index,subscriber,id);
CREATE INDEX relay_source_events_discovery ON relay_source_events
 ((COALESCE('project:'||project_id::text,'system')),topic,sequence);

CREATE FUNCTION snapshot_cache_relay_changed() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP<>'TRUNCATE' THEN
  IF NOT EXISTS(SELECT 1 FROM changed_rows) THEN RETURN NULL; END IF;
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
 PERFORM pg_current_xact_id();
 PERFORM set_config('plowshare.cache_dirty','on',true);
 PERFORM nextval('snapshot_cache_relay');
 PERFORM pg_notify('plowshare_projection_changes','relay');
 RETURN NULL;
END $$;

-- Include execution/conversation ownership and project names: account-filtered branch diagnostics
-- are not versioned by a topic's append position. Lease/claim APIs never consume these DTOs.
DO $$
DECLARE relation TEXT; operation TEXT; transition TEXT;
BEGIN
 FOREACH relation IN ARRAY ARRAY['relay_topics','relay_publications','relay_subscriptions',
   'relay_admissions','relay_deliveries','relay_executions','relay_native_gap_recoveries',
   'conversations','projects'] LOOP
  FOREACH operation IN ARRAY ARRAY['INSERT','UPDATE','DELETE'] LOOP
   transition := CASE WHEN operation='DELETE' THEN 'OLD TABLE AS changed_rows' ELSE 'NEW TABLE AS changed_rows' END;
   IF relation='conversations' AND operation='UPDATE' THEN
    transition := 'OLD TABLE AS old_rows NEW TABLE AS changed_rows';
   END IF;
   EXECUTE format('CREATE TRIGGER snapshot_cache_relay_%s AFTER %s ON %I REFERENCING %s FOR EACH STATEMENT EXECUTE FUNCTION snapshot_cache_relay_changed()',
     lower(operation),operation,relation,transition);
  END LOOP;
  EXECUTE format('CREATE TRIGGER snapshot_cache_relay_truncate AFTER TRUNCATE ON %I FOR EACH STATEMENT EXECUTE FUNCTION snapshot_cache_relay_changed()',relation);
 END LOOP;
END $$;

-- Work-only hints retain no identifiers or content and require no counter allocation: the durable
-- outbox/inbox remains authoritative. Missing hints are recovered by periodic discovery. Updates
-- to leases are deliberately absent, avoiding a self-waking claim/release polling loop.
CREATE FUNCTION relay_work_available() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM changed_rows) THEN
  PERFORM pg_notify('plowshare_projection_changes','relay');
 END IF;
 RETURN NULL;
END $$;
CREATE TRIGGER relay_source_available AFTER INSERT ON relay_source_events
 REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION relay_work_available();
CREATE TRIGGER relay_native_available AFTER INSERT ON relay_native_consumers
 REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION relay_work_available();
-- These owning rows can become ready without a new broker publication (for example an inbox
-- retry after a firing settles). Fixed hints accelerate discovery; time-based retries still poll.
CREATE FUNCTION relay_firing_wakeup() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='INSERT' THEN
  IF NEW.status='queued' THEN PERFORM pg_notify('plowshare_projection_changes','relay'); END IF;
 ELSIF (NEW.status='queued' AND OLD.status IS DISTINCT FROM NEW.status)
   OR (OLD.finished_at IS NULL AND NEW.finished_at IS NOT NULL) THEN
  PERFORM pg_notify('plowshare_projection_changes','relay');
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER relay_firing_available AFTER INSERT OR UPDATE ON firings
 FOR EACH ROW EXECUTE FUNCTION relay_firing_wakeup();
