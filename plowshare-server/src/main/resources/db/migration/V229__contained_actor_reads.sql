-- A contained principal reads its own subtree without becoming a general-read member.
--
-- V223 admitted a contained principal's scope (knowledge_scope_readable's self arm), but
-- knowledge_revision_readable still opens with information_global_read_member($2): an `admins` row
-- joined to an `authority_accounts` row holding the GLOBAL_READ role. A contained principal is a
-- bare UUID with none of those rows by design (V218), so it was refused even its own document.
-- This migration adds a second arm to that opening conjunct. It creates no admins,
-- authority_accounts, authority_roles or service_tokens row and grants no role: the arm is keyed on
-- a live contained_principals row and nothing else.
--
-- THE GENERAL ARM STAYS SHUT. The statement's final disjunct is
-- `knowledge_scope_readable(...) OR knowledge_general_readable(r.id)`, and `s.kind<>'RESTRICTED'`
-- admits CONTAINED. Widening only the opening conjunct would therefore hand a child every
-- generally-readable revision on the installation, regardless of scope -- authority its container
-- need not hold, through audience-driven reads the child never names. For an admitted contained
-- actor the disjunct collapses to its scope arm alone, in the same predicate expression below
-- (`NOT contained_knowledge_actor($2) AND knowledge_general_readable(r.id)`), so there is no reading
-- of this statement under which a contained actor reaches the general arm. Do not simplify that
-- conjunct away. The arm is also one-way: knowledge_scope_readable admits only a principal's own
-- scope, so it never admits an ancestor's or a sibling's.
--
-- Containment dominates membership: a principal that is both a live contained_principals row and a
-- real GLOBAL_READ member loses the general arm entirely, because `contained` sits on the closing
-- side of both knowledge_revision_readable and knowledge_read_authority. That is fail-closed and
-- consistent with a read ceiling only ever narrowing.

-- True only for a TEXT that parses as a UUID present in contained_principals. Every knowledge read
-- on the server evaluates this with a handle such as 'alice', so the cast is guarded: CASE
-- guarantees the regex is tested before the cast is attempted, and a non-UUID is simply false.
CREATE FUNCTION contained_knowledge_actor(actor TEXT) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 SELECT CASE WHEN $1 ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
  THEN EXISTS(SELECT 1 FROM contained_principals c WHERE c.id=$1::uuid) ELSE false END;
$$;

-- Reproduced from V172:34 with the opening conjunct widened and the general disjunct closed for a
-- contained actor. Signature unchanged.
CREATE OR REPLACE FUNCTION knowledge_revision_readable(revision UUID, actor TEXT) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 SELECT coalesce((information_global_read_member($2) OR contained_knowledge_actor($2))
   AND r.availability='active'
   AND p.audience IN ('HOME','GLOBAL_READ') AND s.kind<>'RESTRICTED'
   AND information_privacy_allows(q.id,$2)
   AND (knowledge_scope_readable($2,q.home_scope_id)
     OR (NOT contained_knowledge_actor($2) AND knowledge_general_readable(r.id))),false)
 FROM information_revisions r JOIN information_resources q ON q.id=r.resource_id
 JOIN authority_scopes s ON s.id=q.home_scope_id
 JOIN information_resource_policies p ON p.resource_id=q.id WHERE r.id=$1;
$$;

-- Discovery half. Reproduced from V223 with one change: general_member is pinned false for a
-- contained actor. knowledge_readable_candidates joins against general_member to open the general
-- arm, so a contained actor must never carry it true. The readable scope set is unchanged and
-- already contains the principal's own scope.
CREATE OR REPLACE FUNCTION knowledge_read_authority(actor TEXT)
 RETURNS TABLE(actor_id UUID,general_member BOOLEAN,scope_id UUID)
 LANGUAGE plpgsql STABLE AS $$
BEGIN
 RETURN QUERY
 WITH live AS MATERIALIZED (SELECT l.id FROM authority_live_grants() l),
 actor_fact AS MATERIALIZED (
  SELECT a.id,u.account_kind,information_global_read_member($1) AND NOT contained_knowledge_actor($1) AS member
  FROM (SELECT 1) seed LEFT JOIN authority_accounts a ON a.legacy_handle=$1
  LEFT JOIN admins u ON u.handle=$1
 ),
 project_reads AS MATERIALIZED (
  -- Exactly authority_project_role's READ-capable roles, coverage, agent and Personal fences.
  SELECT g.subject_account,g.scope_id FROM authority_grants g JOIN live l ON l.id=g.id
  JOIN authority_roles role ON role.id=g.role_id JOIN authority_scopes s ON s.id=g.scope_id
  JOIN authority_accounts a ON a.id=g.subject_account
  WHERE g.subject_agent IS NULL AND g.coverage='SELF'
   AND role.name IN ('READER','CONTRIBUTOR','MANAGER')
   AND (s.kind='PROJECT' OR (s.kind='PERSONAL' AND s.account_id=a.id))
 ),
 readable_scopes AS MATERIALIZED (
  SELECT s.id FROM authority_scopes s CROSS JOIN actor_fact a
  LEFT JOIN projects p ON p.id=s.project_id
  WHERE (a.member AND (
   (s.kind='ACCOUNT_KNOWLEDGE' AND s.builtin IS NULL AND s.account_id=a.id)
   OR (s.builtin='GLOBAL_KNOWLEDGE' AND a.account_kind='USER' AND EXISTS(
    SELECT 1 FROM authority_grants g JOIN live l ON l.id=g.id
    JOIN authority_roles role ON role.id=g.role_id
    JOIN authority_role_actions action ON action.role_id=g.role_id AND action.role_version=g.role_version
    WHERE g.subject_account=a.id AND g.subject_agent IS NULL AND g.scope_id=s.id
     AND role.name='ADMIN' AND g.coverage='SELF' AND action.action='READ'
     AND (action.profile='DEFAULT' OR action.profile=s.builtin)))
   OR (s.kind IN ('PROJECT','PERSONAL') AND (
    EXISTS(SELECT 1 FROM project_reads grant_read WHERE grant_read.scope_id=s.id AND grant_read.subject_account=a.id)
    OR EXISTS(
     -- READ survives every allowed token/owner role clamp. General membership already requires
     -- account_active(actor), including issued credential, live token and enabled service owner.
     SELECT 1 FROM service_tokens token JOIN service_token_scopes token_scope ON token_scope.token_id=token.id
     JOIN authority_accounts owner_account ON owner_account.legacy_handle=token.owner_handle
     JOIN project_reads owner_read ON owner_read.subject_account=owner_account.id AND owner_read.scope_id=s.id
     WHERE token.principal_handle=$1 AND token_scope.project_id=s.project_id
      AND p.personal_owner IS NULL AND p.name NOT LIKE 'client:%' AND p.name NOT LIKE 'personal:%')))))
   -- The kind test is redundant with the rule's own body and is stated here anyway: this relation
   -- is evaluated across every authority scope, and without it the containment walk would be
   -- called once per scope row rather than once per contained one.
   OR (s.kind='CONTAINED' AND contained_scope_readable($1,s.id,a.member))
 )
 SELECT a.id,a.member,s.id FROM actor_fact a LEFT JOIN readable_scopes s ON true;
END $$;

-- Discovery baseline. Reproduced from V223:357 with the contained actor admitted to the baseline and
-- every general arm kept shut, as a separate decision from general_member (which stays false for it).
-- The candidates baseline (a.general_member OR a.contained) opens only the selection arms bounded by
-- readable_scopes; the three general arms (global_read, account, include_global) are each guarded by
-- general_member in `candidates`, and by NOT a.contained in `facts`, so no reading of this statement
-- lets a contained actor reach them. The related-resource path ($10) skips the candidates gate, but
-- every closure node must still be `readable` (own subtree only) and the parent selected, so it
-- cannot leave the subtree either; aChildCannotReachOutsideItsSubtreeThroughARelatedResource pins it.
CREATE OR REPLACE FUNCTION knowledge_readable_candidates(actor TEXT,selected_scope TEXT,selected_project TEXT,
 include_global BOOLEAN,boundary_home TEXT,boundary_global BOOLEAN,corpus TEXT,kind TEXT,
 heads BOOLEAN,related_resource UUID,relation TEXT,direction TEXT,targets UUID[],include_excluded BOOLEAN)
 RETURNS TABLE(revision UUID) LANGUAGE SQL STABLE SET jit=off AS $$
 WITH authority AS MATERIALIZED (SELECT * FROM knowledge_read_authority($1)),
 actor_fact AS MATERIALIZED (
  SELECT actor_id,general_member,contained_knowledge_actor($1) AS contained FROM authority LIMIT 1),
 readable_scopes AS MATERIALIZED (SELECT scope_id FROM authority WHERE scope_id IS NOT NULL),
 -- The contained scopes the requested ceiling reaches, resolved once for the statement. No ceiling
 -- means no walk, and the kind test keeps the walk off every scope that cannot be contained.
 boundary_descendants AS MATERIALIZED (
  SELECT s.id FROM authority_scopes s
  WHERE $6 IS NOT NULL AND s.kind='CONTAINED' AND contained_scope_in_home(s.id,$5)
 ),
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
   AND ($10 IS NOT NULL OR ((a.general_member OR a.contained) AND (
    (a.general_member AND $2='global_read' AND $3 IS NULL AND NOT $4 AND root_policy.audience='GLOBAL_READ')
    OR (a.general_member AND $2='account' AND $3 IS NULL AND NOT $4 AND scope.builtin='GLOBAL_KNOWLEDGE')
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
     OR (a.general_member AND $4 AND root_policy.audience='GLOBAL_READ'))))))
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
 -- The outward fence, body 2 of 2: this column is information_public_policy hoisted over the
 -- closure, so it takes the same single added conjunct. Without it, discovery would publish a
 -- contained home outward even though the exact-target predicate now refuses to.
 public_facts AS MATERIALIZED (
  SELECT f.*,coalesce(f.audience='GLOBAL_READ' AND f.scope_kind<>'RESTRICTED'
   AND f.scope_kind<>'CONTAINED' AND NOT f.private_only
   AND NOT coalesce((f.navigation->>'ownerOnly')::boolean,true) AND f.availability='active'
   AND information_public_dependencies(f.id),false) AS general_readable
  FROM base_facts f
 ),
 facts AS MATERIALIZED (
  SELECT f.id,
   coalesce((a.general_member OR a.contained) AND f.availability='active' AND f.audience IN ('HOME','GLOBAL_READ')
    AND f.scope_kind<>'RESTRICTED'
    AND ((NOT f.private_only AND NOT coalesce((f.navigation->>'ownerOnly')::boolean,true))
     OR f.privacy_principal=a.actor_id)
    AND (EXISTS(SELECT 1 FROM readable_scopes s WHERE s.scope_id=f.home_scope_id) OR (NOT a.contained AND f.general_readable)),false) AS readable,
   coalesce((NOT a.contained AND $2='global_read' AND $3 IS NULL AND NOT $4 AND f.general_readable)
    OR (NOT a.contained AND $2='account' AND $3 IS NULL AND NOT $4 AND f.builtin='GLOBAL_KNOWLEDGE')
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
     OR (NOT a.contained AND $4 AND f.general_readable))),false) AS selected,
   -- NULL boundary_global means no retained ceiling; a Global home may itself have NULL project.
   -- The home is the boundary home itself or a contained scope inside the subtree rooted at it;
   -- readability is still required of either, so the ceiling admits nothing on its own.
   coalesce($6 IS NULL OR (
    ((f.project_name=$5 OR EXISTS(SELECT 1 FROM boundary_descendants inside WHERE inside.id=f.home_scope_id))
     AND EXISTS(SELECT 1 FROM readable_scopes s WHERE s.scope_id=f.home_scope_id))
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

-- ==================================================================================================
-- THE WRITE ARM. Distinct from every read arm above: nothing in this section reads.
--
-- A contained principal writes inside its own home and nowhere else. Slice-three characterization
-- (ContainedActorWriteSchemaTest) found two gates that refuse it before any write begins, each
-- keyed on a human or service `admins` row and a PROJECT/PERSONAL scope kind, neither of which a
-- contained principal has by design (V218): information_lock_destination (where a new document may
-- land) and information_home_allows (whether an existing document may be written). Each is widened
-- below by exactly one arm, keyed on a live contained_principals row and on that row's own scope.
-- No admins, authority_accounts, authority_roles or service_tokens row is created or consulted.
--
-- SET_AUDIENCE is deliberately NOT admitted: a child never changes who may read its output, and in
-- particular can never publish it generally readable. Only WRITE reaches the new arm.
-- ==================================================================================================

-- The scope a contained actor owns, if and only if `project` names that scope's own project. Not
-- an ancestor's, a sibling's or a descendant's: the match is the principal's single scope_id row.
-- NULL for a non-contained actor, a null/foreign project, or a revoked principal.
CREATE FUNCTION contained_own_home(actor TEXT, project TEXT) RETURNS UUID LANGUAGE SQL STABLE AS $$
 SELECT CASE WHEN contained_knowledge_actor($1) THEN
  (SELECT s.id FROM contained_principals c JOIN authority_scopes s ON s.id=c.scope_id AND s.kind='CONTAINED'
   JOIN projects p ON p.id=s.project_id WHERE c.id=$1::uuid AND p.name=$2) END;
$$;

-- Reproduced from V163:31 with one added early branch and no other edits. Signature unchanged. The
-- shared advisory lock and scope lock match the ordinary path so a concurrent scope deletion
-- serializes with the write.
CREATE OR REPLACE FUNCTION information_lock_destination(account TEXT, selected_scope TEXT, selected_project TEXT, include_shared BOOLEAN)
RETURNS BOOLEAN LANGUAGE plpgsql AS $$
DECLARE own UUID;
BEGIN
 IF contained_knowledge_actor($1) THEN
  own:=contained_own_home($1,$3);
  IF $2<>'project' OR $4 OR own IS NULL THEN RETURN false; END IF;
  PERFORM pg_advisory_xact_lock_shared(hashtextextended('information-navigation-control',0));
  PERFORM information_lock_scope($1,own);
  -- Post-lock recheck, not a tautology: the scope lock above can wait on a concurrent deletion that
  -- cascades to contained_principals (V218), and this re-evaluates on a fresh snapshot so a principal
  -- revoked meanwhile gets false. Mirrors the account_active recheck at V157:191. Do not simplify.
  RETURN contained_knowledge_actor($1);
 END IF;
 IF $2<>'account' THEN RETURN information_pre_account_lock_destination($1,$2,$3,$4); END IF;
 IF $3 IS NOT NULL OR $4 THEN RETURN false; END IF;
 PERFORM pg_advisory_xact_lock_shared(hashtextextended('information-navigation-control',0));
 PERFORM information_lock_scope($1,'00000000-0000-0000-0000-000000000005');
 RETURN information_account_action($1,'WRITE');
END $$;

-- Reproduced from V163:23 with one added leading arm and no other edits. Signature unchanged. The
-- arm admits WRITE only (reads are the V229 read arms above), and only for a resource whose home_scope_id is the principal's
-- own scope (the scope is resolved from the selected project, then compared to the resource's
-- home, so naming one's own project never reaches another home). SET_AUDIENCE falls through to
-- `false`, and is not delegated to the ordinary path either: a contained actor holds no role there.
CREATE OR REPLACE FUNCTION information_home_allows(resource UUID, account TEXT, selected_scope TEXT,
 selected_project TEXT, action TEXT) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 SELECT CASE WHEN contained_knowledge_actor($2) THEN coalesce($3='project' AND $5='WRITE'
   AND EXISTS(SELECT 1 FROM information_resources q WHERE q.id=$1
     AND q.home_scope_id=contained_own_home($2,$4)),false)
 WHEN $3='account' THEN coalesce($4 IS NULL AND information_account_action($2,$5)
   AND $5 IN ('READ','WRITE','SET_AUDIENCE') AND EXISTS(SELECT 1 FROM information_resources
     WHERE id=$1 AND home_scope_id='00000000-0000-0000-0000-000000000005'),false)
 ELSE information_pre_account_home_allows($1,$2,$3,$4,$5) END;
$$;
