-- Containment ancestry in SQL, and the two scope-admission arms that need it.
--
-- V208 made containment a fifth authority scope kind and every knowledge-read predicate refused a
-- CONTAINED scope by kind. That fails closed, which is why it shipped, but it also left a child
-- unable to read its own writable home and a container unable to read the home of the child it
-- created -- the known limitation ADR 0042 records beside its V172 and V178 inventory rows. This
-- lifts exactly that limitation, at scope admission only.
--
-- Admission is one-way by construction. An ancestor reads a descendant because the walk below runs
-- upward from the descendant and asks whether the role-holding scope is on its chain; a descendant
-- never reads an ancestor and a sibling never reads a sibling, because neither is on the other's
-- chain. Nothing here is a ceiling: the retained read ceiling is a separate gate, applied over the
-- whole dependency closure by knowledge_document_in_boundary, and it is untouched. It still
-- narrows every read this widens.

-- Is `ancestor` on `descendant`'s container chain, exclusive of `descendant` itself?
--
-- The 64-level LIMIT on the walk is a STRUCTURAL SENTINEL, not the configured containment depth
-- bound. The policy bound is ContainmentBounds.maxDepth(), server configuration enforced in Java
-- when a child is created, and it is deliberately not reachable from SQL: duplicating it here as a
-- literal would make a policy change silently disagree with the predicate that enforces it. The
-- sentinel exists for the same reason knowledge_dependency_revisions carries LIMIT 1025 -- so that
-- data which should be impossible fails closed instead of running unbounded. V209 makes the
-- relation acyclic by construction, so the sentinel is not a cycle guard either.
--
-- A chain that cannot be completed -- a container pointing at a scope that is not there -- stops at
-- the break, so nothing above a break is an ancestor of anything below it. That is a refusal, which
-- is the direction a broken chain must fail in.
CREATE FUNCTION contained_scope_within(descendant UUID, ancestor UUID) RETURNS BOOLEAN
 LANGUAGE SQL STABLE AS $$
 WITH RECURSIVE chain(id,distance) AS (
   SELECT s.container,1 FROM authority_scopes s WHERE s.id=$1 AND s.container IS NOT NULL
   UNION ALL
   SELECT s.container,c.distance+1 FROM chain c JOIN authority_scopes s ON s.id=c.id
   WHERE s.container IS NOT NULL AND c.distance<64
 ) SELECT EXISTS(SELECT 1 FROM chain WHERE id=$2);
$$;

-- The containment admission rule itself, declared once and consulted by both chokepoints below, so
-- the exact-target predicate and the batched readable-scope relation cannot drift apart. A
-- contained scope is readable when either:
--
-- (a) the actor IS that scope's own principal. A contained principal's identifier is a bare UUID
--     (V210) with no row in admins, authority_accounts or service_tokens, so it holds no account
--     for information_global_read_member to be true of; requiring that membership here would make
--     this arm dead rather than narrow. It admits exactly one scope, the principal's own, and
--     never walks the chain, so it cannot reach anything above it.
--
-- (b) the actor resolves a project role on a scope that contains this one, transitively.
--     authority_project_role ONLY -- deliberately not the coalesce(authority_project_role,
--     service_project_role) the pre-existing arm uses. A service credential's project role is
--     scoped to a named project by its token; letting it ride a containment chain would hand every
--     token on a container read access to subtrees the token never named, which is the one widening
--     this task must not make. Global-read membership is still required, because that arm's actor
--     is an ordinary account and the baseline gate applies to it exactly as elsewhere.
--
-- Only an existing scope row can be the ancestor in (b), so a dangling container value can never
-- itself be the authority a read resolves through.
CREATE FUNCTION contained_scope_readable(actor TEXT, scope UUID) RETURNS BOOLEAN
 LANGUAGE SQL STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM authority_scopes s WHERE s.id=$2 AND s.kind='CONTAINED' AND (
   EXISTS(SELECT 1 FROM contained_principals principal
     WHERE principal.scope_id=s.id AND principal.id::text=$1)
   OR (information_global_read_member($1) AND EXISTS(
     SELECT 1 FROM authority_scopes ancestor
     JOIN projects ancestor_project ON ancestor_project.id=ancestor.project_id
     -- The kind restriction mirrors authority_project_role's own fence rather than adding one:
     -- that function resolves a role only on a PROJECT or PERSONAL grant scope, so an ancestor of
     -- any other kind -- a CONTAINED intermediate on the chain, or a RESTRICTED root -- already
     -- answered NULL. Stating it here keeps the role lookup off every link of a deep chain.
     WHERE ancestor.kind IN ('PROJECT','PERSONAL')
       AND contained_scope_within(s.id,ancestor.id)
       AND authority_project_role(ancestor_project.name,$1) IS NOT NULL))));
$$;

-- Chokepoint 1 of 2: the exact-target predicate. Reproduced verbatim from V172:9 with one new
-- top-level arm; the pre-existing body, including its information_global_read_member gate, is
-- unchanged, so no existing admission is altered. The new arm sits beside that gate rather than
-- under it for the reason given at (a) above. information_project_readable, information_root_
-- readable and knowledge_revision_readable resolve through this predicate and therefore move with
-- it -- which is intended: ADR 0042's inventory records them as one gate.
CREATE OR REPLACE FUNCTION knowledge_scope_readable(actor TEXT, scope UUID) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 SELECT (information_global_read_member($1) AND EXISTS(SELECT 1 FROM authority_scopes s
 LEFT JOIN projects p ON p.id=s.project_id LEFT JOIN authority_accounts a ON a.legacy_handle=$1
 WHERE s.id=$2 AND (
   (s.kind='ACCOUNT_KNOWLEDGE' AND s.builtin IS NULL AND s.account_id=a.id)
   OR (s.builtin='GLOBAL_KNOWLEDGE' AND information_account_action($1,'READ'))
   OR (s.kind IN ('PROJECT','PERSONAL') AND
     coalesce(authority_project_role(p.name,$1),service_project_role(p.name,$1)) IS NOT NULL))))
 OR contained_scope_readable($1,$2);
$$;

-- Chokepoint 2 of 2: the batched readable-scope relation that discovery joins against. The two are
-- independent bodies -- widening only the exact predicate leaves discovery blind, and widening only
-- this one leaves exact reads refused -- so both are needed and both consult the one rule above.
--
-- Reproduced verbatim from V178:4 with one change: readable_scopes' WHERE. The pre-existing
-- branches keep their `a.member AND (...)` gate exactly as it was, and the containment arm is a
-- disjunct beside it, again because a contained principal has no account to be a member of.
-- Everything else -- the live-grant, project_reads, token and Personal fences, and the
-- MATERIALIZED shape this function exists for -- is untouched, and authority_live_grants still
-- honours no grant against a contained scope, so no role is resolved on one.
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
   OR (s.kind='CONTAINED' AND contained_scope_readable($1,s.id))
 )
 SELECT a.id,a.member,s.id FROM actor_fact a LEFT JOIN readable_scopes s ON true;
END $$;
