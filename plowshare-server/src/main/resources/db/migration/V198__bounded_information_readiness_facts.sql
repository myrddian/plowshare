-- Exact-target readiness shares the existing V185 authority policy with discovery. Candidate
-- filters remain distinct: an excluded or historical target may still be inspected when its
-- current policy permits it. Resolve live READ/dependency facts once over at most 100 targets,
-- preserving privacy, retained ceilings and the per-root 1025th dependency sentinel.
CREATE FUNCTION knowledge_readable_candidates(actor TEXT,selected_scope TEXT,selected_project TEXT,
 include_global BOOLEAN,boundary_home TEXT,boundary_global BOOLEAN,corpus TEXT,kind TEXT,
 heads BOOLEAN,related_resource UUID,relation TEXT,direction TEXT,targets UUID[],include_excluded BOOLEAN)
 RETURNS TABLE(revision UUID) LANGUAGE SQL STABLE SET jit=off AS $$
 WITH authority AS MATERIALIZED (SELECT * FROM knowledge_read_authority($1)),
 actor_fact AS MATERIALIZED (SELECT actor_id,general_member FROM authority LIMIT 1),
 readable_scopes AS MATERIALIZED (SELECT scope_id FROM authority WHERE scope_id IS NOT NULL),
 candidates AS MATERIALIZED (
  SELECT r.id FROM information_revisions r JOIN information_resources q ON q.id=r.resource_id
  CROSS JOIN actor_fact a LEFT JOIN authority_scopes scope ON scope.id=q.home_scope_id
  LEFT JOIN projects home ON home.id=scope.project_id
  LEFT JOIN information_resource_policies root_policy ON root_policy.resource_id=q.id
  WHERE r.availability='active' AND ($14 OR NOT r.excluded) AND ($7 IS NULL OR r.document_type=$7)
   AND ($13 IS NULL OR (cardinality($13) BETWEEN 1 AND 100 AND r.id=ANY($13)))
   AND ($8 IS NULL OR q.kind=$8) AND (NOT $9 OR r.id=information_navigation_head(q.id))
   -- Cheap selection branches prune unrelated tenants BEFORE constructing dependency closures.
   -- GLOBAL_READ is only a possible audience here: full privacy, public-dependency and current
   -- resource checks below still decide disclosure. Related neighbours deliberately bypass this
   -- root selection fence; their parent is selected separately after its own full checks.
   AND ($10 IS NOT NULL OR (a.general_member AND (
    ($2='global_read' AND $3 IS NULL AND NOT $4 AND root_policy.audience='GLOBAL_READ')
    OR ($2='account' AND $3 IS NULL AND NOT $4 AND scope.builtin='GLOBAL_KNOWLEDGE')
    OR ($2 IN ('personal','project') AND (
     ($2='personal' AND $3 IS NULL AND scope.kind='ACCOUNT_KNOWLEDGE' AND scope.builtin IS NULL AND scope.account_id=a.actor_id)
     OR ($2='project' AND home.name=$3 AND EXISTS(SELECT 1 FROM readable_scopes allowed WHERE allowed.scope_id=scope.id))
     OR ($2='project' AND scope.kind='ACCOUNT_KNOWLEDGE' AND scope.account_id=a.actor_id AND EXISTS(
      SELECT 1 FROM information_links link JOIN projects linked ON linked.id=link.project_id
      JOIN authority_scopes linked_scope ON linked_scope.project_id=linked.id
      JOIN readable_scopes allowed ON allowed.scope_id=linked_scope.id
      WHERE link.revision_id=r.id AND linked.name=$3))
     -- An account's own knowledge scope IS its personal project; selecting that project selects
     -- it, with no link row. Same predicate the in_boundary ceiling below already uses.
     OR ($2='project' AND scope.kind='ACCOUNT_KNOWLEDGE' AND scope.builtin IS NULL AND scope.account_id=a.actor_id
      AND EXISTS(SELECT 1 FROM projects personal WHERE personal.name=$3 AND personal.personal_owner=$1))
     OR ($4 AND root_policy.audience='GLOBAL_READ'))))))
   AND ($10 IS NULL OR EXISTS(SELECT 1 FROM information_relationships edge
    WHERE NOT edge.removed AND (CASE WHEN $12='backlinks' THEN edge.target ELSE edge.source END)=$10
     AND (CASE WHEN $12='backlinks' THEN edge.source ELSE edge.target END)=q.id
     AND ($11 IS NULL OR edge.relation=$11)))
 ),
 roots AS MATERIALIZED (
  SELECT id FROM candidates UNION SELECT information_navigation_head($10) WHERE $10 IS NOT NULL
 ),
 dependencies AS MATERIALIZED (
  SELECT root.id AS root,dependency.id FROM roots root
  CROSS JOIN LATERAL knowledge_dependency_revisions(root.id) dependency
 ),
 nodes AS MATERIALIZED (SELECT DISTINCT id FROM dependencies),
 base_facts AS MATERIALIZED (
  SELECT r.id,r.resource_id,r.availability,q.home_scope_id,s.kind AS scope_kind,s.builtin,s.account_id,
   p.name AS project_name,policy.audience,policy.private_only,policy.privacy_principal,
   information_navigation_policy(q.id) AS navigation
  FROM nodes node JOIN information_revisions r ON r.id=node.id
  JOIN information_resources q ON q.id=r.resource_id JOIN authority_scopes s ON s.id=q.home_scope_id
  JOIN information_resource_policies policy ON policy.resource_id=q.id
  LEFT JOIN projects p ON p.id=s.project_id
 ),
 public_facts AS MATERIALIZED (
  SELECT f.*,coalesce(f.audience='GLOBAL_READ' AND f.scope_kind<>'RESTRICTED' AND NOT f.private_only
   AND NOT coalesce((f.navigation->>'ownerOnly')::boolean,true) AND f.availability='active'
   AND information_public_dependencies(f.id),false) AS general_readable
  FROM base_facts f
 ),
 facts AS MATERIALIZED (
  SELECT f.id,
   coalesce(a.general_member AND f.availability='active' AND f.audience IN ('HOME','GLOBAL_READ')
    AND f.scope_kind<>'RESTRICTED'
    AND ((NOT f.private_only AND NOT coalesce((f.navigation->>'ownerOnly')::boolean,true))
     OR f.privacy_principal=a.actor_id)
    AND (EXISTS(SELECT 1 FROM readable_scopes s WHERE s.scope_id=f.home_scope_id) OR f.general_readable),false) AS readable,
   coalesce(($2='global_read' AND $3 IS NULL AND NOT $4 AND f.general_readable)
    OR ($2='account' AND $3 IS NULL AND NOT $4 AND f.builtin='GLOBAL_KNOWLEDGE')
    OR ($2 IN ('personal','project') AND (
     ($2='personal' AND $3 IS NULL AND f.scope_kind='ACCOUNT_KNOWLEDGE' AND f.builtin IS NULL AND f.account_id=a.actor_id)
     OR ($2='project' AND f.project_name=$3 AND EXISTS(SELECT 1 FROM readable_scopes s WHERE s.scope_id=f.home_scope_id))
     OR ($2='project' AND f.scope_kind='ACCOUNT_KNOWLEDGE' AND f.account_id=a.actor_id AND EXISTS(
      SELECT 1 FROM information_links link JOIN projects linked ON linked.id=link.project_id
      JOIN authority_scopes scope ON scope.project_id=linked.id
      JOIN readable_scopes allowed ON allowed.scope_id=scope.id
      WHERE link.revision_id=f.id AND linked.name=$3))
     OR ($2='project' AND f.scope_kind='ACCOUNT_KNOWLEDGE' AND f.builtin IS NULL AND f.account_id=a.actor_id
      AND EXISTS(SELECT 1 FROM projects personal WHERE personal.name=$3 AND personal.personal_owner=$1))
     OR ($4 AND f.general_readable))),false) AS selected,
   -- NULL boundary_global means no retained ceiling; a Global home may itself have NULL project.
   coalesce($6 IS NULL OR (
    (f.project_name=$5 AND EXISTS(SELECT 1 FROM readable_scopes s WHERE s.scope_id=f.home_scope_id))
    OR (f.scope_kind='ACCOUNT_KNOWLEDGE' AND f.builtin IS NULL AND f.account_id=a.actor_id
     AND EXISTS(SELECT 1 FROM projects personal WHERE personal.name=$5 AND personal.personal_owner=$1))
    OR ($6 AND a.general_member AND f.general_readable)),false) AS in_boundary
  FROM public_facts f CROSS JOIN actor_fact a
 ),
 accepted AS MATERIALIZED (
  SELECT d.root,bool_and(coalesce(f.readable,false)) AND count(*)<=1024 AS readable,
   bool_and(coalesce(f.in_boundary,false)) AND count(*)<=1024 AS in_boundary
  FROM dependencies d LEFT JOIN facts f ON f.id=d.id GROUP BY d.root
 )
 SELECT c.id FROM candidates c JOIN accepted checked ON checked.root=c.id
 JOIN facts root_fact ON root_fact.id=c.id
 WHERE checked.readable AND checked.in_boundary AND (
  ($10 IS NULL AND root_fact.selected)
  OR ($10 IS NOT NULL AND EXISTS(
   SELECT 1 FROM accepted parent JOIN facts parent_fact ON parent_fact.id=parent.root
   WHERE parent.root=information_navigation_head($10) AND parent.readable AND parent.in_boundary AND parent_fact.selected)));
$$;

-- Discovery and exact-target reads use one policy implementation. Their candidate filters differ;
-- neither candidate inclusion nor a retained UUID is a permission grant.
CREATE OR REPLACE FUNCTION knowledge_readable_selection(actor TEXT,selected_scope TEXT,selected_project TEXT,
 include_global BOOLEAN,boundary_home TEXT,boundary_global BOOLEAN,corpus TEXT,kind TEXT,
 heads BOOLEAN,related_resource UUID,relation TEXT,direction TEXT)
 RETURNS TABLE(revision UUID) LANGUAGE SQL STABLE SET jit=off AS $$
 SELECT revision FROM knowledge_readable_candidates($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,NULL,false);
$$;
CREATE FUNCTION knowledge_readable_targets(actor TEXT,selected_scope TEXT,selected_project TEXT,
 include_global BOOLEAN,boundary_home TEXT,boundary_global BOOLEAN,targets UUID[],kind TEXT,
 heads BOOLEAN,related_resource UUID,relation TEXT,direction TEXT)
 RETURNS TABLE(revision UUID) LANGUAGE SQL STABLE SET jit=off AS $$
 SELECT revision FROM knowledge_readable_candidates($1,$2,$3,$4,$5,$6,NULL,$8,$9,$10,$11,$12,$7,true)
 WHERE $7 IS NOT NULL;
$$;
