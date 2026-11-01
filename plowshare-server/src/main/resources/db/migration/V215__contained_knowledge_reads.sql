-- Containment ancestry in SQL, and the two scope-admission arms that need it.
--
-- V208 made containment a fifth authority scope kind and every knowledge-read predicate refused a
-- CONTAINED scope by kind. That fails closed, which is why it shipped, but it also left a child
-- unable to read its own writable home and a container unable to read the home of the child it
-- created -- the known limitation ADR 0042 records beside its V172 and V178 inventory rows. This
-- lifts exactly that limitation.
--
-- Admission is one-way by construction. An ancestor reads a descendant because the walk below runs
-- upward from the descendant and asks whether the role-holding scope is on its chain; a descendant
-- never reads an ancestor and a sibling never reads a sibling, because neither is on the other's
-- chain.
--
-- What this opens at document level, stated exactly, because it is more than scope admission. The
-- retained read ceiling is a separate gate, but it is NOT a second fence that re-refuses what is
-- admitted here. Its ceiling term is `p.name=$3 AND knowledge_scope_readable($2,s.id)` in
-- knowledge_document_in_boundary (V172) and `p.name=$2 AND knowledge_scope_readable($1,s.id)` in
-- knowledge_readable_scopes (V184), where the home project is the *request's selected project* and
-- not a value derived from the actor. So an ancestor role holder that addresses a descendant's own
-- `contained:...` project as its boundary home satisfies that equality against the descendant's
-- home scope, and the second conjunct is now true: document-level reads of a descendant's home
-- open HERE, in this migration. That is the intended capability and is exactly the limitation
-- ADR 0042 recorded as deferred.
--
-- The CROSS-BOUNDARY case is the second half, and it is lifted at the end of this migration: an
-- ancestor executing under its OWN project as boundary home, reading a resource whose home is a
-- descendant. A name equality refuses that however readable the scope is, because the selected
-- project and the resource's home project are different names, so the ceiling term there gains a
-- containment disjunct beside the equality. Admission and the ceiling stay separate gates: the
-- ceiling's own readability conjunct is untouched, so the widened term can admit nothing that
-- scope admission has not already admitted, and it still only ever narrows the read.
--
-- Three bodies decide that ceiling and all three are widened together, because each is reached by
-- a different caller and a half-widening would be a silent disagreement rather than a narrower
-- capability: knowledge_document_in_boundary (V172), the exact-target reference predicate;
-- knowledge_readable_scopes (V184), the hoisted form of it that InformationSql.boundary pairs with
-- knowledge_document_admitted on the listing path; and knowledge_readable_candidates (V198), which
-- discovery and exact-target selection resolve through. V184's documented guarantee is that its
-- hoist and the V172 predicate admit identical sets, which is why widening one without the other
-- would leave a false invariant behind rather than merely less capability.
--
-- THE OUTWARD FENCE, which this migration also closes. Everything above widens the inward
-- direction. The general-read publication arm is the one place a contained resource could travel
-- the OTHER way, and it was never fenced on containment at all: information_public_policy (V172:22)
-- and its hoisted twin in knowledge_readable_candidates (the general_readable column below) each
-- excluded only `RESTRICTED`, the single scope kind that existed to be excluded when they were
-- written. V208 added CONTAINED as a fifth kind and did not extend either test. So a revision whose
-- home is a contained scope, published with a GLOBAL_READ audience, satisfied the general arm of
-- knowledge_revision_readable and of the ceiling, and would have been admitted to every global-read
-- member with no containment test taken anywhere -- which is the one direction confinement must
-- never allow.
--
-- It is not reachable today: the policy insert trigger (V158:88) writes audience='HOME', the
-- navigation default makes `NOT coalesce(ownerOnly,true)` the refusing value, and nothing executes
-- a contained child, so no contained-home resource exists to publish. That is a fence resting on a
-- default rather than on a structure, which is precisely the anti-goal the spec names, so both
-- bodies gain `s.kind<>'CONTAINED'` beside their existing RESTRICTED test. It only ever narrows:
-- the conjunct can refuse a general publication, never admit one.
--
-- knowledge_revision_readable (V172:36) carries the same RESTRICTED test and deliberately does NOT
-- gain this conjunct. Its test governs the whole read, not the general arm, so excluding CONTAINED
-- there would refuse an ancestor's read of a descendant's home -- the capability this migration
-- exists to open. Its general arm is `knowledge_general_readable(r.id)`, which resolves through
-- information_public_policy, so the fence below reaches it without touching its kind test. The same
-- reasoning applies to the hoisted `readable` column in knowledge_readable_candidates below.

-- The container chain above a scope, resolved ONCE.
--
-- It returns a SET and promises no row order: the walk is a UNION ALL recursive CTE with no
-- ORDER BY, so a plan change may reorder it, and every consumer below tests it with EXISTS or a
-- join. The `distance` column exists for the sentinel and for nothing else. Do not add an ORDER BY
-- to make a reader's expectation true -- that would be an ordering guarantee no caller needs; and
-- do not start relying on the order a plan happens to produce.
--
-- This exists so that "is any scope the actor holds a role on an ancestor of this one?" costs one
-- walk per contained scope rather than one walk per candidate ancestor. V184's header is the
-- cautionary measurement: authority_project_role costs about 1.2ms, and a predicate of this shape
-- evaluated per row spent six seconds on a live listing. The chain is short -- at most 64 links,
-- in practice one or two -- so resolving it first and testing roles over it inverts the cost.
--
-- The 64-level LIMIT is a STRUCTURAL SENTINEL, not the configured containment depth bound. The
-- policy bound is ContainmentBounds.maxDepth(), server configuration enforced in Java when a child
-- is created, and it is deliberately not reachable from SQL: duplicating it here as a literal would
-- make a policy change silently disagree with the predicate that enforces it. The sentinel exists
-- for the same reason knowledge_dependency_revisions carries LIMIT 1025 -- so that data which
-- should be impossible fails closed instead of running unbounded. V209 makes the relation acyclic
-- by construction, so the sentinel is not a cycle guard either.
--
-- A chain that cannot be completed -- a container pointing at a scope that is not there -- stops at
-- the break, so nothing above a break is an ancestor of anything below it. That is a refusal, which
-- is the direction a broken chain must fail in.
CREATE FUNCTION contained_scope_ancestors(descendant UUID) RETURNS SETOF UUID
 LANGUAGE SQL STABLE AS $$
 WITH RECURSIVE chain(id,distance) AS (
   SELECT s.container,1 FROM authority_scopes s WHERE s.id=$1 AND s.container IS NOT NULL
   UNION ALL
   SELECT s.container,c.distance+1 FROM chain c JOIN authority_scopes s ON s.id=c.id
   WHERE s.container IS NOT NULL AND c.distance<64
 ) SELECT id FROM chain;
$$;

-- The containment half of the retained read ceiling, declared once and consulted by both boundary
-- bodies below for the same reason the admission rule above is shared: they decide the same
-- question in independent SQL and must not drift.
--
-- "Inside the subtree rooted at the project named `home_project`" is asked of the resource's own
-- scope and answered by walking UPWARD from it, so the direction is the same one-way direction the
-- admission rule has: the boundary home must be an ancestor. A descendant's home never contains
-- its container, and a sibling's never contains a sibling's, because neither appears on the
-- other's chain. A NULL home_project matches nothing, which is the refusing direction.
--
-- Ancestry comes from one join over contained_scope_ancestors, resolved once per scope asked
-- about, rather than from a per-candidate-ancestor boolean test that would re-walk the chain for
-- every scope the boundary project owns. The kind test is what keeps the walk off the overwhelming
-- majority of scopes, which are not contained at all; callers on a hot path restate it so the call
-- itself is never made per ordinary row.
CREATE FUNCTION contained_scope_in_home(scope UUID, home_project TEXT) RETURNS BOOLEAN
 LANGUAGE SQL STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM authority_scopes s WHERE s.id=$1 AND s.kind='CONTAINED'
   AND EXISTS(SELECT 1 FROM contained_scope_ancestors(s.id) AS chain(id)
     JOIN authority_scopes ancestor ON ancestor.id=chain.id
     JOIN projects ancestor_project ON ancestor_project.id=ancestor.project_id
     WHERE ancestor_project.name=$2));
$$;

-- The containment admission rule itself, declared once and consulted by both chokepoints below, so
-- the exact-target predicate and the batched readable-scope relation cannot drift apart. A
-- CONTAINED scope is readable when either:
--
-- (a) the actor IS that scope's own principal. A contained principal's identifier is a bare UUID
--     (V210) with no row in admins, authority_accounts or service_tokens, so it holds no account
--     for general-read membership to be true of; requiring that membership here would make this arm
--     dead rather than narrow. It admits exactly one scope, the principal's own, and never walks the
--     chain, so it cannot reach anything above it. Because that leaves the arm outside every
--     account-based check, it also requires that the actor is NOT a handle: admins.handle is
--     unconstrained TEXT, so nothing else in the schema prevents an account named after a
--     principal's UUID, and such an account would otherwise inherit that principal's read with no
--     account_active or enabled test applied to it.
--
-- (b) the actor resolves a project role on a scope that contains this one, transitively.
--     authority_project_role ONLY -- deliberately not the coalesce(authority_project_role,
--     service_project_role) the pre-existing arm uses. A service credential's project role is
--     scoped to a named project by its token; letting it ride a containment chain would hand every
--     token on a container read access to subtrees the token never named, which is the one widening
--     this task must not make. General-read membership is still required, because that arm's actor
--     is an ordinary account and the baseline gate applies to it exactly as elsewhere.
--
-- Membership arrives as an argument rather than being resolved here, in the idiom of V184's
-- knowledge_document_admitted, which takes the hoisted scope set and the hoisted general-read
-- permission so that a call performs no authority resolution of its own. A caller that has already
-- resolved it for the statement passes that value; the parameter must always be
-- information_global_read_member(actor) for the same actor.
--
-- Only an existing scope row can be the ancestor in (b), so a dangling container value can never
-- itself be the authority a read resolves through.
CREATE FUNCTION contained_scope_readable(actor TEXT, scope UUID, general_member BOOLEAN)
 RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM authority_scopes s WHERE s.id=$2 AND s.kind='CONTAINED' AND (
   (NOT EXISTS(SELECT 1 FROM admins account WHERE account.handle=$1)
     AND EXISTS(SELECT 1 FROM contained_principals principal
       WHERE principal.scope_id=s.id AND principal.id::text=$1))
   OR ($3 AND EXISTS(
     SELECT 1 FROM contained_scope_ancestors(s.id) AS chain(id)
     JOIN authority_scopes ancestor ON ancestor.id=chain.id
     JOIN projects ancestor_project ON ancestor_project.id=ancestor.project_id
     -- The kind restriction mirrors authority_project_role's own fence rather than adding one:
     -- that function resolves a role only on a PROJECT or PERSONAL grant scope, so an ancestor of
     -- any other kind -- a CONTAINED intermediate on the chain, or a RESTRICTED root -- already
     -- answered NULL. Stating it here keeps the role lookup off every link of a deep chain.
     WHERE ancestor.kind IN ('PROJECT','PERSONAL')
       AND authority_project_role(ancestor_project.name,$1) IS NOT NULL))));
$$;

-- Chokepoint 1 of 2: the exact-target predicate. Reproduced verbatim from V172:9 with one new
-- top-level arm; the pre-existing body, including its information_global_read_member gate, is
-- unchanged, so no existing admission is altered. The new arm sits beside that gate rather than
-- under it for the reason given at (a) above.
--
-- Membership is resolved twice in this body, once by the reproduced gate and once for the arm.
-- Reproducing the shipped body verbatim is worth more than hoisting it: this predicate answers
-- about one named scope, and V184 already removed the per-row call sites that made this function's
-- cost matter. The relation below, which is evaluated across every scope, is where the hoisted
-- value is reused.
--
-- information_project_readable, information_root_readable, knowledge_revision_readable and V184's
-- knowledge_readable_scopes resolve through this predicate and therefore move with it -- which is
-- intended, and is what the header above describes.
CREATE OR REPLACE FUNCTION knowledge_scope_readable(actor TEXT, scope UUID) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 SELECT (information_global_read_member($1) AND EXISTS(SELECT 1 FROM authority_scopes s
 LEFT JOIN projects p ON p.id=s.project_id LEFT JOIN authority_accounts a ON a.legacy_handle=$1
 WHERE s.id=$2 AND (
   (s.kind='ACCOUNT_KNOWLEDGE' AND s.builtin IS NULL AND s.account_id=a.id)
   OR (s.builtin='GLOBAL_KNOWLEDGE' AND information_account_action($1,'READ'))
   OR (s.kind IN ('PROJECT','PERSONAL') AND
     coalesce(authority_project_role(p.name,$1),service_project_role(p.name,$1)) IS NOT NULL))))
 OR contained_scope_readable($1,$2,information_global_read_member($1));
$$;

-- Chokepoint 2 of 2: the batched readable-scope relation that discovery joins against. The two are
-- independent bodies -- widening only the exact predicate leaves discovery blind, and widening only
-- this one leaves exact reads refused -- so both are needed and both consult the one rule above.
--
-- Reproduced verbatim from V178:4 with one change: readable_scopes' WHERE. The pre-existing
-- branches keep their `a.member AND (...)` gate exactly as it was, and the containment arm is a
-- disjunct beside it, again because a contained principal has no account to be a member of. The arm
-- reuses actor_fact.member, which this statement has already resolved, rather than resolving
-- membership a second time per scope row. Everything else -- the live-grant, project_reads, token
-- and Personal fences, and the MATERIALIZED shape this function exists for -- is untouched, and
-- authority_live_grants still honours no grant against a contained scope, so no role is resolved on
-- one.
CREATE OR REPLACE FUNCTION knowledge_read_authority(actor TEXT)
 RETURNS TABLE(actor_id UUID,general_member BOOLEAN,scope_id UUID)
 LANGUAGE plpgsql STABLE AS $$
BEGIN
 RETURN QUERY
 WITH live AS MATERIALIZED (SELECT l.id FROM authority_live_grants() l),
 actor_fact AS MATERIALIZED (
  SELECT a.id,u.account_kind,information_global_read_member($1) AS member
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

-- The outward fence, body 1 of 2: general-read publication. Reproduced verbatim from V172:21 with
-- exactly one added conjunct, `s.kind<>'CONTAINED'`, placed beside the pre-existing
-- `s.kind<>'RESTRICTED'` so that a mechanical diff against V172 shows that one insertion and
-- nothing else. The audience, private_only, ownerOnly and availability terms, the joins and the
-- coalesce default are untouched.
--
-- This is the narrow body rather than the broad one on purpose. knowledge_general_readable (V172:30)
-- is `information_public_policy AND information_public_dependencies`, and the dependency closure
-- predicates (V158:183, V158:194) also resolve through this function, so one conjunct here fences
-- the general arm of knowledge_revision_readable, of information_root_readable and of
-- knowledge_document_in_boundary's global arm, and additionally refuses a published resource whose
-- closure merely *reaches* a contained home. Every one of those movements is a refusal; the
-- function appears in no WRITE or publication-authorizing body, so nothing is granted by it.
--
-- The argument name is reproduced exactly: this is CREATE OR REPLACE, and PostgreSQL refuses a
-- replacement that renames a parameter.
CREATE OR REPLACE FUNCTION information_public_policy(revision UUID) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 SELECT coalesce(p.audience='GLOBAL_READ' AND s.kind<>'RESTRICTED' AND s.kind<>'CONTAINED' AND NOT p.private_only
   AND NOT coalesce((information_navigation_policy(q.id)->>'ownerOnly')::boolean,true)
   AND r.availability='active',false)
 FROM information_revisions r JOIN information_resources q ON q.id=r.resource_id
 JOIN authority_scopes s ON s.id=q.home_scope_id
 JOIN information_resource_policies p ON p.resource_id=q.id WHERE r.id=$1;
$$;

-- Ceiling chokepoint 1 of 3: the exact-target retained ceiling. Reproduced verbatim from V172:96
-- with one change, in the first arm only: the resource's home project may be the boundary home OR
-- inside the containment subtree rooted at it. The account-knowledge and global arms, the 1024
-- closure sentinel and the bool_and over knowledge_dependency_revisions are untouched, so a
-- closure that leaves the ceiling still refuses the whole read -- the widening moves which rows
-- are inside the ceiling, never how the conjunction over the closure is taken.
--
-- knowledge_scope_readable stays conjoined to the widened term rather than beside it. The ceiling
-- grants nothing: a scope the actor cannot read is outside it whether or not it is contained.
--
-- The kind test is restated here as it is in the other two bodies, which the rule's own header
-- asks of a caller that evaluates it per row -- this one does, once per node of the dependency
-- closure. It is exactly redundant with contained_scope_in_home's body, so it changes no answer;
-- stating it uniformly in all three is what keeps the convention checkable by reading.
--
-- The argument names are reproduced exactly, which V172 and V184 both warn is load-bearing: this
-- is CREATE OR REPLACE, and PostgreSQL refuses a replacement that renames a parameter.
CREATE OR REPLACE FUNCTION knowledge_document_in_boundary(revision UUID, actor TEXT, home_project TEXT, global_read BOOLEAN)
 RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 SELECT count(*)<=1024 AND coalesce(bool_and(coalesce(
   ((p.name=$3 OR (s.kind='CONTAINED' AND contained_scope_in_home(s.id,$3)))
     AND knowledge_scope_readable($2,s.id))
   OR (s.kind='ACCOUNT_KNOWLEDGE' AND s.builtin IS NULL AND a.legacy_handle=$2
     AND EXISTS(SELECT 1 FROM projects personal WHERE personal.name=$3 AND personal.personal_owner=$2))
   OR ($4 AND information_global_read_member($2) AND knowledge_general_readable(r.id)),false)),false)
 FROM knowledge_dependency_revisions($1) dependency
 LEFT JOIN information_revisions r ON r.id=dependency.id LEFT JOIN information_resources q ON q.id=r.resource_id
 LEFT JOIN authority_scopes s ON s.id=q.home_scope_id LEFT JOIN projects p ON p.id=s.project_id
 LEFT JOIN authority_accounts a ON a.id=s.account_id;
$$;

-- Ceiling chokepoint 2 of 3: the hoisted form of the predicate above, which is what the listing
-- path actually evaluates -- InformationSql.boundary pairs it with knowledge_document_admitted,
-- resolving it once per query into an InitPlan. Reproduced verbatim from V184:26 with the same
-- one change the predicate took, in the same arm: the scope's project may be the boundary home OR
-- the scope may be contained inside the subtree rooted at it, and knowledge_scope_readable stays
-- conjoined so the set can hold nothing scope admission has not already admitted.
--
-- V184's guarantee is that this set and the V172 predicate admit identical documents, so the two
-- bodies move together or the guarantee becomes false. The personal-home arm is untouched.
--
-- This body is evaluated once per query, not per row, but it scans every authority scope, so the
-- kind test is RESTATED in the WHERE clause rather than left to contained_scope_in_home's own
-- body. The rule's header states that convention and the candidates body below follows it: without
-- it the function CALL is made for every scope row whose project is not the home -- cheap per call,
-- but on the very listing path V184's header exists because of. Restated, the call is made only for
-- a CONTAINED scope, and it then costs one walk.
CREATE OR REPLACE FUNCTION knowledge_readable_scopes(actor text, home_project text) RETURNS SETOF uuid
  LANGUAGE sql STABLE AS $$
  SELECT s.id FROM authority_scopes s
  LEFT JOIN projects p ON p.id = s.project_id
  LEFT JOIN authority_accounts a ON a.id = s.account_id
  WHERE ((p.name = $2 OR (s.kind = 'CONTAINED' AND contained_scope_in_home(s.id, $2)))
          AND knowledge_scope_readable($1, s.id))
     OR (s.kind = 'ACCOUNT_KNOWLEDGE' AND s.builtin IS NULL AND a.legacy_handle = $1
         AND EXISTS(SELECT 1 FROM projects own
                    WHERE own.name = $2 AND own.personal_owner = $1));
$$;

-- Ceiling chokepoint 3 of 3: the ceiling discovery and exact-target selection actually decide.
-- Reproduced verbatim from V198:5 with three changes and no others: the boundary_descendants CTE
-- below; the in_boundary term's first arm, which gains the same containment disjunct the predicate
-- above gained; and the general_readable column, which gains the outward fence's single conjunct.
-- The readable and selected arms, the per-root bool_and in `accepted` and its 1024 sentinel are
-- unchanged.
--
-- The containment test is hoisted into a CTE rather than called from the in_boundary term, which
-- is evaluated once per closure node: V184's header is the measurement that forbids the per-row
-- shape, and the set of contained scopes inside one boundary home depends on the home alone, never
-- on a revision, which is what makes hoisting it sound. The CTE's own cost is one walk per
-- CONTAINED scope, and none at all when no ceiling was requested.
CREATE OR REPLACE FUNCTION knowledge_readable_candidates(actor TEXT,selected_scope TEXT,selected_project TEXT,
 include_global BOOLEAN,boundary_home TEXT,boundary_global BOOLEAN,corpus TEXT,kind TEXT,
 heads BOOLEAN,related_resource UUID,relation TEXT,direction TEXT,targets UUID[],include_excluded BOOLEAN)
 RETURNS TABLE(revision UUID) LANGUAGE SQL STABLE SET jit=off AS $$
 WITH authority AS MATERIALIZED (SELECT * FROM knowledge_read_authority($1)),
 actor_fact AS MATERIALIZED (SELECT actor_id,general_member FROM authority LIMIT 1),
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
