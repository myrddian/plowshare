-- Mailbox activations keep their existing IDs, cursor positions and receipts.
ALTER TABLE personal_mailbox_subscriptions RENAME COLUMN item TO source_event;
ALTER TABLE personal_mailbox_subscriptions RENAME COLUMN firing TO source_position;
ALTER TABLE personal_mailbox_subscriptions
    ALTER COLUMN schedule DROP NOT NULL,
    ALTER COLUMN ending DROP NOT NULL,
    ADD COLUMN relay_project_id BIGINT,
    ADD COLUMN relay_topic TEXT,
    ADD COLUMN relay_seen BIGINT NOT NULL DEFAULT 0 CHECK (relay_seen >= 0),
    ADD COLUMN relay_input TEXT,
    ADD CONSTRAINT personal_subscription_source CHECK (
       (relay_project_id IS NULL AND relay_topic IS NULL AND schedule IS NOT NULL AND ending IS NOT NULL)
       OR (relay_project_id > 0 AND relay_project_id IS NOT NULL AND relay_topic IS NOT NULL
           AND schedule IS NULL AND ending IS NULL)),
    ADD CONSTRAINT personal_subscription_relay_input CHECK (
       (relay_input IS NOT NULL) = (relay_topic IS NOT NULL AND state='starting')
       AND (relay_input IS NULL OR octet_length(relay_input) <= 32768));

-- A Personal log carrying project text still requires the source project's current read authority.
-- Keep the stable project ID after source deletion so deletion cannot make old text readable.
CREATE TABLE information_relay_log_inputs (
    log_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    project_id BIGINT NOT NULL CHECK (project_id > 0),
    PRIMARY KEY(log_id,project_id)
);
CREATE INDEX information_relay_log_inputs_by_project ON information_relay_log_inputs(project_id,log_id);

CREATE FUNCTION snapshot_cache_relay_log_inputs_changed() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE starts TEXT[];
BEGIN
 IF TG_OP<>'TRUNCATE' THEN
  IF NOT EXISTS(SELECT 1 FROM changed_rows) THEN RETURN NULL; END IF;
 END IF;
 PERFORM snapshot_cache_record_writer(ARRAY['readability']);
 PERFORM nextval('snapshot_cache_readability');
 PERFORM pg_notify('plowshare_readability_changes','readability');
 IF TG_OP='TRUNCATE' THEN
  PERFORM snapshot_cache_record_items('readability',NULL);
 ELSIF (SELECT count(*) FROM (SELECT 1 FROM changed_rows LIMIT 65) bounded)<=64 THEN
  starts:=ARRAY(SELECT log_id FROM changed_rows);
  IF TG_OP='UPDATE' THEN
   starts:=starts||ARRAY(SELECT log_id FROM (SELECT log_id FROM previous_rows LIMIT 65) bounded);
  END IF;
  PERFORM snapshot_cache_record_items('readability',snapshot_cache_log_items(starts));
 ELSE
  PERFORM snapshot_cache_record_items('readability',NULL);
 END IF;
 RETURN NULL;
END $$;
CREATE TRIGGER snapshot_cache_relay_log_inputs_insert AFTER INSERT ON information_relay_log_inputs
 REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION snapshot_cache_relay_log_inputs_changed();
CREATE TRIGGER snapshot_cache_relay_log_inputs_update AFTER UPDATE ON information_relay_log_inputs
 REFERENCING OLD TABLE AS previous_rows NEW TABLE AS changed_rows FOR EACH STATEMENT
 EXECUTE FUNCTION snapshot_cache_relay_log_inputs_changed();
CREATE TRIGGER snapshot_cache_relay_log_inputs_delete AFTER DELETE ON information_relay_log_inputs
 REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION snapshot_cache_relay_log_inputs_changed();
CREATE TRIGGER snapshot_cache_relay_log_inputs_truncate AFTER TRUNCATE ON information_relay_log_inputs
 FOR EACH STATEMENT EXECUTE FUNCTION snapshot_cache_relay_log_inputs_changed();

CREATE OR REPLACE FUNCTION information_log_readable(target TEXT, account TEXT)
 RETURNS BOOLEAN LANGUAGE SQL STABLE SET jit='off' AS $$
 WITH RECURSIVE ancestors(id) AS (
  SELECT $1 UNION SELECT e.parent FROM ancestors a JOIN (
   SELECT id AS child,parent_id AS parent FROM conversations WHERE parent_id IS NOT NULL
   UNION ALL SELECT conductor_conversation,caller_conversation FROM orchestrations WHERE caller_conversation IS NOT NULL
   UNION ALL SELECT o.conductor_conversation,p.conductor_conversation FROM orchestrations o JOIN orchestrations p ON p.id=o.parent
   UNION ALL SELECT id,conductor_conversation FROM orchestrations
  ) e ON e.child=a.id
 ),
 inputs AS MATERIALIZED (SELECT DISTINCT i.revision_id,i.scope,i.project_name,i.include_shared
  FROM ancestors a JOIN information_job_inputs i ON i.job_id=a.id),
 admitted AS MATERIALIZED (SELECT * FROM information_readable_input_selections($2,
  ARRAY(SELECT DISTINCT revision_id FROM inputs)))
 SELECT NOT EXISTS(SELECT 1 FROM ancestors a JOIN information_quarantined_payloads q ON q.payload_id=a.id)
 AND NOT EXISTS(SELECT 1 FROM ancestors a JOIN information_relay_log_inputs r ON r.log_id=a.id
  WHERE $2 IS NULL OR NOT information_project_readable(r.project_id,$2))
 AND NOT EXISTS(SELECT 1 FROM inputs i LEFT JOIN admitted a ON a.revision=i.revision_id AND a.scope=i.scope
  AND a.project_name IS NOT DISTINCT FROM i.project_name AND a.include_shared=i.include_shared
  WHERE $2 IS NULL OR NOT coalesce(a.readable,false));
$$;

CREATE OR REPLACE FUNCTION information_unreadable_logs(account TEXT) RETURNS TABLE(log TEXT)
 LANGUAGE SQL STABLE SET jit='off' AS $$
 WITH RECURSIVE admitted AS MATERIALIZED (
  SELECT * FROM information_readable_input_selections($1,NULL)
 ),
 relay_denied AS MATERIALIZED (
  SELECT project_id FROM (SELECT DISTINCT project_id FROM information_relay_log_inputs) sources
  WHERE $1 IS NULL OR NOT information_project_readable(project_id,$1)
 ),
 blocked(log) AS (
  SELECT payload_id FROM information_quarantined_payloads
  UNION SELECT r.log_id FROM information_relay_log_inputs r JOIN relay_denied denied USING(project_id)
  UNION SELECT i.job_id FROM information_job_inputs i LEFT JOIN admitted a
   ON a.revision=i.revision_id AND a.scope=i.scope
   AND a.project_name IS NOT DISTINCT FROM i.project_name AND a.include_shared=i.include_shared
   WHERE $1 IS NULL OR NOT coalesce(a.readable,false)
 ),
 descendants(log) AS (
  SELECT log FROM blocked UNION SELECT e.child FROM descendants d JOIN (
   SELECT id AS child,parent_id AS parent FROM conversations WHERE parent_id IS NOT NULL
   UNION ALL SELECT conductor_conversation,caller_conversation FROM orchestrations WHERE caller_conversation IS NOT NULL
   UNION ALL SELECT o.conductor_conversation,p.conductor_conversation FROM orchestrations o JOIN orchestrations p ON p.id=o.parent
  ) e ON e.parent=d.log WHERE e.child IS NOT NULL
 ) SELECT log FROM descendants;
$$;
