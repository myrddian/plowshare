-- A processing heartbeat is observation, not a change to every reader's document corpus.
-- Keep INSERT/DELETE/TRUNCATE on the existing document/search path. For UPDATE, compare the
-- complete row except the lease deadline: future metadata columns participate automatically,
-- and a changed primary key cannot escape comparison. Transition tables batch bulk changes.
CREATE FUNCTION snapshot_cache_information_steps_updated() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM changed_rows) THEN RETURN NULL; END IF;
 IF NOT EXISTS(
  SELECT to_jsonb(changed)-'lease_until' FROM changed_rows changed
  EXCEPT
  SELECT to_jsonb(previous)-'lease_until' FROM previous_rows previous
 ) THEN RETURN NULL; END IF;
 PERFORM pg_current_xact_id();
 PERFORM set_config('plowshare.cache_dirty','on',true);
 PERFORM nextval('snapshot_cache_documents');
 PERFORM nextval('snapshot_cache_search');
 PERFORM pg_notify('plowshare_projection_changes','documents');
 PERFORM pg_notify('plowshare_projection_changes','search');
 RETURN NULL;
END $$;

DROP TRIGGER snapshot_cache_changed_update ON information_steps;
CREATE TRIGGER snapshot_cache_changed_update AFTER UPDATE ON information_steps
 REFERENCING OLD TABLE AS previous_rows NEW TABLE AS changed_rows
 FOR EACH STATEMENT EXECUTE FUNCTION snapshot_cache_information_steps_updated();

-- PROC includes lease deadlines and pending/running counts. Its observation version must still
-- change when a lease is extended, shortened or cleared. This also preserves dirty-transaction
-- rejection and committed wakeups without disturbing document or authorization versions.
CREATE TRIGGER snapshot_cache_observation_update AFTER UPDATE ON information_steps
 REFERENCING NEW TABLE AS changed_rows
 FOR EACH STATEMENT EXECUTE FUNCTION snapshot_cache_observation_changed();
