-- Universal eligibility is contextual resource READ, never project membership or a write grant.
INSERT INTO authority_role_actions(role_id,role_version,profile,action)
SELECT id,1,'DEFAULT','READ' FROM authority_roles WHERE name='GLOBAL_READ';
CREATE OR REPLACE FUNCTION authority_builtin_registry_valid() RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 SELECT authority_identity_registry_valid()
   AND (SELECT count(*)=4 FROM authority_roles WHERE name IN ('ADMIN','MANAGER','CONTRIBUTOR','READER') AND assignable)
   AND (SELECT count(*)=26 FROM authority_role_actions a JOIN authority_roles r ON r.id=a.role_id WHERE r.name<>'GLOBAL_READ')
   AND (SELECT count(*)=1 FROM authority_role_actions a JOIN authority_roles r ON r.id=a.role_id WHERE r.name='GLOBAL_READ' AND a.action='READ' AND a.role_version=1 AND a.profile='DEFAULT')
   AND (SELECT count(*)=1 FROM authority_grants g JOIN authority_roles r ON r.id=g.role_id
        JOIN authority_scopes s ON s.id=g.scope_id WHERE g.provenance='ROOT_POLICY' AND g.revoked_at IS NULL AND g.expires_at IS NULL
        AND g.subject_account=r.intrinsic_account AND g.issuer_account=r.intrinsic_account
        AND g.subject_agent IS NULL AND g.issuer_agent IS NULL AND g.issuing_grant IS NULL
        AND g.coverage='ALL_SCOPES' AND s.kind='ADMINISTRATION' AND s.account_id=r.intrinsic_account
        AND g.version=1 AND NOT g.manager_forced);
$$;
CREATE FUNCTION information_global_read_member(account TEXT) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 SELECT account_active($1) AND EXISTS(SELECT 1 FROM admins u JOIN authority_accounts a ON a.legacy_handle=u.handle
   CROSS JOIN authority_roles r JOIN authority_role_actions action ON action.role_id=r.id
   WHERE u.handle=$1 AND NOT u.bootstrap AND a.builtin IS NULL AND r.name='GLOBAL_READ'
      AND action.action='READ' AND action.role_version=1 AND action.profile='DEFAULT');
$$;

CREATE TABLE information_resource_policies (
 resource_id UUID PRIMARY KEY REFERENCES information_resources(id),
 version BIGINT NOT NULL CHECK(version>0),
 audience TEXT NOT NULL CHECK(audience IN ('HOME','LEGACY_SHARED','GLOBAL_READ','QUARANTINED')),
 privacy_principal UUID REFERENCES authority_accounts(id),
 private_only BOOLEAN NOT NULL DEFAULT false,
 needs_review BOOLEAN NOT NULL DEFAULT false,
 actor UUID REFERENCES authority_accounts(id),
 reason TEXT NOT NULL CHECK(reason IN ('MIGRATION','INITIAL','AUDIENCE','ASSIGNMENT')),
 changed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE information_resource_policy_history (
 resource_id UUID NOT NULL REFERENCES information_resources(id),
 version BIGINT NOT NULL CHECK(version>0),
 audience TEXT NOT NULL,
 privacy_principal UUID REFERENCES authority_accounts(id),
 private_only BOOLEAN NOT NULL,
 needs_review BOOLEAN NOT NULL,
 actor UUID REFERENCES authority_accounts(id),
 reason TEXT NOT NULL,
 recorded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY(resource_id,version)
);
CREATE FUNCTION information_resource_policy_history_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'root policy history is immutable'; END $$;
CREATE TRIGGER information_resource_policy_history_guard BEFORE UPDATE OR DELETE ON information_resource_policy_history
 FOR EACH ROW EXECUTE FUNCTION information_resource_policy_history_guard();
CREATE FUNCTION information_resource_policy_record() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'root policy cannot be deleted'; END IF;
 IF TG_OP='UPDATE' AND (NEW.resource_id<>OLD.resource_id OR NEW.version<>OLD.version+1) THEN
   RAISE EXCEPTION 'root policy requires its next version';
 END IF;
 IF TG_OP='UPDATE' AND (NEW.reason NOT IN ('AUDIENCE','ASSIGNMENT') OR NEW.actor IS NULL) THEN
   RAISE EXCEPTION 'root policy changes require actor attribution';
 END IF;
 INSERT INTO information_resource_policy_history(resource_id,version,audience,privacy_principal,private_only,needs_review,actor,reason)
 VALUES(NEW.resource_id,NEW.version,NEW.audience,NEW.privacy_principal,NEW.private_only,NEW.needs_review,NEW.actor,NEW.reason);
 RETURN NEW;
END $$;
CREATE TRIGGER information_resource_policy_record BEFORE INSERT OR UPDATE OR DELETE ON information_resource_policies
 FOR EACH ROW EXECUTE FUNCTION information_resource_policy_record();

-- Preserve original revision flags. Consistent old Shared remains a human-only compatibility
-- audience; mixed histories narrow rather than unioning flags or expanding to service readers.
WITH classified AS (
 SELECT q.id,q.home_scope_id,q.project_id,a.id AS principal,s.builtin,
   count(r.id) AS revisions,count(p.document_id) AS policies,
   count(*) FILTER(WHERE p.visibility='shared') AS shared,
   coalesce(bool_or(p.visibility='quarantined'),false) AS quarantined,
   coalesce(bool_or(p.visibility='personal' AND q.project_id IS NOT NULL),false) AS private,
   coalesce(bool_or(p.visibility='personal' AND p.owner_handle IS DISTINCT FROM q.owner_handle
      OR p.visibility='project' AND p.project_id IS DISTINCT FROM q.project_id),false) AS conflict
 FROM information_resources q LEFT JOIN information_revisions r ON r.resource_id=q.id
 LEFT JOIN information_document_policies p ON p.document_id=r.id
 LEFT JOIN authority_accounts a ON a.legacy_handle=q.owner_handle
 LEFT JOIN authority_scopes s ON s.id=q.home_scope_id
 GROUP BY q.id,a.id,s.builtin
)
INSERT INTO information_resource_policies(resource_id,version,audience,privacy_principal,private_only,needs_review,reason)
SELECT id,1,CASE WHEN home_scope_id IS NULL OR quarantined OR conflict THEN 'QUARANTINED'
  WHEN builtin='GLOBAL_KNOWLEDGE' THEN 'GLOBAL_READ'
  WHEN revisions>0 AND policies=revisions AND shared=revisions THEN 'LEGACY_SHARED' ELSE 'HOME' END,
 principal,private,conflict OR (shared>0 AND (shared<>revisions OR policies<>revisions)),'MIGRATION' FROM classified;

CREATE FUNCTION information_initial_root_policy() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 INSERT INTO information_resource_policies(resource_id,version,audience,privacy_principal,reason)
 SELECT NEW.id,1,CASE WHEN NEW.home_scope_id IS NULL THEN 'QUARANTINED'
     WHEN s.builtin='GLOBAL_KNOWLEDGE' THEN 'GLOBAL_READ' ELSE 'HOME' END,a.id,'INITIAL'
 FROM (SELECT 1) seed LEFT JOIN authority_scopes s ON s.id=NEW.home_scope_id
 LEFT JOIN authority_accounts a ON a.legacy_handle=NEW.owner_handle;
 RETURN NEW;
END $$;
CREATE TRIGGER information_initial_root_policy AFTER INSERT ON information_resources
 FOR EACH ROW EXECUTE FUNCTION information_initial_root_policy();

-- An explicit legacy privacy principal is a reconciled account grant, not created_by or a live
-- comparison with owner_handle. Mandatory ownerOnly remains effective if configured later.
CREATE FUNCTION information_privacy_allows(resource UUID, account TEXT) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 SELECT coalesce((NOT p.private_only AND NOT coalesce((information_navigation_policy(q.id)->>'ownerOnly')::boolean,true))
   OR EXISTS(SELECT 1 FROM authority_accounts a WHERE a.id=p.privacy_principal AND a.legacy_handle=$2),false)
 FROM information_resources q JOIN information_resource_policies p ON p.resource_id=q.id WHERE q.id=$1;
$$;
CREATE FUNCTION information_public_policy(revision UUID) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 SELECT coalesce(p.audience='GLOBAL_READ' AND q.home_scope_id IS NOT NULL AND NOT p.private_only
   AND NOT coalesce((information_navigation_policy(q.id)->>'ownerOnly')::boolean,true)
   AND r.availability='active' AND NOT EXISTS(SELECT 1 FROM information_document_policies legacy
      WHERE legacy.document_id=r.id AND legacy.visibility='quarantined'),false)
 FROM information_revisions r JOIN information_resources q ON q.id=r.resource_id
 JOIN information_resource_policies p ON p.resource_id=q.id WHERE r.id=$1;
$$;

ALTER FUNCTION information_legacy_readable(UUID,TEXT,TEXT,TEXT,BOOLEAN) RENAME TO information_pre_root_readable;
CREATE FUNCTION information_root_readable(revision UUID, account TEXT, selected_scope TEXT,
 selected_project TEXT, include_shared BOOLEAN) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 SELECT coalesce(information_global_read_member($2) AND r.availability='active'
   AND NOT EXISTS(SELECT 1 FROM information_document_policies legacy WHERE legacy.document_id=r.id AND legacy.visibility='quarantined')
   AND policy.audience<>'QUARANTINED' AND s.kind<>'RESTRICTED'
   AND information_privacy_allows(q.id,$2) AND (
     -- The home is retained when an audience is added; selection does not confer membership.
     (policy.audience IN ('HOME','GLOBAL_READ') AND (
       information_home_allows(q.id,$2,$3,$4,'READ')
       OR (s.kind='ACCOUNT_KNOWLEDGE' AND s.account_id=actor.id AND $3='project' AND EXISTS(
         SELECT 1 FROM information_links l JOIN projects p ON p.id=l.project_id
         WHERE l.revision_id=r.id AND p.name=$4 AND information_project_readable(p.id,$2)))))
     OR (($3='shared' OR $5) AND (
       (policy.audience='GLOBAL_READ' AND information_public_policy(r.id))
       OR (policy.audience='LEGACY_SHARED' AND u.account_kind='USER')))),false)
 FROM information_revisions r JOIN information_resources q ON q.id=r.resource_id
 JOIN information_resource_policies policy ON policy.resource_id=q.id
 JOIN authority_scopes s ON s.id=q.home_scope_id
 LEFT JOIN authority_accounts actor ON actor.legacy_handle=$2 LEFT JOIN admins u ON u.handle=$2
 WHERE r.id=$1;
$$;
CREATE FUNCTION information_legacy_readable(target UUID, account TEXT, selected_scope TEXT,
 selected_project TEXT, include_shared BOOLEAN) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 WITH RECURSIVE inputs(id) AS (
   SELECT $1 UNION SELECT i.input_revision FROM information_inputs i JOIN inputs p ON i.derived_revision=p.id
 ) SELECT information_global_read_member($2) AND ($2 NOT LIKE '@service/%' OR $3 IN ('project','shared'))
   AND coalesce(bool_and(CASE WHEN EXISTS(SELECT 1 FROM information_revisions r WHERE r.id=i.id)
      THEN coalesce(information_root_readable(i.id,$2,$3,$4,$5),false)
      ELSE coalesce(information_pre_root_readable(i.id,$2,$3,$4,$5),false)
        AND NOT EXISTS(SELECT 1 FROM information_resources q WHERE q.id=i.id)
        AND (NOT EXISTS(SELECT 1 FROM information_document_policies legacy WHERE legacy.document_id=i.id AND legacy.visibility='shared')
          OR EXISTS(SELECT 1 FROM admins u WHERE u.handle=$2 AND u.account_kind='USER')) END),false) FROM inputs i;
$$;
CREATE OR REPLACE FUNCTION information_navigation_own_readable(revision UUID, account TEXT,
 selected_project TEXT) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 SELECT coalesce(information_privacy_allows(q.id,$2)
   AND ($2 NOT LIKE '@service/%' OR p.name=$3 OR information_public_policy(r.id))
   AND information_legacy_readable(r.id,$2,
       CASE WHEN information_public_policy(r.id) THEN 'shared' WHEN p.id IS NULL THEN 'personal' ELSE 'project' END,
       CASE WHEN information_public_policy(r.id) THEN NULL ELSE p.name END,
       information_public_policy(r.id) OR $2 NOT LIKE '@service/%'),false)
 FROM information_revisions r JOIN information_resources q ON q.id=r.resource_id
 LEFT JOIN projects p ON p.id=q.project_id WHERE r.id=$1;
$$;
CREATE OR REPLACE FUNCTION information_reference_readable(target UUID, account TEXT, selected_project TEXT)
 RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 WITH RECURSIVE inputs(id) AS (
   SELECT $1 UNION SELECT i.input_revision FROM information_inputs i JOIN inputs p ON i.derived_revision=p.id
 ) SELECT coalesce(bool_and((CASE WHEN EXISTS(SELECT 1 FROM information_revisions r WHERE r.id=i.id)
   THEN EXISTS(SELECT 1 FROM information_revisions r JOIN information_resources q ON q.id=r.resource_id
     WHERE r.id=i.id AND information_privacy_allows(q.id,$2))
   ELSE EXISTS(SELECT 1 FROM information_document_policies p WHERE p.document_id=i.id)
     AND NOT EXISTS(SELECT 1 FROM information_resources q WHERE q.id=i.id) END)
   AND information_required_references_readable(i.id,$2,$3)),false) FROM inputs i;
$$;
CREATE OR REPLACE FUNCTION information_navigation_reference_visible(resource UUID, account TEXT, selected_project TEXT)
 RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 SELECT information_global_read_member($2) AND EXISTS(SELECT 1 FROM information_resources q
   JOIN information_revisions r ON r.id=information_navigation_head(q.id)
   JOIN information_resource_policies policy ON policy.resource_id=q.id
   LEFT JOIN projects project ON project.id=q.project_id
   WHERE q.id=$1 AND policy.audience<>'QUARANTINED' AND r.availability='active' AND NOT r.excluded
     AND NOT EXISTS(SELECT 1 FROM information_document_policies legacy WHERE legacy.document_id=r.id AND legacy.visibility='quarantined')
     AND NOT coalesce((information_navigation_policy(q.id)->>'referenceHidden')::boolean,true)
     AND ($2 NOT LIKE '@service/%' OR project.name=$3 OR information_public_policy(r.id)));
$$;
CREATE FUNCTION information_required_references_public(target UUID) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 WITH RECURSIVE required(id) AS (
   SELECT coalesce(o.pinned_revision,information_navigation_head(o.target)) FROM information_reference_obligations o
     JOIN information_revisions r ON r.resource_id=o.source WHERE r.id=$1
   UNION SELECT edge.id FROM required d CROSS JOIN LATERAL (
     SELECT i.input_revision AS id FROM information_inputs i WHERE i.derived_revision=d.id
     UNION SELECT coalesce(o.pinned_revision,information_navigation_head(o.target)) FROM information_reference_obligations o
       JOIN information_revisions r ON r.resource_id=o.source WHERE r.id=d.id) edge
 ), bounded AS (SELECT id FROM required LIMIT 1025)
 SELECT count(*)<=1024 AND coalesce(bool_and(coalesce(information_public_policy(id),false)),true) FROM bounded;
$$;
CREATE FUNCTION information_public_dependencies(target UUID) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 WITH RECURSIVE inputs(id) AS (
   SELECT $1 UNION SELECT i.input_revision FROM information_inputs i JOIN inputs p ON i.derived_revision=p.id
 ) SELECT coalesce(bool_and((id=$1 OR coalesce(information_public_policy(id),false))
     AND information_required_references_public(id)),false) FROM inputs;
$$;

-- Control uses live grants. Global/platform control remains human ADMIN, independently of client.
CREATE FUNCTION information_scope_control(account TEXT, scope UUID) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 SELECT information_global_read_member($1) AND EXISTS(SELECT 1 FROM authority_scopes s
 LEFT JOIN authority_accounts owner ON owner.id=s.account_id
 LEFT JOIN authority_accounts actor ON actor.legacy_handle=$1
 LEFT JOIN admins u ON u.handle=$1 LEFT JOIN projects p ON p.id=s.project_id
 WHERE s.id=$2 AND (
   (s.kind='ACCOUNT_KNOWLEDGE' AND owner.builtin IS NULL AND s.account_id=actor.id)
   OR (s.kind IN ('PROJECT','PERSONAL') AND coalesce(authority_project_role(p.name,$1),service_project_role(p.name,$1))='MANAGER')
   OR (u.account_kind='USER' AND EXISTS(SELECT 1 FROM authority_grant_records g JOIN authority_live_grants() live ON live.id=g.id
     WHERE g.subject_account=actor.id AND g.subject_agent IS NULL AND g.effective_role='ADMIN'
       AND 'SET_AUDIENCE'=ANY(g.actions) AND (g.scope_id=s.id OR (g.coverage='ORDINARY_PROJECT_CONTROL' AND s.kind='PROJECT'))))));
$$;
CREATE OR REPLACE FUNCTION information_knowledge_allows(revision UUID, account TEXT, selected_scope TEXT,
 selected_project TEXT, include_shared BOOLEAN, action TEXT) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 SELECT coalesce((information_home_allows(q.id,$2,$3,$4,$6)
     OR ($6='SET_AUDIENCE' AND $3='shared' AND s.builtin='GLOBAL_KNOWLEDGE' AND information_scope_control($2,s.id)))
   AND ($2 NOT LIKE '@service/%' OR $3 IN ('project','shared'))
   AND policy.audience<>'QUARANTINED'
   AND NOT EXISTS(SELECT 1 FROM information_document_policies legacy WHERE legacy.document_id=r.id AND legacy.visibility='quarantined')
   AND information_privacy_allows(q.id,$2)
   AND information_reference_readable(r.id,$2,$4)
   AND NOT EXISTS(SELECT 1 FROM information_inputs i WHERE i.derived_revision=r.id
      AND NOT information_readable(i.input_revision,$2,$3,$4,$5)),false)
 FROM information_revisions r JOIN information_resources q ON q.id=r.resource_id
 JOIN authority_scopes s ON s.id=q.home_scope_id
 JOIN information_resource_policies policy ON policy.resource_id=q.id WHERE r.id=$1;
$$;

CREATE FUNCTION information_root_publishable(revision UUID) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 SELECT coalesce(p.audience<>'QUARANTINED' AND q.home_scope_id IS NOT NULL AND NOT p.private_only
   AND NOT coalesce((information_navigation_policy(q.id)->>'ownerOnly')::boolean,true)
   AND information_public_dependencies(information_navigation_head(q.id))
   AND information_public_dependencies(r.id),false)
 FROM information_revisions r JOIN information_resources q ON q.id=r.resource_id
 JOIN information_resource_policies p ON p.resource_id=q.id WHERE r.id=$1;
$$;

CREATE FUNCTION information_set_root_audience(revision UUID, account TEXT, selected_scope TEXT,
 selected_project TEXT, include_shared BOOLEAN, general_read BOOLEAN) RETURNS BIGINT LANGUAGE plpgsql AS $$
DECLARE resource UUID; current information_resource_policies; next_version BIGINT;
BEGIN
 PERFORM pg_advisory_xact_lock(hashtextextended('information-navigation-control',0));
 IF NOT information_lock_knowledge($1,$2,$3,$4,$5,'SET_AUDIENCE') THEN RAISE insufficient_privilege USING MESSAGE='scoped audience control required'; END IF;
 SELECT q.id INTO resource FROM information_resources q
    JOIN information_revisions r ON r.resource_id=q.id WHERE r.id=$1;
 SELECT * INTO current FROM information_resource_policies WHERE resource_id=resource FOR UPDATE;
 IF $6 AND NOT coalesce(information_root_publishable($1),false) THEN
   RAISE insufficient_privilege USING MESSAGE='private or restricted dependencies cannot authorize GLOBAL_READ';
 END IF;
 UPDATE information_resource_policies SET version=version+1,audience=CASE WHEN $6 THEN 'GLOBAL_READ' ELSE 'HOME' END,
   needs_review=false,actor=(SELECT id FROM authority_accounts WHERE legacy_handle=$2),reason='AUDIENCE',changed_at=now()
 WHERE resource_id=resource AND version=current.version RETURNING version INTO next_version;
 IF next_version IS NULL THEN RAISE EXCEPTION 'root policy changed'; END IF;
 RETURN next_version;
END $$;

-- Old flags are retained historical evidence. New projections can insert their compatibility
-- policy, but neither an insert nor an old share writer changes the authoritative root.
CREATE OR REPLACE FUNCTION information_audience_write_fence() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF (NEW.visibility IS DISTINCT FROM OLD.visibility OR NEW.project_id IS DISTINCT FROM OLD.project_id)
   AND NEW.visibility IN ('personal','project','shared') AND OLD.visibility IN ('personal','project','shared') THEN
   RAISE insufficient_privilege USING MESSAGE='per-revision audience writers are incompatible with current root policy';
 END IF;
 RETURN NEW;
END $$;
CREATE FUNCTION information_adopt_root_policy(revision UUID, account TEXT) RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE resource UUID;
BEGIN
 PERFORM pg_advisory_xact_lock(hashtextextended('information-navigation-control',0));
 -- The existing service validates the explicitly configured migration operator and audits the
 -- assignment. Raw legacy corpus documents have no lifecycle root to reconcile.
 IF NOT EXISTS(SELECT 1 FROM information_revisions WHERE id=$1) THEN RETURN; END IF;
 IF NOT account_active($2) OR NOT EXISTS(SELECT 1 FROM admins WHERE handle=$2 AND account_kind='USER' AND NOT bootstrap) THEN
   RAISE insufficient_privilege USING MESSAGE='legacy assignment requires active human operator';
 END IF;
 SELECT q.id INTO resource FROM information_resources q JOIN information_revisions r ON r.resource_id=q.id
   WHERE r.id=$1 AND q.home_scope_id IS NOT NULL FOR UPDATE OF q;
 IF resource IS NULL THEN RAISE EXCEPTION 'legacy assignment has no explicit home'; END IF;
 UPDATE information_resource_policies policy SET version=version+1,
   audience=CASE WHEN EXISTS(SELECT 1 FROM information_document_policies legacy WHERE legacy.document_id=$1 AND legacy.visibility='shared')
     THEN 'LEGACY_SHARED' ELSE 'HOME' END,private_only=false,needs_review=false,
   privacy_principal=(SELECT a.id FROM information_resources q JOIN authority_accounts a ON a.legacy_handle=q.owner_handle WHERE q.id=resource),
   actor=(SELECT id FROM authority_accounts WHERE legacy_handle=$2),reason='ASSIGNMENT',changed_at=now()
 WHERE policy.resource_id=resource AND policy.audience='QUARANTINED';
END $$;
