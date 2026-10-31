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
-- What the equality still refuses is the CROSS-BOUNDARY case: an ancestor executing under its OWN
-- project as boundary home, reading a resource whose home is a descendant. The selected project
-- and the resource's home project are then different names, so the ceiling term is false however
-- readable the scope is. Lifting that is a later task's, not this one's.

-- The container chain above a scope, resolved ONCE, nearest first.
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

-- Is `ancestor` on `descendant`'s container chain, exclusive of `descendant` itself? Expressed over
-- the walk above so there is one definition of ancestry; the signature is unchanged because callers
-- outside this migration resolve it.
CREATE FUNCTION contained_scope_within(descendant UUID, ancestor UUID) RETURNS BOOLEAN
 LANGUAGE SQL STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM contained_scope_ancestors($1) AS chain(id) WHERE chain.id=$2);
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
