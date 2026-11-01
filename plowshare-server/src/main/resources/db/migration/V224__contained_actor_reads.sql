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
