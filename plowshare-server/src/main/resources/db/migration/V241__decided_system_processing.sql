-- information_system_processing_revisions(system_authorized) lets the server pass the SYSTEM
-- processing decision it already holds in memory (AuthoritySnapshot.systemProcessingAuthorized).
--
-- The zero-argument function (V236) begins with authority_live_grants(), which evaluates
-- account_active() per grant and so scans admins on every worker poll, although its answer is
-- one installation-wide boolean that changes only when authority changes. With a non-null
-- argument, COALESCE returns before the EXISTS initplan runs; materialized CTEs are read only on
-- demand, so neither `live` nor `intrinsic` executes. NULL keeps the original SQL decision.
--
-- The body is otherwise V236's, character for character. The zero-argument function is kept so
-- that a server binary from before this migration continues to work during a rolling upgrade.
CREATE FUNCTION information_system_processing_revisions(system_authorized BOOLEAN)
 RETURNS TABLE(revision UUID)
 LANGUAGE SQL STABLE AS $$
 WITH RECURSIVE live AS MATERIALIZED (SELECT id FROM authority_live_grants()),
 intrinsic AS MATERIALIZED (
  SELECT g.id FROM authority_grants g JOIN live ON live.id=g.id
  JOIN authority_roles role ON role.id=g.role_id
  JOIN authority_role_actions a ON a.role_id=role.id AND a.role_version=g.role_version AND a.profile='DEFAULT'
  WHERE NOT role.assignable AND role.intrinsic_account=g.subject_account
   AND g.coverage='ALL_SCOPES' AND g.subject_agent IS NULL
  GROUP BY g.id HAVING count(*) FILTER(WHERE a.action IN ('READ','WRITE','CONVERSATION_CREATE','RUN_MODEL'))=4
 ),
 retained(root,id) AS (
  SELECT derived_revision,input_revision FROM information_inputs
  UNION SELECT retained.root,i.input_revision FROM retained JOIN information_inputs i ON i.derived_revision=retained.id
 ),
 invalid AS MATERIALIZED (
  SELECT DISTINCT retained.root FROM retained LEFT JOIN information_revisions r ON r.id=retained.id
  LEFT JOIN information_resources q ON q.id=r.resource_id LEFT JOIN authority_scopes scope ON scope.id=q.home_scope_id
  WHERE r.id IS NULL OR scope.id IS NULL OR r.availability<>'active'
 )
 SELECT r.id FROM information_revisions r JOIN information_resources q ON q.id=r.resource_id
 JOIN authority_scopes home ON home.id=q.home_scope_id LEFT JOIN projects p ON p.id=q.project_id
 WHERE coalesce(system_authorized, EXISTS(SELECT 1 FROM intrinsic))
 AND EXISTS(SELECT 1 FROM authority_scopes target WHERE
  (p.name IS NULL AND target.builtin='GLOBAL_KNOWLEDGE') OR
  (p.name IS NOT NULL AND target.project_id=p.id))
 AND NOT EXISTS(SELECT 1 FROM invalid WHERE invalid.root=r.id);
$$;
