-- Allocation sequences are not commit order. Keep the completed capture probe and pending-XID
-- evidence, but distinguish relevant writers from read-only/probe and unrelated transactions.
-- Journal rows commit/rollback with their source writes; validation reads rows and the retention
-- floor in ONE MVCC snapshot. Never combine absence from that snapshot with a later commit-status
-- observation to infer that a transaction did not write. A commit after validation's snapshot is
-- allowed to linearize after the read and will be detected by the next validation.
--
-- Drain every old watched writer before installing journal-aware trigger functions. This matters
-- for mixed server versions/direct SQL writers, not just a stopped single-server deployment.
DO $$
DECLARE relation REGCLASS;
BEGIN
 FOR relation IN SELECT DISTINCT tgrelid::regclass FROM pg_trigger
  WHERE NOT tgisinternal AND tgname LIKE 'snapshot_cache_%' ORDER BY 1
 LOOP
  EXECUTE format('LOCK TABLE %s IN SHARE ROW EXCLUSIVE MODE',relation);
 END LOOP;
END $$;

CREATE TABLE snapshot_cache_writers (
 xid XID8 NOT NULL,
 domain TEXT NOT NULL CHECK(domain IN ('documents','search','schedules','relay','transcripts',
  'authority','observations','readability','queues','progress','types','extractions')),
 PRIMARY KEY(domain,xid)
);
-- Domain-first probes must not scan thousands of unrelated accounting commits on warm reads.
CREATE INDEX snapshot_cache_writers_retention ON snapshot_cache_writers(xid);
CREATE TABLE snapshot_cache_writer_retention (
 singleton BOOLEAN PRIMARY KEY DEFAULT true CHECK(singleton),
 through XID8 NOT NULL
);
INSERT INTO snapshot_cache_writer_retention(through) VALUES('0'::xid8);
CREATE SEQUENCE snapshot_cache_writer_allocations CACHE 1 NO CYCLE;

-- Opportunistic, bounded work: retain 8192 recent committed writer-domain records, inspect at most
-- that many index entries, delete at most 1024 old rows, and never wait on another maintenance
-- transaction or an old writer's row. Overlapping/long transactions may temporarily exceed the
-- target; no read path does maintenance. Advancing the floor and deleting evidence are atomic.
-- A pending XID at/below the floor ALWAYS refuses sharing, even if its old journal row is absent.
CREATE FUNCTION snapshot_cache_prune_writers(retained INTEGER DEFAULT 8192,batch INTEGER DEFAULT 1024)
 RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE boundary XID8; removed XID8;
BEGIN
 IF retained NOT BETWEEN 1 AND 8192 OR batch NOT BETWEEN 1 AND 1024 THEN
  RAISE EXCEPTION 'Invalid projection writer retention bound';
 END IF;
 IF NOT pg_try_advisory_xact_lock(1347634002,205) THEN RETURN; END IF;
 SELECT xid INTO boundary FROM snapshot_cache_writers ORDER BY xid DESC OFFSET retained-1 LIMIT 1;
 IF boundary IS NULL THEN RETURN; END IF;
 WITH victims AS MATERIALIZED (
  SELECT xid,domain FROM snapshot_cache_writers WHERE xid<boundary ORDER BY xid,domain
  LIMIT batch FOR UPDATE SKIP LOCKED
 ), deleted AS (
  DELETE FROM snapshot_cache_writers writer USING victims WHERE writer.xid=victims.xid AND writer.domain=victims.domain
  RETURNING writer.xid
 ) SELECT xid INTO removed FROM deleted ORDER BY xid DESC LIMIT 1;
 IF removed IS NOT NULL THEN
  UPDATE snapshot_cache_writer_retention SET through=greatest(through,removed) WHERE singleton;
 END IF;
END $$;

-- Record each newly touched domain once per top-level transaction. The local marker and the row
-- both roll back with savepoints. Distinct transactions never contend on the same writer row.
-- Twelve domains per transaction and maintenance every 64 transactions add at most 768 records
-- between ordinary maintenance attempts, below its 1024-row work budget.
-- This helper neither publishes protected facts nor changes permission policy.
CREATE FUNCTION snapshot_cache_record_writer(selected TEXT[]) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE seen TEXT[]; added TEXT[]; first_write BOOLEAN;
BEGIN
 IF selected IS NULL OR cardinality(selected)=0 OR NOT selected <@ ARRAY[
  'documents','search','schedules','relay','transcripts','authority','observations','readability','queues','progress','types','extractions'] THEN
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
REVOKE ALL ON snapshot_cache_writers,snapshot_cache_writer_retention FROM PUBLIC;
REVOKE ALL ON SEQUENCE snapshot_cache_writer_allocations FROM PUBLIC;

CREATE OR REPLACE FUNCTION snapshot_cache_source_changed() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE domain TEXT;
BEGIN
 -- No-op registrations (INSERT ON CONFLICT DO NOTHING) must not invalidate an entire domain.
 -- Transition relations are named by the three separate DML statement triggers below.
 IF TG_OP<>'TRUNCATE' THEN
  IF NOT EXISTS(SELECT 1 FROM changed_rows) THEN RETURN NULL; END IF;
  -- Only document processing budgets are fields of the adopted metadata DTO. Ordinary conversation
  -- accounting must not flush the corpus on every inference call. V164's unique budget index
  -- supports this bounded join without scanning all revisions for every unrelated allowance.
  IF TG_TABLE_NAME='execution_allowances' THEN
   IF NOT EXISTS(SELECT 1 FROM changed_rows changed JOIN information_revisions revision
     ON revision.processing_budget_id=changed.id) THEN RETURN NULL; END IF;
  END IF;
 END IF;
 PERFORM snapshot_cache_record_writer(TG_ARGV);
 FOREACH domain IN ARRAY TG_ARGV LOOP
  IF domain NOT IN ('documents','search','schedules','authority') THEN
   RAISE EXCEPTION 'Unknown projection cache dependency';
  END IF;
  PERFORM nextval(('snapshot_cache_'||domain)::regclass);
  -- PostgreSQL delivers only after commit and coalesces identical channel/payload pairs. Payloads
  -- contain a fixed dependency domain, never an entity ID, credential or source content.
  PERFORM pg_notify('plowshare_projection_changes',domain);
 END LOOP;
 RETURN NULL;
END $$;

CREATE OR REPLACE FUNCTION snapshot_cache_relay_changed() RETURNS TRIGGER LANGUAGE plpgsql AS $$
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
 PERFORM snapshot_cache_record_writer(ARRAY['relay']);
 PERFORM nextval('snapshot_cache_relay');
 PERFORM pg_notify('plowshare_projection_changes','relay');
 RETURN NULL;
END $$;

CREATE OR REPLACE FUNCTION snapshot_cache_transcript_changed() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
 -- TRUNCATE has no transition relation. Keep a separate PL/pgSQL branch so its statement never
 -- plans a query referencing the absent relation, even before boolean short-circuit evaluation.
 IF TG_OP<>'TRUNCATE' THEN
  IF NOT EXISTS(SELECT 1 FROM changed_rows) THEN RETURN NULL; END IF;
 END IF;
 PERFORM snapshot_cache_record_writer(ARRAY['transcripts']);
 PERFORM nextval('snapshot_cache_transcripts');
 PERFORM pg_notify('plowshare_projection_changes','transcripts');
 RETURN NULL;
END $$;

CREATE OR REPLACE FUNCTION snapshot_cache_observation_changed() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
 -- TRUNCATE has no transition relation. Keep a separate PL/pgSQL branch so its statement never
 -- plans a query referencing the absent relation, even before boolean short-circuit evaluation.
 IF TG_OP<>'TRUNCATE' THEN
  IF NOT EXISTS(SELECT 1 FROM changed_rows) THEN RETURN NULL; END IF;
 END IF;
 PERFORM snapshot_cache_record_writer(ARRAY['observations']);
 PERFORM nextval('snapshot_cache_observations');
 PERFORM pg_notify('plowshare_projection_changes','observations');
 RETURN NULL;
END $$;

CREATE OR REPLACE FUNCTION snapshot_cache_readability_changed() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP<>'TRUNCATE' AND TG_LEVEL='STATEMENT' THEN
  IF NOT EXISTS(SELECT 1 FROM changed_rows) THEN RETURN NULL; END IF;
 END IF;
 PERFORM snapshot_cache_record_writer(ARRAY['readability']);
 PERFORM nextval('snapshot_cache_readability');
 PERFORM pg_notify('plowshare_readability_changes','readability');
 RETURN NULL;
END $$;

CREATE OR REPLACE FUNCTION snapshot_cache_information_steps_updated() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM changed_rows) THEN RETURN NULL; END IF;
 IF NOT EXISTS(
  SELECT to_jsonb(changed)-'lease_until' FROM changed_rows changed
  EXCEPT
  SELECT to_jsonb(previous)-'lease_until' FROM previous_rows previous
 ) THEN RETURN NULL; END IF;
 PERFORM snapshot_cache_record_writer(ARRAY['documents','search']);
 PERFORM nextval('snapshot_cache_documents');
 PERFORM nextval('snapshot_cache_search');
 PERFORM pg_notify('plowshare_projection_changes','documents');
 PERFORM pg_notify('plowshare_projection_changes','search');
 RETURN NULL;
END $$;

