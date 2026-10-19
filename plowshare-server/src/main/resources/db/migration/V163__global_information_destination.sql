-- Explicit Global knowledge destination. Identity is not authority; existing selectors keep their policy.
CREATE FUNCTION information_account_action(actor TEXT, action TEXT) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 SELECT coalesce(account_active($1) AND EXISTS(
   SELECT 1 FROM admins u JOIN authority_accounts a ON a.legacy_handle=u.handle
   JOIN authority_grant_records g ON g.subject_account=a.id
   JOIN authority_live_grants() live ON live.id=g.id
   WHERE u.handle=$1 AND u.account_kind='USER' AND NOT u.bootstrap
     AND g.subject_agent IS NULL AND g.scope_id='00000000-0000-0000-0000-000000000005'
     AND g.effective_role='ADMIN' AND g.coverage='SELF' AND $2=ANY(g.actions)),false);
$$;
ALTER FUNCTION information_home(BIGINT,TEXT,TEXT) RENAME TO information_pre_account_home;
CREATE FUNCTION information_home(project BIGINT, namespace TEXT, custodian TEXT) RETURNS UUID LANGUAGE SQL STABLE AS $$
 SELECT CASE WHEN $1 IS NULL AND $2='account:00000000-0000-0000-0000-000000000004'
   AND $3=(SELECT legacy_handle FROM authority_accounts WHERE id='00000000-0000-0000-0000-000000000004')
 THEN '00000000-0000-0000-0000-000000000005'::uuid ELSE information_pre_account_home($1,$2,$3) END;
$$;
ALTER FUNCTION information_namespace_home(TEXT) RENAME TO information_pre_account_namespace_home;
CREATE FUNCTION information_namespace_home(namespace TEXT) RETURNS UUID LANGUAGE SQL STABLE AS $$
 SELECT CASE WHEN $1='account:00000000-0000-0000-0000-000000000004'
 THEN '00000000-0000-0000-0000-000000000005'::uuid ELSE information_pre_account_namespace_home($1) END;
$$;
ALTER FUNCTION information_home_allows(UUID,TEXT,TEXT,TEXT,TEXT) RENAME TO information_pre_account_home_allows;
CREATE FUNCTION information_home_allows(resource UUID, account TEXT, selected_scope TEXT,
 selected_project TEXT, action TEXT) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 SELECT CASE WHEN $3='account' THEN coalesce($4 IS NULL AND information_account_action($2,$5)
   AND $5 IN ('READ','WRITE','SET_AUDIENCE') AND EXISTS(SELECT 1 FROM information_resources
     WHERE id=$1 AND home_scope_id='00000000-0000-0000-0000-000000000005'),false)
 ELSE information_pre_account_home_allows($1,$2,$3,$4,$5) END;
$$;
ALTER FUNCTION information_lock_destination(TEXT,TEXT,TEXT,BOOLEAN) RENAME TO information_pre_account_lock_destination;
CREATE FUNCTION information_lock_destination(account TEXT, selected_scope TEXT, selected_project TEXT, include_shared BOOLEAN)
RETURNS BOOLEAN LANGUAGE plpgsql AS $$
BEGIN
 IF $2<>'account' THEN RETURN information_pre_account_lock_destination($1,$2,$3,$4); END IF;
 IF $3 IS NOT NULL OR $4 THEN RETURN false; END IF;
 PERFORM pg_advisory_xact_lock_shared(hashtextextended('information-navigation-control',0));
 PERFORM information_lock_scope($1,'00000000-0000-0000-0000-000000000005');
 RETURN information_account_action($1,'WRITE');
END $$;
CREATE OR REPLACE FUNCTION information_root_readable(revision UUID, account TEXT, selected_scope TEXT,
 selected_project TEXT, include_shared BOOLEAN) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 SELECT coalesce(information_global_read_member($2) AND r.availability='active'
   AND NOT EXISTS(SELECT 1 FROM information_document_policies legacy WHERE legacy.document_id=r.id AND legacy.visibility='quarantined')
   AND policy.audience<>'QUARANTINED' AND s.kind<>'RESTRICTED'
   AND ($3<>'account' OR s.builtin='GLOBAL_KNOWLEDGE')
   AND information_privacy_allows(q.id,$2) AND (
     -- The home is retained when an audience is added; selection does not confer membership.
     (policy.audience IN ('HOME','GLOBAL_READ') AND (
       information_home_allows(q.id,$2,$3,$4,'READ')
       OR (s.kind='ACCOUNT_KNOWLEDGE' AND s.account_id=actor.id AND $3='project' AND EXISTS(
         SELECT 1 FROM information_links l JOIN projects p ON p.id=l.project_id
         WHERE l.revision_id=r.id AND p.name=$4 AND information_project_readable(p.id,$2)))))
     OR (($3='shared' OR $5 OR ($3='account' AND s.builtin='GLOBAL_KNOWLEDGE')) AND (
       (policy.audience='GLOBAL_READ' AND information_public_policy(r.id))
       OR (policy.audience='LEGACY_SHARED' AND u.account_kind='USER')))),false)
 FROM information_revisions r JOIN information_resources q ON q.id=r.resource_id
 JOIN information_resource_policies policy ON policy.resource_id=q.id
 JOIN authority_scopes s ON s.id=q.home_scope_id
 LEFT JOIN authority_accounts actor ON actor.legacy_handle=$2 LEFT JOIN admins u ON u.handle=$2
 WHERE r.id=$1;
$$;
CREATE OR REPLACE FUNCTION information_legacy_readable(target UUID, account TEXT, selected_scope TEXT,
 selected_project TEXT, include_shared BOOLEAN) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 WITH RECURSIVE inputs(id) AS (
   SELECT $1 UNION SELECT i.input_revision FROM information_inputs i JOIN inputs p ON i.derived_revision=p.id
 ) SELECT information_global_read_member($2) AND ($2 NOT LIKE '@service/%' OR $3 IN ('project','shared','account'))
   AND coalesce(bool_and(CASE WHEN EXISTS(SELECT 1 FROM information_revisions r WHERE r.id=i.id)
      THEN coalesce(information_root_readable(i.id,$2,$3,$4,$5),false)
      ELSE coalesce(information_pre_root_readable(i.id,$2,$3,$4,$5),false)
        AND NOT EXISTS(SELECT 1 FROM information_resources q WHERE q.id=i.id)
        AND (NOT EXISTS(SELECT 1 FROM information_document_policies legacy WHERE legacy.document_id=i.id AND legacy.visibility='shared')
          OR EXISTS(SELECT 1 FROM admins u WHERE u.handle=$2 AND u.account_kind='USER')) END),false) FROM inputs i;
$$;
