-- Multi-scope discovery resolves live authority once per statement. Existing selectors and
-- execution/write homes are unchanged; the new read-only API selects explicit readable scope IDs.
-- NULL boundary_home is ordinary effective-RBAC discovery. Confined/contained work supplies its
-- server-owned home and cannot select generally readable or foreign scopes.
CREATE FUNCTION information_discovery_candidates(actor TEXT, selected_scopes UUID[], include_global BOOLEAN,
 boundary_home TEXT, target UUID DEFAULT NULL)
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
   AND (($5 IS NULL AND NOT r.excluded AND r.id=information_navigation_head(q.id)) OR r.id=$5)
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

-- One common ancestor relation for server-side retention of aggregate contributors. UNION
-- terminates cycles and preserves the same four durable ancestry edges as InformationJobs.
CREATE FUNCTION information_discovery_ancestors(target TEXT) RETURNS TABLE(id TEXT)
 LANGUAGE SQL STABLE AS $$
 WITH RECURSIVE edges(child,parent) AS (
  SELECT id,parent_id FROM conversations WHERE parent_id IS NOT NULL
  UNION SELECT conductor_conversation,caller_conversation FROM orchestrations WHERE caller_conversation IS NOT NULL
  UNION SELECT o.conductor_conversation,p.conductor_conversation FROM orchestrations o
   JOIN orchestrations p ON p.id=o.parent
  UNION SELECT conductor_conversation,id FROM orchestrations
 ), parents(id) AS (
  SELECT $1 WHERE $1 IS NOT NULL UNION SELECT e.parent FROM parents p JOIN edges e ON e.child=p.id
 ) SELECT id FROM parents;
$$;

-- Metadata search remains useful before extraction/indexing. Content search uses the existing
-- stored chunk text_search GIN index rather than rebuilding vectors from full extractions.
CREATE INDEX information_discovery_title_search ON information_revisions
 USING gin(to_tsvector('simple',coalesce(title,'')));
CREATE INDEX information_discovery_name_search ON information_resources
 USING gin(to_tsvector('simple',source_name || ' ' || tags::text));
CREATE INDEX information_discovery_head_order ON information_revisions(created_at DESC,id DESC);
