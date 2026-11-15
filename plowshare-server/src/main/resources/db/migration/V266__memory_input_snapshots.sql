-- A memory is a durable knowledge payload, with its own input selection snapshot.
-- Reuse the retained-input ledger (whose text key already names several payload kinds)
-- rather than using conversation provenance as a permanently growing disclosure fence.
-- The marker distinguishes a complete snapshot from legacy formation provenance.
CREATE TABLE memory_input_snapshots (
 memory_id TEXT NOT NULL REFERENCES memories(id),
 source_log TEXT NOT NULL REFERENCES conversations(id),
 PRIMARY KEY(memory_id,source_log)
);

CREATE FUNCTION information_memory_source_logs(targets TEXT[]) RETURNS TABLE(log TEXT)
 LANGUAGE SQL STABLE AS $$
 WITH RECURSIVE roots(id) AS (
  SELECT unnest($1)
  UNION SELECT p.conversation_id FROM memory_provenance p WHERE p.memory_id=ANY($1)
   AND NOT EXISTS(SELECT 1 FROM memory_input_snapshots s WHERE s.memory_id=p.memory_id)
 ), ancestors(id) AS (
  SELECT id FROM roots UNION SELECT e.parent FROM ancestors a JOIN (
   SELECT id AS child,parent_id AS parent FROM conversations WHERE parent_id IS NOT NULL
   UNION ALL SELECT conductor_conversation,caller_conversation FROM orchestrations WHERE caller_conversation IS NOT NULL
   UNION ALL SELECT o.conductor_conversation,p.conductor_conversation FROM orchestrations o JOIN orchestrations p ON p.id=o.parent
   UNION ALL SELECT id,conductor_conversation FROM orchestrations
  ) e ON e.child=a.id
 ) SELECT id FROM ancestors;
$$;

-- Called under the archive's source/privacy locks in the content transaction. Unions
-- preserve previous dependencies on merge; no agent-provided source list can clear them.
-- Capture legacy provenance before installing the first complete-snapshot marker.
CREATE FUNCTION information_snapshot_memory_inputs(target TEXT, source TEXT) RETURNS VOID
 LANGUAGE plpgsql AS $$
BEGIN
 WITH logs AS MATERIALIZED (
  SELECT log FROM information_memory_source_logs(ARRAY[$1,$2])
 )
 INSERT INTO information_job_inputs(job_id,owner_handle,revision_id,scope,project_name,include_shared)
 SELECT DISTINCT $1,i.owner_handle,i.revision_id,i.scope,i.project_name,i.include_shared
 FROM logs l JOIN information_job_inputs i ON i.job_id=l.log
 ON CONFLICT DO NOTHING;
 INSERT INTO information_quarantined_payloads(payload_id,reason)
 SELECT $1,'Memory input snapshot includes quarantined source history'
 WHERE EXISTS(SELECT 1 FROM information_memory_source_logs(ARRAY[$1,$2]) l
  JOIN information_quarantined_payloads q ON q.payload_id=l.log)
 ON CONFLICT DO NOTHING;
 INSERT INTO memory_input_snapshots VALUES($1,$2) ON CONFLICT DO NOTHING;
END;
$$;

-- A subsequent source quarantine is an audit withdrawal, not an ordinary later read.
-- Propagate quarantine through ancestry without consulting that source log's later inputs.
CREATE FUNCTION information_quarantined_memories() RETURNS TABLE(memory TEXT)
 LANGUAGE SQL STABLE AS $$
 WITH RECURSIVE blocked(log) AS (
  SELECT payload_id FROM information_quarantined_payloads
  UNION SELECT e.child FROM blocked b JOIN (
   SELECT id AS child,parent_id AS parent FROM conversations WHERE parent_id IS NOT NULL
   UNION ALL SELECT conductor_conversation,caller_conversation FROM orchestrations WHERE caller_conversation IS NOT NULL
   UNION ALL SELECT o.conductor_conversation,p.conductor_conversation FROM orchestrations o JOIN orchestrations p ON p.id=o.parent
   UNION ALL SELECT id,conductor_conversation FROM orchestrations
  ) e ON e.parent=b.log WHERE e.child IS NOT NULL
 )
 SELECT s.memory_id FROM memory_input_snapshots s JOIN blocked b ON b.log=s.source_log
 UNION SELECT m.id FROM memories m JOIN blocked b ON b.log=m.id;
$$;

CREATE FUNCTION information_unreadable_memories(actor TEXT) RETURNS TABLE(memory TEXT)
 LANGUAGE SQL STABLE AS $$
 WITH blocked AS MATERIALIZED (SELECT log FROM information_unreadable_logs($1))
 SELECT m.id FROM memories m WHERE m.id IN (SELECT log FROM blocked)
 OR m.id IN (SELECT memory FROM information_quarantined_memories())
 OR (NOT EXISTS(SELECT 1 FROM memory_input_snapshots s WHERE s.memory_id=m.id)
  AND EXISTS(SELECT 1 FROM memory_provenance p JOIN blocked b ON b.log=p.conversation_id
   WHERE p.memory_id=m.id));
$$;

-- Unbound maintenance, digests and promotion remain fail-closed. Only authenticated
-- disclosure paths may evaluate a memory against a particular reader's source grants.
CREATE OR REPLACE FUNCTION information_memory_safe(memory TEXT) RETURNS BOOLEAN
 LANGUAGE SQL STABLE AS $$
 SELECT information_log_readable($1,NULL)
 AND NOT EXISTS(SELECT 1 FROM information_quarantined_memories() q WHERE q.memory=$1)
 AND (EXISTS(SELECT 1 FROM memory_input_snapshots s WHERE s.memory_id=$1)
  OR NOT EXISTS(SELECT 1 FROM memory_provenance p WHERE p.memory_id=$1
   AND NOT information_log_readable(p.conversation_id,NULL)));
$$;

-- SYSTEM indexing is processing, not disclosure. Snapshot outputs can be indexed
-- without making private sources generally readable or depending on creator grants.
-- Withdrawal, missing dependencies, quarantine and the dependency bound still fence it.
CREATE FUNCTION information_memory_indexable(memory TEXT) RETURNS BOOLEAN
 LANGUAGE SQL STABLE AS $$
 SELECT CASE WHEN EXISTS(SELECT 1 FROM memory_input_snapshots s WHERE s.memory_id=$1)
 THEN NOT EXISTS(SELECT 1 FROM information_quarantined_memories() q WHERE q.memory=$1)
  AND NOT EXISTS(SELECT 1 FROM information_job_inputs i WHERE i.job_id=$1 AND NOT (
   SELECT count(*)<=1024 AND coalesce(bool_and(coalesce(r.availability='active',false)),false)
   FROM knowledge_dependency_revisions(i.revision_id) d
   LEFT JOIN information_revisions r ON r.id=d.id))
 ELSE information_memory_safe($1) END;
$$;

-- Copy every disclosed memory's restrictions onto the receiving run AND its ancestors
-- before disclosure. Summaries and index entries carry the same restrictions as bodies.
CREATE FUNCTION information_retain_memory_inputs(target TEXT, sources TEXT[]) RETURNS VOID
 LANGUAGE plpgsql AS $$
BEGIN
 WITH logs AS MATERIALIZED (SELECT log FROM information_memory_source_logs($2)),
 recipients AS MATERIALIZED (SELECT log FROM information_memory_source_logs(ARRAY[$1]))
 INSERT INTO information_job_inputs(job_id,owner_handle,revision_id,scope,project_name,include_shared)
 SELECT DISTINCT r.log,i.owner_handle,i.revision_id,i.scope,i.project_name,i.include_shared
 FROM recipients r CROSS JOIN logs l JOIN information_job_inputs i ON i.job_id=l.log
 ON CONFLICT DO NOTHING;
END;
$$;
