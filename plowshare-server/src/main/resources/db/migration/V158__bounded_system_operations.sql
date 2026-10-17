CREATE TABLE system_operations (
  id UUID PRIMARY KEY,
  purpose TEXT NOT NULL CHECK(purpose IN ('DOCUMENT_PREPARATION','CONVERSATION_COMPACTION','SKILL_SUMMARY','SCRIBE','LEARNING','MEMORY_DIGEST','MEMORY_CURATION','STUCK_ADVISOR','CALL_VALIDATOR','COMMAND_JUDGE','SCHEDULE_READER')),
  scope_id UUID NOT NULL, -- Keep historical identity when a project is deleted; live checks require its mapping.
  grant_id UUID NOT NULL REFERENCES authority_grants(id),
  agent TEXT NOT NULL,
  model TEXT NOT NULL,
  binding_hash TEXT NOT NULL CHECK(binding_hash ~ '^[a-f0-9]{64}$'),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE FUNCTION protect_system_operation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'SYSTEM purpose bindings are immutable'; END $$;
CREATE TRIGGER system_operation_immutable BEFORE UPDATE OR DELETE ON system_operations
  FOR EACH ROW EXECUTE FUNCTION protect_system_operation();

-- Resolve SYSTEM from the persisted intrinsic role/account relationship and live lineage.
-- UUID equality alone never authorizes processing; this issues no per-task grant.
CREATE FUNCTION information_system_scope_valid(project TEXT) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
  SELECT EXISTS(
    SELECT 1 FROM authority_grants g JOIN authority_live_grants() live ON live.id=g.id
      JOIN authority_roles role ON role.id=g.role_id
      JOIN authority_role_actions a ON a.role_id=role.id AND a.role_version=g.role_version AND a.profile='DEFAULT'
    WHERE NOT role.assignable AND role.intrinsic_account=g.subject_account
      AND g.coverage='ALL_SCOPES' AND g.subject_agent IS NULL
      AND EXISTS(SELECT 1 FROM authority_scopes target WHERE
        (project IS NULL AND target.builtin='GLOBAL_KNOWLEDGE') OR
        (project IS NOT NULL AND target.project_id=(SELECT id FROM projects WHERE name=project)))
    GROUP BY g.id HAVING count(*) FILTER(WHERE a.action IN ('READ','WRITE','CONVERSATION_CREATE','RUN_MODEL'))=4)
$$;
CREATE FUNCTION information_system_processing_valid(revision UUID) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
  SELECT information_system_scope_valid((SELECT p.name FROM information_revisions r
      JOIN information_resources q ON q.id=r.resource_id LEFT JOIN projects p ON p.id=q.project_id WHERE r.id=revision))
    AND EXISTS(SELECT 1 FROM information_revisions r JOIN information_resources q ON q.id=r.resource_id JOIN authority_scopes s ON s.id=q.home_scope_id WHERE r.id=revision)
    AND NOT EXISTS(
      WITH RECURSIVE retained(id) AS (
        SELECT input_revision FROM information_inputs WHERE derived_revision=revision
        UNION SELECT i.input_revision FROM information_inputs i JOIN retained p ON i.derived_revision=p.id)
      SELECT 1 FROM retained i LEFT JOIN information_revisions r ON r.id=i.id
      LEFT JOIN information_resources q ON q.id=r.resource_id LEFT JOIN authority_scopes scope ON scope.id=q.home_scope_id
      WHERE r.id IS NULL OR scope.id IS NULL OR r.availability<>'active' OR r.excluded)
$$;

-- Existing conversation allowance is the durable source for compaction, not a SYSTEM wallet.
ALTER TABLE operation_inference_budgets ADD COLUMN source_conversation TEXT;

-- Admission evidence must not prevent the supported project deletion lifecycle.
-- Recreated names receive new scope IDs; retained work cannot adopt that mapping.
ALTER TABLE operation_admissions DROP CONSTRAINT operation_admissions_scope_fkey;
