-- Selection describes the requested corpus; live scoped READ and current root audience authorize it.
-- Retire the human-only Shared audience without silently publishing old resources to all accounts.
UPDATE information_resource_policies SET version=version+1,audience='HOME',needs_review=true,
 actor='00000000-0000-0000-0000-000000000001',reason='AUDIENCE',changed_at=now()
 WHERE audience='LEGACY_SHARED';
ALTER TABLE information_resource_policies DROP CONSTRAINT information_resource_policies_audience_check;
ALTER TABLE information_resource_policies ADD CHECK(audience IN ('HOME','GLOBAL_READ','QUARANTINED'));

CREATE FUNCTION knowledge_scope_readable(actor TEXT, scope UUID) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 SELECT information_global_read_member($1) AND EXISTS(SELECT 1 FROM authority_scopes s
 LEFT JOIN projects p ON p.id=s.project_id LEFT JOIN authority_accounts a ON a.legacy_handle=$1
 WHERE s.id=$2 AND (
   (s.kind='ACCOUNT_KNOWLEDGE' AND s.builtin IS NULL AND s.account_id=a.id)
   OR (s.builtin='GLOBAL_KNOWLEDGE' AND information_account_action($1,'READ'))
   OR (s.kind IN ('PROJECT','PERSONAL') AND
     coalesce(authority_project_role(p.name,$1),service_project_role(p.name,$1)) IS NOT NULL)));
$$;
CREATE OR REPLACE FUNCTION information_project_readable(target BIGINT, account TEXT) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM authority_scopes s WHERE s.project_id=$1 AND knowledge_scope_readable($2,s.id));
$$;
CREATE OR REPLACE FUNCTION information_public_policy(revision UUID) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 SELECT coalesce(p.audience='GLOBAL_READ' AND s.kind<>'RESTRICTED' AND NOT p.private_only
   AND NOT coalesce((information_navigation_policy(q.id)->>'ownerOnly')::boolean,true)
   AND r.availability='active',false)
 FROM information_revisions r JOIN information_resources q ON q.id=r.resource_id
 JOIN authority_scopes s ON s.id=q.home_scope_id
 JOIN information_resource_policies p ON p.resource_id=q.id WHERE r.id=$1;
$$;
-- General READ must not expose derived content after a dependency loses its audience.
CREATE FUNCTION knowledge_general_readable(revision UUID) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 SELECT coalesce(information_public_policy($1) AND information_public_dependencies($1),false);
$$;
-- No owner, revision visibility, account-kind, handle-prefix or rootless compatibility reader.
CREATE FUNCTION knowledge_revision_readable(revision UUID, actor TEXT) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 SELECT coalesce(information_global_read_member($2) AND r.availability='active'
   AND p.audience IN ('HOME','GLOBAL_READ') AND s.kind<>'RESTRICTED'
   AND information_privacy_allows(q.id,$2)
   AND (knowledge_scope_readable($2,q.home_scope_id) OR knowledge_general_readable(r.id)),false)
 FROM information_revisions r JOIN information_resources q ON q.id=r.resource_id
 JOIN authority_scopes s ON s.id=q.home_scope_id
 JOIN information_resource_policies p ON p.resource_id=q.id WHERE r.id=$1;
$$;
-- UNION terminates cycles. The sentinel row makes overlarge dependency closures fail closed.
CREATE FUNCTION knowledge_dependency_revisions(revision UUID) RETURNS TABLE(id UUID) LANGUAGE SQL STABLE AS $$
 WITH RECURSIVE dependencies(id) AS (
   SELECT $1 UNION SELECT edge.id FROM dependencies d CROSS JOIN LATERAL (
     SELECT i.input_revision AS id FROM information_inputs i WHERE i.derived_revision=d.id
     UNION SELECT coalesce(o.pinned_revision,information_navigation_head(o.target))
       FROM information_reference_obligations o JOIN information_revisions r ON r.resource_id=o.source
       WHERE r.id=d.id) edge
 ) SELECT id FROM dependencies LIMIT 1025;
$$;
CREATE FUNCTION knowledge_dependencies_readable(revision UUID, actor TEXT) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 SELECT count(*)<=1024 AND coalesce(bool_and(coalesce(knowledge_revision_readable(id,$2),false)),false)
 FROM knowledge_dependency_revisions($1);
$$;
-- Keep the existing SQL argument names because PostgreSQL retains function signatures on replace.
-- include_shared now carries includeGlobal; no public Shared selector or legacy audience survives.
CREATE OR REPLACE FUNCTION information_root_readable(revision UUID, account TEXT, selected_scope TEXT,
 selected_project TEXT, include_shared BOOLEAN) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 SELECT coalesce(knowledge_revision_readable(r.id,$2) AND (
   ($3='global_read' AND $4 IS NULL AND NOT $5 AND knowledge_general_readable(r.id))
   OR ($3='account' AND $4 IS NULL AND NOT $5 AND s.builtin='GLOBAL_KNOWLEDGE')
   OR ($3 IN ('personal','project') AND (
     ($3='personal' AND $4 IS NULL AND s.kind='ACCOUNT_KNOWLEDGE' AND s.builtin IS NULL AND s.account_id=actor.id)
     OR ($3='project' AND p.name=$4 AND knowledge_scope_readable($2,s.id))
     OR ($3='project' AND s.kind='ACCOUNT_KNOWLEDGE' AND s.account_id=actor.id AND EXISTS(
       SELECT 1 FROM information_links l JOIN projects linked ON linked.id=l.project_id
       WHERE l.revision_id=r.id AND linked.name=$4 AND information_project_readable(linked.id,$2)))
     OR ($5 AND knowledge_general_readable(r.id))))),false)
 FROM information_revisions r JOIN information_resources q ON q.id=r.resource_id
 JOIN authority_scopes s ON s.id=q.home_scope_id LEFT JOIN projects p ON p.id=s.project_id
 LEFT JOIN authority_accounts actor ON actor.legacy_handle=$2 WHERE r.id=$1;
$$;
CREATE OR REPLACE FUNCTION information_readable(target UUID, account TEXT, selected_scope TEXT,
 selected_project TEXT, include_shared BOOLEAN) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 SELECT coalesce(information_root_readable($1,$2,$3,$4,$5),false) AND knowledge_dependencies_readable($1,$2);
$$;
CREATE OR REPLACE FUNCTION information_navigation_own_readable(revision UUID, account TEXT,
 selected_project TEXT) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 SELECT knowledge_dependencies_readable($1,$2);
$$;
CREATE OR REPLACE FUNCTION information_reference_readable(target UUID, account TEXT, selected_project TEXT)
 RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$ SELECT knowledge_dependencies_readable($1,$2); $$;
CREATE OR REPLACE FUNCTION information_navigation_reference_visible(resource UUID, account TEXT, selected_project TEXT)
 RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM information_resources q JOIN information_revisions r ON r.id=information_navigation_head(q.id)
   JOIN information_resource_policies policy ON policy.resource_id=q.id
   JOIN authority_scopes s ON s.id=q.home_scope_id
   WHERE q.id=$1 AND information_global_read_member($2) AND NOT r.excluded
   AND r.availability='active' AND policy.audience<>'QUARANTINED' AND s.kind<>'RESTRICTED'
   AND NOT coalesce((information_navigation_policy(q.id)->>'referenceHidden')::boolean,true));
$$;
-- A retained ceiling is checked across the entire consumed dependency closure before disclosure.
-- Personal run addresses are projects; Personal documents live in that account's knowledge home.
CREATE FUNCTION knowledge_document_in_boundary(revision UUID, actor TEXT, home_project TEXT, global_read BOOLEAN)
 RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 SELECT count(*)<=1024 AND coalesce(bool_and(coalesce(
   (p.name=$3 AND knowledge_scope_readable($2,s.id))
   OR (s.kind='ACCOUNT_KNOWLEDGE' AND s.builtin IS NULL AND a.legacy_handle=$2
     AND EXISTS(SELECT 1 FROM projects personal WHERE personal.name=$3 AND personal.personal_owner=$2))
   OR ($4 AND information_global_read_member($2) AND knowledge_general_readable(r.id)),false)),false)
 FROM knowledge_dependency_revisions($1) dependency
 LEFT JOIN information_revisions r ON r.id=dependency.id LEFT JOIN information_resources q ON q.id=r.resource_id
 LEFT JOIN authority_scopes s ON s.id=q.home_scope_id LEFT JOIN projects p ON p.id=s.project_id
 LEFT JOIN authority_accounts a ON a.id=s.account_id;
$$;
-- Existing audience management still requires scoped control; the general selector grants no WRITE.
CREATE OR REPLACE FUNCTION information_knowledge_allows(revision UUID, account TEXT, selected_scope TEXT,
 selected_project TEXT, include_shared BOOLEAN, action TEXT) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 SELECT coalesce((information_home_allows(q.id,$2,$3,$4,$6)
     OR ($6='SET_AUDIENCE' AND $3='global_read' AND s.builtin='GLOBAL_KNOWLEDGE' AND information_scope_control($2,s.id)))
   AND policy.audience<>'QUARANTINED' AND information_privacy_allows(q.id,$2)
   AND knowledge_dependencies_readable(r.id,$2),false)
 FROM information_revisions r JOIN information_resources q ON q.id=r.resource_id
 JOIN authority_scopes s ON s.id=q.home_scope_id
 JOIN information_resource_policies policy ON policy.resource_id=q.id WHERE r.id=$1;
$$;
-- Explicit legacy assignment can establish a home, never manufacture general-read publication.
CREATE OR REPLACE FUNCTION information_adopt_root_policy(revision UUID, account TEXT) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE resource UUID;
BEGIN
 PERFORM pg_advisory_xact_lock(hashtextextended('information-navigation-control',0));
 IF NOT EXISTS(SELECT 1 FROM information_revisions WHERE id=$1) THEN RETURN; END IF;
 IF NOT account_active($2) OR NOT EXISTS(SELECT 1 FROM admins WHERE handle=$2 AND account_kind='USER' AND NOT bootstrap) THEN
   RAISE insufficient_privilege USING MESSAGE='legacy assignment requires active human operator';
 END IF;
 SELECT q.id INTO resource FROM information_resources q JOIN information_revisions r ON r.resource_id=q.id
   WHERE r.id=$1 AND q.home_scope_id IS NOT NULL FOR UPDATE OF q;
 IF resource IS NULL THEN RAISE EXCEPTION 'legacy assignment has no explicit home'; END IF;
 UPDATE information_resource_policies SET version=version+1,audience='HOME',private_only=false,needs_review=false,
   actor=(SELECT id FROM authority_accounts WHERE legacy_handle=$2),reason='ASSIGNMENT',changed_at=now()
 WHERE resource_id=resource AND audience='QUARANTINED';
END $$;

-- Remove the obsolete fallback entry points after replacing every active reader.
DROP FUNCTION information_legacy_readable(UUID,TEXT,TEXT,TEXT,BOOLEAN);
DROP FUNCTION information_pre_root_readable(UUID,TEXT,TEXT,TEXT,BOOLEAN);
