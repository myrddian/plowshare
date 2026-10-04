-- Personal is an account-owned project runtime, presented separately from projects.
ALTER TABLE projects ADD COLUMN personal_owner TEXT UNIQUE REFERENCES admins(handle);
ALTER TABLE projects DROP CONSTRAINT projects_a_union_is_rooted_on_a_client;
ALTER TABLE projects ADD CONSTRAINT projects_a_union_has_a_place
    CHECK (union_since IS NULL OR machine IS NOT NULL OR personal_owner IS NOT NULL);
ALTER TABLE projects ADD CONSTRAINT projects_personal_identity
    CHECK (personal_owner IS NULL OR name = 'personal:' || encode(convert_to(personal_owner, 'UTF8'), 'hex'));

INSERT INTO projects(name, personal_owner)
SELECT 'personal:' || encode(convert_to(handle, 'UTF8'), 'hex'), handle FROM admins;
INSERT INTO project_members(project_id, handle)
SELECT id, personal_owner FROM projects WHERE personal_owner IS NOT NULL;

-- Keep ownerless legacy history in global. Owned histories retain ids and lineage.
UPDATE conversations c SET project_id = p.id
FROM projects p WHERE c.project_id IS NULL AND c.owner_handle = p.personal_owner;

-- Running orchestration homes follow the migrated conductor, retaining ids and receipts.
UPDATE orchestrations o SET project = p.name FROM projects p
WHERE o.project IS NULL AND o.caller_handle = p.personal_owner;

-- Provenance remains durable when inherited resources execute inside another project.
ALTER TABLE skill_executions DROP CONSTRAINT skill_executions_tier_check;
ALTER TABLE skill_executions ADD CONSTRAINT skill_executions_tier_check
    CHECK (tier IN ('PROJECT', 'SESSION', 'PERSONAL', 'GLOBAL', 'SHIPPED'));
ALTER TABLE orchestrations DROP CONSTRAINT orchestrations_tier_is_known;
ALTER TABLE orchestrations ADD CONSTRAINT orchestrations_tier_is_known
    CHECK (tier IN ('PROJECT', 'SESSION', 'PERSONAL', 'GLOBAL', 'SHIPPED'));
