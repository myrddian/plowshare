-- Read multiple exact revisions with one authority/dependency snapshot. NULL targets selects heads.
CREATE FUNCTION information_selected_candidates(actor TEXT, selected_scopes UUID[], include_global BOOLEAN,
 boundary_home TEXT, targets UUID[])
 RETURNS TABLE(revision UUID,allowed BOOLEAN,read_scope TEXT,read_project TEXT)
 LANGUAGE SQL STABLE SET jit=off AS $$
 WITH authority AS MATERIALIZED (SELECT * FROM knowledge_read_authority($1)),
 actor_fact AS MATERIALIZED (
  SELECT actor_id,general_member,contained_knowledge_actor($1) AS contained FROM authority LIMIT 1
 ),
 readable_scopes AS MATERIALIZED (
  SELECT a.scope_id FROM authority a JOIN authority_scopes s ON s.id=a.scope_id
  LEFT JOIN projects p ON p.id=s.project_id
  WHERE $4 IS NULL OR p.name=$4 OR (s.kind='CONTAINED' AND contained_scope_in_home(s.id,$4))
 ),
 admission AS MATERIALIZED (
  SELECT (a.general_member OR a.contained)
   AND cardinality($2)<=32 AND (cardinality($2)>0 OR $3)
   AND NOT EXISTS(SELECT 1 FROM unnest($2) chosen(id)
    WHERE NOT EXISTS(SELECT 1 FROM readable_scopes s WHERE s.scope_id=chosen.id))
   AND (NOT $3 OR (a.general_member AND NOT a.contained AND $4 IS NULL)) AS allowed
  FROM actor_fact a
 ),
 candidates AS MATERIALIZED (
  SELECT r.id FROM information_revisions r JOIN information_resources q ON q.id=r.resource_id
  JOIN information_resource_policies policy ON policy.resource_id=q.id CROSS JOIN admission a
  WHERE a.allowed AND r.availability='active' AND r.document_type='document'
   AND (($5 IS NULL AND NOT r.excluded AND r.id=information_navigation_head(q.id)) OR r.id=ANY($5))
   AND (q.home_scope_id=ANY($2) OR ($3 AND policy.audience='GLOBAL_READ'))
 ),
 dependencies AS MATERIALIZED (
  SELECT root.id AS root,dependency.id FROM candidates root
  CROSS JOIN LATERAL knowledge_dependency_revisions(root.id) dependency
 ),
 nodes AS MATERIALIZED (SELECT DISTINCT id FROM dependencies),
 base_facts AS MATERIALIZED (
  SELECT r.id,r.availability,q.home_scope_id,s.kind AS scope_kind,s.builtin,p.name AS project_name,
   policy.audience,policy.private_only,policy.privacy_principal,
   information_navigation_policy(q.id) AS navigation
  FROM nodes node JOIN information_revisions r ON r.id=node.id
  JOIN information_resources q ON q.id=r.resource_id JOIN authority_scopes s ON s.id=q.home_scope_id
  JOIN information_resource_policies policy ON policy.resource_id=q.id
  LEFT JOIN projects p ON p.id=s.project_id
 ),
 public_facts AS MATERIALIZED (
  SELECT f.*,coalesce(f.audience='GLOBAL_READ' AND f.scope_kind NOT IN ('RESTRICTED','CONTAINED')
   AND NOT f.private_only AND NOT coalesce((f.navigation->>'ownerOnly')::boolean,true)
   AND f.availability='active' AND information_public_dependencies(f.id),false) AS general_readable
  FROM base_facts f
 ),
 facts AS MATERIALIZED (
  SELECT f.*,EXISTS(SELECT 1 FROM readable_scopes s WHERE s.scope_id=f.home_scope_id) AS home_readable,
   coalesce((a.general_member OR a.contained) AND f.availability='active'
    AND f.audience IN ('HOME','GLOBAL_READ') AND f.scope_kind<>'RESTRICTED'
    AND ((NOT f.private_only AND NOT coalesce((f.navigation->>'ownerOnly')::boolean,true))
     OR f.privacy_principal=a.actor_id)
    AND (EXISTS(SELECT 1 FROM readable_scopes s WHERE s.scope_id=f.home_scope_id)
     OR ($4 IS NULL AND a.general_member AND NOT a.contained AND f.general_readable)),false) AS readable
  FROM public_facts f CROSS JOIN actor_fact a
 ),
 accepted AS MATERIALIZED (
  SELECT d.root,bool_and(coalesce(f.readable,false)) AND count(*)<=1024 AS readable
  FROM dependencies d LEFT JOIN facts f ON f.id=d.id GROUP BY d.root
 )
 SELECT c.id,true,
  CASE WHEN NOT f.home_readable THEN 'global_read'
   WHEN f.builtin='GLOBAL_KNOWLEDGE' THEN 'account'
   WHEN f.project_name IS NOT NULL THEN 'project' ELSE 'personal' END,
  CASE WHEN f.home_readable THEN f.project_name ELSE NULL END
 FROM candidates c JOIN accepted a ON a.root=c.id JOIN facts f ON f.id=c.id WHERE a.readable
 UNION ALL SELECT NULL,false,NULL,NULL FROM admission WHERE NOT allowed;
$$;

-- Preserve the shipped discovery routine and its exact-target contract through the same policy.
CREATE OR REPLACE FUNCTION information_discovery_candidates(actor TEXT, selected_scopes UUID[], include_global BOOLEAN,
 boundary_home TEXT, target UUID DEFAULT NULL)
 RETURNS TABLE(revision UUID,allowed BOOLEAN,read_scope TEXT,read_project TEXT)
 LANGUAGE SQL STABLE SET jit=off AS $$
 SELECT * FROM information_selected_candidates($1,$2,$3,$4,
  CASE WHEN $5 IS NULL THEN NULL::uuid[] ELSE ARRAY[$5] END);
$$;
