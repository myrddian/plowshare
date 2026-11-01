-- Live manifest identities retain the file-provider session fence. Immutable source reuse uses
-- provider/root/path within the existing account/agent/workspace scope and exact hash/parser.
-- Alias only accepted manifests whose retained index independently matches the same revision.
INSERT INTO code_workspace_indexes(workspace_id,source_key,source_hash,parser_version,revision_id)
SELECT DISTINCT s.workspace_id,
 encode(sha256(convert_to('workspace-source-v1'||chr(10)||s.provider||chr(10)||s.root||chr(10)||s.path,'UTF8')),'hex'),
 s.source_hash,i.parser_version,s.revision_id
FROM code_workspace_sources s JOIN code_workspace_indexes i
 ON i.workspace_id=s.workspace_id AND i.source_key=s.source_key
 AND i.source_hash=s.source_hash AND i.revision_id=s.revision_id
WHERE s.provider IN ('local','remote')
 AND s.root !~ '[[:cntrl:]]' AND s.path !~ '[[:cntrl:]]'
ON CONFLICT DO NOTHING;

-- Historical workspace syntax intake skipped paid stages but incorrectly retained automatic
-- policy. Index provenance, exact generated source identity and the untouched first generation
-- identify that intake; a name prefix alone is insufficient. Preserve explicit owner requests.
CREATE TEMP TABLE workspace_syntax_policy_repair ON COMMIT DROP AS
SELECT r.id,r.generation,q.owner_handle,
 (SELECT request_id FROM information_requests request
  WHERE request.revision_id=r.id ORDER BY request_id LIMIT 1) AS request_id
FROM information_revisions r JOIN information_resources q ON q.id=r.resource_id
WHERE r.document_type='code' AND q.kind='source' AND r.generation=1
 AND r.processing_policy='automatic' AND NOT r.auto_tag_requested
 AND EXISTS(SELECT 1 FROM code_workspace_indexes i
  WHERE i.revision_id=r.id
   AND q.source_name LIKE 'workspace-code/'||i.workspace_id||':'||i.source_key||'/%'
   AND r.source_uri='workspace-code:'||i.workspace_id||':'||i.source_key)
 AND NOT EXISTS(SELECT 1 FROM information_stage_authorizations a
  WHERE a.revision_id=r.id AND a.stage NOT IN ('extract','derive'))
 -- Older generic retries did not record stage selections. Preserve ambiguous owner intent rather
 -- than infer that those requests were automatic, or manufacture retrospective stage grants.
 AND NOT EXISTS(SELECT 1 FROM information_revision_events e
  WHERE e.revision_id=r.id AND e.stage='processing' AND e.action='retry')
 AND EXISTS(SELECT 1 FROM information_requests request WHERE request.revision_id=r.id);

-- These two retained authorizations keep incomplete deterministic syntax work recoverable.
INSERT INTO information_stage_authorizations(revision_id,generation,stage,actor,request_id)
SELECT repair.id,repair.generation,stage,repair.owner_handle,repair.request_id
FROM workspace_syntax_policy_repair repair CROSS JOIN (VALUES ('extract'),('derive')) allowed(stage)
ON CONFLICT DO NOTHING;

-- This migration is the single repair of the original immutable policy. The trigger is restored
-- in the same migration transaction. No lineage, generation, results or attempt receipts change.
ALTER TABLE information_revisions DISABLE TRIGGER information_revision_lineage_immutable;
UPDATE information_revisions r SET processing_policy='explicit_only'
FROM workspace_syntax_policy_repair repair WHERE r.id=repair.id;
ALTER TABLE information_revisions ENABLE TRIGGER information_revision_lineage_immutable;

-- Removing queued/running automatic work also fences late stage publication. Finished evidence
-- and separate embedding attempt/unknown-outcome fences remain untouched; nothing is replayed.
UPDATE information_steps s SET state='skipped',error='workspace_syntax_only',
 lease_token=NULL,lease_until=NULL,finished_at=now()
FROM workspace_syntax_policy_repair repair
WHERE s.revision_id=repair.id AND s.generation=repair.generation
 AND s.stage IN ('embed','summarise','summary_embed','autoTag','tagGroups')
 AND s.state IN ('pending','running');
