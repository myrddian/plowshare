-- Log disclosure evaluates the requesting account's scoped READ authority over every
-- retained input and dependency. Attribution records who consumed the input. It must
-- not veto another reader who has current ACL/RBAC permission for that selection.
-- Current scope, resource privacy, availability, dependency bounds and quarantine
-- continue to use the existing knowledge authority predicates. No administrator
-- exception or new grant is introduced, and no retained input is rewritten.
-- Collection reads share one authority snapshot for all retained selections so
-- targeted reads, lists and cached disclosure fences agree after grant changes.
CREATE OR REPLACE FUNCTION information_readable_input_selections(actor TEXT, roots UUID[])
 RETURNS TABLE(revision UUID,scope TEXT,project_name TEXT,include_shared BOOLEAN,readable BOOLEAN)
 LANGUAGE SQL STABLE AS $$
 WITH authority AS MATERIALIZED (SELECT * FROM knowledge_read_authority($1)),
 actor_fact AS MATERIALIZED (SELECT actor_id,general_member FROM authority LIMIT 1),
 readable_scopes AS MATERIALIZED (SELECT scope_id FROM authority WHERE scope_id IS NOT NULL),
 selections AS MATERIALIZED (
  -- Prune homes outside the reader's grant set before walking dependencies.
  -- Public candidates still undergo the complete retained selection and ACL checks below.
  SELECT DISTINCT i.revision_id,i.scope,i.project_name,i.include_shared
  FROM information_job_inputs i JOIN information_revisions r ON r.id=i.revision_id
  JOIN information_resources resource ON resource.id=r.resource_id
  JOIN information_resource_policies policy ON policy.resource_id=resource.id
  WHERE ($2 IS NULL OR i.revision_id=ANY($2))
    AND (EXISTS(SELECT 1 FROM readable_scopes allowed WHERE allowed.scope_id=resource.home_scope_id)
      OR policy.audience='GLOBAL_READ')
 ),
 dependencies AS MATERIALIZED (
  SELECT root.revision_id AS root,dependency.id FROM (SELECT DISTINCT revision_id FROM selections) root
  CROSS JOIN LATERAL knowledge_dependency_revisions(root.revision_id) dependency
 ),
 nodes AS MATERIALIZED (SELECT DISTINCT id FROM dependencies),
 base_facts AS MATERIALIZED (
  SELECT r.id,r.availability,q.id AS resource_id,q.home_scope_id,s.kind AS scope_kind,s.builtin,
   s.account_id,p.name AS project_name,policy.audience,policy.private_only,policy.privacy_principal,
   information_navigation_policy(q.id) AS navigation
  FROM nodes node JOIN information_revisions r ON r.id=node.id
  JOIN information_resources q ON q.id=r.resource_id JOIN authority_scopes s ON s.id=q.home_scope_id
  JOIN information_resource_policies policy ON policy.resource_id=q.id
  LEFT JOIN projects p ON p.id=s.project_id
 ),
 public_facts AS MATERIALIZED (
  SELECT f.*,coalesce(f.audience='GLOBAL_READ' AND f.scope_kind<>'RESTRICTED' AND NOT f.private_only
   AND NOT coalesce((f.navigation->>'ownerOnly')::boolean,true) AND f.availability='active'
   AND information_public_dependencies(f.id),false) AS general_readable FROM base_facts f
 ),
 facts AS MATERIALIZED (
  SELECT f.*,a.actor_id,coalesce(a.general_member AND f.availability='active'
   AND f.audience IN ('HOME','GLOBAL_READ') AND f.scope_kind<>'RESTRICTED'
   AND ((NOT f.private_only AND NOT coalesce((f.navigation->>'ownerOnly')::boolean,true))
     OR f.privacy_principal=a.actor_id)
   AND (EXISTS(SELECT 1 FROM readable_scopes allowed WHERE allowed.scope_id=f.home_scope_id)
     OR f.general_readable),false) AS readable FROM public_facts f CROSS JOIN actor_fact a
 ),
 accepted AS MATERIALIZED (
  SELECT d.root,count(*)<=1024 AND bool_and(coalesce(f.readable,false)) AS readable
  FROM dependencies d LEFT JOIN facts f ON f.id=d.id GROUP BY d.root
 )
 SELECT selected.revision_id,selected.scope,selected.project_name,selected.include_shared,
  coalesce(accepted.readable AND (
   (selected.scope='global_read' AND selected.project_name IS NULL AND NOT selected.include_shared
     AND f.general_readable)
   OR (selected.scope='account' AND selected.project_name IS NULL AND NOT selected.include_shared
     AND f.builtin='GLOBAL_KNOWLEDGE')
   OR (selected.scope IN ('personal','project') AND (
    (selected.scope='personal' AND selected.project_name IS NULL AND f.scope_kind='ACCOUNT_KNOWLEDGE'
      AND f.builtin IS NULL AND f.account_id=f.actor_id)
    OR (selected.scope='project' AND f.project_name=selected.project_name AND EXISTS(
      SELECT 1 FROM readable_scopes allowed WHERE allowed.scope_id=f.home_scope_id))
    OR (selected.scope='project' AND f.scope_kind='ACCOUNT_KNOWLEDGE' AND f.account_id=f.actor_id
      AND EXISTS(SELECT 1 FROM information_links link JOIN projects linked ON linked.id=link.project_id
       JOIN authority_scopes linked_scope ON linked_scope.project_id=linked.id
       JOIN readable_scopes allowed ON allowed.scope_id=linked_scope.id
       WHERE link.revision_id=f.id AND linked.name=selected.project_name))
    OR (selected.scope='project' AND f.scope_kind='ACCOUNT_KNOWLEDGE' AND f.builtin IS NULL
      AND f.account_id=f.actor_id AND EXISTS(SELECT 1 FROM projects personal
       WHERE personal.name=selected.project_name AND personal.personal_owner=$1))
    OR (selected.include_shared AND f.general_readable)))),false)
 FROM selections selected LEFT JOIN accepted ON accepted.root=selected.revision_id
 LEFT JOIN facts f ON f.id=selected.revision_id;
$$;

-- Targeted and collection reads apply the same current READ decisions to the exact
-- retained selections. The recorded input owner is attribution, not a second ACL.
CREATE OR REPLACE FUNCTION information_log_readable(target TEXT, account TEXT)
 RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 WITH RECURSIVE ancestors(id) AS (
  SELECT $1 UNION SELECT e.parent FROM ancestors a JOIN (
   SELECT id AS child,parent_id AS parent FROM conversations WHERE parent_id IS NOT NULL
   UNION ALL SELECT conductor_conversation,caller_conversation FROM orchestrations WHERE caller_conversation IS NOT NULL
   UNION ALL SELECT o.conductor_conversation,p.conductor_conversation FROM orchestrations o JOIN orchestrations p ON p.id=o.parent
   UNION ALL SELECT id,conductor_conversation FROM orchestrations
  ) e ON e.child=a.id
 ),
 inputs AS MATERIALIZED (SELECT DISTINCT i.revision_id,i.scope,i.project_name,i.include_shared
  FROM ancestors a JOIN information_job_inputs i ON i.job_id=a.id),
 admitted AS MATERIALIZED (SELECT * FROM information_readable_input_selections($2,
  ARRAY(SELECT DISTINCT revision_id FROM inputs)))
 SELECT NOT EXISTS(SELECT 1 FROM ancestors a JOIN information_quarantined_payloads q ON q.payload_id=a.id)
 AND NOT EXISTS(SELECT 1 FROM inputs i LEFT JOIN admitted a ON a.revision=i.revision_id AND a.scope=i.scope
  AND a.project_name IS NOT DISTINCT FROM i.project_name AND a.include_shared=i.include_shared
  WHERE $2 IS NULL OR NOT coalesce(a.readable,false));
$$;

-- Collection predicates consume this uncorrelated relation before ordering, counts and LIMIT.
-- Propagate blocked seeds down the exact inverse of the four ancestry edges. UNION terminates
-- cycles and deduplicates logs reached through several paths. Unknown logs remain readable just
-- as in the targeted predicate unless they carry a blocked input or quarantine themselves.
CREATE OR REPLACE FUNCTION information_unreadable_logs(account TEXT) RETURNS TABLE(log TEXT)
 LANGUAGE SQL STABLE SET jit='off' AS $$
 WITH RECURSIVE admitted AS MATERIALIZED (
  SELECT * FROM information_readable_input_selections($1,NULL)
 ),
 blocked(log) AS (
  SELECT payload_id FROM information_quarantined_payloads
  UNION SELECT i.job_id FROM information_job_inputs i LEFT JOIN admitted a
   ON a.revision=i.revision_id AND a.scope=i.scope
   AND a.project_name IS NOT DISTINCT FROM i.project_name AND a.include_shared=i.include_shared
   WHERE $1 IS NULL OR NOT coalesce(a.readable,false)
 ),
 descendants(log) AS (
  SELECT log FROM blocked UNION SELECT e.child FROM descendants d JOIN (
   SELECT id AS child,parent_id AS parent FROM conversations WHERE parent_id IS NOT NULL
   UNION ALL SELECT conductor_conversation,caller_conversation FROM orchestrations WHERE caller_conversation IS NOT NULL
   UNION ALL SELECT o.conductor_conversation,p.conductor_conversation FROM orchestrations o JOIN orchestrations p ON p.id=o.parent
   UNION ALL SELECT id,conductor_conversation FROM orchestrations
  ) e ON e.parent=d.log WHERE e.child IS NOT NULL
 ) SELECT log FROM descendants;
$$;

