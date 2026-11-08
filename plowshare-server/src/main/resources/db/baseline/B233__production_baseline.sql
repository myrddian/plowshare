-- Cumulative schema for fresh databases at V233.
-- Generated with PostgreSQL 16 pg_dump from a disposable database migrated
-- through V233 with pgvector. The Flyway history and psql commands are excluded.
-- Only migration-owned seed rows are included. Objects are ordered so seed
-- rows are loaded before triggers and foreign keys, as in a pg_dump restore.
--
-- Routine bodies may reference relations defined later in the dump. Scope
-- this setting to Flyway's migration transaction.
SET LOCAL check_function_bodies = false;

--
-- Name: vector; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;


--
-- Name: account_active(text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.account_active(account text) RETURNS boolean
    LANGUAGE sql STABLE
    AS $$
    SELECT EXISTS (SELECT 1 FROM admins a WHERE a.handle=account AND a.enabled AND (
        a.account_kind<>'SERVICE_TOKEN' OR EXISTS (
            SELECT 1 FROM service_tokens t JOIN admins owner ON owner.handle=t.owner_handle
            WHERE t.principal_handle=a.handle AND t.credential_issued
                AND t.revoked_at IS NULL AND t.expires_at>now()
                AND owner.enabled AND owner.account_kind='SERVICE'
        )
    ));
$$;


--
-- Name: attach_execution_allowance(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.attach_execution_allowance() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF TG_TABLE_NAME='conversations' THEN
  IF NEW.budget_total IS NOT NULL OR NEW.budget_lifted THEN
   INSERT INTO execution_allowances(id,call_limit,spent)
    VALUES(NEW.budget_id,CASE WHEN NEW.budget_lifted THEN NULL ELSE NEW.budget_total END,NEW.budget_spent)
    ON CONFLICT(id) DO NOTHING;
   NEW.allowance_id=NEW.budget_id; NEW.owns_allowance=true;
  ELSIF NEW.parent_id IS NOT NULL THEN
   SELECT allowance_id INTO NEW.allowance_id FROM conversations WHERE id=NEW.parent_id;
  END IF;
 ELSIF TG_TABLE_NAME='information_revisions' THEN
  INSERT INTO execution_allowances(id,call_limit,spent) VALUES(NEW.processing_budget_id,NEW.allowance_total,NEW.allowance_spent);
 ELSIF TG_TABLE_NAME='embedding_queries' THEN
  INSERT INTO execution_allowances(id,call_limit,spent) VALUES(NEW.budget_id,NEW.call_limit,NEW.spent);
 ELSIF TG_TABLE_NAME='board_topics' THEN
  IF NEW.parent IS NULL THEN
   NEW.allowance_id=gen_random_uuid();
   INSERT INTO execution_allowances(id,call_limit,spent) VALUES(NEW.allowance_id,NEW.pot_total,NEW.pot_spent);
  ELSE SELECT allowance_id INTO NEW.allowance_id FROM board_topics WHERE id=NEW.root; END IF;
 END IF;
 RETURN NEW;
END $$;


--
-- Name: authority_account_lifecycle(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.authority_account_lifecycle() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP IN ('UPDATE','DELETE') AND OLD.account_kind='BUILTIN' THEN
        RAISE EXCEPTION 'Built-in accounts are immutable and cannot be deleted';
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    IF NEW.account_kind<>'BUILTIN' AND (lower(NEW.handle) IN ('system','global')
        OR NEW.handle IN ('00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002',
            '00000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-000000000004',
            '00000000-0000-0000-0000-000000000005','00000000-0000-0000-0000-000000000006')) THEN
        RAISE EXCEPTION 'Reserved identity cannot be an ordinary account';
    END IF;
    RETURN NEW;
END $$;


--
-- Name: authority_account_mapping_locked(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.authority_account_mapping_locked() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF OLD.builtin IS NOT NULL THEN RAISE EXCEPTION 'Built-in identity mapping is immutable'; END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    IF OLD.id<>NEW.id OR OLD.builtin IS DISTINCT FROM NEW.builtin THEN
        RAISE EXCEPTION 'Account identity cannot be reassigned';
    END IF;
    -- A handle may follow a parent FK rename, never an independent identity reassignment.
    IF OLD.legacy_handle<>NEW.legacy_handle AND EXISTS (SELECT 1 FROM admins WHERE handle=OLD.legacy_handle) THEN
        RAISE EXCEPTION 'Account locator must follow its original account';
    END IF;
    RETURN NEW;
END $$;


--
-- Name: authority_admin_changed(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.authority_admin_changed() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP='INSERT' OR (OLD.server_admin,OLD.bootstrap,OLD.account_kind) IS DISTINCT FROM (NEW.server_admin,NEW.bootstrap,NEW.account_kind) THEN
        PERFORM authority_refresh_admin(id) FROM authority_accounts WHERE legacy_handle=NEW.handle;
    END IF;
    RETURN NULL;
END $$;


--
-- Name: authority_audit_grant(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.authority_audit_grant() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    INSERT INTO authority_grant_audit(grant_id,event,version,actor_account,actor_agent,authority)
    VALUES(NEW.id,CASE WHEN TG_OP='INSERT' THEN 'ISSUE' ELSE 'REVOKE' END,NEW.version,coalesce(NEW.revoked_by,NEW.issuer_account),
        CASE WHEN TG_OP='INSERT' THEN NEW.issuer_agent ELSE NEW.revoked_by_agent END,
        coalesce(NEW.revoking_grant,NEW.issuing_grant));
    RETURN NEW;
END $$;


--
-- Name: authority_bootstrap_builtins(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.authority_bootstrap_builtins() RETURNS void
    LANGUAGE plpgsql
    AS $$
BEGIN
    INSERT INTO admins(handle,password_hash,must_change_password,server_admin,bootstrap,enabled,account_kind)
        VALUES ('SYSTEM','!locked-built-in',FALSE,FALSE,FALSE,FALSE,'BUILTIN'),
            ('GLOBAL','!locked-built-in',FALSE,FALSE,FALSE,FALSE,'BUILTIN') ON CONFLICT (handle) DO NOTHING;
    INSERT INTO authority_roles(id,name,assignable,intrinsic_account) VALUES
        ('00000000-0000-0000-0000-000000000002','SYSTEM',FALSE,'00000000-0000-0000-0000-000000000001'),
        ('00000000-0000-0000-0000-000000000006','GLOBAL_READ',TRUE,NULL) ON CONFLICT DO NOTHING;
    IF NOT authority_builtin_registry_valid() THEN
        RAISE EXCEPTION 'Reserved identity collision or incomplete bootstrap; restore the exact registry';
    END IF;
END $$;


--
-- Name: authority_builtin_registry_valid(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.authority_builtin_registry_valid() RETURNS boolean
    LANGUAGE sql STABLE
    AS $$
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


--
-- Name: authority_grant_insert(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.authority_grant_insert() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE parent authority_grants; parent_role TEXT; requested TEXT; effective TEXT; destination authority_scopes;
    subject_kind TEXT; issuer_kind TEXT; parent_depth INTEGER;
BEGIN
    IF NEW.provenance='ROOT_POLICY' THEN RAISE EXCEPTION 'Root policy cannot be assigned'; END IF;
    PERFORM authority_lock_lineage(NEW.issuing_grant);
    SELECT * INTO parent FROM authority_grants WHERE id=NEW.issuing_grant;
    SELECT depth INTO parent_depth FROM authority_live_grants() WHERE id=parent.id;
    IF parent_depth IS NULL OR parent_depth>=32 THEN RAISE EXCEPTION 'Issuing authority is not live or chain is too deep'; END IF;
    IF NOT ((parent.subject_account=NEW.issuer_account AND parent.subject_agent IS NOT DISTINCT FROM NEW.issuer_agent AND NEW.issuing_token IS NULL)
        OR (NEW.issuing_token IS NOT NULL AND NEW.issuer_agent IS NULL AND parent.subject_agent IS NULL AND EXISTS(
            SELECT 1 FROM service_tokens t JOIN authority_accounts owner ON owner.legacy_handle=t.owner_handle
            JOIN authority_accounts principal ON principal.legacy_handle=t.principal_handle
            JOIN service_token_scopes ts ON ts.token_id=t.id JOIN authority_scopes s ON s.project_id=ts.project_id
            WHERE t.id=NEW.issuing_token AND owner.id=parent.subject_account AND principal.id=NEW.issuer_account
                AND s.id=NEW.scope_id AND ts.role='MANAGER' AND account_active(t.principal_handle)
                AND NEW.expires_at IS NOT NULL AND NEW.expires_at<=t.expires_at))) THEN
        RAISE EXCEPTION 'Issuing grant belongs to a different principal'; END IF;
    SELECT name INTO parent_role FROM authority_roles WHERE id=parent.role_id;
    SELECT name INTO effective FROM authority_roles WHERE id=NEW.role_id;
    SELECT * INTO destination FROM authority_scopes WHERE id=NEW.scope_id;
    IF NOT FOUND THEN RAISE EXCEPTION 'Grant scope is unavailable'; END IF;
    SELECT u.account_kind INTO subject_kind FROM authority_accounts a JOIN admins u ON u.handle=a.legacy_handle WHERE a.id=NEW.subject_account;
    SELECT u.account_kind INTO issuer_kind FROM authority_accounts a JOIN admins u ON u.handle=a.legacy_handle WHERE a.id=NEW.issuer_account;
    IF subject_kind NOT IN ('USER','SERVICE') OR destination.kind IN ('RESTRICTED','CONTAINED')
        OR (destination.kind='PERSONAL' AND destination.account_id<>NEW.subject_account)
        OR (effective='ADMIN' AND (subject_kind<>'USER' OR NEW.subject_agent IS NOT NULL)) THEN
        RAISE EXCEPTION 'Recipient or scope cannot receive that role'; END IF;
    IF effective IN ('SYSTEM','GLOBAL_READ') OR NEW.requested_role='SYSTEM' THEN RAISE EXCEPTION 'Role is not assignable'; END IF;
    IF NEW.subject_agent IS NOT NULL AND NEW.requested_role='ADMIN' THEN RAISE EXCEPTION 'Agents cannot receive ADMIN'; END IF;
    IF NEW.manager_forced AND (NEW.subject_agent IS NULL OR NEW.requested_role<>'MANAGER'
        OR issuer_kind<>'USER' OR NEW.issuer_agent IS NOT NULL) THEN RAISE EXCEPTION 'MANAGER force requires a human issuer'; END IF;
    IF NEW.issuer_agent IS NOT NULL AND (NEW.manager_forced OR effective='ADMIN') THEN
        RAISE EXCEPTION 'Agent cannot propagate administrative exceptions'; END IF;
    requested := CASE WHEN NEW.subject_agent IS NOT NULL AND NEW.requested_role='MANAGER' AND NOT NEW.manager_forced
        THEN 'CONTRIBUTOR' ELSE NEW.requested_role END;
    IF effective IS DISTINCT FROM requested OR parent_role NOT IN ('SYSTEM','ADMIN','MANAGER')
        OR (parent_role='MANAGER' AND effective='ADMIN') THEN RAISE EXCEPTION 'Role exceeds issuing authority'; END IF;
    IF NOT (parent.coverage='ALL_SCOPES' OR
        parent.scope_id=NEW.scope_id OR
        (parent.coverage='ORDINARY_PROJECT_CONTROL' AND destination.kind='PROJECT')) THEN
        RAISE EXCEPTION 'Grant exceeds issuing scope'; END IF;
    IF NEW.coverage<>'SELF' AND NOT (NEW.provenance='ADMIN_CONTROL' AND parent.provenance='ROOT_POLICY'
        AND NEW.coverage='ORDINARY_PROJECT_CONTROL' AND destination.kind='ADMINISTRATION' AND effective='ADMIN') THEN
        RAISE EXCEPTION 'Unsupported descendant coverage'; END IF;
    IF parent.expires_at IS NOT NULL AND (NEW.expires_at IS NULL OR NEW.expires_at>parent.expires_at) THEN
        RAISE EXCEPTION 'Grant outlives issuing authority'; END IF;
    IF NEW.version<>1 OR NEW.revoked_at IS NOT NULL THEN RAISE EXCEPTION 'Invalid initial grant state'; END IF;
    RETURN NEW;
END $$;


--
-- Name: authority_grant_mutation(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.authority_grant_mutation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Grant history cannot be deleted'; END IF;
    IF OLD.provenance='ROOT_POLICY' OR OLD.revoked_at IS NOT NULL OR NEW.revoked_at IS NULL OR NEW.version<>OLD.version+1
        OR (to_jsonb(NEW)-'revoked_at'-'version'-'revoked_by'-'revoked_by_agent'-'revoking_grant') IS DISTINCT FROM (to_jsonb(OLD)-'revoked_at'-'version'-'revoked_by'-'revoked_by_agent'-'revoking_grant') THEN
        RAISE EXCEPTION 'Grant is immutable except for one versioned revocation'; END IF;
    RETURN NEW;
END $$;


--
-- Name: authority_identity_registry_valid(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.authority_identity_registry_valid() RETURNS boolean
    LANGUAGE sql STABLE
    AS $$
    SELECT
        (SELECT count(*)=2 FROM authority_accounts i JOIN admins a ON a.handle=i.legacy_handle
            WHERE (i.builtin='SYSTEM' AND i.id='00000000-0000-0000-0000-000000000001' AND a.handle='SYSTEM'
                OR i.builtin='GLOBAL' AND i.id='00000000-0000-0000-0000-000000000004' AND a.handle='GLOBAL')
            AND a.account_kind='BUILTIN' AND NOT a.enabled AND NOT a.server_admin AND NOT a.bootstrap
            AND NOT a.must_change_password AND a.password_hash='!locked-built-in' AND a.session_version=0)
        AND (SELECT count(*)=2 FROM authority_roles WHERE
            (id='00000000-0000-0000-0000-000000000002' AND name='SYSTEM' AND NOT assignable
                AND intrinsic_account='00000000-0000-0000-0000-000000000001') OR
            (id='00000000-0000-0000-0000-000000000006' AND name='GLOBAL_READ' AND assignable AND intrinsic_account IS NULL))
        AND (SELECT count(*)=2 FROM authority_scopes WHERE
            (id='00000000-0000-0000-0000-000000000003' AND kind='ADMINISTRATION'
                AND builtin='SYSTEM_ADMINISTRATION' AND account_id='00000000-0000-0000-0000-000000000001') OR
            (id='00000000-0000-0000-0000-000000000005' AND kind='ACCOUNT_KNOWLEDGE'
                AND builtin='GLOBAL_KNOWLEDGE' AND account_id='00000000-0000-0000-0000-000000000004'));
$$;


--
-- Name: authority_issue_membership(text, text, text, text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.authority_issue_membership(project text, target text, actor text, next_role text) RETURNS uuid
    LANGUAGE plpgsql
    AS $$
DECLARE authority UUID; sid UUID; subject UUID; issuer UUID; result UUID; expiry TIMESTAMPTZ; token UUID; owner TEXT; token_expiry TIMESTAMPTZ;
BEGIN
    authority := authority_project_management_grant(project,actor);
    IF authority IS NULL AND service_project_role(project,actor)='MANAGER' THEN
        SELECT t.id,t.owner_handle,t.expires_at INTO token,owner,token_expiry FROM service_tokens t WHERE t.principal_handle=actor;
        authority := authority_project_management_grant(project,owner);
    END IF;
    IF authority IS NULL THEN RAISE EXCEPTION 'Account cannot manage this project'; END IF;
    SELECT s.id INTO sid FROM authority_scopes s JOIN projects p ON p.id=s.project_id WHERE p.name=project;
    SELECT id INTO subject FROM authority_accounts WHERE legacy_handle=target;
    SELECT id INTO issuer FROM authority_accounts WHERE legacy_handle=actor;
    IF EXISTS(SELECT 1 FROM authority_grants WHERE id=authority AND provenance='MEMBERSHIP' AND subject_account=subject) THEN
        RAISE EXCEPTION 'Replacing your issuing membership requires another manager'; END IF;
    UPDATE authority_grants SET revoked_at=now(),version=version+1,revoked_by=issuer,revoking_grant=authority
        WHERE scope_id=sid AND subject_account=subject AND provenance='MEMBERSHIP' AND revoked_at IS NULL;
    SELECT expires_at INTO expiry FROM authority_grants WHERE id=authority;
    expiry := least(expiry,token_expiry);
    INSERT INTO authority_grants(subject_account,scope_id,role_id,requested_role,issuer_account,issuing_grant,expires_at,issuing_token,provenance)
    SELECT subject,sid,r.id,r.name,issuer,authority,expiry,token,'MEMBERSHIP' FROM authority_roles r
        WHERE r.name=CASE WHEN next_role='VIEWER' THEN 'READER' ELSE next_role END RETURNING id INTO result;
    IF result IS NULL THEN RAISE EXCEPTION 'Unknown project role'; END IF;
    RETURN result;
END $$;


--
-- Name: authority_live_grants(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.authority_live_grants() RETURNS TABLE(id uuid, depth integer)
    LANGUAGE sql STABLE
    AS $$
    WITH RECURSIVE live(id,depth) AS (
        SELECT g.id,0 FROM authority_grants g JOIN authority_roles r ON r.id=g.role_id
        JOIN authority_accounts a ON a.id=g.subject_account JOIN admins u ON u.handle=a.legacy_handle
        JOIN authority_scopes root_scope ON root_scope.id=g.scope_id AND root_scope.kind='ADMINISTRATION' AND root_scope.account_id=r.intrinsic_account
        WHERE g.provenance='ROOT_POLICY' AND g.revoked_at IS NULL
            AND r.intrinsic_account=a.id AND NOT r.assignable AND u.account_kind='BUILTIN'
            AND NOT u.enabled AND g.coverage='ALL_SCOPES'
        UNION ALL
        SELECT g.id,l.depth+1 FROM authority_grants g JOIN live l ON l.id=g.issuing_grant
        JOIN authority_accounts a ON a.id=g.subject_account JOIN admins u ON u.handle=a.legacy_handle
        JOIN authority_scopes s ON s.id=g.scope_id
        WHERE l.depth<32 AND g.revoked_at IS NULL AND (g.expires_at IS NULL OR g.expires_at>now())
            AND s.kind NOT IN ('RESTRICTED','CONTAINED') AND account_active(u.handle) AND NOT u.bootstrap
            AND (s.kind<>'PERSONAL' OR s.account_id=g.subject_account)
            AND (g.issuing_token IS NULL OR EXISTS(SELECT 1 FROM service_tokens t JOIN service_token_scopes ts ON ts.token_id=t.id
                JOIN projects p ON p.id=ts.project_id
                WHERE t.id=g.issuing_token AND ts.project_id=s.project_id AND ts.role='MANAGER'
                    AND account_active(t.principal_handle)))
            AND (g.provenance NOT IN ('ADMIN_CONTROL','LEGACY_ADMIN_DATA') OR (u.account_kind='USER' AND u.server_admin))
    ) SELECT * FROM live;
$$;


--
-- Name: authority_lock_lineage(uuid); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.authority_lock_lineage(authority uuid) RETURNS void
    LANGUAGE plpgsql
    AS $$
BEGIN
    -- Share-lock the bounded immutable lineage before validating it. Cross-scope revocation
    -- cannot commit between the issuing-authority check and this grant's commit.
    PERFORM g.id FROM authority_grants g WHERE g.id IN (
        WITH RECURSIVE chain(id,issuing_grant,depth) AS (
            SELECT id,issuing_grant,0 FROM authority_grants WHERE id=authority
            UNION ALL SELECT p.id,p.issuing_grant,c.depth+1 FROM authority_grants p
                JOIN chain c ON c.issuing_grant=p.id WHERE c.depth<32
        ) SELECT id FROM chain) ORDER BY g.id FOR SHARE;
END $$;


--
-- Name: authority_map_account(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.authority_map_account() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE identity UUID;
BEGIN
    identity := CASE WHEN NEW.account_kind='BUILTIN' AND NEW.handle='SYSTEM' THEN '00000000-0000-0000-0000-000000000001'::uuid
        WHEN NEW.account_kind='BUILTIN' AND NEW.handle='GLOBAL' THEN '00000000-0000-0000-0000-000000000004'::uuid
        ELSE gen_random_uuid() END;
    INSERT INTO authority_accounts(id,legacy_handle,builtin)
        VALUES(identity,NEW.handle,CASE WHEN NEW.account_kind='BUILTIN' THEN NEW.handle ELSE NULL END);
    IF NEW.account_kind IN ('USER','SERVICE') THEN
        INSERT INTO authority_scopes(kind,account_id) VALUES('ACCOUNT_KNOWLEDGE',identity);
    ELSIF NEW.account_kind='BUILTIN' AND NEW.handle='SYSTEM' THEN
        INSERT INTO authority_scopes(id,kind,account_id,builtin) VALUES
            ('00000000-0000-0000-0000-000000000003','ADMINISTRATION',identity,'SYSTEM_ADMINISTRATION');
    ELSIF NEW.account_kind='BUILTIN' AND NEW.handle='GLOBAL' THEN
        INSERT INTO authority_scopes(id,kind,account_id,builtin) VALUES
            ('00000000-0000-0000-0000-000000000005','ACCOUNT_KNOWLEDGE',identity,'GLOBAL_KNOWLEDGE');
    END IF;
    RETURN NEW;
END $$;


--
-- Name: authority_map_project(bigint); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.authority_map_project(project bigint) RETURNS void
    LANGUAGE sql
    AS $$
    INSERT INTO authority_scopes(kind,project_id,account_id,restriction_reason)
    SELECT CASE WHEN (p.personal_owner IS NULL AND p.name NOT LIKE 'personal:%' AND p.name NOT LIKE 'client:%') THEN 'PROJECT'
            WHEN a.account_kind='USER' THEN 'PERSONAL' ELSE 'RESTRICTED' END,
        p.id,CASE WHEN a.account_kind='USER' THEN i.id ELSE NULL END,
        CASE WHEN (p.personal_owner IS NULL AND p.name NOT LIKE 'personal:%' AND p.name NOT LIKE 'client:%')
            OR a.account_kind='USER' THEN NULL ELSE 'legacy_target_requires_explicit_classification' END
    FROM projects p LEFT JOIN admins a ON a.handle=p.personal_owner
        LEFT JOIN authority_accounts i ON i.legacy_handle=a.handle
    WHERE p.id=project AND p.name NOT LIKE 'contained:%'
    ON CONFLICT (project_id) DO UPDATE SET kind=EXCLUDED.kind,account_id=EXCLUDED.account_id,restriction_reason=EXCLUDED.restriction_reason
    WHERE (authority_scopes.kind,authority_scopes.account_id,authority_scopes.restriction_reason)
        IS DISTINCT FROM (EXCLUDED.kind,EXCLUDED.account_id,EXCLUDED.restriction_reason);
$$;


--
-- Name: authority_membership_authority(text, text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.authority_membership_authority(project text, actor text) RETURNS uuid
    LANGUAGE sql STABLE
    AS $$
    SELECT coalesce(authority_project_management_grant(project,actor),
        (SELECT authority_project_management_grant(project,t.owner_handle) FROM service_tokens t
            WHERE t.principal_handle=actor AND service_project_role(project,actor)='MANAGER'));
$$;


--
-- Name: authority_membership_changed(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.authority_membership_changed() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE sid UUID; account UUID; next_role TEXT; root authority_grants;
BEGIN
    SELECT s.id INTO sid FROM authority_scopes s WHERE s.project_id=CASE WHEN TG_OP='DELETE' THEN OLD.project_id ELSE NEW.project_id END AND s.kind NOT IN ('RESTRICTED','CONTAINED');
    SELECT a.id INTO account FROM authority_accounts a WHERE a.legacy_handle=CASE WHEN TG_OP='DELETE' THEN OLD.handle ELSE NEW.handle END;
    IF sid IS NULL OR account IS NULL THEN RETURN NULL; END IF;
    next_role := CASE WHEN TG_OP='DELETE' THEN NULL WHEN NEW.role='VIEWER' THEN 'READER' ELSE NEW.role END;
    IF next_role IS NOT NULL AND EXISTS(SELECT 1 FROM authority_grants g JOIN authority_roles r ON r.id=g.role_id
        WHERE g.scope_id=sid AND g.subject_account=account AND g.provenance='MEMBERSHIP' AND g.revoked_at IS NULL AND r.name=next_role) THEN RETURN NULL; END IF;
    SELECT * INTO root FROM authority_grants WHERE provenance='ROOT_POLICY';
    UPDATE authority_grants SET revoked_at=now(),version=version+1,revoked_by=root.subject_account,revoking_grant=root.id WHERE scope_id=sid AND subject_account=account AND provenance='MEMBERSHIP' AND revoked_at IS NULL;
    IF next_role IS NOT NULL THEN
        SELECT * INTO root FROM authority_grants WHERE provenance='ROOT_POLICY';
        INSERT INTO authority_grants(subject_account,scope_id,role_id,requested_role,issuer_account,issuing_grant,provenance)
        SELECT account,sid,r.id,next_role,root.subject_account,root.id,'MEMBERSHIP' FROM authority_roles r WHERE r.name=next_role;
    END IF;
    RETURN NULL;
END $$;


--
-- Name: authority_no_builtin_acting(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.authority_no_builtin_acting() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM admins WHERE handle IN (NEW.actor,NEW.subject) AND account_kind='BUILTIN') THEN
        RAISE EXCEPTION 'Built-in accounts cannot be selected for acting';
    END IF;
    RETURN NEW;
END $$;


--
-- Name: authority_no_builtin_session(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.authority_no_builtin_session() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM admins WHERE handle=NEW.handle AND account_kind='BUILTIN') THEN
        RAISE EXCEPTION 'Built-in accounts cannot have login sessions';
    END IF;
    RETURN NEW;
END $$;


--
-- Name: authority_no_builtin_token(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.authority_no_builtin_token() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM admins WHERE handle IN (NEW.owner_handle,NEW.principal_handle) AND account_kind='BUILTIN') THEN
        RAISE EXCEPTION 'Built-in accounts cannot issue or hold service credentials';
    END IF;
    RETURN NEW;
END $$;


--
-- Name: authority_project_inserted(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.authority_project_inserted() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN PERFORM authority_map_project(NEW.id); RETURN NEW; END $$;


--
-- Name: authority_project_lifecycle(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.authority_project_lifecycle() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.personal_owner IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM admins WHERE handle=NEW.personal_owner AND account_kind='USER') THEN
        RAISE EXCEPTION 'Only USER accounts have Personal projects';
    END IF;
    RETURN NEW;
END $$;


--
-- Name: authority_project_management_grant(text, text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.authority_project_management_grant(project text, account text) RETURNS uuid
    LANGUAGE sql STABLE
    AS $$
    SELECT g.id FROM authority_grants g JOIN authority_live_grants() l ON l.id=g.id
    JOIN authority_accounts a ON a.id=g.subject_account AND a.legacy_handle=account
    JOIN authority_roles r ON r.id=g.role_id JOIN authority_scopes s ON s.id=g.scope_id
    JOIN authority_scopes target ON target.project_id=(SELECT id FROM projects WHERE name=project)
    WHERE g.subject_agent IS NULL AND ((g.scope_id=target.id AND r.name IN ('MANAGER','ADMIN'))
        OR (g.coverage='ORDINARY_PROJECT_CONTROL' AND target.kind='PROJECT' AND r.name='ADMIN'))
    ORDER BY (r.name='ADMIN') DESC,g.created_at,g.id LIMIT 1;
$$;


--
-- Name: authority_project_manager_count(uuid); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.authority_project_manager_count(scope uuid) RETURNS bigint
    LANGUAGE sql STABLE
    AS $$
    SELECT count(*) FROM authority_grants g JOIN authority_live_grants() l ON l.id=g.id
    JOIN authority_roles r ON r.id=g.role_id
    WHERE g.scope_id=scope AND r.name='MANAGER' AND g.subject_agent IS NULL;
$$;


--
-- Name: authority_project_role(text, text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.authority_project_role(project text, account text) RETURNS text
    LANGUAGE sql STABLE
    AS $$
    SELECT CASE WHEN bool_or(r.name='MANAGER') THEN 'MANAGER' WHEN bool_or(r.name='CONTRIBUTOR') THEN 'CONTRIBUTOR'
        WHEN bool_or(r.name='READER') THEN 'VIEWER' END
    FROM authority_grants g JOIN authority_live_grants() l ON l.id=g.id
    JOIN authority_accounts a ON a.id=g.subject_account AND a.legacy_handle=account
    JOIN authority_scopes s ON s.id=g.scope_id JOIN projects p ON p.id=s.project_id AND p.name=project
    JOIN authority_roles r ON r.id=g.role_id
    WHERE g.subject_agent IS NULL AND g.coverage='SELF'
        AND (s.kind='PROJECT' OR (s.kind='PERSONAL' AND s.account_id=a.id));
$$;


--
-- Name: authority_refresh_admin(uuid); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.authority_refresh_admin(account uuid) RETURNS void
    LANGUAGE plpgsql
    AS $$
DECLARE root authority_grants;
BEGIN
    UPDATE authority_grants SET revoked_at=now(),version=version+1 WHERE subject_account=account
        AND provenance='ADMIN_CONTROL' AND revoked_at IS NULL;
    IF NOT EXISTS(SELECT 1 FROM authority_accounts a JOIN admins u ON u.handle=a.legacy_handle
        WHERE a.id=account AND u.server_admin AND NOT u.bootstrap) THEN
        UPDATE authority_grants SET revoked_at=now(),version=version+1 WHERE subject_account=account
            AND provenance='LEGACY_ADMIN_DATA' AND revoked_at IS NULL;
    END IF;
    IF EXISTS(SELECT 1 FROM authority_accounts a JOIN admins u ON u.handle=a.legacy_handle
        WHERE a.id=account AND u.account_kind='USER' AND u.server_admin AND NOT u.bootstrap) THEN
        SELECT * INTO root FROM authority_grants WHERE provenance='ROOT_POLICY';
        INSERT INTO authority_grants(subject_account,scope_id,role_id,requested_role,coverage,issuer_account,issuing_grant,provenance)
        SELECT account,s.id,r.id,'ADMIN',CASE WHEN s.kind='ADMINISTRATION' THEN 'ORDINARY_PROJECT_CONTROL' ELSE 'SELF' END,
            root.subject_account,root.id,'ADMIN_CONTROL'
        FROM authority_scopes s CROSS JOIN authority_roles r
        WHERE r.name='ADMIN' AND (s.kind='ADMINISTRATION' OR s.builtin='GLOBAL_KNOWLEDGE');
    END IF;
END $$;


--
-- Name: authority_reserved_id(uuid); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.authority_reserved_id(id uuid) RETURNS boolean
    LANGUAGE sql IMMUTABLE
    AS $$
    SELECT id IN ('00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002',
        '00000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-000000000004',
        '00000000-0000-0000-0000-000000000005','00000000-0000-0000-0000-000000000006',
        '00000000-0000-0000-0000-000000000007','00000000-0000-0000-0000-000000000008',
        '00000000-0000-0000-0000-000000000009','00000000-0000-0000-0000-00000000000a');
$$;


--
-- Name: authority_role_locked(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.authority_role_locked() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN RAISE EXCEPTION 'Built-in role identities are immutable; SYSTEM is intrinsic and non-assignable'; END $$;


--
-- Name: authority_scope_classified(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.authority_scope_classified() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.kind='PERSONAL' AND OLD.kind='RESTRICTED' THEN
        UPDATE project_members SET role=role WHERE project_id=NEW.project_id
            AND handle=(SELECT legacy_handle FROM authority_accounts WHERE id=NEW.account_id);
    END IF;
    RETURN NULL;
END $$;


--
-- Name: authority_scope_container_immutable(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.authority_scope_container_immutable() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF NEW.container IS DISTINCT FROM OLD.container THEN
   RAISE EXCEPTION 'authority scope containment is immutable';
 END IF;
 RETURN NEW;
END $$;


--
-- Name: authority_scope_locked(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.authority_scope_locked() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF OLD.builtin IS NOT NULL THEN RAISE EXCEPTION 'Built-in scope is immutable'; END IF;
    IF TG_OP='UPDATE' THEN
        -- Only the source-project trigger may refresh compatibility metadata. The UUID and
        -- source key remain stable; this is never a standalone scope transfer operation.
        IF pg_trigger_depth()<2 OR OLD.id<>NEW.id OR OLD.project_id IS NULL
            OR OLD.project_id IS DISTINCT FROM NEW.project_id OR NEW.builtin IS NOT NULL THEN
            RAISE EXCEPTION 'Scope identity cannot be reassigned or mutated';
        END IF;
        RETURN NEW;
    END IF;
    RETURN OLD;
END $$;


--
-- Name: authority_scope_shape(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.authority_scope_shape() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.kind='PERSONAL' AND NOT EXISTS (
        SELECT 1 FROM projects p JOIN admins a ON a.handle=p.personal_owner AND a.account_kind='USER'
        JOIN authority_accounts i ON i.legacy_handle=a.handle
        WHERE p.id=NEW.project_id AND i.id=NEW.account_id) THEN
        RAISE EXCEPTION 'Personal scope must identify its exact USER owner and project';
    END IF;
    IF NEW.kind='ACCOUNT_KNOWLEDGE' AND NOT EXISTS (
        SELECT 1 FROM authority_accounts i JOIN admins a ON a.handle=i.legacy_handle
        WHERE i.id=NEW.account_id AND (a.account_kind IN ('USER','SERVICE') OR
            (i.builtin='GLOBAL' AND NEW.builtin='GLOBAL_KNOWLEDGE'))) THEN
        RAISE EXCEPTION 'Account has no knowledge home';
    END IF;
    IF NEW.kind='PROJECT' AND NOT EXISTS (SELECT 1 FROM projects p WHERE p.id=NEW.project_id
        AND p.personal_owner IS NULL AND p.name NOT LIKE 'personal:%' AND p.name NOT LIKE 'client:%'
        AND p.name NOT LIKE 'contained:%') THEN
        RAISE EXCEPTION 'Project scope requires an ordinary project';
    END IF;
    IF NEW.kind='CONTAINED' THEN
        IF NOT EXISTS (SELECT 1 FROM projects p WHERE p.id=NEW.project_id
            AND p.personal_owner IS NULL AND p.name LIKE 'contained:%') THEN
            RAISE EXCEPTION 'Contained scope requires a contained project';
        END IF;
        IF NEW.container<>NEW.id AND NOT EXISTS (
            SELECT 1 FROM authority_scopes s WHERE s.id=NEW.container) THEN
            RAISE EXCEPTION 'Contained scope requires an existing container';
        END IF;
    END IF;
    RETURN NEW;
END $$;


--
-- Name: authority_standard_role_locator(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.authority_standard_role_locator() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF lower(NEW.handle) IN ('00000000-0000-0000-0000-000000000007','00000000-0000-0000-0000-000000000008',
        '00000000-0000-0000-0000-000000000009','00000000-0000-0000-0000-00000000000a') THEN
        RAISE EXCEPTION 'Reserved role identity cannot be an ordinary account';
    END IF;
    RETURN NEW;
END $$;


--
-- Name: contained_application_agrees(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.contained_application_agrees() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM authority_scopes s
        WHERE s.id = NEW.scope_id AND s.kind = 'CONTAINED'
          AND s.container = NEW.container AND s.project_id = NEW.project_id
    ) THEN
        RAISE EXCEPTION 'contained_applications row disagrees with its authority scope';
    END IF;
    RETURN NEW;
END;
$$;


--
-- Name: contained_knowledge_actor(text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.contained_knowledge_actor(actor text) RETURNS boolean
    LANGUAGE sql STABLE
    AS $_$
 SELECT CASE WHEN $1 ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
  THEN EXISTS(SELECT 1 FROM contained_principals c WHERE c.id=$1::uuid) ELSE false END;
$_$;


--
-- Name: contained_memory_actor_allows(text, text, boolean); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.contained_memory_actor_allows(actor text, project text, writing boolean) RETURNS boolean
    LANGUAGE sql STABLE
    AS $_$
 SELECT coalesce(contained_knowledge_actor($1)
   AND NOT EXISTS(SELECT 1 FROM admins u WHERE u.handle=$1)
   AND contained_own_home($1,$2) IS NOT NULL
   -- Redundant with contained_own_home above, which has no answer for a NULL project; restated
   -- for the write site only, where the Global tier's administrator arm is the thing being kept.
   AND (NOT $3 OR $2 IS NOT NULL),false);
$_$;


--
-- Name: contained_own_home(text, text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.contained_own_home(actor text, project text) RETURNS uuid
    LANGUAGE sql STABLE
    AS $_$
 SELECT CASE WHEN contained_knowledge_actor($1) THEN
  (SELECT s.id FROM contained_principals c JOIN authority_scopes s ON s.id=c.scope_id AND s.kind='CONTAINED'
   JOIN projects p ON p.id=s.project_id WHERE c.id=$1::uuid AND p.name=$2) END;
$_$;


--
-- Name: contained_scope_ancestors(uuid); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.contained_scope_ancestors(descendant uuid) RETURNS SETOF uuid
    LANGUAGE sql STABLE
    AS $_$
 WITH RECURSIVE chain(id,distance) AS (
   SELECT s.container,1 FROM authority_scopes s WHERE s.id=$1 AND s.container IS NOT NULL
   UNION ALL
   SELECT s.container,c.distance+1 FROM chain c JOIN authority_scopes s ON s.id=c.id
   WHERE s.container IS NOT NULL AND c.distance<64
 ) SELECT id FROM chain;
$_$;


--
-- Name: contained_scope_in_home(uuid, text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.contained_scope_in_home(scope uuid, home_project text) RETURNS boolean
    LANGUAGE sql STABLE
    AS $_$
 SELECT EXISTS(SELECT 1 FROM authority_scopes s WHERE s.id=$1 AND s.kind='CONTAINED'
   AND EXISTS(SELECT 1 FROM contained_scope_ancestors(s.id) AS chain(id)
     JOIN authority_scopes ancestor ON ancestor.id=chain.id
     JOIN projects ancestor_project ON ancestor_project.id=ancestor.project_id
     WHERE ancestor_project.name=$2));
$_$;


--
-- Name: contained_scope_readable(text, uuid, boolean); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.contained_scope_readable(actor text, scope uuid, general_member boolean) RETURNS boolean
    LANGUAGE sql STABLE
    AS $_$
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
$_$;


--
-- Name: conversation_allowance_identity_immutable(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.conversation_allowance_identity_immutable() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF NEW.budget_id<>OLD.budget_id THEN RAISE EXCEPTION 'Conversation allowance identity is immutable'; END IF;
 RETURN NEW;
END $$;


--
-- Name: digest_dirty(text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.digest_dirty(start_id text) RETURNS void
    LANGUAGE sql
    AS $$
 WITH RECURSIVE ancestors(id) AS (
 SELECT start_id UNION SELECT e.parent_id FROM digest_children e JOIN ancestors a ON e.child_id=a.id
 ) UPDATE digests SET stale_at=CURRENT_TIMESTAMP, revision=revision+1
 WHERE id IN (SELECT id FROM ancestors);
$$;


--
-- Name: digest_fold_written(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.digest_fold_written() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE leaf TEXT := 'dig_f_'||md5(NEW.conversation_id||':'||NEW.ordinal);
DECLARE home BIGINT;
DECLARE lower_turn INT;
BEGIN
 IF NEW.kind <> 'summary' THEN RETURN NEW; END IF;
 SELECT project_id INTO home FROM conversations WHERE id=NEW.conversation_id;
 SELECT COALESCE(max(turn_ordinal),0) INTO lower_turn FROM entries
 WHERE conversation_id=NEW.conversation_id AND kind='summary'
 AND turn_ordinal < NEW.turn_ordinal AND superseded_by IS NULL;
 INSERT INTO digests(id,project_id,depth,summary) VALUES(leaf,home,0,NEW.content);
 INSERT INTO digest_spans VALUES(leaf,NEW.conversation_id,lower_turn,NEW.turn_ordinal,NEW.ordinal);
 RETURN NEW;
END $$;


--
-- Name: digest_memory_changed(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.digest_memory_changed() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE leaf TEXT := 'dig_m_' || NEW.id;
BEGIN
 IF TG_OP='INSERT' THEN
  INSERT INTO digests(id,project_id,depth,summary) VALUES(leaf,NEW.project_id,0,NEW.summary || E'\n' || NEW.scope);
  INSERT INTO digest_memories VALUES(leaf,NEW.id);
 ELSIF ROW(OLD.summary,OLD.scope,OLD.body,OLD.state,OLD.project_id)
       IS DISTINCT FROM ROW(NEW.summary,NEW.scope,NEW.body,NEW.state,NEW.project_id) THEN
  PERFORM digest_dirty(leaf);
 END IF;
 RETURN NEW;
END $$;


--
-- Name: digest_validate_edge(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.digest_validate_edge() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE p digests; c digests;
BEGIN
 SELECT * INTO STRICT p FROM digests WHERE id=NEW.parent_id;
 SELECT * INTO STRICT c FROM digests WHERE id=NEW.child_id;
 IF p.project_id IS DISTINCT FROM c.project_id OR p.depth <= c.depth THEN
  RAISE EXCEPTION 'digest edges must descend within one home';
 END IF;
 RETURN NEW;
END $$;


--
-- Name: eject_trajectory_images(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.eject_trajectory_images() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF OLD.ejected_at IS NULL AND NEW.ejected_at IS NOT NULL THEN
        DELETE FROM trajectory_image_references
          WHERE conversation_id=OLD.conversation_id AND entry_ordinal=OLD.ordinal;
        NEW.tool_observation := NULL;
    END IF;
    RETURN NEW;
END;
$$;


--
-- Name: embedding_record_guard(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.embedding_record_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE source_changed boolean := false;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.embedding_source_revision <> 0 THEN
            RAISE EXCEPTION 'embedding source revisions are maintained by the source trigger' USING ERRCODE = '23514';
        END IF;
        NEW.embedding_source_revision := nextval('embedding_source_revisions');
    ELSE
        -- Only changes to the owning embedding input advance the token. Slot
        -- publication and unrelated metadata must not invalidate the other slot.
        CASE TG_TABLE_NAME
            WHEN 'memories' THEN source_changed := ROW(NEW.summary, NEW.body, NEW.scope) IS DISTINCT FROM ROW(OLD.summary, OLD.body, OLD.scope);
            WHEN 'digests' THEN source_changed := ROW(NEW.summary, NEW.revision, NEW.stale_at) IS DISTINCT FROM ROW(OLD.summary, OLD.revision, OLD.stale_at);
            WHEN 'chunks' THEN source_changed := ROW(NEW.text, NEW.paragraph_id) IS DISTINCT FROM ROW(OLD.text, OLD.paragraph_id);
            WHEN 'documents' THEN source_changed := ROW(NEW.summary, NEW.text_hash) IS DISTINCT FROM ROW(OLD.summary, OLD.text_hash);
            WHEN 'retrieval_passages' THEN source_changed := ROW(NEW.passage, NEW.source_type, NEW.source_id, NEW.position) IS DISTINCT FROM ROW(OLD.passage, OLD.source_type, OLD.source_id, OLD.position);
            ELSE RAISE EXCEPTION 'unsupported embedding source';
        END CASE;
        IF NEW.embedding_source_revision <> OLD.embedding_source_revision THEN
            RAISE EXCEPTION 'embedding source revisions are maintained by the source trigger' USING ERRCODE = '23514';
        END IF;
        IF source_changed THEN
            -- Source writes and replacement publication are separate operations.
            -- Never silently discard an explicitly supplied replacement vector.
            IF ROW(NEW.code_embedding, NEW.code_space_id, NEW.code_source_revision,
                   NEW.prose_embedding, NEW.prose_space_id, NEW.prose_source_revision)
                IS DISTINCT FROM ROW(OLD.code_embedding, OLD.code_space_id, OLD.code_source_revision,
                                     OLD.prose_embedding, OLD.prose_space_id, OLD.prose_source_revision)
                AND (NEW.code_embedding IS NOT NULL OR NEW.code_space_id IS NOT NULL OR NEW.code_source_revision IS NOT NULL
                     OR NEW.prose_embedding IS NOT NULL OR NEW.prose_space_id IS NOT NULL OR NEW.prose_source_revision IS NOT NULL) THEN
                RAISE EXCEPTION 'commit source changes before publishing replacement embeddings' USING ERRCODE = '23514';
            END IF;
            NEW.embedding_source_revision := nextval('embedding_source_revisions');
            NEW.code_embedding := NULL; NEW.code_space_id := NULL; NEW.code_source_revision := NULL;
            NEW.prose_embedding := NULL; NEW.prose_space_id := NULL; NEW.prose_source_revision := NULL;
        END IF;
    END IF;
    -- A removed document summary cannot retain or acquire a summary embedding.
    IF TG_TABLE_NAME = 'documents' THEN
        IF NEW.summary IS NULL AND (NEW.code_embedding IS NOT NULL OR NEW.prose_embedding IS NOT NULL) THEN
            RAISE EXCEPTION 'a document summary is required for its embedding' USING ERRCODE = '23514';
        END IF;
    END IF;
    PERFORM embedding_validate_pair(NEW.code_space_id, NEW.code_embedding, NEW.code_source_revision, NEW.embedding_source_revision);
    PERFORM embedding_validate_pair(NEW.prose_space_id, NEW.prose_embedding, NEW.prose_source_revision, NEW.embedding_source_revision);
    RETURN NEW;
END $$;


--
-- Name: embedding_repair_profiles(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.embedding_repair_profiles() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 INSERT INTO embedding_repair_scans(slot,space_id,store)
 SELECT NEW.slot,p.space_id,s.store FROM
  (SELECT NEW.active_space_id AS space_id UNION SELECT NEW.target_space_id) p
 CROSS JOIN unnest(ARRAY['memories','digests','documents','chunks','retrieval_passages']) s(store)
 WHERE p.space_id IS NOT NULL ON CONFLICT DO NOTHING;
 IF ROW(NEW.target_space_id,NEW.target_mode,NEW.target_distance) IS DISTINCT FROM ROW(OLD.target_space_id,OLD.target_mode,OLD.target_distance) THEN
  UPDATE embedding_repair_scans SET next_at=now() WHERE slot=NEW.slot AND space_id=NEW.target_space_id;
 END IF;
 DELETE FROM embedding_repair_scans WHERE slot=NEW.slot
  AND space_id IS DISTINCT FROM NEW.active_space_id AND space_id IS DISTINCT FROM NEW.target_space_id;
 DELETE FROM embedding_repair_work WHERE slot=NEW.slot
  AND space_id IS DISTINCT FROM NEW.active_space_id AND space_id IS DISTINCT FROM NEW.target_space_id;
 RETURN NULL;
END $$;


--
-- Name: embedding_repair_progress(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.embedding_repair_progress() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE source_kind TEXT := ''; source_position INTEGER := 0; owner_store TEXT;
BEGIN
 owner_store:=replace(replace(TG_TABLE_NAME,'_embedding_staging',''),'_embedding_failures','');
 IF TG_OP='DELETE' THEN
  IF owner_store='retrieval_passages' THEN source_kind:=OLD.source_type; source_position:=OLD.position; END IF;
  UPDATE embedding_repair_work SET available_at=now()
   WHERE slot=OLD.slot AND space_id=OLD.space_id AND store=owner_store
    AND source_type=source_kind AND source_id=OLD.source_id::text AND position=source_position
    AND source_revision=OLD.source_revision;
  RETURN NULL;
 END IF;
 IF owner_store='retrieval_passages' THEN source_kind:=NEW.source_type; source_position:=NEW.position; END IF;
 IF TG_TABLE_NAME LIKE '%_embedding_staging' THEN
  -- Only a rebuilding slot needs an activation wakeup. Ordinary active-space publication must
  -- not turn a steady stream of source edits into repeated recovery scans of unrelated content.
  UPDATE embedding_repair_scans scan SET next_at=least(scan.next_at,now())
   FROM embedding_slots configured WHERE configured.slot=NEW.slot
    AND scan.slot=NEW.slot AND scan.space_id=NEW.space_id
    AND ROW(configured.active_space_id,configured.active_mode,configured.active_distance)
     IS DISTINCT FROM ROW(configured.target_space_id,configured.target_mode,configured.target_distance);
  DELETE FROM embedding_repair_work WHERE slot=NEW.slot AND space_id=NEW.space_id
   AND store=owner_store AND source_type=source_kind AND source_id=NEW.source_id::text
   AND position=source_position AND source_revision=NEW.source_revision;
 ELSE
  -- A failure can arrive before initial queue enumeration (including on an older server in a
  -- rolling upgrade). UPDATE-only loses that wakeup if no key exists yet. The source guard has
  -- already checked its current revision; preserve its exact retry/infinite submission fence.
  INSERT INTO embedding_repair_work(slot,space_id,store,source_type,source_id,position,source_revision,available_at)
   SELECT NEW.slot,NEW.space_id,owner_store,source_kind,NEW.source_id::text,source_position,NEW.source_revision,NEW.retry_at
   FROM embedding_slots configured WHERE configured.slot=NEW.slot
    AND (configured.active_space_id=NEW.space_id OR configured.target_space_id=NEW.space_id)
   ON CONFLICT(slot,space_id,store,source_type,source_id,position) DO UPDATE
    SET source_revision=excluded.source_revision,available_at=excluded.available_at
    WHERE embedding_repair_work.source_revision<=excluded.source_revision;
 END IF;
 RETURN NULL;
END $$;


--
-- Name: embedding_repair_source_changed(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.embedding_repair_source_changed() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE source_key TEXT; source_kind TEXT := ''; source_position INTEGER := 0;
BEGIN
 IF TG_OP='TRUNCATE' THEN
  DELETE FROM embedding_repair_work WHERE store=TG_TABLE_NAME;
  UPDATE embedding_repair_scans SET source_type=NULL,source_id=NULL,position=NULL,enumerated=false,next_at=now()
   WHERE store=TG_TABLE_NAME;
  RETURN NULL;
 END IF;
 IF TG_TABLE_NAME='retrieval_passages' THEN
  IF TG_OP='DELETE' THEN
   source_key:=OLD.source_id; source_kind:=OLD.source_type; source_position:=OLD.position;
  ELSE
   source_key:=NEW.source_id; source_kind:=NEW.source_type; source_position:=NEW.position;
  END IF;
 ELSE
  IF TG_OP='DELETE' THEN source_key:=OLD.id::text; ELSE source_key:=NEW.id::text; END IF;
 END IF;
 IF TG_OP='DELETE' THEN
  DELETE FROM embedding_repair_work WHERE store=TG_TABLE_NAME AND source_type=source_kind
   AND source_id=source_key AND position=source_position;
  RETURN NULL;
 END IF;
 -- Vector publication does not enqueue its own input again. Source revisions are guarded by V115.
 IF TG_OP='UPDATE' AND NEW.embedding_source_revision=OLD.embedding_source_revision THEN RETURN NULL; END IF;
 INSERT INTO embedding_repair_work(slot,space_id,store,source_type,source_id,position,source_revision)
 SELECT p.slot,p.space_id,TG_TABLE_NAME,source_kind,source_key,source_position,NEW.embedding_source_revision
 FROM (SELECT slot,active_space_id AS space_id FROM embedding_slots UNION
       SELECT slot,target_space_id FROM embedding_slots) p WHERE p.space_id IS NOT NULL
 ON CONFLICT(slot,space_id,store,source_type,source_id,position) DO UPDATE
  SET source_revision=excluded.source_revision,available_at=now();
 RETURN NULL;
END $$;


--
-- Name: embedding_slot_version(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.embedding_slot_version() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'DELETE' OR NEW.slot IS DISTINCT FROM OLD.slot THEN
        RAISE EXCEPTION 'embedding slot identities cannot be removed or renamed';
    END IF;
    -- Callers fence configuration changes with WHERE version = expected_version.
    -- Updating pointers advances the version even if the requested model is equal.
    NEW.version := OLD.version + 1;
    RETURN NEW;
END $$;


--
-- Name: embedding_space_identity(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.embedding_space_identity() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE fingerprint text;
BEGIN
    fingerprint := encode(sha256(convert_to(jsonb_build_array(
        NEW.model_id, NEW.model_revision, NEW.dimensions, NEW.query_prefix,
        NEW.document_prefix, NEW.pooling, NEW.normalization, NEW.reduction)::text, 'UTF8')), 'hex');
    IF NEW.id IS NOT NULL AND NEW.id <> fingerprint THEN
        RAISE EXCEPTION 'embedding space identifier does not match its descriptor' USING ERRCODE = '23514';
    END IF;
    NEW.id := fingerprint;
    RETURN NEW;
END $$;


--
-- Name: embedding_space_immutable(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.embedding_space_immutable() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'embedding space descriptors are immutable; register a new space';
END $$;


--
-- Name: embedding_staging_guard(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.embedding_staging_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $_$
DECLARE target text; active text; current_revision bigint; owner_table text;
BEGIN
    -- Lock order for future activation: slot configuration, then source rows.
    -- The target snapshot cannot change while a staging publication commits.
    SELECT target_space_id, active_space_id INTO target, active FROM embedding_slots WHERE slot = NEW.slot FOR SHARE;
    IF NEW.space_id IS DISTINCT FROM target AND NEW.space_id IS DISTINCT FROM active THEN
        RAISE EXCEPTION 'staged embedding does not match the slot active or target space' USING ERRCODE = '23514';
    END IF;
    CASE TG_TABLE_NAME
        WHEN 'memories_embedding_staging' THEN owner_table := 'memories';
        WHEN 'digests_embedding_staging' THEN owner_table := 'digests';
        WHEN 'chunks_embedding_staging' THEN owner_table := 'chunks';
        WHEN 'documents_embedding_staging' THEN owner_table := 'documents';
        WHEN 'retrieval_passages_embedding_staging' THEN owner_table := 'retrieval_passages';
        ELSE RAISE EXCEPTION 'unsupported embedding staging table';
    END CASE;
    IF owner_table = 'retrieval_passages' THEN
        SELECT embedding_source_revision INTO current_revision FROM retrieval_passages
            WHERE source_type = NEW.source_type AND source_id = NEW.source_id AND position = NEW.position FOR SHARE;
    ELSE
        -- Identifiers are selected only from the fixed table allowlist above.
        EXECUTE format('SELECT embedding_source_revision FROM %I WHERE id = $1 FOR SHARE', owner_table)
            INTO current_revision USING NEW.source_id;
    END IF;
    IF current_revision IS NULL THEN
        RAISE EXCEPTION 'embedding source no longer exists' USING ERRCODE = '23503';
    END IF;
    IF owner_table = 'documents' THEN
        IF (SELECT summary IS NULL FROM documents WHERE id = NEW.source_id) THEN
            RAISE EXCEPTION 'a document summary is required for its embedding' USING ERRCODE = '23514';
        END IF;
    END IF;
    PERFORM embedding_validate_pair(NEW.space_id, NEW.embedding, NEW.source_revision, current_revision);
    RETURN NEW;
END $_$;


--
-- Name: embedding_validate_pair(text, public.vector, bigint, bigint); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.embedding_validate_pair(space_id text, value public.vector, source_revision bigint, current_revision bigint) RETURNS void
    LANGUAGE plpgsql
    AS $$
DECLARE descriptor embedding_spaces; magnitude double precision;
BEGIN
    IF space_id IS NULL AND value IS NULL AND source_revision IS NULL THEN RETURN; END IF;
    IF space_id IS NULL OR value IS NULL OR source_revision IS NULL THEN
        RAISE EXCEPTION 'embedding vector, space and source revision must be present together' USING ERRCODE = '23514';
    END IF;
    IF current_revision IS NULL OR source_revision < 0 OR source_revision <> current_revision THEN
        RAISE EXCEPTION 'embedding source revision is stale' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO descriptor FROM embedding_spaces WHERE id = space_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'embedding space is not registered' USING ERRCODE = '23503';
    END IF;
    IF vector_dims(value) <> descriptor.dimensions THEN
        RAISE EXCEPTION 'embedding dimensions do not match the registered space' USING ERRCODE = '23514';
    END IF;
    magnitude := vector_norm(value);
    IF magnitude <= 0 OR (descriptor.normalization = 'l2' AND abs(magnitude - 1) > 0.001) THEN
        RAISE EXCEPTION 'embedding vector violates the space normalization contract' USING ERRCODE = '23514';
    END IF;
END $$;


--
-- Name: execution_allowance_accept(uuid, integer, integer); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.execution_allowance_accept(source uuid, sequence integer, ceiling integer) RETURNS void
    LANGUAGE plpgsql
    AS $$
DECLARE prior execution_allowance_claims; current execution_allowances;
BEGIN
 IF sequence<1 OR ceiling IS NOT NULL AND (ceiling<sequence OR ceiling<1) THEN
  RAISE EXCEPTION 'Invalid inference claim'; END IF;
 INSERT INTO execution_allowances(id,call_limit,spent) VALUES(source,ceiling,0) ON CONFLICT(id) DO NOTHING;
 SELECT * INTO current FROM execution_allowances WHERE id=source FOR UPDATE;
 SELECT * INTO prior FROM execution_allowance_claims WHERE budget_id=source AND ordinal=sequence;
 IF FOUND THEN
  IF prior.call_limit IS DISTINCT FROM ceiling THEN RAISE EXCEPTION 'Claim ceiling changed'; END IF;
 ELSIF current.spent::bigint+1=sequence AND current.call_limit IS NOT DISTINCT FROM ceiling THEN
  UPDATE execution_allowances SET spent=sequence WHERE id=source;
  INSERT INTO execution_allowance_claims VALUES(source,sequence,ceiling);
 ELSE RAISE EXCEPTION 'Unreserved, stale or exhausted inference claim'; END IF;
END $$;


--
-- Name: execution_allowance_claim(uuid, integer, integer); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.execution_allowance_claim(source uuid, reserve integer, expected_spent integer DEFAULT NULL::integer) RETURNS TABLE(ordinal integer, call_limit integer)
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF reserve<0 THEN RAISE EXCEPTION 'Negative reserve'; END IF;
 RETURN QUERY WITH charged AS (
  UPDATE execution_allowances a SET spent=a.spent+1 WHERE a.id=source AND a.spent<2147483647
   AND (a.call_limit IS NULL OR a.spent::bigint+reserve<a.call_limit)
   AND (expected_spent IS NULL OR a.spent=expected_spent)
  RETURNING a.spent,a.call_limit
 ), retained AS (
  INSERT INTO execution_allowance_claims(budget_id,ordinal,call_limit)
  SELECT source,c.spent,c.call_limit FROM charged c RETURNING execution_allowance_claims.ordinal,execution_allowance_claims.call_limit
 ) SELECT r.ordinal,r.call_limit FROM retained r;
 IF NOT FOUND AND EXISTS(SELECT 1 FROM execution_allowances a
   WHERE a.id=source AND a.call_limit IS NULL AND a.spent=2147483647) THEN
  RAISE EXCEPTION 'Model allowance counter exhausted';
 END IF;
END $$;


--
-- Name: execution_allowance_immutable(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.execution_allowance_immutable() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Allowance evidence is retained'; END IF;
 IF NEW.id<>OLD.id OR NEW.created_at<>OLD.created_at OR NEW.spent<OLD.spent
   OR (OLD.call_limit=0 AND NEW.call_limit IS DISTINCT FROM 0 AND NOT EXISTS(
     SELECT 1 FROM information_revisions r WHERE r.processing_budget_id=OLD.id
       AND r.processing_policy='explicit_only'))
   THEN RAISE EXCEPTION 'Allowance cannot be rebound, reset or enabled from no-call'; END IF;
 RETURN NEW;
END $$;


--
-- Name: historical_allowance_immutable(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.historical_allowance_immutable() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF TG_TABLE_NAME='conversations' THEN
  IF (NEW.budget_id,NEW.budget_total,NEW.budget_spent,NEW.budget_lifted,NEW.owns_allowance)
    IS DISTINCT FROM (OLD.budget_id,OLD.budget_total,OLD.budget_spent,OLD.budget_lifted,OLD.owns_allowance)
   THEN RAISE EXCEPTION 'Conversation allowance snapshots are historical'; END IF;
 ELSIF TG_TABLE_NAME='information_revisions' THEN
  IF (NEW.processing_budget_id,NEW.allowance_total,NEW.allowance_spent)
    IS DISTINCT FROM (OLD.processing_budget_id,OLD.allowance_total,OLD.allowance_spent)
   THEN RAISE EXCEPTION 'Preparation allowance snapshots are historical'; END IF;
 ELSIF TG_TABLE_NAME='board_topics' THEN
  IF (NEW.allowance_id,NEW.pot_total,NEW.pot_spent)
    IS DISTINCT FROM (OLD.allowance_id,OLD.pot_total,OLD.pot_spent)
   THEN RAISE EXCEPTION 'Board allowance snapshots are historical'; END IF;
 ELSIF TG_TABLE_NAME='embedding_queries' THEN
  IF NEW.spent IS DISTINCT FROM OLD.spent THEN
   RAISE EXCEPTION 'Query allowance snapshots are historical';
  END IF;
 END IF;
 IF TG_TABLE_NAME='conversations' THEN
 IF OLD.allowance_id IS NOT NULL AND NEW.allowance_id IS DISTINCT FROM OLD.allowance_id THEN
  -- Processing logs are attached once by their owning revision, never rebound by a caller.
  IF NOT EXISTS(SELECT 1 FROM information_revisions r
    WHERE r.processing_log=NEW.id AND r.processing_budget_id=NEW.allowance_id) THEN
   RAISE EXCEPTION 'Conversation allowance cannot be rebound'; END IF;
 END IF;
 END IF;
 RETURN NEW;
END $$;


--
-- Name: information_account_action(text, text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.information_account_action(actor text, action text) RETURNS boolean
    LANGUAGE sql STABLE
    AS $_$
 SELECT coalesce(account_active($1) AND EXISTS(
   SELECT 1 FROM admins u JOIN authority_accounts a ON a.legacy_handle=u.handle
   JOIN authority_grant_records g ON g.subject_account=a.id
   JOIN authority_live_grants() live ON live.id=g.id
   WHERE u.handle=$1 AND u.account_kind='USER' AND NOT u.bootstrap
     AND g.subject_agent IS NULL AND g.scope_id='00000000-0000-0000-0000-000000000005'
     AND g.effective_role='ADMIN' AND g.coverage='SELF' AND $2=ANY(g.actions)),false);
$_$;


--
-- Name: information_adopt_root_policy(uuid, text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.information_adopt_root_policy(revision uuid, account text) RETURNS void
    LANGUAGE plpgsql
    AS $_$
DECLARE resource UUID;
BEGIN
 PERFORM pg_advisory_xact_lock(hashtextextended('information-navigation-control',0));
 IF NOT EXISTS(SELECT 1 FROM information_revisions WHERE id=$1) THEN RETURN; END IF;
 IF NOT account_active($2) OR NOT EXISTS(SELECT 1 FROM admins WHERE handle=$2 AND account_kind='USER' AND NOT bootstrap) THEN
   RAISE insufficient_privilege USING MESSAGE='legacy assignment requires active human operator';
 END IF;
 SELECT q.id INTO resource FROM information_resources q JOIN information_revisions r ON r.resource_id=q.id
   WHERE r.id=$1 AND q.home_scope_id IS NOT NULL FOR UPDATE OF q;
 IF resource IS NULL THEN RAISE EXCEPTION 'legacy assignment has no explicit home'; END IF;
 UPDATE information_resource_policies SET version=version+1,audience='HOME',private_only=false,needs_review=false,
   actor=(SELECT id FROM authority_accounts WHERE legacy_handle=$2),reason='ASSIGNMENT',changed_at=now()
 WHERE resource_id=resource AND audience='QUARANTINED';
END $_$;


--
-- Name: information_advance_tags_version(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.information_advance_tags_version() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    NEW.tags_version = OLD.tags_version + CASE WHEN NEW.tags IS DISTINCT FROM OLD.tags THEN 1 ELSE 0 END;
    RETURN NEW;
END $$;


--
-- Name: information_audience_write_fence(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.information_audience_write_fence() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF (NEW.visibility IS DISTINCT FROM OLD.visibility OR NEW.project_id IS DISTINCT FROM OLD.project_id)
   AND NEW.visibility IN ('personal','project','shared') AND OLD.visibility IN ('personal','project','shared') THEN
   RAISE insufficient_privilege USING MESSAGE='per-revision audience writers are incompatible with current root policy';
 END IF;
 RETURN NEW;
END $$;


--
-- Name: information_content_type_identity_immutable(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.information_content_type_identity_immutable() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF (NEW.id,NEW.namespace,NEW.slug,NEW.label,NEW.description,NEW.created_by,NEW.created_at)
       IS DISTINCT FROM (OLD.id,OLD.namespace,OLD.slug,OLD.label,OLD.description,OLD.created_by,OLD.created_at) THEN
        RAISE EXCEPTION 'content type identity is immutable';
    END IF;
    RETURN NEW;
END $$;


--
-- Name: information_content_type_version_immutable(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.information_content_type_version_immutable() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.type_id IS DISTINCT FROM OLD.type_id
       OR NEW.ordinal IS DISTINCT FROM OLD.ordinal OR NEW.schema_definition IS DISTINCT FROM OLD.schema_definition
       OR NEW.presentation IS DISTINCT FROM OLD.presentation OR NEW.profile IS DISTINCT FROM OLD.profile
       OR NEW.definition_hash IS DISTINCT FROM OLD.definition_hash OR NEW.created_by IS DISTINCT FROM OLD.created_by
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'content type definition is immutable';
    END IF;
    IF NEW.state <> OLD.state AND NOT ((OLD.state='draft' AND NEW.state='active') OR (OLD.state='active' AND NEW.state='deprecated')) THEN
        RAISE EXCEPTION 'invalid content type lifecycle transition';
    END IF;
    RETURN NEW;
END $$;


--
-- Name: information_digest_safe(text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.information_digest_safe(root text) RETURNS boolean
    LANGUAGE sql STABLE
    AS $$
 WITH RECURSIVE tree(id) AS (SELECT root UNION SELECT e.child_id FROM digest_children e JOIN tree t ON e.parent_id=t.id)
 SELECT NOT EXISTS(SELECT 1 FROM tree t JOIN digest_spans s ON s.digest_id=t.id
      WHERE NOT information_log_readable(s.conversation_id,NULL))
 AND NOT EXISTS(SELECT 1 FROM tree t JOIN digest_memories m ON m.digest_id=t.id
      WHERE NOT information_memory_safe(m.memory_id));
$$;


--
-- Name: information_document_type_immutable(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.information_document_type_immutable() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.document_type IS DISTINCT FROM OLD.document_type OR NEW.document_subtype IS DISTINCT FROM OLD.document_subtype THEN
        RAISE EXCEPTION 'information revision document type is immutable';
    END IF;
    RETURN NEW;
END $$;


--
-- Name: information_global_read_member(text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.information_global_read_member(account text) RETURNS boolean
    LANGUAGE sql STABLE
    AS $_$
 SELECT account_active($1) AND EXISTS(SELECT 1 FROM admins u JOIN authority_accounts a ON a.legacy_handle=u.handle
   CROSS JOIN authority_roles r JOIN authority_role_actions action ON action.role_id=r.id
   WHERE u.handle=$1 AND NOT u.bootstrap AND a.builtin IS NULL AND r.name='GLOBAL_READ'
      AND action.action='READ' AND action.role_version=1 AND action.profile='DEFAULT');
$_$;


--
-- Name: information_home(bigint, text, text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.information_home(project bigint, namespace text, custodian text) RETURNS uuid
    LANGUAGE sql STABLE
    AS $_$
 SELECT CASE WHEN $1 IS NULL AND $2='account:00000000-0000-0000-0000-000000000004'
   AND $3=(SELECT legacy_handle FROM authority_accounts WHERE id='00000000-0000-0000-0000-000000000004')
 THEN '00000000-0000-0000-0000-000000000005'::uuid ELSE information_pre_account_home($1,$2,$3) END;
$_$;


--
-- Name: information_home_allows(uuid, text, text, text, text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.information_home_allows(resource uuid, account text, selected_scope text, selected_project text, action text) RETURNS boolean
    LANGUAGE sql STABLE
    AS $_$
 SELECT CASE WHEN contained_knowledge_actor($2) THEN coalesce($3='project' AND $5='WRITE'
   AND EXISTS(SELECT 1 FROM information_resources q WHERE q.id=$1
     AND q.home_scope_id=contained_own_home($2,$4)),false)
 WHEN $3='account' THEN coalesce($4 IS NULL AND information_account_action($2,$5)
   AND $5 IN ('READ','WRITE','SET_AUDIENCE') AND EXISTS(SELECT 1 FROM information_resources
     WHERE id=$1 AND home_scope_id='00000000-0000-0000-0000-000000000005'),false)
 ELSE information_pre_account_home_allows($1,$2,$3,$4,$5) END;
$_$;


--
-- Name: information_immutable_evidence(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.information_immutable_evidence() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF NEW IS DISTINCT FROM OLD THEN
   IF NEW.quote='[deleted]' AND (SELECT availability FROM information_revisions WHERE id=OLD.revision_id)='deleted'
      AND (to_jsonb(NEW)-'quote')=(to_jsonb(OLD)-'quote') THEN RETURN NEW; END IF;
   RAISE EXCEPTION 'retained evidence is immutable';
 END IF;
 RETURN NEW;
END;
$$;


--
-- Name: information_import_origin_immutable(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.information_import_origin_immutable() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP='DELETE' OR NOT (
        NEW.revision_id=OLD.revision_id AND NOT OLD.redacted AND NEW.redacted
        AND NEW.origin='{}'::jsonb
        AND (SELECT availability FROM information_revisions WHERE id=OLD.revision_id)='deleted'
    ) THEN RAISE EXCEPTION 'import origins are immutable'; END IF;
    RETURN NEW;
END $$;


--
-- Name: information_import_origin_redact(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.information_import_origin_redact() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.availability='deleted' AND OLD.availability<>'deleted' THEN
        UPDATE information_import_origins SET origin='{}'::jsonb,redacted=TRUE WHERE revision_id=NEW.id AND NOT redacted;
    END IF;
    RETURN NEW;
END $$;


--
-- Name: information_initial_root_policy(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.information_initial_root_policy() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 INSERT INTO information_resource_policies(resource_id,version,audience,privacy_principal,reason)
 SELECT NEW.id,1,CASE WHEN NEW.home_scope_id IS NULL THEN 'QUARANTINED'
     WHEN s.builtin='GLOBAL_KNOWLEDGE' THEN 'GLOBAL_READ' ELSE 'HOME' END,a.id,'INITIAL'
 FROM (SELECT 1) seed LEFT JOIN authority_scopes s ON s.id=NEW.home_scope_id
 LEFT JOIN authority_accounts a ON a.legacy_handle=NEW.owner_handle;
 RETURN NEW;
END $$;


--
-- Name: information_input_immutable(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.information_input_immutable() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'information input dependencies are immutable';
END;
$$;


--
-- Name: information_knowledge_allows(uuid, text, text, text, boolean, text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.information_knowledge_allows(revision uuid, account text, selected_scope text, selected_project text, include_shared boolean, action text) RETURNS boolean
    LANGUAGE sql STABLE
    AS $_$
 SELECT coalesce((information_home_allows(q.id,$2,$3,$4,$6)
     OR ($6='SET_AUDIENCE' AND $3='global_read' AND s.builtin='GLOBAL_KNOWLEDGE' AND information_scope_control($2,s.id)))
   AND policy.audience<>'QUARANTINED' AND information_privacy_allows(q.id,$2)
   AND knowledge_dependencies_readable(r.id,$2),false)
 FROM information_revisions r JOIN information_resources q ON q.id=r.resource_id
 JOIN authority_scopes s ON s.id=q.home_scope_id
 JOIN information_resource_policies policy ON policy.resource_id=q.id WHERE r.id=$1;
$_$;


--
-- Name: information_knowledge_identity(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.information_knowledge_identity() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE expected UUID;
BEGIN
    IF TG_TABLE_NAME='information_resources' THEN
        expected:=information_home(NEW.project_id,NEW.namespace,NEW.owner_handle);
        IF TG_OP='UPDATE' AND (NEW.created_by IS DISTINCT FROM OLD.created_by
            OR NEW.creator_evidence IS DISTINCT FROM OLD.creator_evidence) THEN
            RAISE EXCEPTION 'knowledge creator provenance is immutable';
        END IF;
        -- Existing administrative legacy assignment is the sole supported home change. There is
        -- no general move API; collection links do not modify these fields.
        IF TG_OP='UPDATE' AND OLD.home_scope_id IS NOT NULL AND
            (NEW.home_scope_id IS DISTINCT FROM OLD.home_scope_id
             OR NEW.project_id IS DISTINCT FROM OLD.project_id
             OR NEW.namespace IS DISTINCT FROM OLD.namespace
             OR NEW.owner_handle IS DISTINCT FROM OLD.owner_handle) THEN
            RAISE EXCEPTION 'knowledge home transfer is not supported';
        END IF;
        -- A project rename changes its locator, not its scope or resource namespace history.
        IF TG_OP='UPDATE' AND OLD.home_scope_id IS NOT NULL AND NEW.project_id IS NOT NULL
            AND EXISTS(SELECT 1 FROM authority_scopes WHERE id=OLD.home_scope_id AND project_id=NEW.project_id) THEN
            expected:=OLD.home_scope_id;
        END IF;
        IF NEW.home_scope_id IS NULL THEN NEW.home_scope_id:=expected; END IF;
        IF NEW.home_scope_id IS DISTINCT FROM expected THEN
            RAISE EXCEPTION 'knowledge home does not match its compatibility mapping';
        END IF;
    ELSIF TG_OP='UPDATE' AND (NEW.created_by IS DISTINCT FROM OLD.created_by
        OR NEW.creator_evidence IS DISTINCT FROM OLD.creator_evidence) THEN
        RAISE EXCEPTION 'revision creator provenance is immutable';
    END IF;
    RETURN NEW;
END $$;


--
-- Name: information_lock_destination(text, text, text, boolean); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.information_lock_destination(account text, selected_scope text, selected_project text, include_shared boolean) RETURNS boolean
    LANGUAGE plpgsql
    AS $_$
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
END $_$;


--
-- Name: information_lock_knowledge(uuid, text, text, text, boolean, text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.information_lock_knowledge(revision uuid, account text, selected_scope text, selected_project text, include_shared boolean, action text) RETURNS boolean
    LANGUAGE plpgsql
    AS $_$
DECLARE resource UUID; home UUID;
BEGIN
    PERFORM pg_advisory_xact_lock_shared(hashtextextended('information-navigation-control',0));
    SELECT q.id,q.home_scope_id INTO resource,home FROM information_revisions r
        JOIN information_resources q ON q.id=r.resource_id WHERE r.id=$1;
    IF home IS NULL THEN RETURN false; END IF;
    -- Match membership/grant mutation order, then lock resource. Recheck against a fresh command
    -- snapshot after waiting; no remote/model work runs in this transaction.
    PERFORM information_lock_scope($2,home);
    PERFORM id FROM information_resources WHERE id=resource FOR UPDATE;
    IF NOT coalesce(information_knowledge_allows($1,$2,$3,$4,$5,$6),false) THEN RETURN false; END IF;
    IF $6='SET_AUDIENCE' THEN
        -- Transaction-local marker fences pre-cutover owner-based audience writers. It records
        -- this exact admitted revision/actor, never an ambient or client-supplied permission.
        PERFORM set_config('plowshare.knowledge_audience_actor',$2,true);
        PERFORM set_config('plowshare.knowledge_audience_revision',$1::text,true);
    END IF;
    RETURN true;
END $_$;


--
-- Name: information_lock_scope(text, uuid); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.information_lock_scope(account text, scope uuid) RETURNS void
    LANGUAGE plpgsql
    AS $_$
DECLARE grant_id UUID;
BEGIN
    PERFORM id FROM projects WHERE id=(SELECT project_id FROM authority_scopes WHERE id=$2) FOR SHARE;
    PERFORM id FROM authority_scopes WHERE id=$2 FOR SHARE;
    PERFORM u.handle FROM admins u WHERE u.handle=$1 OR u.handle=(SELECT owner_handle FROM service_tokens WHERE principal_handle=$1)
        OR u.handle IN (
            WITH RECURSIVE chain AS (
                SELECT g.id,g.subject_account,g.issuing_grant,g.issuing_token,0 AS depth
                FROM authority_grants g JOIN authority_live_grants() live ON live.id=g.id JOIN authority_accounts a ON a.id=g.subject_account
                WHERE g.scope_id=$2 AND g.subject_agent IS NULL
                    AND (a.legacy_handle=$1 OR a.legacy_handle=(SELECT owner_handle FROM service_tokens WHERE principal_handle=$1))
                UNION
                SELECT g.id,g.subject_account,g.issuing_grant,g.issuing_token,c.depth+1
                FROM authority_grants g JOIN chain c ON c.issuing_grant=g.id WHERE c.depth<32
            ) SELECT a.legacy_handle FROM chain c JOIN authority_accounts a ON a.id=c.subject_account
        ) ORDER BY u.handle FOR SHARE;
    PERFORM id FROM service_tokens WHERE principal_handle=$1 FOR SHARE;
    FOR grant_id IN SELECT g.id FROM authority_grants g JOIN authority_live_grants() live ON live.id=g.id JOIN authority_accounts a ON a.id=g.subject_account
        WHERE g.subject_agent IS NULL AND g.scope_id=$2
          AND (a.legacy_handle=$1 OR a.legacy_handle=(SELECT owner_handle FROM service_tokens WHERE principal_handle=$1))
        ORDER BY g.id
    LOOP
        PERFORM authority_lock_lineage(grant_id);
    END LOOP;
END $_$;


--
-- Name: information_log_readable(text, text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.information_log_readable(target text, account text) RETURNS boolean
    LANGUAGE sql STABLE
    AS $_$
 WITH RECURSIVE ancestors(id) AS (
  SELECT $1 UNION SELECT e.parent FROM ancestors a JOIN (
   SELECT id AS child,parent_id AS parent FROM conversations WHERE parent_id IS NOT NULL
   UNION ALL SELECT conductor_conversation,caller_conversation FROM orchestrations WHERE caller_conversation IS NOT NULL
   UNION ALL SELECT o.conductor_conversation,p.conductor_conversation FROM orchestrations o JOIN orchestrations p ON p.id=o.parent
   UNION ALL SELECT id,conductor_conversation FROM orchestrations
  ) e ON e.child=a.id
 ),
 inputs AS MATERIALIZED (SELECT DISTINCT i.owner_handle,i.revision_id,i.scope,i.project_name,i.include_shared
  FROM ancestors a JOIN information_job_inputs i ON i.job_id=a.id),
 admitted AS MATERIALIZED (SELECT * FROM information_readable_input_selections($2,
  ARRAY(SELECT DISTINCT revision_id FROM inputs)))
 SELECT NOT EXISTS(SELECT 1 FROM ancestors a JOIN information_quarantined_payloads q ON q.payload_id=a.id)
 AND NOT EXISTS(SELECT 1 FROM inputs i LEFT JOIN admitted a ON a.revision=i.revision_id AND a.scope=i.scope
  AND a.project_name IS NOT DISTINCT FROM i.project_name AND a.include_shared=i.include_shared
  WHERE $2 IS NULL OR $2<>i.owner_handle OR NOT coalesce(a.readable,false));
$_$;


--
-- Name: information_memory_safe(text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.information_memory_safe(memory text) RETURNS boolean
    LANGUAGE sql STABLE
    AS $$
 SELECT NOT EXISTS(SELECT 1 FROM memory_provenance p WHERE p.memory_id=memory
     AND NOT information_log_readable(p.conversation_id,NULL));
$$;


--
-- Name: information_namespace_home(text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.information_namespace_home(namespace text) RETURNS uuid
    LANGUAGE sql STABLE
    AS $_$
 SELECT CASE WHEN $1='account:00000000-0000-0000-0000-000000000004'
 THEN '00000000-0000-0000-0000-000000000005'::uuid ELSE information_pre_account_namespace_home($1) END;
$_$;


--
-- Name: information_navigation_head(uuid); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.information_navigation_head(resource uuid) RETURNS uuid
    LANGUAGE sql STABLE
    AS $$
 SELECT id FROM information_revisions WHERE resource_id=resource ORDER BY ordinal DESC LIMIT 1;
$$;


--
-- Name: information_navigation_obligations(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.information_navigation_obligations() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE changed INTEGER; rounds INTEGER := 0;
BEGIN
 IF TG_TABLE_NAME='information_relationships' THEN
   IF NEW.pinned_revision IS NOT NULL AND NOT EXISTS(SELECT 1 FROM information_revisions
       WHERE id=NEW.pinned_revision AND resource_id=NEW.target) THEN
     RAISE EXCEPTION 'pinned revision must belong to relationship target';
   END IF;
 END IF;
 -- A non-propagating intermediary cannot launder inherited restrictions. Reach a fixed point over
 -- current incoming references whenever an obligation is added or a target policy tightens.
 LOOP
   INSERT INTO information_reference_obligations(relationship,source,target,pinned_revision)
     SELECT e.id,e.source,e.target,e.pinned_revision FROM information_relationships e
     WHERE NOT e.removed AND (
       coalesce((information_navigation_policy(e.target)->>'propagate')::boolean,true)
       OR EXISTS(SELECT 1 FROM information_reference_obligations inherited WHERE inherited.source=e.target))
     ON CONFLICT DO NOTHING;
   GET DIAGNOSTICS changed = ROW_COUNT;
   EXIT WHEN changed=0;
   rounds := rounds+1;
   IF rounds>1024 THEN RAISE EXCEPTION 'reference propagation exceeds closure bound'; END IF;
 END LOOP;
 RETURN NEW;
END $$;


--
-- Name: information_navigation_own_readable(uuid, text, text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.information_navigation_own_readable(revision uuid, account text, selected_project text) RETURNS boolean
    LANGUAGE sql STABLE
    AS $_$
 SELECT knowledge_dependencies_readable($1,$2);
$_$;


--
-- Name: information_navigation_policy(uuid); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.information_navigation_policy(resource uuid) RETURNS jsonb
    LANGUAGE sql STABLE
    AS $$
 WITH settings AS (
   SELECT q.id, global.settings AS g, project.settings AS p, document.settings AS d
   FROM information_resources q
   LEFT JOIN information_navigation_policies global ON global.policy_key='platform'
   LEFT JOIN information_navigation_policies project ON project.policy_key='project:'||q.project_id::text
   LEFT JOIN information_navigation_policies document ON document.policy_key='document:'||q.id::text
   WHERE q.id=resource
 ), chosen AS (
   SELECT *, coalesce(g,'{"defaults":{"ownerOnly":false,"referenceHidden":true,"propagate":true},"mandatory":{"ownerOnly":false,"referenceHidden":false,"propagate":false},"allowOverrides":true,"referenceLabel":"Restricted document"}'::jsonb) AS platform,
     coalesce(p,g,'{"defaults":{"ownerOnly":false,"referenceHidden":true,"propagate":true},"mandatory":{"ownerOnly":false,"referenceHidden":false,"propagate":false},"allowOverrides":true,"referenceLabel":"Restricted document"}'::jsonb) AS project
   FROM settings
 ), effective AS (
   SELECT *, CASE WHEN coalesce((platform->>'allowOverrides')::boolean,false)
         AND coalesce((project->>'allowOverrides')::boolean,false) THEN coalesce(d,project) ELSE project END AS selected
   FROM chosen
 )
 SELECT jsonb_build_object(
   'ownerOnly', coalesce((selected->'defaults'->>'ownerOnly')::boolean,true) OR coalesce((selected->'mandatory'->>'ownerOnly')::boolean,true)
      OR coalesce((platform->'mandatory'->>'ownerOnly')::boolean,true) OR coalesce((project->'mandatory'->>'ownerOnly')::boolean,true),
   'referenceHidden', coalesce((selected->'defaults'->>'referenceHidden')::boolean,true) OR coalesce((selected->'mandatory'->>'referenceHidden')::boolean,true)
      OR coalesce((platform->'mandatory'->>'referenceHidden')::boolean,true) OR coalesce((project->'mandatory'->>'referenceHidden')::boolean,true),
   'propagate', coalesce((selected->'defaults'->>'propagate')::boolean,true) OR coalesce((selected->'mandatory'->>'propagate')::boolean,true)
      OR coalesce((platform->'mandatory'->>'propagate')::boolean,true) OR coalesce((project->'mandatory'->>'propagate')::boolean,true),
   'referenceLabel', selected->>'referenceLabel') FROM effective;
$$;


--
-- Name: information_navigation_reference_visible(uuid, text, text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.information_navigation_reference_visible(resource uuid, account text, selected_project text) RETURNS boolean
    LANGUAGE sql STABLE
    AS $_$
 SELECT EXISTS(SELECT 1 FROM information_resources q JOIN information_revisions r ON r.id=information_navigation_head(q.id)
   JOIN information_resource_policies policy ON policy.resource_id=q.id
   JOIN authority_scopes s ON s.id=q.home_scope_id
   WHERE q.id=$1
   AND (information_global_read_member($2)
     OR (contained_knowledge_actor($2) AND knowledge_scope_readable($2,q.home_scope_id)))
   AND NOT r.excluded
   AND r.availability='active' AND policy.audience<>'QUARANTINED' AND s.kind<>'RESTRICTED'
   AND NOT coalesce((information_navigation_policy(q.id)->>'referenceHidden')::boolean,true));
$_$;


--
-- Name: information_pre_account_home(bigint, text, text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.information_pre_account_home(project bigint, namespace text, custodian text) RETURNS uuid
    LANGUAGE sql STABLE
    AS $_$
    SELECT s.id FROM authority_scopes s WHERE
      ($1 IS NOT NULL AND s.project_id=$1 AND (($2='legacy' AND $3 IS NOT NULL) OR $2='project:'||(SELECT name FROM projects WHERE id=$1)))
      OR ($1 IS NULL AND s.kind='ACCOUNT_KNOWLEDGE' AND s.account_id=(
          SELECT id FROM authority_accounts WHERE legacy_handle=$3)
          AND $2='account:'||$3);
$_$;


--
-- Name: information_pre_account_home_allows(uuid, text, text, text, text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.information_pre_account_home_allows(resource uuid, account text, selected_scope text, selected_project text, action text) RETURNS boolean
    LANGUAGE sql STABLE
    AS $_$
    SELECT coalesce(account_active($2) AND EXISTS(SELECT 1 FROM admins WHERE handle=$2 AND NOT bootstrap) AND $5 IN ('READ','WRITE','SET_AUDIENCE') AND (
      (s.kind='ACCOUNT_KNOWLEDGE' AND s.account_id=a.id AND q.project_id IS NULL
          AND q.namespace='account:'||$2 AND $3='personal' AND $4 IS NULL)
      -- The same grant under the project spelling of the same place. Every other conjunct is the
      -- one above: the resource is the actor's own account knowledge, and the selected project is
      -- that actor's personal space. Identical authority, so an account keeps READ, WRITE and
      -- SET_AUDIENCE over its own knowledge and gains nothing over anyone else's.
      OR (s.kind='ACCOUNT_KNOWLEDGE' AND s.account_id=a.id AND q.project_id IS NULL
          AND q.namespace='account:'||$2 AND $3='project'
          AND EXISTS(SELECT 1 FROM projects personal
                      WHERE personal.name=$4 AND personal.personal_owner=$2))
      OR (s.kind IN ('PROJECT','PERSONAL') AND s.project_id=q.project_id
          AND p.name=$4 AND $3='project'
          AND CASE $5
            WHEN 'READ' THEN coalesce(authority_project_role(p.name,$2),service_project_role(p.name,$2)) IS NOT NULL
            WHEN 'WRITE' THEN coalesce(authority_project_role(p.name,$2),service_project_role(p.name,$2)) IN ('CONTRIBUTOR','MANAGER')
            WHEN 'SET_AUDIENCE' THEN coalesce(authority_project_role(p.name,$2),service_project_role(p.name,$2))='MANAGER'
          END)),false)
    FROM information_resources q JOIN authority_scopes s ON s.id=q.home_scope_id
    LEFT JOIN projects p ON p.id=s.project_id
    LEFT JOIN authority_accounts a ON a.legacy_handle=$2 WHERE q.id=$1;
$_$;


--
-- Name: information_pre_account_lock_destination(text, text, text, boolean); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.information_pre_account_lock_destination(account text, selected_scope text, selected_project text, include_shared boolean) RETURNS boolean
    LANGUAGE plpgsql
    AS $_$
DECLARE home UUID;
BEGIN
    -- Shared lock excludes policy/reference changes without serializing ordinary content writes.
    PERFORM pg_advisory_xact_lock_shared(hashtextextended('information-navigation-control',0));
    SELECT s.id INTO home FROM authority_scopes s LEFT JOIN projects p ON p.id=s.project_id
        LEFT JOIN authority_accounts a ON a.id=s.account_id WHERE
        ($2='project' AND p.name=$3 AND s.kind IN ('PROJECT','PERSONAL')) OR
        ($2='personal' AND $3 IS NULL AND s.kind='ACCOUNT_KNOWLEDGE' AND a.legacy_handle=$1 AND a.builtin IS NULL);
    IF home IS NULL OR ($1 LIKE '@service/%' AND ($2<>'project' OR $4)) THEN RETURN false; END IF;
    PERFORM information_lock_scope($1,home);
    RETURN coalesce(account_active($1) AND EXISTS(SELECT 1 FROM admins WHERE handle=$1 AND NOT bootstrap) AND CASE WHEN $2='project' THEN
        coalesce(authority_project_role($3,$1),service_project_role($3,$1)) IN ('CONTRIBUTOR','MANAGER')
        ELSE true END,false);
END $_$;


--
-- Name: information_pre_account_namespace_home(text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.information_pre_account_namespace_home(namespace text) RETURNS uuid
    LANGUAGE sql STABLE
    AS $_$
    SELECT s.id FROM authority_scopes s JOIN projects p ON p.id=s.project_id WHERE 'project:'||p.name=$1
    UNION ALL SELECT s.id FROM authority_scopes s JOIN authority_accounts a ON a.id=s.account_id
        WHERE s.kind='ACCOUNT_KNOWLEDGE' AND 'account:'||a.legacy_handle=$1;
$_$;


--
-- Name: information_privacy_allows(uuid, text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.information_privacy_allows(resource uuid, account text) RETURNS boolean
    LANGUAGE sql STABLE
    AS $_$
 SELECT coalesce((NOT p.private_only AND NOT coalesce((information_navigation_policy(q.id)->>'ownerOnly')::boolean,true))
   OR EXISTS(SELECT 1 FROM authority_accounts a WHERE a.id=p.privacy_principal AND a.legacy_handle=$2),false)
 FROM information_resources q JOIN information_resource_policies p ON p.resource_id=q.id WHERE q.id=$1;
$_$;


--
-- Name: information_processing_budget_locked(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.information_processing_budget_locked() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF NEW.allowance_spent<OLD.allowance_spent THEN RAISE EXCEPTION 'Processing spending cannot be reset'; END IF;
 IF NEW.processing_budget_id<>OLD.processing_budget_id THEN RAISE EXCEPTION 'Processing allowance identity is immutable'; END IF;
 RETURN NEW;
END $$;


--
-- Name: information_processing_provenance_immutable(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.information_processing_provenance_immutable() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'processing authorizations and reuse provenance are immutable';
END $$;


--
-- Name: information_project_readable(bigint, text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.information_project_readable(target bigint, account text) RETURNS boolean
    LANGUAGE sql STABLE
    AS $_$
 SELECT EXISTS(SELECT 1 FROM authority_scopes s WHERE s.project_id=$1 AND knowledge_scope_readable($2,s.id));
$_$;


--
-- Name: information_public_dependencies(uuid); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.information_public_dependencies(target uuid) RETURNS boolean
    LANGUAGE sql STABLE
    AS $_$
 WITH RECURSIVE inputs(id) AS (
   SELECT $1 UNION SELECT i.input_revision FROM information_inputs i JOIN inputs p ON i.derived_revision=p.id
 ) SELECT coalesce(bool_and((id=$1 OR coalesce(information_public_policy(id),false))
     AND information_required_references_public(id)),false) FROM inputs;
$_$;


--
-- Name: information_public_policy(uuid); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.information_public_policy(revision uuid) RETURNS boolean
    LANGUAGE sql STABLE
    AS $_$
 SELECT coalesce(p.audience='GLOBAL_READ' AND s.kind<>'RESTRICTED' AND s.kind<>'CONTAINED' AND NOT p.private_only
   AND NOT coalesce((information_navigation_policy(q.id)->>'ownerOnly')::boolean,true)
   AND r.availability='active',false)
 FROM information_revisions r JOIN information_resources q ON q.id=r.resource_id
 JOIN authority_scopes s ON s.id=q.home_scope_id
 JOIN information_resource_policies p ON p.resource_id=q.id WHERE r.id=$1;
$_$;


--
-- Name: information_readable(uuid, text, text, text, boolean); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.information_readable(target uuid, account text, selected_scope text, selected_project text, include_shared boolean) RETURNS boolean
    LANGUAGE sql STABLE
    AS $_$
 SELECT coalesce(information_root_readable($1,$2,$3,$4,$5),false) AND knowledge_dependencies_readable($1,$2);
$_$;


--
-- Name: information_readable_input_selections(text, uuid[]); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.information_readable_input_selections(actor text, roots uuid[]) RETURNS TABLE(revision uuid, scope text, project_name text, include_shared boolean, readable boolean)
    LANGUAGE sql STABLE
    AS $_$
 WITH selections AS MATERIALIZED (
  SELECT DISTINCT i.revision_id,i.scope,i.project_name,i.include_shared
  FROM information_job_inputs i WHERE i.owner_handle=$1 AND ($2 IS NULL OR i.revision_id=ANY($2))
 ),
 authority AS MATERIALIZED (SELECT * FROM knowledge_read_authority($1)),
 actor_fact AS MATERIALIZED (SELECT actor_id,general_member FROM authority LIMIT 1),
 readable_scopes AS MATERIALIZED (SELECT scope_id FROM authority WHERE scope_id IS NOT NULL),
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
$_$;


--
-- Name: information_reference_readable(uuid, text, text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.information_reference_readable(target uuid, account text, selected_project text) RETURNS boolean
    LANGUAGE sql STABLE
    AS $_$ SELECT knowledge_dependencies_readable($1,$2); $_$;


--
-- Name: information_report_immutable(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.information_report_immutable() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF NEW.revision_id IS DISTINCT FROM OLD.revision_id OR NEW.feedback_revision IS DISTINCT FROM OLD.feedback_revision
  OR NEW.produced_by IS DISTINCT FROM OLD.produced_by OR NEW.definition_hash IS DISTINCT FROM OLD.definition_hash THEN
  RAISE EXCEPTION 'report provenance is immutable';
 END IF;
 IF NEW.details IS DISTINCT FROM OLD.details AND NOT (NEW.details='{"deleted":true}'::jsonb
   AND (SELECT availability FROM information_revisions WHERE id=OLD.revision_id)='deleted') THEN
  RAISE EXCEPTION 'report findings are immutable; record a feedback revision';
 END IF;
 RETURN NEW;
END $$;


--
-- Name: information_required_references_public(uuid); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.information_required_references_public(target uuid) RETURNS boolean
    LANGUAGE sql STABLE
    AS $_$
 WITH RECURSIVE required(id) AS (
   SELECT coalesce(o.pinned_revision,information_navigation_head(o.target)) FROM information_reference_obligations o
     JOIN information_revisions r ON r.resource_id=o.source WHERE r.id=$1
   UNION SELECT edge.id FROM required d CROSS JOIN LATERAL (
     SELECT i.input_revision AS id FROM information_inputs i WHERE i.derived_revision=d.id
     UNION SELECT coalesce(o.pinned_revision,information_navigation_head(o.target)) FROM information_reference_obligations o
       JOIN information_revisions r ON r.resource_id=o.source WHERE r.id=d.id) edge
 ), bounded AS (SELECT id FROM required LIMIT 1025)
 SELECT count(*)<=1024 AND coalesce(bool_and(coalesce(information_public_policy(id),false)),true) FROM bounded;
$_$;


--
-- Name: information_required_references_readable(uuid, text, text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.information_required_references_readable(target uuid, account text, selected_project text) RETURNS boolean
    LANGUAGE sql STABLE
    AS $_$
 WITH RECURSIVE required(id) AS (
   -- Qualify the parameter: an unqualified target would bind the relationship column.
   SELECT coalesce(o.pinned_revision,information_navigation_head(o.target))
     FROM information_reference_obligations o JOIN information_revisions r ON r.resource_id=o.source
     WHERE r.id=$1
   UNION
   SELECT edge.id FROM required d CROSS JOIN LATERAL (
     SELECT i.input_revision AS id FROM information_inputs i WHERE i.derived_revision=d.id
     UNION
     SELECT coalesce(o.pinned_revision,information_navigation_head(o.target))
       FROM information_reference_obligations o JOIN information_revisions r ON r.resource_id=o.source
       WHERE r.id=d.id
   ) edge
 ), bounded AS (SELECT id FROM required LIMIT 1025)
 SELECT count(*) <= 1024 AND coalesce(bool_and(
   coalesce(information_navigation_own_readable(b.id,account,selected_project),false)),true)
 FROM bounded b;
$_$;


--
-- Name: information_resource_policy_history_guard(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.information_resource_policy_history_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN RAISE EXCEPTION 'root policy history is immutable'; END $$;


--
-- Name: information_resource_policy_record(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.information_resource_policy_record() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
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


--
-- Name: information_reuse_preparation_immutable(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.information_reuse_preparation_immutable() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP='UPDATE' AND NEW.actor=OLD.actor AND NEW.request_id=OLD.request_id
       AND NEW.payload=OLD.payload-'binding' AND NOT (NEW.payload ? 'binding')
       AND ((SELECT availability FROM information_revisions WHERE id=(OLD.payload->'manifest'->>'revision')::uuid)='deleted'
         OR EXISTS(SELECT 1 FROM information_write_gates g JOIN information_revisions r ON r.id=(g.response->>'revision')::uuid
             WHERE g.account=OLD.actor AND g.request_id=OLD.request_id AND r.availability='deleted')) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'prepared reuse candidate is immutable except deletion redaction';
END $$;


--
-- Name: information_revision_content_contract(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.information_revision_content_contract() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NOT EXISTS(SELECT 1 FROM information_content_type_versions v
        JOIN information_content_types t ON t.id=v.type_id
        JOIN information_revisions r ON r.id=NEW.revision_id
        JOIN information_resources q ON q.id=r.resource_id
        WHERE v.id=NEW.type_version AND v.profile=NEW.profile AND v.definition_hash=NEW.definition_hash
          AND t.namespace=q.namespace AND r.document_type='document' AND v.state IN ('active','deprecated')) THEN
        RAISE EXCEPTION 'revision content contract mismatch';
    END IF;
    RETURN NEW;
END $$;


--
-- Name: information_revision_content_immutable(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.information_revision_content_immutable() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'typed revision bindings cannot be removed'; END IF;
    IF NEW.revision_id IS DISTINCT FROM OLD.revision_id OR NEW.type_version IS DISTINCT FROM OLD.type_version
       OR NEW.profile IS DISTINCT FROM OLD.profile OR NEW.definition_hash IS DISTINCT FROM OLD.definition_hash
       OR NEW.metadata_hash IS DISTINCT FROM OLD.metadata_hash
       OR ((NEW.metadata IS DISTINCT FROM OLD.metadata OR NEW.redacted IS DISTINCT FROM OLD.redacted)
           AND NOT (NOT OLD.redacted AND NEW.redacted AND NEW.metadata='{}'::jsonb
               AND (SELECT availability FROM information_revisions WHERE id=OLD.revision_id)='deleted')) THEN
        RAISE EXCEPTION 'typed revision metadata is immutable';
    END IF;
    RETURN NEW;
END $$;


--
-- Name: information_revision_immutable(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.information_revision_immutable() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.resource_id<>OLD.resource_id OR NEW.ordinal<>OLD.ordinal OR NEW.content_hash<>OLD.content_hash
       OR NEW.byte_size<>OLD.byte_size OR NEW.created_at<>OLD.created_at
       OR NEW.media_type<>OLD.media_type OR NEW.source_uri IS DISTINCT FROM OLD.source_uri
       OR (OLD.converter IS NOT NULL AND NEW.converter IS DISTINCT FROM OLD.converter)
       OR (OLD.text_hash IS NOT NULL AND NEW.text_hash IS DISTINCT FROM OLD.text_hash)
       OR (OLD.extracted_text IS NOT NULL AND NEW.outline_top_level IS DISTINCT FROM OLD.outline_top_level)
       OR (NEW.source_bytes IS DISTINCT FROM OLD.source_bytes AND NOT (NEW.availability='deleted' AND NEW.source_bytes IS NULL))
       OR (OLD.extracted_text IS NOT NULL AND NEW.extracted_text IS DISTINCT FROM OLD.extracted_text
           AND NOT (NEW.availability='deleted' AND NEW.extracted_text IS NULL)) THEN
        RAISE EXCEPTION 'information revision content is immutable';
    END IF;
    RETURN NEW;
END;
$$;


--
-- Name: information_revision_lineage_immutable(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.information_revision_lineage_immutable() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.processing_policy IS DISTINCT FROM OLD.processing_policy
       OR NEW.starting_revision IS DISTINCT FROM OLD.starting_revision
       OR NEW.revised_by IS DISTINCT FROM OLD.revised_by THEN
        RAISE EXCEPTION 'revision processing policy and lineage are immutable';
    END IF;
    RETURN NEW;
END $$;


--
-- Name: information_root_publishable(uuid); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.information_root_publishable(revision uuid) RETURNS boolean
    LANGUAGE sql STABLE
    AS $_$
 SELECT coalesce(p.audience<>'QUARANTINED' AND q.home_scope_id IS NOT NULL AND NOT p.private_only
   AND NOT coalesce((information_navigation_policy(q.id)->>'ownerOnly')::boolean,true)
   AND information_public_dependencies(information_navigation_head(q.id))
   AND information_public_dependencies(r.id),false)
 FROM information_revisions r JOIN information_resources q ON q.id=r.resource_id
 JOIN information_resource_policies p ON p.resource_id=q.id WHERE r.id=$1;
$_$;


--
-- Name: information_root_readable(uuid, text, text, text, boolean); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.information_root_readable(revision uuid, account text, selected_scope text, selected_project text, include_shared boolean) RETURNS boolean
    LANGUAGE sql STABLE
    AS $_$
 SELECT coalesce(knowledge_revision_readable(r.id,$2) AND (
   ($3='global_read' AND $4 IS NULL AND NOT $5 AND knowledge_general_readable(r.id))
   OR ($3='account' AND $4 IS NULL AND NOT $5 AND s.builtin='GLOBAL_KNOWLEDGE')
   OR ($3 IN ('personal','project') AND (
     ($3='personal' AND $4 IS NULL AND s.kind='ACCOUNT_KNOWLEDGE' AND s.builtin IS NULL AND s.account_id=actor.id)
     OR ($3='project' AND p.name=$4 AND knowledge_scope_readable($2,s.id))
     OR ($3='project' AND s.kind='ACCOUNT_KNOWLEDGE' AND s.account_id=actor.id AND EXISTS(
       SELECT 1 FROM information_links l JOIN projects linked ON linked.id=l.project_id
       WHERE l.revision_id=r.id AND linked.name=$4 AND information_project_readable(linked.id,$2)))
     OR ($3='project' AND s.kind='ACCOUNT_KNOWLEDGE' AND s.builtin IS NULL AND s.account_id=actor.id
       AND EXISTS(SELECT 1 FROM projects personal WHERE personal.name=$4 AND personal.personal_owner=$2))
     OR ($5 AND knowledge_general_readable(r.id))))),false)
 FROM information_revisions r JOIN information_resources q ON q.id=r.resource_id
 JOIN authority_scopes s ON s.id=q.home_scope_id LEFT JOIN projects p ON p.id=s.project_id
 LEFT JOIN authority_accounts actor ON actor.legacy_handle=$2 WHERE r.id=$1;
$_$;


--
-- Name: information_scope_control(text, uuid); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.information_scope_control(account text, scope uuid) RETURNS boolean
    LANGUAGE sql STABLE
    AS $_$
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
$_$;


--
-- Name: information_set_root_audience(uuid, text, text, text, boolean, boolean); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.information_set_root_audience(revision uuid, account text, selected_scope text, selected_project text, include_shared boolean, general_read boolean) RETURNS bigint
    LANGUAGE plpgsql
    AS $_$
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
END $_$;


--
-- Name: information_system_processing_revisions(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.information_system_processing_revisions() RETURNS TABLE(revision uuid)
    LANGUAGE sql STABLE
    AS $$
 WITH RECURSIVE live AS MATERIALIZED (SELECT id FROM authority_live_grants()),
 intrinsic AS MATERIALIZED (
  SELECT g.id FROM authority_grants g JOIN live ON live.id=g.id
  JOIN authority_roles role ON role.id=g.role_id
  JOIN authority_role_actions a ON a.role_id=role.id AND a.role_version=g.role_version AND a.profile='DEFAULT'
  WHERE NOT role.assignable AND role.intrinsic_account=g.subject_account
   AND g.coverage='ALL_SCOPES' AND g.subject_agent IS NULL
  GROUP BY g.id HAVING count(*) FILTER(WHERE a.action IN ('READ','WRITE','CONVERSATION_CREATE','RUN_MODEL'))=4
 ),
 retained(root,id) AS (
  SELECT derived_revision,input_revision FROM information_inputs
  UNION SELECT retained.root,i.input_revision FROM retained JOIN information_inputs i ON i.derived_revision=retained.id
 ),
 invalid AS MATERIALIZED (
  SELECT DISTINCT retained.root FROM retained LEFT JOIN information_revisions r ON r.id=retained.id
  LEFT JOIN information_resources q ON q.id=r.resource_id LEFT JOIN authority_scopes scope ON scope.id=q.home_scope_id
  WHERE r.id IS NULL OR scope.id IS NULL OR r.availability<>'active' OR r.excluded
 )
 SELECT r.id FROM information_revisions r JOIN information_resources q ON q.id=r.resource_id
 JOIN authority_scopes home ON home.id=q.home_scope_id LEFT JOIN projects p ON p.id=q.project_id
 WHERE EXISTS(SELECT 1 FROM intrinsic)
 AND EXISTS(SELECT 1 FROM authority_scopes target WHERE
  (p.name IS NULL AND target.builtin='GLOBAL_KNOWLEDGE') OR
  (p.name IS NOT NULL AND target.project_id=p.id))
 AND NOT EXISTS(SELECT 1 FROM invalid WHERE invalid.root=r.id);
$$;


--
-- Name: information_system_processing_valid(uuid); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.information_system_processing_valid(revision uuid) RETURNS boolean
    LANGUAGE sql STABLE
    AS $$
  SELECT information_system_scope_valid((SELECT p.name FROM information_revisions r
      JOIN information_resources q ON q.id=r.resource_id LEFT JOIN projects p ON p.id=q.project_id WHERE r.id=revision))
    AND EXISTS(SELECT 1 FROM information_revisions r JOIN information_resources q ON q.id=r.resource_id JOIN authority_scopes s ON s.id=q.home_scope_id WHERE r.id=revision)
    AND NOT EXISTS(
      WITH RECURSIVE retained(id) AS (
        SELECT input_revision FROM information_inputs WHERE derived_revision=revision
        UNION SELECT i.input_revision FROM information_inputs i JOIN retained p ON i.derived_revision=p.id)
      SELECT 1 FROM retained i LEFT JOIN information_revisions r ON r.id=i.id
      LEFT JOIN information_resources q ON q.id=r.resource_id LEFT JOIN authority_scopes scope ON scope.id=q.home_scope_id
      WHERE r.id IS NULL OR scope.id IS NULL OR r.availability<>'active' OR r.excluded)
$$;


--
-- Name: information_system_scope_valid(text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.information_system_scope_valid(project text) RETURNS boolean
    LANGUAGE sql STABLE
    AS $$
  SELECT EXISTS(
    SELECT 1 FROM authority_grants g JOIN authority_live_grants() live ON live.id=g.id
      JOIN authority_roles role ON role.id=g.role_id
      JOIN authority_role_actions a ON a.role_id=role.id AND a.role_version=g.role_version AND a.profile='DEFAULT'
    WHERE NOT role.assignable AND role.intrinsic_account=g.subject_account
      AND g.coverage='ALL_SCOPES' AND g.subject_agent IS NULL
      AND EXISTS(SELECT 1 FROM authority_scopes target WHERE
        (project IS NULL AND target.builtin='GLOBAL_KNOWLEDGE') OR
        (project IS NOT NULL AND target.project_id=(SELECT id FROM projects WHERE name=project)))
    GROUP BY g.id HAVING count(*) FILTER(WHERE a.action IN ('READ','WRITE','CONVERSATION_CREATE','RUN_MODEL'))=4)
$$;


--
-- Name: information_unreadable_logs(text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.information_unreadable_logs(account text) RETURNS TABLE(log text)
    LANGUAGE sql STABLE
    SET jit TO 'off'
    AS $_$
 WITH RECURSIVE admitted AS MATERIALIZED (
  SELECT * FROM information_readable_input_selections($1,NULL)
 ),
 blocked(log) AS (
  SELECT payload_id FROM information_quarantined_payloads
  UNION SELECT i.job_id FROM information_job_inputs i LEFT JOIN admitted a
   ON a.revision=i.revision_id AND a.scope=i.scope
   AND a.project_name IS NOT DISTINCT FROM i.project_name AND a.include_shared=i.include_shared
   WHERE $1 IS NULL OR $1<>i.owner_handle OR NOT coalesce(a.readable,false)
 ),
 descendants(log) AS (
  SELECT log FROM blocked UNION SELECT e.child FROM descendants d JOIN (
   SELECT id AS child,parent_id AS parent FROM conversations WHERE parent_id IS NOT NULL
   UNION ALL SELECT conductor_conversation,caller_conversation FROM orchestrations WHERE caller_conversation IS NOT NULL
   UNION ALL SELECT o.conductor_conversation,p.conductor_conversation FROM orchestrations o JOIN orchestrations p ON p.id=o.parent
   UNION ALL SELECT id,conductor_conversation FROM orchestrations
  ) e ON e.parent=d.log WHERE e.child IS NOT NULL
 ) SELECT log FROM descendants;
$_$;


--
-- Name: information_visible_tag_groups(jsonb, jsonb); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.information_visible_tag_groups(groups jsonb, tags jsonb) RETURNS jsonb
    LANGUAGE sql IMMUTABLE
    AS $$
 SELECT coalesce(jsonb_object_agg(name,members),'{}'::jsonb)
 FROM (SELECT g.key AS name,jsonb_agg(DISTINCT t.tag ORDER BY t.tag) AS members
       FROM jsonb_each(groups) g CROSS JOIN LATERAL jsonb_array_elements_text(g.value) t(tag)
       WHERE tags ? t.tag GROUP BY g.key) retained
$$;


--
-- Name: information_visible_tags(jsonb, jsonb); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.information_visible_tags(manual jsonb, automatic jsonb) RETURNS jsonb
    LANGUAGE sql IMMUTABLE
    AS $$
 SELECT coalesce(jsonb_agg(tag ORDER BY tag),'[]'::jsonb)
 FROM (SELECT DISTINCT tag FROM jsonb_array_elements_text(manual || automatic) AS t(tag) WHERE tag IS NOT NULL) tags
$$;


--
-- Name: knowledge_dependencies_readable(uuid, text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.knowledge_dependencies_readable(revision uuid, actor text) RETURNS boolean
    LANGUAGE sql STABLE
    AS $_$
 SELECT count(*)<=1024 AND coalesce(bool_and(coalesce(knowledge_revision_readable(id,$2),false)),false)
 FROM knowledge_dependency_revisions($1);
$_$;


--
-- Name: knowledge_dependency_revisions(uuid); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.knowledge_dependency_revisions(revision uuid) RETURNS TABLE(id uuid)
    LANGUAGE sql STABLE
    AS $_$
 WITH RECURSIVE dependencies(id) AS (
   SELECT $1 UNION SELECT edge.id FROM dependencies d CROSS JOIN LATERAL (
     SELECT i.input_revision AS id FROM information_inputs i WHERE i.derived_revision=d.id
     UNION SELECT coalesce(o.pinned_revision,information_navigation_head(o.target))
       FROM information_reference_obligations o JOIN information_revisions r ON r.resource_id=o.source
       WHERE r.id=d.id) edge
 ) SELECT id FROM dependencies LIMIT 1025;
$_$;


--
-- Name: knowledge_document_admitted(uuid, boolean, uuid[]); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.knowledge_document_admitted(revision uuid, general boolean, readable uuid[]) RETURNS boolean
    LANGUAGE sql STABLE
    AS $_$
  SELECT CASE
    WHEN NOT EXISTS(SELECT 1 FROM information_inputs i WHERE i.derived_revision = $1)
     AND NOT EXISTS(SELECT 1 FROM information_reference_obligations o
                    JOIN information_revisions v ON v.resource_id = o.source WHERE v.id = $1)
    THEN EXISTS(SELECT 1 FROM information_revisions r
                JOIN information_resources q ON q.id = r.resource_id
                WHERE r.id = $1
                  AND (q.home_scope_id = ANY($3)
                       OR ($2 AND knowledge_general_readable(r.id))))
    ELSE (SELECT count(*) <= 1024
            AND coalesce(bool_and(q.home_scope_id = ANY($3)
                                  OR ($2 AND knowledge_general_readable(r.id))), false)
          FROM knowledge_dependency_revisions($1) dependency
          LEFT JOIN information_revisions r ON r.id = dependency.id
          LEFT JOIN information_resources q ON q.id = r.resource_id)
  END;
$_$;


--
-- Name: FUNCTION knowledge_document_admitted(revision uuid, general boolean, readable uuid[]); Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON FUNCTION public.knowledge_document_admitted(revision uuid, general boolean, readable uuid[]) IS 'Per-row read boundary test. Both non-revision arguments must be hoisted by the caller; resolving them per row is what this replaces.';


--
-- Name: knowledge_document_in_boundary(uuid, text, text, boolean); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.knowledge_document_in_boundary(revision uuid, actor text, home_project text, global_read boolean) RETURNS boolean
    LANGUAGE sql STABLE
    AS $_$
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
$_$;


--
-- Name: knowledge_general_readable(uuid); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.knowledge_general_readable(revision uuid) RETURNS boolean
    LANGUAGE sql STABLE
    AS $_$
 SELECT coalesce(information_public_policy($1) AND information_public_dependencies($1),false);
$_$;


--
-- Name: knowledge_read_authority(text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.knowledge_read_authority(actor text) RETURNS TABLE(actor_id uuid, general_member boolean, scope_id uuid)
    LANGUAGE plpgsql STABLE
    AS $_$
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
END $_$;


--
-- Name: knowledge_readable_candidates(text, text, text, boolean, text, boolean, text, text, boolean, uuid, text, text, uuid[], boolean); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.knowledge_readable_candidates(actor text, selected_scope text, selected_project text, include_global boolean, boundary_home text, boundary_global boolean, corpus text, kind text, heads boolean, related_resource uuid, relation text, direction text, targets uuid[], include_excluded boolean) RETURNS TABLE(revision uuid)
    LANGUAGE sql STABLE
    SET jit TO 'off'
    AS $_$
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
$_$;


--
-- Name: knowledge_readable_scopes(text, text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.knowledge_readable_scopes(actor text, home_project text) RETURNS SETOF uuid
    LANGUAGE sql STABLE
    AS $_$
  SELECT s.id FROM authority_scopes s
  LEFT JOIN projects p ON p.id = s.project_id
  LEFT JOIN authority_accounts a ON a.id = s.account_id
  WHERE ((p.name = $2 OR (s.kind = 'CONTAINED' AND contained_scope_in_home(s.id, $2)))
          AND knowledge_scope_readable($1, s.id))
     OR (s.kind = 'ACCOUNT_KNOWLEDGE' AND s.builtin IS NULL AND a.legacy_handle = $1
         AND EXISTS(SELECT 1 FROM projects own
                    WHERE own.name = $2 AND own.personal_owner = $1));
$_$;


--
-- Name: FUNCTION knowledge_readable_scopes(actor text, home_project text); Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON FUNCTION public.knowledge_readable_scopes(actor text, home_project text) IS 'Scopes readable by an actor inside one boundary home. Hoist this to an InitPlan: evaluated per row it is slower than the predicate it replaces.';


--
-- Name: knowledge_readable_selection(text, text, text, boolean, text, boolean, text, text, boolean, uuid, text, text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.knowledge_readable_selection(actor text, selected_scope text, selected_project text, include_global boolean, boundary_home text, boundary_global boolean, corpus text, kind text, heads boolean, related_resource uuid, relation text, direction text) RETURNS TABLE(revision uuid)
    LANGUAGE sql STABLE
    SET jit TO 'off'
    AS $_$
 SELECT revision FROM knowledge_readable_candidates($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,NULL,false);
$_$;


--
-- Name: knowledge_readable_targets(text, text, text, boolean, text, boolean, uuid[], text, boolean, uuid, text, text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.knowledge_readable_targets(actor text, selected_scope text, selected_project text, include_global boolean, boundary_home text, boundary_global boolean, targets uuid[], kind text, heads boolean, related_resource uuid, relation text, direction text) RETURNS TABLE(revision uuid)
    LANGUAGE sql STABLE
    SET jit TO 'off'
    AS $_$
 SELECT revision FROM knowledge_readable_candidates($1,$2,$3,$4,$5,$6,NULL,$8,$9,$10,$11,$12,$7,true)
 WHERE $7 IS NOT NULL;
$_$;


--
-- Name: knowledge_revision_readable(uuid, text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.knowledge_revision_readable(revision uuid, actor text) RETURNS boolean
    LANGUAGE sql STABLE
    AS $_$
 SELECT coalesce((information_global_read_member($2) OR contained_knowledge_actor($2))
   AND r.availability='active'
   AND p.audience IN ('HOME','GLOBAL_READ') AND s.kind<>'RESTRICTED'
   AND information_privacy_allows(q.id,$2)
   AND (knowledge_scope_readable($2,q.home_scope_id)
     OR (NOT contained_knowledge_actor($2) AND knowledge_general_readable(r.id))),false)
 FROM information_revisions r JOIN information_resources q ON q.id=r.resource_id
 JOIN authority_scopes s ON s.id=q.home_scope_id
 JOIN information_resource_policies p ON p.resource_id=q.id WHERE r.id=$1;
$_$;


--
-- Name: knowledge_scope_readable(text, uuid); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.knowledge_scope_readable(actor text, scope uuid) RETURNS boolean
    LANGUAGE sql STABLE
    AS $_$
 SELECT (information_global_read_member($1) AND EXISTS(SELECT 1 FROM authority_scopes s
 LEFT JOIN projects p ON p.id=s.project_id LEFT JOIN authority_accounts a ON a.legacy_handle=$1
 WHERE s.id=$2 AND (
   (s.kind='ACCOUNT_KNOWLEDGE' AND s.builtin IS NULL AND s.account_id=a.id)
   OR (s.builtin='GLOBAL_KNOWLEDGE' AND information_account_action($1,'READ'))
   OR (s.kind IN ('PROJECT','PERSONAL') AND
     coalesce(authority_project_role(p.name,$1),service_project_role(p.name,$1)) IS NOT NULL))))
 OR contained_scope_readable($1,$2,information_global_read_member($1));
$_$;


--
-- Name: memory_knowledge_home(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.memory_knowledge_home() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE expected UUID;
BEGIN
    expected:=CASE WHEN NEW.project_id IS NULL THEN '00000000-0000-0000-0000-000000000005'::uuid
        ELSE (SELECT id FROM authority_scopes WHERE project_id=NEW.project_id) END;
    IF expected IS NULL THEN
        RAISE foreign_key_violation USING MESSAGE='memories_project_exists: memory requires an existing project scope';
    END IF;
    -- Keep existing storage saves atomic with the explicit mapping; promotion creates a separate
    -- row and its own provenance. This trigger grants no new archive operation.
    IF TG_OP='INSERT' AND NEW.home_scope_id IS NOT NULL AND NEW.home_scope_id IS DISTINCT FROM expected THEN
        RAISE EXCEPTION 'memory home does not match its project';
    END IF;
    IF TG_OP='UPDATE' AND NEW.project_id IS NOT DISTINCT FROM OLD.project_id
        AND NEW.home_scope_id IS DISTINCT FROM OLD.home_scope_id THEN
        RAISE EXCEPTION 'memory home does not match its project';
    END IF;
    NEW.home_scope_id:=expected;
    RETURN NEW;
END $$;


--
-- Name: memory_knowledge_scope(bigint); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.memory_knowledge_scope(project bigint) RETURNS uuid
    LANGUAGE sql STABLE
    AS $_$
    SELECT CASE WHEN $1 IS NULL THEN '00000000-0000-0000-0000-000000000005'::uuid
        ELSE (SELECT id FROM authority_scopes WHERE project_id=$1) END;
$_$;


--
-- Name: operation_admission_immutable(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.operation_admission_immutable() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF TG_OP='INSERT' THEN
   IF EXISTS(SELECT 1 FROM operation_admissions WHERE id=NEW.operation AND sealed) THEN
     RAISE EXCEPTION 'sealed operation authority cannot gain support';
   END IF;
   RETURN NEW;
 END IF;
 IF TG_OP='DELETE' OR TG_TABLE_NAME='operation_admission_support' THEN
   RAISE EXCEPTION 'operation authority is immutable';
 END IF;
 IF (to_jsonb(NEW)-'job_id'-'sealed') IS DISTINCT FROM (to_jsonb(OLD)-'job_id'-'sealed')
     OR (OLD.sealed AND NOT NEW.sealed)
     OR (OLD.job_id IS NOT NULL AND NEW.job_id IS DISTINCT FROM OLD.job_id) THEN
   RAISE EXCEPTION 'operation authority cannot be widened or rebound';
 END IF;
 RETURN NEW;
END $$;


--
-- Name: operation_conversation_valid(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.operation_conversation_valid() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE source conversations;
 independent_conductor BOOLEAN;
BEGIN
 SELECT * INTO source FROM conversations WHERE id=NEW.conversation FOR UPDATE;
 independent_conductor=source.origin='orchestration' AND source.parent_id IS NULL AND source.owns_allowance;
 IF NOT EXISTS (
  SELECT 1 FROM operation_admissions a JOIN projects p ON p.id=source.project_id
  WHERE a.id=NEW.operation AND a.sealed AND source.agent=NEW.agent AND p.name=a.project_name
   AND (source.parent_id IS NOT DISTINCT FROM NEW.parent_conversation OR source.origin='orchestration' AND source.parent_id IS NULL)
   AND NOT EXISTS(SELECT 1 FROM operation_admission_support s WHERE s.operation=a.id AND s.action NOT IN ('READ','WRITE','CONVERSATION_CREATE','RUN_MODEL'))
 ) THEN RAISE EXCEPTION 'Conversation does not match retained execution'; END IF;
 IF NEW.allowance_id IS NULL OR NOT EXISTS(SELECT 1 FROM execution_allowances WHERE id=NEW.allowance_id) THEN
  RAISE EXCEPTION 'Missing retained allowance'; END IF;
 -- Independent funding is permitted only for the conductor's own retained source. The caller
 -- must still be the actual authority ancestor; another operation's grants cannot be substituted.
 IF NEW.parent_conversation IS NOT NULL AND NOT EXISTS (
  SELECT 1 FROM conversations parent JOIN operation_admission_parents edge ON edge.child=NEW.operation
  LEFT JOIN operation_conversations bound ON bound.conversation=parent.id
  WHERE parent.id=NEW.parent_conversation
    AND (parent.allowance_id=NEW.allowance_id OR independent_conductor)
    AND (bound.operation IS NULL OR EXISTS (
      WITH RECURSIVE lineage(id,depth) AS (
       SELECT edge.parent,0 UNION ALL
       SELECT e.parent,l.depth+1 FROM operation_admission_parents e
       JOIN lineage l ON e.child=l.id WHERE l.depth<64
      ) SELECT 1 FROM lineage WHERE id=bound.operation
    ))
 ) THEN RAISE EXCEPTION 'Child cannot replace retained parent lineage or allowance'; END IF;
 IF source.allowance_id IS NOT NULL AND source.allowance_id<>NEW.allowance_id THEN RAISE EXCEPTION 'Allowance source changed'; END IF;
 UPDATE conversations SET allowance_id=NEW.allowance_id WHERE id=NEW.conversation AND allowance_id IS NULL;
 RETURN NEW;
END $$;


--
-- Name: operation_global_read_parent_valid(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.operation_global_read_parent_valid() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM operation_admissions c JOIN operation_admissions p ON p.id=NEW.parent
     WHERE c.id=NEW.child AND c.global_read AND NOT p.global_read) THEN
   RAISE EXCEPTION 'child cannot acquire Global read outside its retained parent';
 END IF;
 RETURN NEW;
END $$;


--
-- Name: operation_lineage_immutable(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.operation_lineage_immutable() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 RAISE EXCEPTION 'operation lineage is immutable';
END $$;


--
-- Name: operation_parent_valid(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.operation_parent_valid() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 -- Serialize overlapping ancestry inserts without changing an existing admission.
 PERFORM id FROM operation_admissions WHERE id IN (NEW.child,NEW.parent) ORDER BY id FOR UPDATE;
 IF NOT EXISTS (
   SELECT 1 FROM operation_admissions c JOIN operation_admissions p ON p.id=NEW.parent
   WHERE c.id=NEW.child AND c.sealed AND p.sealed AND c.job_id IS NULL
     AND c.executor=p.executor AND c.executor_handle=p.executor_handle
     AND c.source_account=p.source_account
     -- THE ONE EDIT. Either the original equality, or the containment edge itself.
     AND ((c.scope=p.scope AND c.project_name=p.project_name)
       OR EXISTS (SELECT 1 FROM authority_scopes s JOIN projects pr ON pr.id=s.project_id
         WHERE s.id=c.scope AND s.kind='CONTAINED' AND s.container=p.scope
           AND pr.name=c.project_name AND pr.name LIKE 'contained:%'))
     AND NOT EXISTS (SELECT 1 FROM operation_conversations bound WHERE bound.operation=c.id)
     AND NOT EXISTS (
       SELECT 1 FROM operation_admission_support cs WHERE cs.operation=c.id AND
         (cs.action NOT IN ('READ','WRITE','CONVERSATION_CREATE','RUN_MODEL') OR NOT EXISTS (
           SELECT 1 FROM operation_admission_support ps WHERE ps.operation=p.id
             AND ps.action=cs.action AND ps.grant_id=cs.grant_id)))) THEN
   RAISE EXCEPTION 'child authority exceeds retained parent';
 END IF;
 IF EXISTS (WITH RECURSIVE chain(id,depth) AS (
   SELECT NEW.parent,0 UNION ALL
   SELECT edge.parent,chain.depth+1 FROM operation_admission_parents edge JOIN chain ON edge.child=chain.id
   WHERE chain.depth<64
 ) SELECT 1 FROM chain WHERE id=NEW.child OR depth=64) THEN
   RAISE EXCEPTION 'invalid operation ancestry';
 END IF;
 RETURN NEW;
END $$;


--
-- Name: processing_log_allowance(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.processing_log_allowance() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF NEW.processing_log IS NOT NULL AND NEW.processing_log IS DISTINCT FROM OLD.processing_log THEN
  UPDATE conversations SET allowance_id=NEW.processing_budget_id WHERE id=NEW.processing_log;
 END IF;
 RETURN NEW;
END $$;


--
-- Name: protect_embedding_query(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.protect_embedding_query() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Query evidence is retained'; END IF;
 IF NEW.id<>OLD.id OR NEW.budget_id<>OLD.budget_id OR NEW.account_id<>OLD.account_id
   OR NEW.scope_id<>OLD.scope_id OR NEW.fingerprint<>OLD.fingerprint
   OR NEW.invocation IS DISTINCT FROM OLD.invocation OR NEW.call_limit IS DISTINCT FROM OLD.call_limit
   OR NEW.consumer_kind<>OLD.consumer_kind OR NEW.created_at<>OLD.created_at OR NEW.spent<OLD.spent
   OR OLD.closed AND (NOT NEW.closed OR NEW.spent<>OLD.spent) THEN
   RAISE EXCEPTION 'Operation source cannot be rebound or reset';
 END IF;
 RETURN NEW;
END $$;


--
-- Name: protect_inference_admission(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.protect_inference_admission() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
  IF TG_OP='DELETE' THEN RAISE EXCEPTION 'inference claims are immutable'; END IF;
  IF NEW.id<>OLD.id OR NEW.operation_id<>OLD.operation_id OR NEW.budget_id<>OLD.budget_id
    OR NEW.ordinal<>OLD.ordinal OR NEW.call_limit IS DISTINCT FROM OLD.call_limit
    OR NEW.created_at<>OLD.created_at OR (OLD.submitted AND NOT NEW.submitted)
    OR (OLD.accounting_call IS NOT NULL AND NEW.accounting_call IS DISTINCT FROM OLD.accounting_call)
    OR (NEW.accounting_call IS NOT NULL AND NOT NEW.submitted) THEN
    RAISE EXCEPTION 'inference claim cannot be rebound';
  END IF;
  RETURN NEW;
END $$;


--
-- Name: protect_operation_inference_budget(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.protect_operation_inference_budget() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN RAISE EXCEPTION 'operation budget bindings are immutable'; END $$;


--
-- Name: protect_preparation_claim(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.protect_preparation_claim() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF OLD.preparation_revision IS NOT NULL AND (NEW.preparation_revision IS DISTINCT FROM OLD.preparation_revision
  OR NEW.preparation_generation IS DISTINCT FROM OLD.preparation_generation OR NEW.preparation_targets IS DISTINCT FROM OLD.preparation_targets
  OR OLD.preparation_published AND NOT NEW.preparation_published) THEN RAISE EXCEPTION 'Preparation claim targets are immutable'; END IF;
 IF NEW.preparation_revision IS NOT NULL AND NOT EXISTS(
  SELECT 1 FROM information_revisions r JOIN execution_allowances a ON a.id=r.processing_budget_id
  WHERE r.id=NEW.preparation_revision AND a.id=NEW.budget_id AND NEW.ordinal<=a.spent
 ) THEN RAISE EXCEPTION 'Preparation claim has no charged source'; END IF;
 RETURN NEW;
END $$;


--
-- Name: protect_query_claim(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.protect_query_claim() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF OLD.query_id IS NOT NULL AND (NEW.query_id IS DISTINCT FROM OLD.query_id OR NEW.query_task_hash IS DISTINCT FROM OLD.query_task_hash) THEN RAISE EXCEPTION 'Query claim cannot be rebound'; END IF;
 IF NEW.query_id IS NOT NULL AND NOT EXISTS(
  SELECT 1 FROM embedding_queries q JOIN execution_allowances a ON a.id=q.budget_id
  WHERE q.id=NEW.query_id AND a.id=NEW.budget_id AND NEW.ordinal<=a.spent
 ) THEN RAISE EXCEPTION 'Query claim has no charged source'; END IF;
 RETURN NEW;
END $$;


--
-- Name: protect_system_operation(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.protect_system_operation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN RAISE EXCEPTION 'SYSTEM purpose bindings are immutable'; END $$;


--
-- Name: relay_capture_approval(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.relay_capture_approval() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP='INSERT' AND NEW.state='asked' THEN
        INSERT INTO relay_source_events(project_id,topic,family,subject,state,context,related,occurred_at)
        VALUES(NEW.project_id,'approval.requested','LIFECYCLE',NEW.id,NEW.state,NEW.conversation,NEW.asked_in,NEW.created_at);
    ELSIF (TG_OP='INSERT' AND NEW.state<>'asked') OR (TG_OP='UPDATE' AND OLD.state IS DISTINCT FROM NEW.state) THEN
        INSERT INTO relay_source_events(project_id,topic,family,subject,state,context,related,occurred_at)
        VALUES(NEW.project_id,'approval.resolved','LIFECYCLE',NEW.id,NEW.state,NEW.conversation,NEW.asked_in,COALESCE(NEW.answered_at,clock_timestamp()));
    END IF;
    RETURN NEW;
END $$;


--
-- Name: relay_capture_board_message(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.relay_capture_board_message() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE project_key BIGINT; public_topic BOOLEAN;
BEGIN
    SELECT p.id,NOT EXISTS(SELECT 1 FROM board_message_instances i WHERE i.topic=t.id) INTO project_key,public_topic
      FROM board_topics t JOIN projects p ON p.name=t.project WHERE t.id=NEW.topic;
    IF public_topic AND project_key IS NOT NULL THEN
        INSERT INTO relay_source_events(project_id,topic,family,subject,state,context,related,occurred_at)
        VALUES(project_key,'board.message.posted','LIFECYCLE',NEW.id,NEW.kind,NEW.topic,NEW.reply_to,NEW.posted_at);
    END IF;
    RETURN NEW;
END $$;


--
-- Name: relay_capture_board_topic(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.relay_capture_board_topic() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE project_key BIGINT; event_topic TEXT;
BEGIN
    IF EXISTS(SELECT 1 FROM board_message_instances WHERE topic=NEW.id) THEN RETURN NEW; END IF;
    SELECT id INTO project_key FROM projects WHERE name=NEW.project;
    IF project_key IS NULL THEN RETURN NEW; END IF;
    IF TG_OP='INSERT' THEN event_topic='board.opened';
    ELSIF OLD.state IS DISTINCT FROM NEW.state AND NEW.state='closed' THEN event_topic='board.closed';
    END IF;
    IF event_topic IS NOT NULL THEN
        INSERT INTO relay_source_events(project_id,topic,family,subject,state,context,related,occurred_at)
        VALUES(project_key,event_topic,'LIFECYCLE',NEW.id,NEW.state,NEW.root,NEW.parent,
            COALESCE(NEW.closed_at,NEW.opened_at));
    END IF;
    IF TG_OP='UPDATE' AND OLD.resolution IS NULL AND NEW.resolution IS NOT NULL THEN
        INSERT INTO relay_source_events(project_id,topic,family,subject,state,context,related,occurred_at)
        VALUES(project_key,'board.resolved','LIFECYCLE',NEW.id,NEW.state,NEW.root,NEW.resolution,clock_timestamp());
    END IF;
    RETURN NEW;
END $$;


--
-- Name: relay_capture_job(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.relay_capture_job() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP='INSERT' THEN
        INSERT INTO relay_source_events(project_id,topic,family,subject,state,context,occurred_at)
        VALUES(NEW.project_id,'job.started','LIFECYCLE',NEW.id,'submitted',NEW.agent,NEW.started_at);
    ELSIF OLD.ending IS DISTINCT FROM NEW.ending THEN
        IF NEW.ending IS NOT NULL THEN
            INSERT INTO relay_source_events(project_id,topic,family,subject,state,context,occurred_at)
            VALUES(NEW.project_id,'job.ended','LIFECYCLE',NEW.id,NEW.ending,NEW.agent,NEW.ended_at);
        END IF;
    END IF;
    RETURN NEW;
END $$;


--
-- Name: relay_capture_message_instance(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.relay_capture_message_instance() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP='INSERT' OR OLD.active IS DISTINCT FROM NEW.active OR OLD.is_default IS DISTINCT FROM NEW.is_default THEN
        INSERT INTO relay_source_events(topic,family,subject,state,context,related,occurred_at)
        VALUES('message.instance.changed','LIFECYCLE',NEW.id,
            CASE WHEN NEW.active THEN 'active' ELSE 'inactive' END,NEW.conversation,NEW.topic,clock_timestamp());
    END IF;
    RETURN NEW;
END $$;


--
-- Name: relay_capture_message_route(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.relay_capture_message_route() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP='INSERT' THEN
        INSERT INTO relay_source_events(topic,family,subject,state,context,related,occurred_at)
        VALUES('message.accepted','LIFECYCLE',NEW.message,'accepted',NEW.recipient,NEW.sender,clock_timestamp());
        IF NEW.reply_to IS NOT NULL THEN
            INSERT INTO relay_source_events(topic,family,subject,state,context,related,occurred_at)
            VALUES('message.reply.recorded','LIFECYCLE',NEW.message,
                CASE WHEN NEW.final THEN 'final' ELSE 'reply' END,NEW.recipient,NEW.reply_to,clock_timestamp());
        END IF;
    ELSIF OLD.handled_at IS NULL AND NEW.handled_at IS NOT NULL THEN
        INSERT INTO relay_source_events(topic,family,subject,state,context,related,occurred_at)
        VALUES('message.handling.ended','LIFECYCLE',NEW.message,'handled',NEW.recipient,NEW.reply_to,NEW.handled_at);
    END IF;
    RETURN NEW;
END $$;


--
-- Name: relay_capture_orchestration(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.relay_capture_orchestration() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE project_key BIGINT; event_topic TEXT;
BEGIN
    SELECT project_id INTO project_key FROM conversations WHERE id=NEW.conductor_conversation;
    IF TG_OP='INSERT' THEN event_topic='orchestration.started';
    ELSIF NEW.state IN ('finished','failed','capped','cancelled') AND OLD.state IS DISTINCT FROM NEW.state THEN
        event_topic='orchestration.ended';
    ELSIF OLD.state='failed' AND NEW.state='running' THEN event_topic='orchestration.resumed';
    END IF;
    IF event_topic IS NOT NULL THEN
        INSERT INTO relay_source_events(project_id,topic,family,subject,state,context,related,occurred_at)
        VALUES(project_key,event_topic,'LIFECYCLE',NEW.id,NEW.state,NEW.conductor_conversation,NEW.parent,
            COALESCE(NEW.ended_at,CASE WHEN TG_OP='INSERT' THEN NEW.created_at ELSE clock_timestamp() END));
    END IF;
    IF TG_OP='UPDATE' AND OLD.state IS DISTINCT FROM NEW.state AND NEW.state='finished' AND NEW.parent IS NOT NULL THEN
        INSERT INTO relay_source_events(project_id,topic,family,subject,state,context,related,occurred_at)
        VALUES(project_key,'orchestration.child.completed','LIFECYCLE',NEW.id,NEW.state,NEW.conductor_conversation,NEW.parent,NEW.ended_at);
    END IF;
    RETURN NEW;
END $$;


--
-- Name: relay_capture_question(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.relay_capture_question() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE project_key BIGINT; conversation_key TEXT;
BEGIN
    SELECT c.project_id,o.conductor_conversation INTO project_key,conversation_key
      FROM orchestrations o JOIN conversations c ON c.id=o.conductor_conversation WHERE o.id=NEW.orchestration;
    INSERT INTO relay_source_events(project_id,topic,family,subject,state,context,related,occurred_at)
    VALUES(project_key,CASE NEW.kind WHEN 'question' THEN 'orchestration.question.asked' ELSE 'orchestration.question.answered' END,
        'LIFECYCLE',NEW.orchestration,NEW.kind,conversation_key,NEW.id,NEW.created_at);
    RETURN NEW;
END $$;


--
-- Name: relay_capture_wake(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.relay_capture_wake() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE wake_type TEXT; event_topic TEXT;
BEGIN
    wake_type=CASE WHEN NEW.data ? 'direct_message' THEN 'MESSAGE' ELSE 'BOARD' END;
    event_topic=CASE wake_type WHEN 'MESSAGE' THEN 'message.wake.requested' ELSE 'board.wake.requested' END;
    INSERT INTO relay_source_events(topic,family,subject,state,context,occurred_at)
    VALUES(event_topic,'WAKE_REQUESTED',NEW.id,wake_type,NEW.target,NEW.arrived_at);
    RETURN NEW;
END $$;


--
-- Name: relay_firing_wakeup(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.relay_firing_wakeup() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF TG_OP='INSERT' THEN
  IF NEW.status='queued' THEN PERFORM pg_notify('plowshare_projection_changes','relay'); END IF;
 ELSIF (NEW.status='queued' AND OLD.status IS DISTINCT FROM NEW.status)
   OR (OLD.finished_at IS NULL AND NEW.finished_at IS NOT NULL) THEN
  PERFORM pg_notify('plowshare_projection_changes','relay');
 END IF;
 RETURN NEW;
END $$;


--
-- Name: relay_inherit_conversation(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.relay_inherit_conversation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.parent_id IS NOT NULL AND NEW.relay_causation IS NULL THEN
      SELECT relay_causation INTO NEW.relay_causation FROM conversations WHERE id=NEW.parent_id;
      IF NEW.relay_causation IS NULL THEN
        NEW.relay_causation=jsonb_build_object('rootId','conversation:'||NEW.parent_id,'parentId',NULL,'depth',-1);
      END IF;
    END IF;
    RETURN NEW;
END $$;


--
-- Name: relay_inherit_job(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.relay_inherit_job() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE inherited JSONB;
BEGIN
    IF NEW.conversation_id IS NOT NULL THEN
      -- Serialize the first job on a conversation; all later turns retain its ancestry.
      SELECT relay_causation INTO inherited FROM conversations WHERE id=NEW.conversation_id FOR UPDATE;
      IF inherited IS NOT NULL AND NEW.relay_causation IS NOT NULL AND inherited<>NEW.relay_causation THEN
        RAISE EXCEPTION 'Job causation conflicts with its conversation';
      END IF;
    END IF;
    NEW.relay_causation=COALESCE(NEW.relay_causation,inherited,
      jsonb_build_object('rootId','job:'||NEW.id,'parentId',NULL,'depth',0));
    IF NEW.conversation_id IS NOT NULL THEN
      UPDATE conversations SET relay_causation=NEW.relay_causation
        WHERE id=NEW.conversation_id AND relay_causation IS NULL;
    END IF;
    RETURN NEW;
END $$;


--
-- Name: relay_inherit_orchestration(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.relay_inherit_orchestration() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NEW.relay_causation IS NULL AND NEW.parent IS NOT NULL THEN
      SELECT relay_causation INTO NEW.relay_causation FROM orchestrations WHERE id=NEW.parent;
    END IF;
    IF NEW.relay_causation IS NULL AND NEW.caller_conversation IS NOT NULL THEN
      SELECT relay_causation INTO NEW.relay_causation FROM conversations WHERE id=NEW.caller_conversation;
      IF NEW.relay_causation IS NULL THEN
        NEW.relay_causation=jsonb_build_object('rootId','conversation:'||NEW.caller_conversation,'parentId',NULL,'depth',-1);
      END IF;
    END IF;
    NEW.relay_causation=COALESCE(NEW.relay_causation,
      jsonb_build_object('rootId','orchestration:'||NEW.id,'parentId',NULL,'depth',0));
    UPDATE conversations SET relay_causation=NEW.relay_causation
      WHERE id=NEW.conductor_conversation AND relay_causation IS NULL;
    RETURN NEW;
END $$;


--
-- Name: relay_inherit_source(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.relay_inherit_source() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE conversation_key TEXT; derived BOOLEAN=false;
BEGIN
    IF NEW.relay_causation IS NOT NULL THEN RETURN NEW; END IF;
    IF NEW.topic LIKE 'job.%' THEN
      derived=true;
      SELECT relay_causation INTO NEW.relay_causation FROM jobs WHERE id=NEW.subject;
    ELSIF NEW.topic LIKE 'orchestration.%' THEN
      derived=true;
      SELECT relay_causation INTO NEW.relay_causation FROM orchestrations WHERE id=NEW.subject;
    ELSIF NEW.topic LIKE 'approval.%' OR NEW.topic='message.instance.changed' THEN
      conversation_key=NEW.context;
    ELSIF NEW.topic='board.message.posted' THEN
      SELECT conversation INTO conversation_key FROM board_messages WHERE id=NEW.subject;
    ELSIF NEW.topic LIKE 'board.%' THEN
      SELECT origin_conversation INTO conversation_key FROM board_topics WHERE id=NEW.subject;
    ELSIF NEW.topic LIKE 'message.%' THEN
      SELECT conversation INTO conversation_key FROM board_messages WHERE id=NEW.subject;
    END IF;
    IF conversation_key IS NOT NULL THEN
      derived=true;
      SELECT relay_causation INTO NEW.relay_causation FROM conversations WHERE id=conversation_key;
    END IF;
    NEW.relay_causation=COALESCE(NEW.relay_causation,
      jsonb_build_object('rootId','source:'||NEW.id,'parentId',NULL,'depth',CASE WHEN derived THEN -1 ELSE 0 END));
    RETURN NEW;
END $$;


--
-- Name: relay_payload_validate_bytes(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.relay_payload_validate_bytes() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NOT (
        jsonb_typeof(NEW.payload) = 'object'
        AND octet_length(convert_to(NEW.payload::text, 'UTF8')) <= 314573824
        AND (NOT (NEW.payload ? 'text') OR (
            jsonb_typeof(NEW.payload->'text') = 'string'
            AND octet_length(convert_to(NEW.payload->>'text', 'UTF8')) <= 52428800
        ))
    ) THEN
        RAISE EXCEPTION 'Relay payload violates byte/type bounds'
            USING ERRCODE = '23514', CONSTRAINT = 'relay_payloads_payload_check';
    END IF;
    RETURN NEW;
END;
$$;


--
-- Name: relay_subscription_authority_revoked(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.relay_subscription_authority_revoked() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    DELETE FROM relay_subscription_authority a
    USING changed_rows c
    WHERE a.scope_key = 'project:' || c.project_id::TEXT AND a.account = c.handle;
    RETURN NULL;
END $$;


--
-- Name: FUNCTION relay_subscription_authority_revoked(); Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON FUNCTION public.relay_subscription_authority_revoked() IS 'Withdraws Relay subscription authority when project membership changes (decision 0038).';


--
-- Name: relay_subscription_authority_validity(uuid, uuid, timestamp with time zone, interval, timestamp with time zone); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.relay_subscription_authority_validity(recorded_generation uuid, current_generation uuid, established_at timestamp with time zone, maximum_age interval, now_at timestamp with time zone) RETURNS text
    LANGUAGE sql IMMUTABLE
    AS $_$
    SELECT CASE
        WHEN $1 IS NULL OR $2 IS NULL OR $3 IS NULL THEN 'NOT_ESTABLISHED'
        WHEN $1 <> $2 THEN 'GENERATION_CHANGED'
        WHEN $3 + $4 <= $5 THEN 'EXPIRED'
        ELSE 'VALID' END;
$_$;


--
-- Name: FUNCTION relay_subscription_authority_validity(recorded_generation uuid, current_generation uuid, established_at timestamp with time zone, maximum_age interval, now_at timestamp with time zone); Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON FUNCTION public.relay_subscription_authority_validity(recorded_generation uuid, current_generation uuid, established_at timestamp with time zone, maximum_age interval, now_at timestamp with time zone) IS 'Whether an established Relay subscription authority is still valid, and if not, why (0038).';


--
-- Name: relay_valid_causation(jsonb); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.relay_valid_causation(value jsonb) RETURNS boolean
    LANGUAGE sql IMMUTABLE
    AS $_$
    SELECT value IS NULL OR (
      jsonb_typeof(value)='object' AND value ?& ARRAY['rootId','parentId','depth']
      AND (value - ARRAY['rootId','parentId','depth'])='{}'::jsonb
      AND jsonb_typeof(value->'rootId')='string'
      AND length(value->>'rootId') BETWEEN 1 AND 256
      AND value->>'rootId'=btrim(value->>'rootId')
      AND value->>'rootId' !~ '[[:cntrl:]]'
      AND jsonb_typeof(value->'depth')='number'
      AND (value->>'depth') ~ '^(-1|[0-9]|[12][0-9]|3[0-2])$'
      AND CASE WHEN (value->>'depth')::integer <= 0 THEN value->'parentId'='null'::jsonb
        ELSE jsonb_typeof(value->'parentId')='string'
          AND length(value->>'parentId') BETWEEN 1 AND 256
          AND value->>'parentId'=btrim(value->>'parentId')
          AND value->>'parentId' !~ '[[:cntrl:]]' END
    ) IS TRUE
$_$;


--
-- Name: relay_work_available(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.relay_work_available() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM changed_rows) THEN
  PERFORM pg_notify('plowshare_projection_changes','relay');
 END IF;
 RETURN NULL;
END $$;


--
-- Name: release_invocation_blob_claim(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.release_invocation_blob_claim() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    PERFORM 1 FROM binary_blobs b JOIN binary_blob_claims c
        ON c.scope=b.scope AND c.sha256=b.sha256
        WHERE c.kind='invocation-admission' AND c.owner=OLD.invocation_id::text
          AND c.slot=OLD.ordinal
        ORDER BY b.scope,b.sha256 FOR UPDATE OF b;
    UPDATE binary_blobs b SET unclaimed_at=clock_timestamp()
        FROM binary_blob_claims c
        WHERE c.scope=b.scope AND c.sha256=b.sha256
          AND c.kind='invocation-admission' AND c.owner=OLD.invocation_id::text
          AND c.slot=OLD.ordinal;
    DELETE FROM binary_blob_claims
        WHERE kind='invocation-admission' AND owner=OLD.invocation_id::text
          AND slot=OLD.ordinal AND retention='RELEASABLE';
    RETURN OLD;
END;
$$;


--
-- Name: release_relay_blob_claim(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.release_relay_blob_claim() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    PERFORM 1 FROM binary_blobs WHERE scope=OLD.blob_scope AND sha256=OLD.sha256 FOR UPDATE;
    DELETE FROM binary_blob_claims WHERE scope=OLD.blob_scope AND sha256=OLD.sha256
        AND kind='relay-publication' AND owner=OLD.claim_owner AND slot=0 AND retention='RELEASABLE';
    UPDATE binary_blobs SET unclaimed_at=clock_timestamp()
        WHERE scope=OLD.blob_scope AND sha256=OLD.sha256;
    RETURN OLD;
END;
$$;


--
-- Name: release_trajectory_blob_claim(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.release_trajectory_blob_claim() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    PERFORM 1 FROM binary_blobs WHERE scope=OLD.blob_scope AND sha256=OLD.sha256 FOR UPDATE;
    DELETE FROM binary_blob_claims WHERE scope=OLD.blob_scope AND sha256=OLD.sha256
      AND kind='trajectory-entry' AND owner=OLD.conversation_id || ':' || OLD.entry_ordinal
      AND slot=OLD.slot AND retention='RELEASABLE';
    UPDATE binary_blobs SET unclaimed_at=clock_timestamp()
      WHERE scope=OLD.blob_scope AND sha256=OLD.sha256;
    RETURN OLD;
END;
$$;


--
-- Name: require_shared_orchestration_source(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.require_shared_orchestration_source() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF NEW.origin='orchestration' AND NOT NEW.owns_allowance AND NOT EXISTS (
   SELECT 1 FROM operation_conversations c JOIN operation_conversations parent ON parent.conversation=c.parent_conversation
   WHERE c.conversation=NEW.id AND c.allowance_id IS NOT NULL AND parent.allowance_id=c.allowance_id
 ) THEN RAISE EXCEPTION 'Shared orchestration needs retained caller and allowance'; END IF;
 RETURN NULL;
END $$;


--
-- Name: retrieval_digest_changed(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.retrieval_digest_changed() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF TG_OP='DELETE' THEN
  DELETE FROM retrieval_sources WHERE source_type='digest' AND source_id=OLD.id;
  RETURN OLD;
 END IF;
 IF TG_OP='UPDATE' AND ROW(OLD.summary,OLD.revision,OLD.stale_at) IS NOT DISTINCT FROM ROW(NEW.summary,NEW.revision,NEW.stale_at) THEN
  RETURN NEW;
 END IF;
 DELETE FROM retrieval_sources WHERE source_type='digest' AND source_id=NEW.id;
 INSERT INTO retrieval_sources(source_type,source_id,source_hash) VALUES('digest',NEW.id,md5(NEW.summary || ':' || NEW.revision));
 RETURN NEW;
END $$;


--
-- Name: retrieval_entry_changed(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.retrieval_entry_changed() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE key text;
BEGIN
 IF TG_OP='DELETE' THEN
  DELETE FROM retrieval_sources WHERE source_type='entry' AND source_id=OLD.conversation_id || ':' || OLD.ordinal;
  RETURN OLD;
 END IF;
 key := NEW.conversation_id || ':' || NEW.ordinal;
 IF TG_OP='UPDATE' AND ROW(OLD.content,OLD.role,OLD.ejected_at) IS NOT DISTINCT FROM ROW(NEW.content,NEW.role,NEW.ejected_at) THEN
  RETURN NEW;
 END IF;
 DELETE FROM retrieval_sources WHERE source_type='entry' AND source_id=key;
 IF NEW.role IS NOT NULL AND NEW.content IS NOT NULL AND btrim(NEW.content)<>'' AND NEW.ejected_at IS NULL THEN
  INSERT INTO retrieval_sources(source_type,source_id,conversation_id,ordinal,source_hash)
  VALUES('entry',key,NEW.conversation_id,NEW.ordinal,md5(NEW.content));
 END IF;
 RETURN NEW;
END $$;


--
-- Name: retrieval_sources_truncated(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.retrieval_sources_truncated() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 DELETE FROM retrieval_sources WHERE source_type=CASE WHEN TG_TABLE_NAME='entries' THEN 'entry' ELSE 'digest' END;
 RETURN NULL;
END $$;


--
-- Name: revoke_deleted_project_provider_grants(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.revoke_deleted_project_provider_grants() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    UPDATE provider_connections SET revision=gen_random_uuid(),enabled=FALSE,deleted=TRUE
        WHERE project_id=OLD.id;
    UPDATE provider_credentials g SET generation=gen_random_uuid(),connection_revision=c.revision,
        state='REVOKED',expires_at=NULL,key_id=NULL,nonce=NULL,ciphertext=NULL,attempt=NULL,
        state_hash=NULL,initiator=NULL,intent_expires_at=NULL
        FROM provider_connections c WHERE g.connection_id=c.id AND c.project_id=OLD.id;
    RETURN OLD;
END;
$$;


--
-- Name: service_project_role(text, text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.service_project_role(project text, account text) RETURNS text
    LANGUAGE sql STABLE
    AS $$
    SELECT CASE WHEN effective.role='VIEWER' OR s.role='VIEWER' THEN 'VIEWER'
        WHEN effective.role='CONTRIBUTOR' OR s.role='CONTRIBUTOR' THEN 'CONTRIBUTOR' ELSE 'MANAGER' END
    FROM service_tokens t JOIN service_token_scopes s ON s.token_id=t.id
    JOIN projects p ON p.id=s.project_id AND p.name=project AND p.personal_owner IS NULL
        AND p.name NOT LIKE 'client:%' AND p.name NOT LIKE 'personal:%'
    CROSS JOIN LATERAL (SELECT authority_project_role(project,t.owner_handle) AS role) effective
    WHERE t.principal_handle=account AND account_active(account) AND effective.role IS NOT NULL;
$$;


--
-- Name: snapshot_cache_bump(text[]); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.snapshot_cache_bump(selected text[]) RETURNS void
    LANGUAGE plpgsql
    AS $$
DECLARE domain TEXT; channel TEXT;
BEGIN
 PERFORM snapshot_cache_record_writer(selected);
 FOREACH domain IN ARRAY selected LOOP
  PERFORM nextval(('snapshot_cache_'||domain)::regclass);
  channel:=CASE WHEN domain='knowledge_revocations' THEN 'plowshare_knowledge_revocations'
   WHEN domain='knowledge_access' THEN 'plowshare_knowledge_access_changes'
   WHEN domain IN ('queues','progress','types','extractions') THEN 'plowshare_projection_changes_v2'
   WHEN domain='readability' THEN 'plowshare_readability_changes' ELSE 'plowshare_projection_changes' END;
  PERFORM pg_notify(channel,domain);
 END LOOP;
END $$;


--
-- Name: snapshot_cache_capture_counters(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.snapshot_cache_capture_counters() RETURNS TABLE(database_id uuid, documents bigint, search bigint, schedules bigint, relay bigint, transcripts bigint, authority bigint, observations bigint, readability bigint, queues bigint, progress bigint, types bigint, extractions bigint, knowledge_access bigint, knowledge_revocations bigint)
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF pg_current_xact_id_if_assigned() IS NOT NULL THEN
  RAISE EXCEPTION 'Projection counter capture requires a fresh autocommit statement';
 END IF;
 SELECT identity.database_id,
  (SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM snapshot_cache_documents),
  (SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM snapshot_cache_search),
  (SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM snapshot_cache_schedules),
  (SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM snapshot_cache_relay),
  (SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM snapshot_cache_transcripts),
  (SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM snapshot_cache_authority),
  (SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM snapshot_cache_observations),
  (SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM snapshot_cache_readability),
  (SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM snapshot_cache_queues),
  (SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM snapshot_cache_progress),
  (SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM snapshot_cache_types),
  (SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM snapshot_cache_extractions),
  (SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM snapshot_cache_knowledge_access),
  (SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM snapshot_cache_knowledge_revocations)
 INTO database_id,documents,search,schedules,relay,transcripts,authority,observations,readability,queues,progress,types,extractions,knowledge_access,knowledge_revocations
 FROM snapshot_cache_identity identity WHERE identity.singleton;
 IF NOT FOUND THEN RAISE EXCEPTION 'Projection cache database identity is missing'; END IF;
 RETURN NEXT;
END $$;


--
-- Name: snapshot_cache_extractions_changed(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.snapshot_cache_extractions_changed() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF TG_OP<>'TRUNCATE' THEN
  IF NOT EXISTS(SELECT 1 FROM changed_rows) THEN RETURN NULL; END IF;
  IF TG_OP='UPDATE' THEN
   IF NOT EXISTS(SELECT 1 FROM changed_rows n FULL JOIN previous_rows o USING(id)
    WHERE ROW(n.id,n.resource_id,n.document_type,n.document_subtype,n.content_hash,n.text_hash,n.generation,n.extracted_text)
     IS DISTINCT FROM ROW(o.id,o.resource_id,o.document_type,o.document_subtype,o.content_hash,o.text_hash,o.generation,o.extracted_text)) THEN RETURN NULL; END IF;
  END IF;
 END IF;
 PERFORM snapshot_cache_bump(ARRAY['extractions']);
 RETURN NULL;
END $$;


--
-- Name: snapshot_cache_history_changed(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.snapshot_cache_history_changed() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF TG_OP<>'TRUNCATE' THEN
  IF NOT EXISTS(SELECT 1 FROM changed_rows) THEN RETURN NULL; END IF;
  IF TG_OP='UPDATE' THEN
   IF NOT EXISTS(SELECT 1 FROM changed_rows n FULL JOIN previous_rows o
     USING(revision_id,relay_project,relay_position) WHERE n IS DISTINCT FROM o) THEN RETURN NULL; END IF;
  END IF;
 END IF;
 PERFORM snapshot_cache_bump(ARRAY['progress']);
 RETURN NULL;
END $$;


--
-- Name: snapshot_cache_identity_immutable(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.snapshot_cache_identity_immutable() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 RAISE EXCEPTION 'Projection cache database identity is immutable';
END $$;


--
-- Name: snapshot_cache_information_steps_changed(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.snapshot_cache_information_steps_changed() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE selected TEXT[]:=ARRAY['queues','observations'];
BEGIN
 IF TG_OP='TRUNCATE' THEN
  PERFORM snapshot_cache_bump(selected||ARRAY['documents','search','progress']); RETURN NULL;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM changed_rows) THEN RETURN NULL; END IF;
 IF TG_OP='UPDATE' THEN
  IF NOT EXISTS(SELECT to_jsonb(n) FROM changed_rows n EXCEPT SELECT to_jsonb(o) FROM previous_rows o) THEN RETURN NULL; END IF;
  IF EXISTS(SELECT to_jsonb(n)-'lease_until' FROM changed_rows n EXCEPT SELECT to_jsonb(o)-'lease_until' FROM previous_rows o) THEN selected:=array_append(selected,'progress'); END IF;
  IF EXISTS(SELECT 1 FROM changed_rows n FULL JOIN previous_rows o USING(revision_id,generation,stage)
    WHERE ROW(n.revision_id,n.generation,n.stage,n.state,n.fingerprint) IS DISTINCT FROM ROW(o.revision_id,o.generation,o.stage,o.state,o.fingerprint)) THEN selected:=array_append(selected,'search'); END IF;
  IF EXISTS(SELECT 1 FROM changed_rows n FULL JOIN previous_rows o USING(revision_id,generation,stage)
    WHERE coalesce(n.stage,o.stage) IN ('autoTag','tagGroups')
     AND ROW(n.revision_id,n.generation,n.stage,n.state) IS DISTINCT FROM ROW(o.revision_id,o.generation,o.stage,o.state)) THEN selected:=array_append(selected,'documents'); END IF;
 ELSE
  selected:=selected||ARRAY['progress','search'];
  IF EXISTS(SELECT 1 FROM changed_rows WHERE stage IN ('autoTag','tagGroups')) THEN selected:=array_append(selected,'documents'); END IF;
 END IF;
 PERFORM snapshot_cache_bump(selected);
 RETURN NULL;
END $$;


--
-- Name: snapshot_cache_information_steps_updated(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.snapshot_cache_information_steps_updated() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM changed_rows) THEN RETURN NULL; END IF;
 IF NOT EXISTS(
  SELECT to_jsonb(changed)-'lease_until' FROM changed_rows changed
  EXCEPT
  SELECT to_jsonb(previous)-'lease_until' FROM previous_rows previous
 ) THEN RETURN NULL; END IF;
 PERFORM snapshot_cache_record_writer(ARRAY['documents','search']);
 PERFORM nextval('snapshot_cache_documents');
 PERFORM nextval('snapshot_cache_search');
 PERFORM pg_notify('plowshare_projection_changes','documents');
 PERFORM pg_notify('plowshare_projection_changes','search');
 RETURN NULL;
END $$;


--
-- Name: snapshot_cache_knowledge_access_changed(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.snapshot_cache_knowledge_access_changed() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE born TEXT[]; revokes BOOLEAN:=true;
BEGIN
 IF TG_OP<>'TRUNCATE' THEN
  IF NOT EXISTS(SELECT 1 FROM changed_rows) THEN RETURN NULL; END IF;
  IF TG_OP='UPDATE' THEN
   IF TG_TABLE_NAME='information_revisions' THEN
    IF NOT EXISTS(SELECT id,resource_id,ordinal,availability,excluded FROM changed_rows
      EXCEPT SELECT id,resource_id,ordinal,availability,excluded FROM previous_rows) THEN RETURN NULL; END IF;
   ELSIF TG_TABLE_NAME='information_resources' THEN
    IF NOT EXISTS(SELECT id,home_scope_id,project_id FROM changed_rows
      EXCEPT SELECT id,home_scope_id,project_id FROM previous_rows) THEN RETURN NULL; END IF;
   ELSIF NOT EXISTS(SELECT to_jsonb(n) FROM changed_rows n EXCEPT SELECT to_jsonb(o) FROM previous_rows o) THEN RETURN NULL;
   END IF;
  END IF;
  -- Initial policies/inputs for rows born in this transaction cannot revoke a previously
  -- delivered snapshot. Both sides of UPDATE must be new; a moved old edge stays hard.
  IF TG_TABLE_NAME IN ('information_resources','information_resource_policies','information_reference_obligations') THEN
   born:=coalesce(string_to_array(nullif(current_setting('plowshare.cache_born_resources',true),''),','),ARRAY[]::text[]);
   IF TG_TABLE_NAME='information_resources' THEN
    revokes:=EXISTS(SELECT 1 FROM changed_rows WHERE NOT id::text=ANY(born));
    IF TG_OP='UPDATE' THEN revokes:=revokes OR EXISTS(SELECT 1 FROM previous_rows WHERE NOT id::text=ANY(born)); END IF;
   ELSIF TG_TABLE_NAME='information_resource_policies' THEN
    revokes:=EXISTS(SELECT 1 FROM changed_rows WHERE NOT resource_id::text=ANY(born));
    IF TG_OP='UPDATE' THEN revokes:=revokes OR EXISTS(SELECT 1 FROM previous_rows WHERE NOT resource_id::text=ANY(born)); END IF;
   ELSE
    revokes:=EXISTS(SELECT 1 FROM changed_rows WHERE NOT source::text=ANY(born));
    IF TG_OP='UPDATE' THEN revokes:=revokes OR EXISTS(SELECT 1 FROM previous_rows WHERE NOT source::text=ANY(born)); END IF;
   END IF;
  ELSIF TG_TABLE_NAME IN ('information_revisions','information_inputs','information_links') THEN
   born:=coalesce(string_to_array(nullif(current_setting('plowshare.cache_born_revisions',true),''),','),ARRAY[]::text[]);
   IF TG_TABLE_NAME='information_revisions' THEN
    -- A new head of an existing resource can redirect unpinned dependencies of old reports.
    -- Only a resource created here proves that no previously delivered graph can depend on it.
    IF TG_OP='INSERT' THEN
     revokes:=EXISTS(SELECT 1 FROM changed_rows WHERE NOT resource_id::text=ANY(
       coalesce(string_to_array(nullif(current_setting('plowshare.cache_born_resources',true),''),','),ARRAY[]::text[])));

    ELSE
     revokes:=EXISTS(SELECT 1 FROM changed_rows WHERE NOT id::text=ANY(born));
     IF TG_OP='UPDATE' THEN revokes:=revokes OR EXISTS(SELECT 1 FROM previous_rows WHERE NOT id::text=ANY(born)); END IF;
    END IF;
   ELSIF TG_TABLE_NAME='information_inputs' THEN
    revokes:=EXISTS(SELECT 1 FROM changed_rows WHERE NOT derived_revision::text=ANY(born));
    IF TG_OP='UPDATE' THEN revokes:=revokes OR EXISTS(SELECT 1 FROM previous_rows WHERE NOT derived_revision::text=ANY(born)); END IF;
   ELSE
    revokes:=EXISTS(SELECT 1 FROM changed_rows WHERE NOT revision_id::text=ANY(born));
    IF TG_OP='UPDATE' THEN revokes:=revokes OR EXISTS(SELECT 1 FROM previous_rows WHERE NOT revision_id::text=ANY(born)); END IF;
   END IF;
  ELSIF TG_TABLE_NAME='information_document_policies' THEN
   born:=coalesce(string_to_array(nullif(current_setting('plowshare.cache_born_documents',true),''),','),ARRAY[]::text[]);
   revokes:=EXISTS(SELECT 1 FROM changed_rows WHERE NOT document_id::text=ANY(born));
   IF TG_OP='UPDATE' THEN revokes:=revokes OR EXISTS(SELECT 1 FROM previous_rows WHERE NOT document_id::text=ANY(born)); END IF;
  END IF;
  IF TG_TABLE_NAME='information_resources' AND TG_OP='INSERT' THEN revokes:=false; END IF;
 END IF;
 PERFORM snapshot_cache_bump(ARRAY['knowledge_access']);
 IF revokes THEN PERFORM snapshot_cache_bump(ARRAY['knowledge_revocations']); END IF;
 RETURN NULL;
END $$;


--
-- Name: snapshot_cache_knowledge_born(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.snapshot_cache_knowledge_born() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE setting TEXT; born TEXT[];
BEGIN
 setting:=CASE TG_TABLE_NAME WHEN 'information_resources' THEN 'plowshare.cache_born_resources'
  WHEN 'information_revisions' THEN 'plowshare.cache_born_revisions'
  WHEN 'documents' THEN 'plowshare.cache_born_documents' END;
 IF setting IS NULL THEN RAISE EXCEPTION 'Unknown knowledge creation relation'; END IF;
 born:=array_append(coalesce(string_to_array(nullif(current_setting(setting,true),''),','),ARRAY[]::text[]),NEW.id::text);
 IF cardinality(born)>128 THEN born:=born[cardinality(born)-127:cardinality(born)]; END IF;
 PERFORM set_config(setting,array_to_string(born,','),true);
 RETURN NULL;
END $$;


--
-- Name: snapshot_cache_observation_changed(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.snapshot_cache_observation_changed() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 -- TRUNCATE has no transition relation. Keep a separate PL/pgSQL branch so its statement never
 -- plans a query referencing the absent relation, even before boolean short-circuit evaluation.
 IF TG_OP<>'TRUNCATE' THEN
  IF NOT EXISTS(SELECT 1 FROM changed_rows) THEN RETURN NULL; END IF;
 END IF;
 PERFORM snapshot_cache_record_writer(ARRAY['observations']);
 PERFORM nextval('snapshot_cache_observations');
 PERFORM pg_notify('plowshare_projection_changes','observations');
 RETURN NULL;
END $$;


--
-- Name: snapshot_cache_prune_writers(integer, integer); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.snapshot_cache_prune_writers(retained integer DEFAULT 8192, batch integer DEFAULT 1024) RETURNS void
    LANGUAGE plpgsql
    AS $$
DECLARE boundary XID8; removed XID8;
BEGIN
 IF retained NOT BETWEEN 1 AND 8192 OR batch NOT BETWEEN 1 AND 1024 THEN
  RAISE EXCEPTION 'Invalid projection writer retention bound';
 END IF;
 IF NOT pg_try_advisory_xact_lock(1347634002,205) THEN RETURN; END IF;
 SELECT xid INTO boundary FROM snapshot_cache_writers ORDER BY xid DESC OFFSET retained-1 LIMIT 1;
 IF boundary IS NULL THEN RETURN; END IF;
 WITH victims AS MATERIALIZED (
  SELECT xid,domain FROM snapshot_cache_writers WHERE xid<boundary ORDER BY xid,domain
  LIMIT batch FOR UPDATE SKIP LOCKED
 ), deleted AS (
  DELETE FROM snapshot_cache_writers writer USING victims WHERE writer.xid=victims.xid AND writer.domain=victims.domain
  RETURNING writer.xid
 ) SELECT xid INTO removed FROM deleted ORDER BY xid DESC LIMIT 1;
 IF removed IS NOT NULL THEN
  UPDATE snapshot_cache_writer_retention SET through=greatest(through,removed) WHERE singleton;
 END IF;
END $$;


--
-- Name: snapshot_cache_readability_changed(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.snapshot_cache_readability_changed() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF TG_OP<>'TRUNCATE' AND TG_LEVEL='STATEMENT' THEN
  IF NOT EXISTS(SELECT 1 FROM changed_rows) THEN RETURN NULL; END IF;
 END IF;
 PERFORM snapshot_cache_record_writer(ARRAY['readability']);
 PERFORM nextval('snapshot_cache_readability');
 PERFORM pg_notify('plowshare_readability_changes','readability');
 RETURN NULL;
END $$;


--
-- Name: snapshot_cache_record_writer(text[]); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.snapshot_cache_record_writer(selected text[]) RETURNS void
    LANGUAGE plpgsql
    AS $$
DECLARE seen TEXT[]; added TEXT[]; first_write BOOLEAN;
BEGIN
 IF selected IS NULL OR cardinality(selected)=0 OR NOT selected <@ ARRAY[
  'documents','search','schedules','relay','transcripts','authority','observations','readability','queues','progress','types','extractions','knowledge_access','knowledge_revocations'] THEN
  RAISE EXCEPTION 'Unknown projection writer domains';
 END IF;
 seen:=string_to_array(nullif(current_setting('plowshare.cache_writer_domains',true),''),',');
 IF selected <@ coalesce(seen,ARRAY[]::text[]) THEN RETURN; END IF;
 first_write:=seen IS NULL;
 SELECT array_agg(DISTINCT domain ORDER BY domain) INTO added FROM unnest(selected) domain
  WHERE NOT domain=ANY(coalesce(seen,ARRAY[]::text[]));
 seen:=coalesce(seen,ARRAY[]::text[])||added;
 INSERT INTO snapshot_cache_writers(xid,domain)
  SELECT pg_current_xact_id(),domain FROM unnest(added) domain
  ON CONFLICT(domain,xid) DO NOTHING;
 PERFORM set_config('plowshare.cache_writer_domains',array_to_string(seen,','),true);
 PERFORM set_config('plowshare.cache_dirty','on',true);
 IF first_write AND nextval('snapshot_cache_writer_allocations')%64=0 THEN
  PERFORM snapshot_cache_prune_writers();
 END IF;
END $$;


--
-- Name: snapshot_cache_relay_changed(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.snapshot_cache_relay_changed() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF TG_OP<>'TRUNCATE' THEN
  IF NOT EXISTS(SELECT 1 FROM changed_rows) THEN RETURN NULL; END IF;
  IF TG_OP='UPDATE' AND TG_TABLE_NAME<>'conversations' THEN
   IF NOT EXISTS(SELECT to_jsonb(n) FROM changed_rows n EXCEPT SELECT to_jsonb(o) FROM old_rows o) THEN RETURN NULL; END IF;
  END IF;
  -- Only conversation identity/project fields enter branch diagnostics. Token, transcript and
  -- compaction updates must not flush every Relay page or wake every worker during inference.
  IF TG_TABLE_NAME='conversations' THEN
   IF TG_OP='UPDATE' THEN
    IF NOT EXISTS(SELECT 1 FROM changed_rows n FULL JOIN old_rows o USING(id)
      WHERE n.id IS NULL OR o.id IS NULL OR n.project_id IS DISTINCT FROM o.project_id) THEN
     RETURN NULL;
    END IF;
    IF NOT EXISTS(SELECT 1 FROM relay_executions e WHERE e.conversation_id IN
      (SELECT id FROM changed_rows UNION SELECT id FROM old_rows)) THEN RETURN NULL; END IF;
   ELSIF NOT EXISTS(SELECT 1 FROM changed_rows c JOIN relay_executions e ON e.conversation_id=c.id) THEN
    RETURN NULL;
   END IF;
  END IF;
 END IF;
 PERFORM snapshot_cache_record_writer(ARRAY['relay']);
 PERFORM nextval('snapshot_cache_relay');
 PERFORM pg_notify('plowshare_projection_changes','relay');
 RETURN NULL;
END $$;


--
-- Name: snapshot_cache_source_changed(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.snapshot_cache_source_changed() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF TG_OP<>'TRUNCATE' THEN
  IF NOT EXISTS(SELECT 1 FROM changed_rows) THEN RETURN NULL; END IF;
  IF TG_OP='UPDATE' AND TG_TABLE_NAME IN ('schedule_sources','schedule_files') THEN
   IF NOT EXISTS(SELECT to_jsonb(n) FROM changed_rows n EXCEPT SELECT to_jsonb(o) FROM previous_rows o) THEN RETURN NULL; END IF;
  END IF;
  IF TG_TABLE_NAME='execution_allowances' THEN
   IF NOT EXISTS(SELECT 1 FROM changed_rows changed JOIN information_revisions revision
     ON revision.processing_budget_id=changed.id) THEN RETURN NULL; END IF;
  END IF;
 END IF;
 PERFORM snapshot_cache_bump(TG_ARGV);
 RETURN NULL;
END $$;


--
-- Name: snapshot_cache_transcript_changed(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.snapshot_cache_transcript_changed() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 -- TRUNCATE has no transition relation. Keep a separate PL/pgSQL branch so its statement never
 -- plans a query referencing the absent relation, even before boolean short-circuit evaluation.
 IF TG_OP<>'TRUNCATE' THEN
  IF NOT EXISTS(SELECT 1 FROM changed_rows) THEN RETURN NULL; END IF;
 END IF;
 PERFORM snapshot_cache_record_writer(ARRAY['transcripts']);
 PERFORM nextval('snapshot_cache_transcripts');
 PERFORM pg_notify('plowshare_projection_changes','transcripts');
 RETURN NULL;
END $$;


--
-- Name: withdraw_deleted_project_tool_hosts(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.withdraw_deleted_project_tool_hosts() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    DELETE FROM hosted_tool_scope_workers w USING hosted_tool_scopes b
        WHERE w.binding_id=b.id AND b.project_id=OLD.id;
    UPDATE hosted_tool_scopes SET enabled=FALSE,deleted=TRUE,generation=LEAST(generation+1,9007199254740991)
        WHERE project_id=OLD.id;
    RETURN OLD;
END;
$$;


--
-- Name: acting_audit; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.acting_audit (
    id bigint NOT NULL,
    actor text NOT NULL,
    action text NOT NULL,
    application text NOT NULL,
    grant_name text,
    token_id uuid,
    request_id uuid,
    revision uuid,
    occurred_at timestamp with time zone NOT NULL,
    CONSTRAINT acting_audit_action_check CHECK ((action = ANY (ARRAY['ACTIVATE'::text, 'ISSUE'::text, 'REVOKE'::text, 'ELIGIBILITY'::text])))
);


--
-- Name: acting_audit_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.acting_audit_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: acting_audit_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.acting_audit_id_seq OWNED BY public.acting_audit.id;


--
-- Name: acting_context_audit; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.acting_context_audit (
    id bigint NOT NULL,
    actor text NOT NULL,
    application text NOT NULL,
    grant_name text NOT NULL,
    action text NOT NULL,
    context_id uuid,
    subject text,
    occurred_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT acting_context_audit_action_check CHECK ((action = ANY (ARRAY['OPEN'::text, 'REFUSED'::text, 'CLOSE'::text]))),
    CONSTRAINT acting_context_audit_application_check CHECK (((length(application) >= 1) AND (length(application) <= 256))),
    CONSTRAINT acting_context_audit_check CHECK (((action = 'REFUSED'::text) = (context_id IS NULL))),
    CONSTRAINT acting_context_audit_check1 CHECK (((action = 'REFUSED'::text) = (subject IS NULL))),
    CONSTRAINT acting_context_audit_grant_name_check CHECK ((grant_name ~ '^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$'::text))
);


--
-- Name: acting_context_audit_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.acting_context_audit_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: acting_context_audit_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.acting_context_audit_id_seq OWNED BY public.acting_context_audit.id;


--
-- Name: acting_eligible_accounts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.acting_eligible_accounts (
    application text NOT NULL,
    grant_name text NOT NULL,
    account text NOT NULL
);


--
-- Name: acting_eligible_projects; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.acting_eligible_projects (
    application text NOT NULL,
    grant_name text NOT NULL,
    project_id bigint NOT NULL
);


--
-- Name: acting_grants; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.acting_grants (
    application text NOT NULL,
    name text NOT NULL,
    revision uuid NOT NULL,
    declared boolean NOT NULL,
    ceiling text NOT NULL,
    personal boolean NOT NULL,
    acting_token boolean NOT NULL,
    CONSTRAINT acting_grants_ceiling_check CHECK ((ceiling = ANY (ARRAY['VIEWER'::text, 'CONTRIBUTOR'::text]))),
    CONSTRAINT acting_grants_name_check CHECK ((name ~ '^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$'::text))
);


--
-- Name: acting_mutation_receipts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.acting_mutation_receipts (
    request_id uuid NOT NULL,
    actor text NOT NULL,
    fingerprint text NOT NULL,
    action text NOT NULL,
    application text NOT NULL,
    grant_name text NOT NULL,
    token_id uuid,
    completed_at timestamp with time zone NOT NULL,
    CONSTRAINT acting_mutation_receipts_action_check CHECK ((action = ANY (ARRAY['ISSUE'::text, 'REVOKE'::text, 'ELIGIBILITY'::text]))),
    CONSTRAINT acting_mutation_receipts_check CHECK (((action = 'ELIGIBILITY'::text) = (token_id IS NULL))),
    CONSTRAINT acting_mutation_receipts_fingerprint_check CHECK ((fingerprint ~ '^[0-9a-f]{64}$'::text))
);


--
-- Name: acting_token_projects; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.acting_token_projects (
    token_id uuid NOT NULL,
    project_id bigint NOT NULL
);


--
-- Name: acting_tokens; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.acting_tokens (
    id uuid NOT NULL,
    application text NOT NULL,
    grant_name text NOT NULL,
    actor text NOT NULL,
    subject text NOT NULL,
    ceiling text NOT NULL,
    personal boolean NOT NULL,
    digest text NOT NULL,
    external_subject text,
    created_at timestamp with time zone NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    revoked_at timestamp with time zone,
    CONSTRAINT acting_tokens_actor_check CHECK ((actor ~ '^@service/[0-9a-f-]{36}$'::text)),
    CONSTRAINT acting_tokens_ceiling_check CHECK ((ceiling = ANY (ARRAY['VIEWER'::text, 'CONTRIBUTOR'::text]))),
    CONSTRAINT acting_tokens_check CHECK ((expires_at > created_at)),
    CONSTRAINT acting_tokens_digest_check CHECK ((digest ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT acting_tokens_external_subject_check CHECK (((length(external_subject) >= 1) AND (length(external_subject) <= 512)))
);


--
-- Name: acting_work_conversations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.acting_work_conversations (
    conversation text NOT NULL,
    personal_project_id bigint NOT NULL,
    actor text NOT NULL,
    subject text NOT NULL,
    application text NOT NULL,
    grant_name text NOT NULL,
    proof uuid NOT NULL,
    ceiling text NOT NULL,
    CONSTRAINT acting_work_conversations_ceiling_check CHECK ((ceiling = ANY (ARRAY['VIEWER'::text, 'CONTRIBUTOR'::text])))
);


--
-- Name: acting_work_receipts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.acting_work_receipts (
    actor text NOT NULL,
    request_id uuid NOT NULL,
    fingerprint text NOT NULL,
    conversation text NOT NULL,
    agent text NOT NULL,
    state text NOT NULL,
    job text,
    admitted_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT acting_work_receipts_agent_check CHECK (((length(agent) >= 1) AND (length(agent) <= 64))),
    CONSTRAINT acting_work_receipts_check CHECK (((state <> ALL (ARRAY['STARTED'::text, 'FINISHED'::text])) OR (job IS NOT NULL))),
    CONSTRAINT acting_work_receipts_fingerprint_check CHECK ((fingerprint ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT acting_work_receipts_state_check CHECK ((state = ANY (ARRAY['ADMITTED'::text, 'STARTED'::text, 'FINISHED'::text, 'UNCERTAIN'::text])))
);


--
-- Name: admin_audit; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.admin_audit (
    id bigint NOT NULL,
    occurred_at timestamp with time zone DEFAULT now() NOT NULL,
    actor text NOT NULL,
    action text NOT NULL,
    target text NOT NULL,
    enabled boolean,
    server_admin boolean
);


--
-- Name: admin_audit_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.admin_audit_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: admin_audit_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.admin_audit_id_seq OWNED BY public.admin_audit.id;


--
-- Name: admin_pricing; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.admin_pricing (
    billing_route text NOT NULL,
    model text NOT NULL,
    rate_card jsonb NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: admin_pricing_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.admin_pricing_history (
    id bigint NOT NULL,
    occurred_at timestamp with time zone DEFAULT now() NOT NULL,
    actor text NOT NULL,
    billing_route text NOT NULL,
    model text NOT NULL,
    version text NOT NULL,
    rate_card jsonb NOT NULL
);


--
-- Name: admin_pricing_history_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.admin_pricing_history_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: admin_pricing_history_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.admin_pricing_history_id_seq OWNED BY public.admin_pricing_history.id;


--
-- Name: admins; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.admins (
    handle text NOT NULL,
    password_hash text NOT NULL,
    must_change_password boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    server_admin boolean DEFAULT false NOT NULL,
    bootstrap boolean DEFAULT false NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    session_version bigint DEFAULT 0 NOT NULL,
    account_kind text DEFAULT 'USER'::text NOT NULL,
    CONSTRAINT admins_account_kind_check CHECK ((account_kind = ANY (ARRAY['USER'::text, 'SERVICE'::text, 'SERVICE_TOKEN'::text, 'BUILTIN'::text]))),
    CONSTRAINT admins_bootstrap_restricted CHECK (((NOT bootstrap) OR (must_change_password AND (NOT server_admin)))),
    CONSTRAINT admins_handle_named CHECK ((handle <> ''::text)),
    CONSTRAINT admins_password_hash_named CHECK ((password_hash <> ''::text)),
    CONSTRAINT builtin_accounts_are_locked CHECK (((account_kind <> 'BUILTIN'::text) OR ((handle = ANY (ARRAY['SYSTEM'::text, 'GLOBAL'::text])) AND (NOT enabled) AND (NOT bootstrap) AND (NOT server_admin) AND (NOT must_change_password) AND (session_version = 0) AND (password_hash = '!locked-built-in'::text)))),
    CONSTRAINT machine_accounts_are_not_administrators CHECK (((account_kind = 'USER'::text) OR ((NOT server_admin) AND (NOT bootstrap) AND (NOT must_change_password))))
);


--
-- Name: COLUMN admins.password_hash; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.admins.password_hash IS 'The Argon2id encoded hash from auth.PasswordHasher.hash -- salt and cost parameters included in the string, nothing stored beside it. Never the plaintext password, and never logged: see auth.Tokens for why this server treats an accept-side secret as something that is only ever checked and not recovered.';


--
-- Name: COLUMN admins.must_change_password; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.admins.must_change_password IS 'True until a login sets a real password. A seeded account is created with this true and stays true across restarts -- it is cleared only by the password actually changing, never by time or by a later boot -- so a default password an operator forgot to rotate cannot quietly become the permanent one.';


--
-- Name: application_deployment_heads; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.application_deployment_heads (
    project_id bigint NOT NULL,
    revision uuid NOT NULL
);


--
-- Name: application_deployment_receipts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.application_deployment_receipts (
    request_id uuid NOT NULL,
    account text NOT NULL,
    project_id bigint NOT NULL,
    fingerprint text NOT NULL,
    revision uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT application_deployment_receipts_fingerprint_check CHECK ((fingerprint ~ '^[0-9a-f]{64}$'::text))
);


--
-- Name: application_identities; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.application_identities (
    project_name text NOT NULL,
    project_id bigint,
    service_handle text NOT NULL,
    role text NOT NULL,
    token_id uuid NOT NULL,
    state text DEFAULT 'RESERVED'::text NOT NULL,
    credential_request_id uuid,
    reserved_by text NOT NULL,
    reserved_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT application_identities_check CHECK (((state <> 'RESERVED'::text) OR (project_id IS NULL))),
    CONSTRAINT application_identities_project_name_check CHECK (((length(project_name) >= 1) AND (length(project_name) <= 256))),
    CONSTRAINT application_identities_role_check CHECK ((role = ANY (ARRAY['VIEWER'::text, 'CONTRIBUTOR'::text]))),
    CONSTRAINT application_identities_state_check CHECK ((state = ANY (ARRAY['RESERVED'::text, 'ACTIVE'::text])))
);


--
-- Name: application_releases; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.application_releases (
    revision uuid NOT NULL,
    project_id bigint NOT NULL,
    digest text NOT NULL,
    file_count integer NOT NULL,
    destination jsonb NOT NULL,
    placement jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT application_releases_digest_check CHECK ((digest ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT application_releases_file_count_check CHECK (((file_count >= 1) AND (file_count <= 128)))
);


--
-- Name: application_token_receipts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.application_token_receipts (
    request_id uuid NOT NULL,
    actor text NOT NULL,
    project_name text NOT NULL,
    token_id uuid NOT NULL,
    expires_in_days integer NOT NULL,
    rotate boolean NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT application_token_receipts_expires_in_days_check CHECK (((expires_in_days >= 1) AND (expires_in_days <= 365)))
);


--
-- Name: auth_session_chains; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.auth_session_chains (
    id uuid NOT NULL,
    handle text NOT NULL,
    restricted boolean NOT NULL,
    revoked boolean DEFAULT false NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    session_version bigint DEFAULT 0 NOT NULL
);


--
-- Name: auth_session_grants; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.auth_session_grants (
    digest text NOT NULL,
    chain_id uuid NOT NULL,
    kind text NOT NULL,
    spent boolean DEFAULT false NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    CONSTRAINT auth_session_grants_kind_check CHECK ((kind = ANY (ARRAY['access'::text, 'refresh'::text])))
);


--
-- Name: authority_accounts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.authority_accounts (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    legacy_handle text NOT NULL,
    builtin text,
    CONSTRAINT authority_account_registry_shape CHECK ((((builtin IS NULL) AND (NOT public.authority_reserved_id(id))) OR ((builtin IS NOT NULL) AND (((builtin = 'SYSTEM'::text) AND (id = '00000000-0000-0000-0000-000000000001'::uuid) AND (legacy_handle = 'SYSTEM'::text)) OR ((builtin = 'GLOBAL'::text) AND (id = '00000000-0000-0000-0000-000000000004'::uuid) AND (legacy_handle = 'GLOBAL'::text)))))),
    CONSTRAINT authority_accounts_builtin_check CHECK ((builtin = ANY (ARRAY['SYSTEM'::text, 'GLOBAL'::text]))),
    CONSTRAINT authority_accounts_id_check CHECK ((id <> '00000000-0000-0000-0000-000000000000'::uuid))
);


--
-- Name: authority_grant_audit; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.authority_grant_audit (
    id bigint NOT NULL,
    grant_id uuid NOT NULL,
    occurred_at timestamp with time zone DEFAULT now() NOT NULL,
    event text NOT NULL,
    version bigint NOT NULL,
    actor_account uuid NOT NULL,
    actor_agent text,
    authority uuid,
    CONSTRAINT authority_grant_audit_event_check CHECK ((event = ANY (ARRAY['ISSUE'::text, 'REVOKE'::text])))
);


--
-- Name: authority_grant_audit_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.authority_grant_audit_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: authority_grant_audit_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.authority_grant_audit_id_seq OWNED BY public.authority_grant_audit.id;


--
-- Name: authority_grants; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.authority_grants (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    subject_account uuid NOT NULL,
    subject_agent text,
    scope_id uuid NOT NULL,
    role_id uuid NOT NULL,
    requested_role text NOT NULL,
    role_version integer DEFAULT 1 NOT NULL,
    coverage text DEFAULT 'SELF'::text NOT NULL,
    issuer_account uuid NOT NULL,
    issuer_agent text,
    issuing_grant uuid,
    issuing_token uuid,
    manager_forced boolean DEFAULT false NOT NULL,
    provenance text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    expires_at timestamp with time zone,
    revoked_at timestamp with time zone,
    revoked_by uuid,
    revoked_by_agent text,
    revoking_grant uuid,
    version bigint DEFAULT 1 NOT NULL,
    CONSTRAINT authority_grants_check CHECK (((expires_at IS NULL) OR (expires_at > created_at))),
    CONSTRAINT authority_grants_check1 CHECK ((issuing_grant IS DISTINCT FROM id)),
    CONSTRAINT authority_grants_check2 CHECK (((provenance = 'ROOT_POLICY'::text) = (issuing_grant IS NULL))),
    CONSTRAINT authority_grants_coverage_check CHECK ((coverage = ANY (ARRAY['SELF'::text, 'ORDINARY_PROJECT_CONTROL'::text, 'ALL_SCOPES'::text]))),
    CONSTRAINT authority_grants_issuer_agent_check CHECK ((issuer_agent ~ '^[A-Za-z0-9][A-Za-z0-9_.:/-]{0,127}$'::text)),
    CONSTRAINT authority_grants_provenance_check CHECK ((provenance = ANY (ARRAY['ROOT_POLICY'::text, 'MEMBERSHIP'::text, 'LEGACY_ADMIN_DATA'::text, 'ADMIN_CONTROL'::text, 'ROLE'::text]))),
    CONSTRAINT authority_grants_requested_role_check CHECK ((requested_role = ANY (ARRAY['SYSTEM'::text, 'ADMIN'::text, 'MANAGER'::text, 'CONTRIBUTOR'::text, 'READER'::text]))),
    CONSTRAINT authority_grants_role_version_check CHECK ((role_version = 1)),
    CONSTRAINT authority_grants_subject_agent_check CHECK ((subject_agent ~ '^[A-Za-z0-9][A-Za-z0-9_.:/-]{0,127}$'::text)),
    CONSTRAINT authority_grants_version_check CHECK ((version > 0))
);


--
-- Name: authority_role_actions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.authority_role_actions (
    role_id uuid NOT NULL,
    role_version integer NOT NULL,
    profile text NOT NULL,
    action text NOT NULL,
    CONSTRAINT authority_role_actions_action_check CHECK ((action = ANY (ARRAY['READ'::text, 'WRITE'::text, 'MANAGE_ACCESS'::text, 'MANAGE_SCOPE'::text, 'SET_AUDIENCE'::text, 'ADMINISTER'::text, 'CONVERSATION_CREATE'::text, 'RUN_MODEL'::text]))),
    CONSTRAINT authority_role_actions_profile_check CHECK ((profile = ANY (ARRAY['DEFAULT'::text, 'GLOBAL_KNOWLEDGE'::text]))),
    CONSTRAINT authority_role_actions_role_version_check CHECK ((role_version = 1))
);


--
-- Name: authority_roles; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.authority_roles (
    id uuid NOT NULL,
    name text NOT NULL,
    assignable boolean NOT NULL,
    intrinsic_account uuid,
    CONSTRAINT authority_role_registry_shape CHECK ((((id = '00000000-0000-0000-0000-000000000002'::uuid) AND (name = 'SYSTEM'::text) AND (NOT assignable) AND (intrinsic_account IS NOT NULL) AND (intrinsic_account = '00000000-0000-0000-0000-000000000001'::uuid)) OR (assignable AND (intrinsic_account IS NULL) AND (((id = '00000000-0000-0000-0000-000000000006'::uuid) AND (name = 'GLOBAL_READ'::text)) OR ((id = '00000000-0000-0000-0000-000000000007'::uuid) AND (name = 'ADMIN'::text)) OR ((id = '00000000-0000-0000-0000-000000000008'::uuid) AND (name = 'MANAGER'::text)) OR ((id = '00000000-0000-0000-0000-000000000009'::uuid) AND (name = 'CONTRIBUTOR'::text)) OR ((id = '00000000-0000-0000-0000-00000000000a'::uuid) AND (name = 'READER'::text))))))
);


--
-- Name: authority_scopes; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.authority_scopes (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    kind text NOT NULL,
    project_id bigint,
    account_id uuid,
    builtin text,
    restriction_reason text,
    container uuid,
    CONSTRAINT authority_scope_kind CHECK ((kind = ANY (ARRAY['PROJECT'::text, 'PERSONAL'::text, 'ACCOUNT_KNOWLEDGE'::text, 'ADMINISTRATION'::text, 'RESTRICTED'::text, 'CONTAINED'::text]))),
    CONSTRAINT authority_scope_kind_shape CHECK (((((kind = 'PROJECT'::text) AND (project_id IS NOT NULL) AND (account_id IS NULL)) OR ((kind = 'PERSONAL'::text) AND (project_id IS NOT NULL) AND (account_id IS NOT NULL)) OR ((kind = ANY (ARRAY['ACCOUNT_KNOWLEDGE'::text, 'ADMINISTRATION'::text])) AND (project_id IS NULL) AND (account_id IS NOT NULL)) OR ((kind = 'RESTRICTED'::text) AND (project_id IS NOT NULL) AND (account_id IS NULL)) OR ((kind = 'CONTAINED'::text) AND (project_id IS NOT NULL) AND (account_id IS NULL) AND (container IS NOT NULL))) AND ((kind = 'CONTAINED'::text) OR (container IS NULL)))),
    CONSTRAINT authority_scope_not_its_own_container CHECK (((container IS NULL) OR (container <> id))),
    CONSTRAINT authority_scopes_builtin_check CHECK ((builtin = ANY (ARRAY['SYSTEM_ADMINISTRATION'::text, 'GLOBAL_KNOWLEDGE'::text]))),
    CONSTRAINT authority_scopes_check1 CHECK ((((kind = 'RESTRICTED'::text) AND (restriction_reason IS NOT NULL) AND (length(restriction_reason) > 0)) OR ((kind <> 'RESTRICTED'::text) AND (restriction_reason IS NULL)))),
    CONSTRAINT authority_scopes_check2 CHECK ((((builtin IS NULL) AND (NOT public.authority_reserved_id(id)) AND (kind <> 'ADMINISTRATION'::text)) OR ((builtin IS NOT NULL) AND (((builtin = 'SYSTEM_ADMINISTRATION'::text) AND (id = '00000000-0000-0000-0000-000000000003'::uuid) AND (kind = 'ADMINISTRATION'::text) AND (account_id = '00000000-0000-0000-0000-000000000001'::uuid)) OR ((builtin = 'GLOBAL_KNOWLEDGE'::text) AND (id = '00000000-0000-0000-0000-000000000005'::uuid) AND (kind = 'ACCOUNT_KNOWLEDGE'::text) AND (account_id = '00000000-0000-0000-0000-000000000004'::uuid)))))),
    CONSTRAINT authority_scopes_id_check CHECK ((id <> '00000000-0000-0000-0000-000000000000'::uuid))
);


--
-- Name: projects; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.projects (
    name text NOT NULL,
    workspace text,
    exclusions text[] DEFAULT '{}'::text[] NOT NULL,
    defined_at timestamp with time zone DEFAULT now() NOT NULL,
    id bigint NOT NULL,
    machine text,
    lent text[] DEFAULT '{}'::text[] NOT NULL,
    union_since timestamp with time zone,
    sync_hidden text[] DEFAULT '{}'::text[] NOT NULL,
    personal_owner text,
    project_type text DEFAULT 'STANDARD'::text NOT NULL,
    write_paths text[] DEFAULT ARRAY['.'::text] NOT NULL,
    application_boundary boolean DEFAULT false NOT NULL,
    application_storage jsonb,
    CONSTRAINT disjoint_never_union CHECK (((project_type <> 'DISJOINT'::text) OR (union_since IS NULL))),
    CONSTRAINT projects_a_union_has_a_place CHECK (((union_since IS NULL) OR (machine IS NOT NULL) OR (personal_owner IS NOT NULL))),
    CONSTRAINT projects_application_storage_shape CHECK (((application_storage IS NULL) OR ((machine IS NULL) AND (project_type = ANY (ARRAY['MANAGED'::text, 'DISJOINT'::text])) AND application_boundary AND (jsonb_typeof(application_storage) = 'object'::text) AND (application_storage ? 'applicationRoot'::text) AND (application_storage ? 'writableAreas'::text) AND (jsonb_typeof((application_storage -> 'applicationRoot'::text)) = 'object'::text) AND (jsonb_typeof((application_storage -> 'writableAreas'::text)) = 'array'::text)))),
    CONSTRAINT projects_exclusions_named CHECK ((NOT (''::text = ANY (exclusions)))),
    CONSTRAINT projects_id_is_positive CHECK ((id > 0)),
    CONSTRAINT projects_lent_named CHECK ((NOT (''::text = ANY (lent)))),
    CONSTRAINT projects_machine_has_a_place CHECK (((machine IS NULL) OR (workspace IS NOT NULL))),
    CONSTRAINT projects_machine_named CHECK ((machine <> ''::text)),
    CONSTRAINT projects_name_named CHECK ((name <> ''::text)),
    CONSTRAINT projects_personal_identity CHECK (((personal_owner IS NULL) OR (name = ('personal:'::text || encode(convert_to(personal_owner, 'UTF8'::name), 'hex'::text))))),
    CONSTRAINT projects_project_type_check CHECK ((project_type = ANY (ARRAY['STANDARD'::text, 'MANAGED'::text, 'DISJOINT'::text]))),
    CONSTRAINT projects_workspace_named CHECK ((workspace <> ''::text))
);


--
-- Name: COLUMN projects.workspace; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.projects.workspace IS 'Where this project''s files are on the machine named by the machine column -- the directory its jobs may reach when that column is NULL, and a path on somebody else''s disk when it is not. NULL for a project that has never been given a place. MUST NOT be resolved against this server''s filesystem unless machine IS NULL: on a row with a machine it is a string only that machine can interpret.';


--
-- Name: COLUMN projects.exclusions; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.projects.exclusions IS 'Extra paths this project may not reach. The two mandatory exclusions are applied in Java and are not stored here, so a row cannot override them.';


--
-- Name: COLUMN projects.machine; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.projects.machine IS 'The machine this project''s files are on, as the client holding them calls itself, or NULL for this server. Read it with workspace and never alone: NULL with a workspace is server-rooted, NULL without one is a project that has no place at all. It is the <MACHINE> of the canonical name <MACHINE>/<PATH>/<PROJ_NAME>, and it is written by a client declaring a presence -- never guessed by the server, which cannot know it.';


--
-- Name: COLUMN projects.lent; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.projects.lent IS 'Additional directories this project''s jobs may reach, beyond the workspace. The leash is workspace prepended to this list; the identity is the workspace alone and never reads this column, which is why workspace stayed scalar. A lent root may sit outside the workspace -- the only bound is the mandatory exclusions, applied in Java, which FileAccess.of drops a covered root against whether it came from here or from workspace.';


--
-- Name: authority_grant_records; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.authority_grant_records AS
 SELECT g.id,
    g.subject_account,
    g.subject_agent,
    g.scope_id,
    g.role_id,
    g.requested_role,
    g.role_version,
    g.coverage,
    g.issuer_account,
    g.issuer_agent,
    g.issuing_grant,
    g.issuing_token,
    g.manager_forced,
    g.provenance,
    g.created_at,
    g.expires_at,
    g.revoked_at,
    g.revoked_by,
    g.revoked_by_agent,
    g.revoking_grant,
    g.version,
    r.name AS effective_role,
    a.legacy_handle AS subject_handle,
    p.name AS project_name,
    ARRAY( SELECT a_1.action
           FROM public.authority_role_actions a_1
          WHERE ((a_1.role_id = g.role_id) AND (a_1.role_version = g.role_version) AND ((a_1.profile = 'DEFAULT'::text) OR (a_1.profile = s.builtin)))
          ORDER BY a_1.action) AS actions
   FROM ((((public.authority_grants g
     JOIN public.authority_roles r ON ((r.id = g.role_id)))
     LEFT JOIN public.authority_scopes s ON ((s.id = g.scope_id)))
     JOIN public.authority_accounts a ON ((a.id = g.subject_account)))
     LEFT JOIN public.projects p ON ((p.id = s.project_id)));


--
-- Name: binary_blob_aliases; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.binary_blob_aliases (
    scope text NOT NULL,
    alias text NOT NULL,
    sha256 text NOT NULL,
    CONSTRAINT binary_blob_aliases_alias_check CHECK ((((length(alias) >= 1) AND (length(alias) <= 256)) AND (alias ~ '^[A-Za-z0-9_.:-]+$'::text)))
);


--
-- Name: binary_blob_claims; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.binary_blob_claims (
    scope text NOT NULL,
    kind text NOT NULL,
    owner text NOT NULL,
    slot integer NOT NULL,
    sha256 text NOT NULL,
    retention text NOT NULL,
    CONSTRAINT binary_blob_claims_kind_check CHECK ((kind ~ '^[a-z][a-z0-9._-]{0,63}$'::text)),
    CONSTRAINT binary_blob_claims_owner_check CHECK ((((length(owner) >= 1) AND (length(owner) <= 256)) AND (owner ~ '^[A-Za-z0-9_.:-]+$'::text))),
    CONSTRAINT binary_blob_claims_retention_check CHECK ((retention = ANY (ARRAY['PINNED'::text, 'RELEASABLE'::text]))),
    CONSTRAINT binary_blob_claims_slot_check CHECK (((slot >= 0) AND (slot <= 65535)))
);


--
-- Name: binary_blobs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.binary_blobs (
    scope text NOT NULL,
    sha256 text NOT NULL,
    file text NOT NULL,
    byte_length bigint NOT NULL,
    generation bigint DEFAULT 1 NOT NULL,
    state text NOT NULL,
    unclaimed_at timestamp with time zone NOT NULL,
    CONSTRAINT binary_blobs_byte_length_check CHECK (((byte_length >= 1) AND (byte_length <= 2147483647))),
    CONSTRAINT binary_blobs_file_check CHECK (((length(file) >= 1) AND (length(file) <= 1024))),
    CONSTRAINT binary_blobs_generation_check CHECK ((generation > 0)),
    CONSTRAINT binary_blobs_scope_check CHECK ((scope ~ '^(global|projects/[1-9][0-9]{0,18})$'::text)),
    CONSTRAINT binary_blobs_sha256_check CHECK ((sha256 ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT binary_blobs_state_check CHECK ((state = ANY (ARRAY['READY'::text, 'DELETING'::text, 'DELETED'::text])))
);


--
-- Name: board_topics; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.board_topics (
    id text NOT NULL,
    project text NOT NULL,
    parent text,
    root text NOT NULL,
    depth integer NOT NULL,
    title text NOT NULL,
    label text NOT NULL,
    account text NOT NULL,
    opener_kind text NOT NULL,
    opener text NOT NULL,
    origin_conversation text,
    state text NOT NULL,
    resolution text,
    pot_total integer,
    pot_spent integer,
    reserve integer,
    quiet_notified_at timestamp with time zone,
    opened_at timestamp with time zone NOT NULL,
    closed_at timestamp with time zone,
    resolution_delivered_at timestamp with time zone,
    quiet_through text,
    swarm_selection jsonb,
    allowance_id uuid,
    CONSTRAINT board_topic_swarm_selection CHECK (((swarm_selection IS NULL) OR COALESCE(((jsonb_typeof(swarm_selection) = 'object'::text) AND (jsonb_typeof((swarm_selection -> 'name'::text)) = 'string'::text) AND (length((swarm_selection ->> 'name'::text)) <= 64) AND ((swarm_selection ->> 'name'::text) ~ '^[a-z][a-z0-9]*([-_][a-z0-9]+)*$'::text) AND ((swarm_selection ->> 'revision'::text) ~ '^[a-f0-9]{64}$'::text) AND (jsonb_typeof((swarm_selection -> 'description'::text)) = 'string'::text) AND (length((swarm_selection ->> 'description'::text)) <= 4096) AND (jsonb_typeof((swarm_selection -> 'members'::text)) = 'array'::text) AND ((jsonb_array_length((swarm_selection -> 'members'::text)) >= 1) AND (jsonb_array_length((swarm_selection -> 'members'::text)) <= 64)) AND (jsonb_typeof((swarm_selection -> 'budget'::text)) = 'number'::text) AND ((swarm_selection ->> 'budget'::text) ~ '^[0-9]+$'::text) AND ((((swarm_selection ->> 'budget'::text))::numeric >= (2)::numeric) AND (((swarm_selection ->> 'budget'::text))::numeric <= (2147483647)::numeric))), false))),
    CONSTRAINT board_topics_a_definition_opens_from_a_conversation CHECK (((opener_kind = ANY (ARRAY['bot'::text, 'agent'::text])) = (origin_conversation IS NOT NULL))),
    CONSTRAINT board_topics_a_root_is_depth_zero CHECK (((parent IS NULL) = (depth = 0))),
    CONSTRAINT board_topics_a_root_is_its_own_root CHECK (((parent IS NULL) = (root = id))),
    CONSTRAINT board_topics_allowance_shape CHECK ((((parent IS NULL) AND (pot_spent IS NOT NULL) AND (reserve IS NOT NULL) AND (reserve >= 0)) OR ((parent IS NOT NULL) AND (pot_total IS NULL) AND (pot_spent IS NULL) AND (reserve IS NULL)))),
    CONSTRAINT board_topics_closed_iff_ended CHECK (((state = 'closed'::text) = (closed_at IS NOT NULL))),
    CONSTRAINT board_topics_delivered_only_once_resolved CHECK (((resolution_delivered_at IS NULL) OR (resolution IS NOT NULL))),
    CONSTRAINT board_topics_named CHECK (((project <> ''::text) AND (title <> ''::text) AND (label <> ''::text))),
    CONSTRAINT board_topics_opener_kind_is_known CHECK ((opener_kind = ANY (ARRAY['person'::text, 'bot'::text, 'agent'::text, 'member'::text]))),
    CONSTRAINT board_topics_state_is_known CHECK ((state = ANY (ARRAY['open'::text, 'exhausted'::text, 'closed'::text]))),
    CONSTRAINT board_topics_title_and_label_are_bounded CHECK (((char_length(title) <= 120) AND (char_length(label) <= 60))),
    -- Escape the line breaks explicitly so dump readers cannot normalize CR to LF.
    CONSTRAINT board_topics_title_and_label_are_one_line CHECK (
        POSITION(E'\n' IN title) = 0 AND POSITION(E'\r' IN title) = 0
        AND POSITION(E'\n' IN label) = 0 AND POSITION(E'\r' IN label) = 0
    )
);


--
-- Name: execution_allowances; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.execution_allowances (
    id uuid NOT NULL,
    call_limit integer,
    spent integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT execution_allowances_call_limit_check CHECK ((call_limit >= 0)),
    CONSTRAINT execution_allowances_spent_check CHECK ((spent >= 0))
);


--
-- Name: board_allowance_state; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.board_allowance_state AS
 SELECT r.id,
    r.project,
    r.parent,
    r.root,
    r.depth,
    r.title,
    r.label,
    r.account,
    r.opener_kind,
    r.opener,
    r.origin_conversation,
    r.state,
    r.resolution,
        CASE
            WHEN (r.parent IS NULL) THEN a.call_limit
            ELSE NULL::integer
        END AS pot_total,
        CASE
            WHEN (r.parent IS NULL) THEN a.spent
            ELSE NULL::integer
        END AS pot_spent,
    r.reserve,
    r.quiet_notified_at,
    r.opened_at,
    r.closed_at,
    r.resolution_delivered_at,
    r.quiet_through,
    r.swarm_selection,
    r.allowance_id
   FROM (public.board_topics r
     JOIN public.execution_allowances a ON ((a.id = r.allowance_id)));


--
-- Name: board_decisions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.board_decisions (
    request text NOT NULL,
    approved boolean NOT NULL,
    reason text NOT NULL,
    child text,
    decided_at timestamp with time zone NOT NULL,
    CONSTRAINT board_decisions_check CHECK ((approved = (child IS NOT NULL))),
    CONSTRAINT board_decisions_reason_check CHECK ((reason <> ''::text))
);


--
-- Name: board_message_instances; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.board_message_instances (
    id text NOT NULL,
    account text NOT NULL,
    project text NOT NULL,
    agent text NOT NULL,
    conversation text NOT NULL,
    topic text NOT NULL,
    lifetime text NOT NULL,
    is_default boolean DEFAULT false NOT NULL,
    active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    archived_at timestamp with time zone,
    request_id text,
    open_default boolean,
    CONSTRAINT board_message_instances_check CHECK (((NOT is_default) OR (lifetime = 'persistent'::text))),
    CONSTRAINT board_message_instances_lifetime_check CHECK ((lifetime = ANY (ARRAY['persistent'::text, 'task'::text, 'caller'::text, 'external'::text])))
);


--
-- Name: board_message_route_bindings; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.board_message_route_bindings (
    account text NOT NULL,
    source_project text NOT NULL,
    route_name text NOT NULL,
    instance text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: board_message_routes; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.board_message_routes (
    message text NOT NULL,
    sender text NOT NULL,
    recipient text NOT NULL,
    reply_to text,
    reply_expected boolean NOT NULL,
    final boolean DEFAULT false NOT NULL,
    generated boolean DEFAULT false NOT NULL,
    ending text,
    call_id text,
    handled_at timestamp with time zone,
    started_at timestamp with time zone,
    awaiting boolean DEFAULT false NOT NULL,
    deadline_at timestamp with time zone,
    termination text,
    send_fingerprint text,
    CONSTRAINT board_message_routes_check CHECK (((NOT final) OR (reply_to IS NOT NULL))),
    CONSTRAINT board_message_routes_check1 CHECK (((NOT generated) OR (final AND (ending IS NOT NULL)))),
    CONSTRAINT board_message_routes_send_fingerprint_check CHECK (((send_fingerprint IS NULL) OR (send_fingerprint ~ '^[a-f0-9]{64}$'::text)))
);


--
-- Name: board_messages; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.board_messages (
    id text NOT NULL,
    seq bigint NOT NULL,
    topic text NOT NULL,
    reply_to text,
    author_kind text NOT NULL,
    author text NOT NULL,
    conversation text,
    entry integer,
    kind text NOT NULL,
    title text,
    body text NOT NULL,
    alert boolean DEFAULT false NOT NULL,
    mentions text[] DEFAULT '{}'::text[] NOT NULL,
    posted_at timestamp with time zone NOT NULL,
    CONSTRAINT board_messages_a_document_or_request_is_titled CHECK (((kind = ANY (ARRAY['document'::text, 'request'::text])) = (title IS NOT NULL))),
    CONSTRAINT board_messages_author_kind_is_known CHECK ((author_kind = ANY (ARRAY['member'::text, 'opener'::text, 'person'::text, 'harness'::text]))),
    CONSTRAINT board_messages_kind_is_known CHECK ((kind = ANY (ARRAY['post'::text, 'pass'::text, 'document'::text, 'request'::text, 'resolution'::text, 'note'::text, 'hook'::text]))),
    CONSTRAINT board_messages_only_a_post_alerts CHECK (((NOT alert) OR (kind = 'post'::text))),
    CONSTRAINT board_messages_say_something CHECK (((body <> ''::text) AND (author <> ''::text)))
);


--
-- Name: board_messages_seq_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.board_messages ALTER COLUMN seq ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.board_messages_seq_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: board_notices; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.board_notices (
    message text NOT NULL,
    topic text NOT NULL,
    kind text NOT NULL,
    delivered_at timestamp with time zone,
    wake text,
    CONSTRAINT board_notices_kind_check CHECK ((kind = ANY (ARRAY['quiet'::text, 'exhausted'::text])))
);


--
-- Name: board_post_receipts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.board_post_receipts (
    account text NOT NULL,
    request_id uuid NOT NULL,
    payload jsonb NOT NULL,
    message_id text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: board_reads; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.board_reads (
    conversation text NOT NULL,
    topic text NOT NULL,
    seen_through text NOT NULL
);


--
-- Name: board_seats; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.board_seats (
    topic text NOT NULL,
    occupant text NOT NULL,
    conversation text NOT NULL,
    passed boolean DEFAULT false NOT NULL,
    failed_ending text,
    silent_wakes integer DEFAULT 0 NOT NULL,
    seen_through text,
    alerts_used integer DEFAULT 0 NOT NULL,
    CONSTRAINT board_seats_counts_are_natural CHECK (((silent_wakes >= 0) AND (alerts_used >= 0)))
);


--
-- Name: chapters; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.chapters (
    id uuid NOT NULL,
    document_id uuid NOT NULL,
    ordinal integer NOT NULL,
    title text,
    summary text,
    is_synthetic boolean DEFAULT false NOT NULL,
    CONSTRAINT chapters_a_named_chapter_has_a_title CHECK ((is_synthetic OR ((title IS NOT NULL) AND (title <> ''::text)))),
    CONSTRAINT chapters_counted_from_one CHECK ((ordinal >= 1)),
    CONSTRAINT chapters_summary_is_not_blank CHECK (((summary IS NULL) OR (summary <> ''::text)))
);


--
-- Name: chunks; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.chunks (
    id uuid NOT NULL,
    paragraph_id uuid NOT NULL,
    ordinal integer NOT NULL,
    text text NOT NULL,
    byte_size integer NOT NULL,
    split_mid_sentence boolean NOT NULL,
    embedding public.vector(768),
    text_search tsvector GENERATED ALWAYS AS (to_tsvector('english'::regconfig, text)) STORED,
    embedding_source_revision bigint DEFAULT 0 NOT NULL,
    code_embedding public.vector,
    code_space_id text,
    code_source_revision bigint,
    prose_embedding public.vector,
    prose_space_id text,
    prose_source_revision bigint,
    CONSTRAINT chunks_counted_from_one CHECK ((ordinal >= 1)),
    CONSTRAINT chunks_embedding_source_revision_check CHECK ((embedding_source_revision >= 0)),
    CONSTRAINT chunks_has_size CHECK ((byte_size > 0)),
    CONSTRAINT chunks_not_blank CHECK ((text <> ''::text))
);


--
-- Name: TABLE chunks; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.chunks IS 'Chunk text is whatever was uploaded: other people''s papers, notes and source. Like `entries`, this table holds content the server did not write and cannot vouch for, and unlike `entries` it is content a search will surface out of context. Anything that puts a chunk into a prompt is putting an uploaded document into a prompt.';


--
-- Name: COLUMN chunks.text_search; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.chunks.text_search IS 'The chunk''s own text as english lexemes, computed by the database and writable by nothing -- so a lexical index that disagrees with the text it indexes is unreachable rather than merely unlikely. The query side must name the same text search configuration or it silently stops matching; DocumentStore.LEXICAL_SQL does, and a test compares the two spellings.';


--
-- Name: chunks_embedding_failures; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.chunks_embedding_failures (
    source_id uuid NOT NULL,
    slot text NOT NULL,
    space_id text NOT NULL,
    source_revision bigint NOT NULL,
    attempts integer DEFAULT 1 NOT NULL,
    category text NOT NULL,
    retry_at timestamp with time zone NOT NULL,
    CONSTRAINT chunks_embedding_failures_attempts_check CHECK ((attempts > 0)),
    CONSTRAINT chunks_embedding_failures_category_check CHECK ((category ~ '^[A-Za-z][A-Za-z0-9]{0,79}$'::text)),
    CONSTRAINT chunks_embedding_failures_source_revision_check CHECK ((source_revision >= 0))
);


--
-- Name: chunks_embedding_staging; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.chunks_embedding_staging (
    source_id uuid NOT NULL,
    slot text NOT NULL,
    space_id text NOT NULL,
    source_revision bigint NOT NULL,
    embedding public.vector NOT NULL,
    CONSTRAINT chunks_embedding_staging_source_revision_check CHECK ((source_revision >= 0))
);


--
-- Name: citations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.citations (
    id uuid NOT NULL,
    paragraph_id uuid,
    document_id uuid,
    source_name text NOT NULL,
    paragraph_ordinal integer NOT NULL,
    conversation_id text,
    turn_ordinal integer,
    agent text NOT NULL,
    cited_at timestamp with time zone NOT NULL,
    CONSTRAINT citations_a_turn_is_counted_from_one CHECK (((turn_ordinal IS NULL) OR (turn_ordinal >= 1))),
    CONSTRAINT citations_a_turn_is_in_a_conversation CHECK (((conversation_id IS NULL) = (turn_ordinal IS NULL))),
    CONSTRAINT citations_counted_from_one CHECK ((paragraph_ordinal >= 1)),
    CONSTRAINT citations_named CHECK (((source_name <> ''::text) AND (agent <> ''::text)))
);


--
-- Name: TABLE citations; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.citations IS 'What an answer said it took from the corpus. Written by the server reading a finished answer, never by a model calling a tool. A row holds coordinates and no document text: the words are in `paragraphs`, and anything that renders them is putting an uploaded document in front of a reader and owes the quoting rule `agents.DocumentTools` states.';


--
-- Name: code_outlines; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.code_outlines (
    document_id uuid NOT NULL,
    source_hash text NOT NULL,
    language text NOT NULL,
    parser_version text NOT NULL,
    status text NOT NULL,
    reason text,
    symbol_count integer NOT NULL,
    CONSTRAINT code_outlines_status_check CHECK ((status = ANY (ARRAY['ready'::text, 'partial'::text, 'unsupported'::text, 'limited'::text, 'failed'::text]))),
    CONSTRAINT code_outlines_symbol_count_check CHECK ((symbol_count >= 0))
);


--
-- Name: code_passages; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.code_passages (
    chunk_id uuid NOT NULL,
    document_id uuid NOT NULL,
    start_offset integer NOT NULL,
    end_offset integer NOT NULL,
    CONSTRAINT code_passages_check CHECK ((end_offset > start_offset)),
    CONSTRAINT code_passages_start_offset_check CHECK ((start_offset >= 0))
);


--
-- Name: code_symbols; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.code_symbols (
    document_id uuid NOT NULL,
    ordinal integer NOT NULL,
    name text NOT NULL,
    kind text NOT NULL,
    qualified_name text NOT NULL,
    parent_ordinal integer,
    signature text NOT NULL,
    start_offset integer NOT NULL,
    end_offset integer NOT NULL,
    start_line integer NOT NULL,
    end_line integer NOT NULL,
    CONSTRAINT code_symbols_check CHECK ((end_offset > start_offset)),
    CONSTRAINT code_symbols_check1 CHECK ((end_line >= start_line)),
    CONSTRAINT code_symbols_ordinal_check CHECK ((ordinal > 0)),
    CONSTRAINT code_symbols_start_line_check CHECK ((start_line > 0)),
    CONSTRAINT code_symbols_start_offset_check CHECK ((start_offset >= 0))
);


--
-- Name: code_workspace_indexes; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.code_workspace_indexes (
    workspace_id text NOT NULL,
    source_key text NOT NULL,
    source_hash text NOT NULL,
    parser_version text NOT NULL,
    revision_id uuid NOT NULL
);


--
-- Name: code_workspace_mutations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.code_workspace_mutations (
    token text NOT NULL,
    project_id bigint,
    owner_handle text NOT NULL,
    session_id text,
    expires_at timestamp with time zone NOT NULL
);


--
-- Name: code_workspace_sources; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.code_workspace_sources (
    workspace_id text NOT NULL,
    source_key text NOT NULL,
    provider text NOT NULL,
    root text NOT NULL,
    path text NOT NULL,
    relative_path text NOT NULL,
    source_hash text NOT NULL,
    source_bytes bigint NOT NULL,
    language text NOT NULL,
    outline_status text NOT NULL,
    observed_at timestamp with time zone NOT NULL,
    revision_id uuid,
    CONSTRAINT code_workspace_sources_source_bytes_check CHECK ((source_bytes >= 0)),
    CONSTRAINT code_workspace_sources_source_hash_check CHECK ((source_hash ~ '^[a-f0-9]{64}$'::text))
);


--
-- Name: code_workspaces; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.code_workspaces (
    id text NOT NULL,
    project_id bigint,
    owner_handle text NOT NULL,
    agent_name text NOT NULL,
    session_id text,
    pattern text NOT NULL,
    generation bigint DEFAULT 0 NOT NULL,
    state text NOT NULL,
    issues jsonb DEFAULT '[]'::jsonb NOT NULL,
    checked_at timestamp with time zone,
    next_poll timestamp with time zone NOT NULL,
    lease_until timestamp with time zone,
    scan_started_at timestamp with time zone,
    CONSTRAINT code_workspaces_id_check CHECK ((length(id) = 64)),
    CONSTRAINT code_workspaces_state_check CHECK ((state = ANY (ARRAY['checking'::text, 'observed'::text, 'partial'::text, 'dirty'::text, 'unavailable'::text])))
);


--
-- Name: command_invocations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.command_invocations (
    account_handle text NOT NULL,
    invocation uuid NOT NULL,
    conversation text NOT NULL,
    source_run text NOT NULL,
    caller text NOT NULL,
    command text NOT NULL,
    kind text NOT NULL,
    name text NOT NULL,
    definition_hash text NOT NULL,
    arguments text NOT NULL,
    mode text,
    state text DEFAULT 'bound'::text NOT NULL,
    result text,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT command_invocations_kind_check CHECK ((kind = ANY (ARRAY['skill'::text, 'orchestration'::text]))),
    CONSTRAINT command_invocations_state_check CHECK ((state = ANY (ARRAY['bound'::text, 'dispatching'::text, 'finished'::text, 'failed'::text])))
);


--
-- Name: compactions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.compactions (
    conversation_id text NOT NULL,
    through_ordinal integer NOT NULL,
    summary text NOT NULL,
    CONSTRAINT compactions_summary_says_something CHECK ((summary <> ''::text))
);


--
-- Name: TABLE compactions; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.compactions IS 'What a conversation''s older turns were summarised into, and how far back the summary reaches. The turns themselves are never deleted: this table changes what the model is shown on the next turn and changes nothing a person can read.';


--
-- Name: contained_applications; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.contained_applications (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    scope_id uuid NOT NULL,
    container uuid NOT NULL,
    project_id bigint NOT NULL,
    request_id uuid NOT NULL,
    fingerprint text NOT NULL,
    revision uuid NOT NULL,
    workspace text NOT NULL,
    allowance uuid NOT NULL,
    ceiling integer NOT NULL,
    live boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT contained_application_ceiling CHECK ((ceiling >= 0)),
    CONSTRAINT contained_application_fingerprint CHECK ((fingerprint ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT contained_application_not_its_own_container CHECK ((container <> scope_id)),
    CONSTRAINT contained_application_workspace CHECK (((workspace <> ''::text) AND (workspace ~~ '/%'::text)))
);


--
-- Name: contained_collections; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.contained_collections (
    child uuid NOT NULL,
    container uuid,
    project text NOT NULL,
    revision uuid,
    workspace text NOT NULL,
    source_removed boolean DEFAULT false NOT NULL,
    cause text NOT NULL,
    collected_at timestamp with time zone DEFAULT now() NOT NULL,
    scope uuid,
    root uuid,
    CONSTRAINT contained_collection_cause CHECK ((cause = ANY (ARRAY['DESTROYED'::text, 'SWEPT'::text]))),
    CONSTRAINT contained_collection_project CHECK ((project ~~ 'contained:%'::text)),
    CONSTRAINT contained_collection_shape CHECK ((((cause = 'DESTROYED'::text) AND (container IS NOT NULL) AND (revision IS NOT NULL) AND (scope IS NOT NULL) AND (root IS NOT NULL)) OR ((cause = 'SWEPT'::text) AND (container IS NULL) AND (revision IS NULL) AND (scope IS NULL) AND (root IS NULL)))),
    CONSTRAINT contained_collection_workspace CHECK (((workspace <> ''::text) AND (workspace ~~ '/%'::text)))
);


--
-- Name: contained_principals; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.contained_principals (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    scope_id uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: conversations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.conversations (
    id text NOT NULL,
    created_at timestamp with time zone NOT NULL,
    last_turn_at timestamp with time zone,
    budget_total integer,
    budget_spent integer,
    turn_cap integer,
    turn_cap_lifted boolean DEFAULT false NOT NULL,
    project_id bigint,
    origin text NOT NULL,
    parent_id text,
    agent text,
    lifecycle text DEFAULT 'active'::text,
    budget_lifted boolean DEFAULT false NOT NULL,
    title text,
    opening_block text,
    owner_handle text,
    log_closed_at timestamp with time zone,
    opened_by_call text,
    local_hooks text,
    relay_causation jsonb,
    budget_id uuid DEFAULT gen_random_uuid() NOT NULL,
    allowance_id uuid,
    owns_allowance boolean DEFAULT false NOT NULL,
    CONSTRAINT conversations_a_delegation_is_what_has_a_parent CHECK (((origin = 'delegation'::text) = (parent_id IS NOT NULL))),
    CONSTRAINT conversations_a_person_s_conversation_names_no_agent CHECK (((origin = 'turn'::text) = (agent IS NULL))),
    CONSTRAINT conversations_a_root_is_where_the_lifecycle_lives CHECK (((parent_id IS NULL) = (lifecycle IS NOT NULL))),
    CONSTRAINT conversations_a_title_is_named CHECK (((title IS NULL) OR (title <> ''::text))),
    CONSTRAINT conversations_agent_is_named CHECK (((agent IS NULL) OR (agent <> ''::text))),
    CONSTRAINT conversations_an_allowance_is_both_halves_or_neither CHECK ((((budget_total IS NOT NULL) AND (budget_spent IS NOT NULL)) OR ((budget_total IS NULL) AND (budget_spent IS NULL) AND (NOT budget_lifted)) OR ((budget_total IS NULL) AND (budget_spent IS NOT NULL) AND budget_lifted))),
    CONSTRAINT conversations_an_allowance_is_owned_or_shared CHECK (
CASE
    WHEN (origin = 'orchestration'::text) THEN true
    ELSE ((origin = ANY (ARRAY['turn'::text, 'submission'::text, 'memory'::text, 'event'::text, 'contained'::text])) = ((budget_total IS NOT NULL) OR budget_lifted))
END),
    CONSTRAINT conversations_an_owner_is_named CHECK (((owner_handle IS NULL) OR (owner_handle <> ''::text))),
    CONSTRAINT conversations_budget_is_a_number_or_no_ceiling CHECK ((NOT (budget_lifted AND (budget_total IS NOT NULL)))),
    CONSTRAINT conversations_budget_is_spendable CHECK ((budget_total >= 0)),
    CONSTRAINT conversations_id_named CHECK ((id <> ''::text)),
    CONSTRAINT conversations_lifecycle_is_known CHECK (((lifecycle IS NULL) OR (lifecycle = ANY (ARRAY['active'::text, 'archived'::text, 'to_be_ejected'::text, 'ejected'::text])))),
    CONSTRAINT conversations_nothing_delegated_to_itself CHECK (((parent_id IS NULL) OR (parent_id <> id))),
    CONSTRAINT conversations_only_a_child_was_opened_by_a_call CHECK (((opened_by_call IS NULL) OR (parent_id IS NOT NULL))),
    CONSTRAINT conversations_origin_is_known CHECK ((origin = ANY (ARRAY['turn'::text, 'delegation'::text, 'curator'::text, 'submission'::text, 'memory'::text, 'event'::text, 'orchestration'::text, 'board'::text, 'contained'::text]))),
    CONSTRAINT conversations_relay_causation_check CHECK (public.relay_valid_causation(relay_causation)),
    CONSTRAINT conversations_spending_is_not_negative CHECK ((budget_spent >= 0)),
    CONSTRAINT conversations_spent_within_budget CHECK ((budget_spent <= budget_total)),
    CONSTRAINT conversations_turn_cap_is_a_cap CHECK (((turn_cap IS NULL) OR (turn_cap >= 1))),
    CONSTRAINT conversations_turn_cap_is_a_number_or_no_cap CHECK ((NOT (turn_cap_lifted AND (turn_cap IS NOT NULL)))),
    CONSTRAINT conversations_turn_is_not_before_the_conversation CHECK (((last_turn_at IS NULL) OR (last_turn_at >= created_at)))
);


--
-- Name: COLUMN conversations.budget_total; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.conversations.budget_total IS 'The allowance every turn in this conversation spends from, or NULL for a conversation that spends an allowance it does not own -- a delegated child spends its parent''s by reference, a curator''s ruling spends the pass''s. NULL is how this table refuses to hold a second copy of a shared budget, which would double the accounting the first time anything summed it. NULL with budget_lifted TRUE is the third case and not that one: the allowance is this conversation''s own and has no ceiling, and budget_spent still counts what it has used.';


--
-- Name: COLUMN conversations.budget_spent; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.conversations.budget_spent IS 'How many model calls this conversation has made, or NULL when the allowance is not its own to count. Present without budget_total exactly when budget_lifted is TRUE: spend is a measurement and the ceiling is a policy, so a conversation may have the first without the second.';


--
-- Name: COLUMN conversations.turn_cap; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.conversations.turn_cap IS 'How many turns each turn in this conversation may take, or NULL when this conversation does not decide -- in which case the agent''s own max-turns stands. NULL with turn_cap_lifted TRUE means there is to be no cap.';


--
-- Name: COLUMN conversations.turn_cap_lifted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.conversations.turn_cap_lifted IS 'Whether this conversation says its turns are to run with no turn cap at all. FALSE and turn_cap NULL is the ordinary shape: this conversation does not decide. Never TRUE beside a number.';


--
-- Name: COLUMN conversations.project_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.conversations.project_id IS 'The project this conversation belongs to, or NULL for the global tier. Same rule as memories.project_id, and the same one V1 states.';


--
-- Name: COLUMN conversations.origin; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.conversations.origin IS 'Which door this conversation came through: turn (a person, through POST /v1/conversations), delegation (a child a run started with agent_run), curator (one ruling of a curator pass), submission (a run started on its own behalf through POST /v1/agents/{name}/runs with no conversation). Written when the row is written and never derived later, because it decides which listing the row belongs in and which retention policy will apply to it.';


--
-- Name: COLUMN conversations.parent_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.conversations.parent_id IS 'The conversation that delegated to this one, or NULL for a root. Exactly the rows with origin delegation have one. A child''s entries are in its own log and never in its parent''s prompt -- that separation is what this column exists to make structural rather than a filter.';


--
-- Name: COLUMN conversations.agent; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.conversations.agent IS 'The one agent this conversation is, or NULL for a person''s conversation, which may hold several agents'' turns. Which agent answered a particular turn is turns.agent and is a different fact.';


--
-- Name: COLUMN conversations.lifecycle; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.conversations.lifecycle IS 'Where the tree rooted at this conversation has got to: active, archived, to_be_ejected, ejected. NULL on a delegated child, which resolves to its root -- one state per tree, never one per conversation. An archived or ejected conversation accepts no new turn and no resumption; see agents.Turn.';


--
-- Name: COLUMN conversations.budget_lifted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.conversations.budget_lifted IS 'Whether this conversation owns its allowance and puts no ceiling on it. FALSE is the ordinary shape: budget_total is the ceiling, or both budget columns are NULL because the allowance is somebody else''s. Never TRUE beside a number, and never TRUE on a conversation that shares an allowance it does not own.';


--
-- Name: COLUMN conversations.title; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.conversations.title IS 'A human name for this conversation, derived by ConversationStore.titleFrom from the first turn''s utterance and written by the first turn only -- the write is UPDATE ... WHERE id = ? AND title IS NULL, so a second turn changes nothing and the conversation keeps the name it opened with. NULL means unnamed, which is true of every row written before V38, of a conversation nobody has spoken into yet, and of one whose first utterance held no line to name it with. NULL is never filled in later: it is a fact a client renders (by showing the id), not a gap to paper over, and a server that invented "Untitled" would be three clients showing three inventions again.';


--
-- Name: COLUMN conversations.opened_by_call; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.conversations.opened_by_call IS 'On a delegated child: the id of the agent_run call that opened it; NULL before V62.';


--
-- Name: conversation_allowance_state; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.conversation_allowance_state AS
 SELECT r.id,
    r.created_at,
    r.last_turn_at,
        CASE
            WHEN r.owns_allowance THEN a.call_limit
            ELSE NULL::integer
        END AS budget_total,
        CASE
            WHEN r.owns_allowance THEN a.spent
            ELSE NULL::integer
        END AS budget_spent,
    r.turn_cap,
    r.turn_cap_lifted,
    r.project_id,
    r.origin,
    r.parent_id,
    r.agent,
    r.lifecycle,
    (r.owns_allowance AND (a.call_limit IS NULL)) AS budget_lifted,
    r.title,
    r.opening_block,
    r.owner_handle,
    r.log_closed_at,
    r.opened_by_call,
    r.local_hooks,
    r.relay_causation,
    COALESCE(a.id, r.budget_id) AS budget_id,
    r.allowance_id,
    r.owns_allowance
   FROM (public.conversations r
     LEFT JOIN public.execution_allowances a ON ((a.id = r.allowance_id)));


--
-- Name: digest_children; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.digest_children (
    parent_id text NOT NULL,
    child_id text NOT NULL,
    CONSTRAINT digest_children_check CHECK ((parent_id <> child_id))
);


--
-- Name: digest_memories; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.digest_memories (
    digest_id text NOT NULL,
    memory_id text NOT NULL
);


--
-- Name: digest_revisions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.digest_revisions (
    digest_id text NOT NULL,
    revision bigint NOT NULL,
    summary text NOT NULL,
    recorded_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: digest_spans; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.digest_spans (
    digest_id text NOT NULL,
    conversation_id text NOT NULL,
    since_turn integer NOT NULL,
    through_turn integer NOT NULL,
    summary_ordinal integer,
    CONSTRAINT digest_spans_check CHECK ((through_turn > since_turn)),
    CONSTRAINT digest_spans_since_turn_check CHECK ((since_turn >= 0))
);


--
-- Name: digests; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.digests (
    id text NOT NULL,
    project_id bigint,
    depth integer NOT NULL,
    summary text NOT NULL,
    embedding public.vector(768),
    stale_at timestamp with time zone,
    revision bigint DEFAULT 0 NOT NULL,
    embedding_generation text,
    embedding_source_revision bigint DEFAULT 0 NOT NULL,
    code_embedding public.vector,
    code_space_id text,
    code_source_revision bigint,
    prose_embedding public.vector,
    prose_space_id text,
    prose_source_revision bigint,
    CONSTRAINT digests_depth_check CHECK ((depth >= 0)),
    CONSTRAINT digests_embedding_source_revision_check CHECK ((embedding_source_revision >= 0)),
    CONSTRAINT digests_summary_check CHECK ((btrim(summary) <> ''::text))
);


--
-- Name: digests_embedding_failures; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.digests_embedding_failures (
    source_id text NOT NULL,
    slot text NOT NULL,
    space_id text NOT NULL,
    source_revision bigint NOT NULL,
    attempts integer DEFAULT 1 NOT NULL,
    category text NOT NULL,
    retry_at timestamp with time zone NOT NULL,
    CONSTRAINT digests_embedding_failures_attempts_check CHECK ((attempts > 0)),
    CONSTRAINT digests_embedding_failures_category_check CHECK ((category ~ '^[A-Za-z][A-Za-z0-9]{0,79}$'::text)),
    CONSTRAINT digests_embedding_failures_source_revision_check CHECK ((source_revision >= 0))
);


--
-- Name: digests_embedding_staging; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.digests_embedding_staging (
    source_id text NOT NULL,
    slot text NOT NULL,
    space_id text NOT NULL,
    source_revision bigint NOT NULL,
    embedding public.vector NOT NULL,
    CONSTRAINT digests_embedding_staging_source_revision_check CHECK ((source_revision >= 0))
);


--
-- Name: document_references; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.document_references (
    id uuid NOT NULL,
    document_id uuid NOT NULL,
    ref_num integer NOT NULL,
    raw text NOT NULL,
    CONSTRAINT document_references_not_blank CHECK ((raw <> ''::text)),
    CONSTRAINT document_references_numbered_from_one CHECK ((ref_num >= 1))
);


--
-- Name: TABLE document_references; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.document_references IS 'What a document cites, scraped from its own reference list. NOT `citations`, which is what an answer took from the corpus. Like `chunks`, this table holds content the server did not write and cannot vouch for.';


--
-- Name: documents; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.documents (
    id uuid NOT NULL,
    source_name text NOT NULL,
    title text NOT NULL,
    content_hash text NOT NULL,
    text_hash text NOT NULL,
    byte_size bigint NOT NULL,
    ingested_at timestamp with time zone NOT NULL,
    ingested_by text NOT NULL,
    summary text,
    top_level_label text,
    summary_embedding public.vector(768),
    information_namespace text DEFAULT 'legacy'::text NOT NULL,
    document_type text DEFAULT 'document'::text NOT NULL,
    document_subtype text DEFAULT 'text'::text NOT NULL,
    embedding_source_revision bigint DEFAULT 0 NOT NULL,
    code_embedding public.vector,
    code_space_id text,
    code_source_revision bigint,
    prose_embedding public.vector,
    prose_space_id text,
    prose_source_revision bigint,
    CONSTRAINT documents_document_subtype_check CHECK ((document_subtype ~ '^[a-z][a-z0-9_]*$'::text)),
    CONSTRAINT documents_document_type_check CHECK ((document_type = ANY (ARRAY['document'::text, 'code'::text]))),
    CONSTRAINT documents_embedding_source_revision_check CHECK ((embedding_source_revision >= 0)),
    CONSTRAINT documents_has_content CHECK (((byte_size > 0) OR ((byte_size = 0) AND (document_type = 'code'::text)))),
    CONSTRAINT documents_named CHECK (((source_name <> ''::text) AND (title <> ''::text))),
    CONSTRAINT documents_summary_is_not_blank CHECK (((summary IS NULL) OR (summary <> ''::text))),
    CONSTRAINT documents_top_level_label_is_one_of_three CHECK (((top_level_label IS NULL) OR (top_level_label = ANY (ARRAY['CHAPTER'::text, 'SECTION'::text, 'PART'::text]))))
);


--
-- Name: COLUMN documents.summary; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.documents.summary IS 'What this document claims, written by the document_summariser agent from the summaries below it, or NULL for a document nothing has summarised yet. Dropped by DocumentStore.write when a re-ingest adds or removes a paragraph, because a summary of a derivation that has changed is a wrong answer rather than an old one.';


--
-- Name: COLUMN documents.summary_embedding; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.documents.summary_embedding IS 'The document summary as a vector, for ranking whole documents against a question without touching a chunk. NULL until something embeds the summary, which is a state the reads report rather than hide. Deliberately unindexed: one row per document is memories scale, and V18 records that an exact scan is correct there.';


--
-- Name: documents_embedding_failures; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.documents_embedding_failures (
    source_id uuid NOT NULL,
    slot text NOT NULL,
    space_id text NOT NULL,
    source_revision bigint NOT NULL,
    attempts integer DEFAULT 1 NOT NULL,
    category text NOT NULL,
    retry_at timestamp with time zone NOT NULL,
    CONSTRAINT documents_embedding_failures_attempts_check CHECK ((attempts > 0)),
    CONSTRAINT documents_embedding_failures_category_check CHECK ((category ~ '^[A-Za-z][A-Za-z0-9]{0,79}$'::text)),
    CONSTRAINT documents_embedding_failures_source_revision_check CHECK ((source_revision >= 0))
);


--
-- Name: documents_embedding_staging; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.documents_embedding_staging (
    source_id uuid NOT NULL,
    slot text NOT NULL,
    space_id text NOT NULL,
    source_revision bigint NOT NULL,
    embedding public.vector NOT NULL,
    CONSTRAINT documents_embedding_staging_source_revision_check CHECK ((source_revision >= 0))
);


--
-- Name: embedding_input_limits; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.embedding_input_limits (
    space_id text NOT NULL,
    max_input_tokens integer NOT NULL,
    CONSTRAINT embedding_input_limits_max_input_tokens_check CHECK ((max_input_tokens > 0))
);


--
-- Name: embedding_queries; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.embedding_queries (
    id uuid NOT NULL,
    budget_id uuid NOT NULL,
    account_id uuid NOT NULL,
    scope_id uuid NOT NULL,
    fingerprint text NOT NULL,
    invocation text,
    call_limit integer,
    spent integer DEFAULT 0 NOT NULL,
    closed boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    consumer_kind text DEFAULT 'QUERY'::text NOT NULL,
    CONSTRAINT embedding_queries_call_limit_check CHECK (((call_limit IS NULL) OR (call_limit > 0) OR ((consumer_kind = 'MEMORY_WRITE'::text) AND (call_limit = 0)))),
    CONSTRAINT embedding_queries_check CHECK (((spent >= 0) AND (spent <= call_limit))),
    CONSTRAINT embedding_queries_consumer_kind_check CHECK ((consumer_kind = ANY (ARRAY['QUERY'::text, 'MEMORY_WRITE'::text]))),
    CONSTRAINT embedding_queries_fingerprint_check CHECK ((fingerprint ~ '^[a-f0-9]{64}$'::text))
);


--
-- Name: embedding_query_allowances; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.embedding_query_allowances AS
 SELECT q.id,
    q.budget_id,
    q.account_id,
    q.scope_id,
    q.fingerprint,
    q.invocation,
    a.call_limit,
    a.spent,
    q.closed,
    q.created_at,
    q.consumer_kind
   FROM (public.embedding_queries q
     JOIN public.execution_allowances a ON ((a.id = q.budget_id)));


--
-- Name: embedding_repair_attempts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.embedding_repair_attempts (
    id uuid NOT NULL,
    slot text NOT NULL,
    space_id text NOT NULL,
    store text NOT NULL,
    source_type text DEFAULT ''::text NOT NULL,
    source_id text NOT NULL,
    "position" integer NOT NULL,
    source_revision bigint NOT NULL,
    profile_version bigint,
    phase text NOT NULL,
    failure_phase text,
    reason text NOT NULL,
    failure_type text,
    call_id uuid,
    wire_attempt_id uuid,
    http_status integer,
    dispatch_outcome text,
    recorded_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    prepared_at timestamp with time zone,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    provider_started_at timestamp with time zone,
    result_received_at timestamp with time zone,
    legacy_imported boolean DEFAULT false NOT NULL,
    CONSTRAINT embedding_repair_attempts_check CHECK ((((store = 'retrieval_passages'::text) AND (source_type = ANY (ARRAY['entry'::text, 'digest'::text]))) OR ((store <> 'retrieval_passages'::text) AND (source_type = ''::text) AND ("position" = 0)))),
    CONSTRAINT embedding_repair_attempts_failure_type_check CHECK (((failure_type IS NULL) OR (((length(failure_type) >= 1) AND (length(failure_type) <= 256)) AND (failure_type ~ '^[A-Za-z0-9_.$]+$'::text)))),
    CONSTRAINT embedding_repair_attempts_http_status_check CHECK (((http_status >= 100) AND (http_status <= 599))),
    CONSTRAINT embedding_repair_attempts_phase_check CHECK ((phase = ANY (ARRAY['PREPARED'::text, 'ACCOUNTED'::text, 'QUEUED'::text, 'RUNNING'::text, 'ATTEMPT_STARTED'::text, 'RESPONSE_RECEIVED'::text, 'RESULT_VALIDATED'::text, 'PUBLISHING'::text, 'PUBLISHED'::text, 'NOT_DISPATCHED'::text, 'FAILED'::text, 'BLOCKED'::text, 'SUPERSEDED'::text, 'OUTCOME_UNKNOWN'::text]))),
    CONSTRAINT embedding_repair_attempts_position_check CHECK (("position" >= 0)),
    CONSTRAINT embedding_repair_attempts_reason_check CHECK ((reason = ANY (ARRAY['NONE'::text, 'LEGACY_UNCORRELATED'::text, 'CAPACITY_EXHAUSTED'::text, 'AUTHORIZATION_REJECTED'::text, 'ACCOUNTING_UNAVAILABLE'::text, 'DISPATCH_FAILED'::text, 'TRANSPORT_OUTCOME_UNKNOWN'::text, 'PROVIDER_REFUSED'::text, 'INVALID_RESULT'::text, 'PUBLICATION_FAILED'::text, 'SOURCE_OR_PROFILE_CHANGED'::text, 'INTERRUPTED'::text, 'PROCESS_LOST'::text]))),
    CONSTRAINT embedding_repair_attempts_source_revision_check CHECK ((source_revision >= 0)),
    CONSTRAINT embedding_repair_attempts_store_check CHECK ((store = ANY (ARRAY['memories'::text, 'digests'::text, 'documents'::text, 'chunks'::text, 'retrieval_passages'::text])))
);


--
-- Name: embedding_repair_scans; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.embedding_repair_scans (
    slot text NOT NULL,
    space_id text NOT NULL,
    store text NOT NULL,
    source_type text,
    source_id text,
    "position" integer,
    enumerated boolean DEFAULT false NOT NULL,
    next_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT embedding_repair_scans_store_check CHECK ((store = ANY (ARRAY['memories'::text, 'digests'::text, 'documents'::text, 'chunks'::text, 'retrieval_passages'::text])))
);


--
-- Name: embedding_repair_work; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.embedding_repair_work (
    slot text NOT NULL,
    space_id text NOT NULL,
    store text NOT NULL,
    source_type text DEFAULT ''::text NOT NULL,
    source_id text NOT NULL,
    "position" integer DEFAULT 0 NOT NULL,
    source_revision bigint NOT NULL,
    available_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT embedding_repair_work_position_check CHECK (("position" >= 0)),
    CONSTRAINT embedding_repair_work_source_revision_check CHECK ((source_revision >= 0)),
    CONSTRAINT embedding_repair_work_store_check CHECK ((store = ANY (ARRAY['memories'::text, 'digests'::text, 'documents'::text, 'chunks'::text, 'retrieval_passages'::text])))
);


--
-- Name: embedding_slots; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.embedding_slots (
    slot text NOT NULL,
    active_space_id text,
    target_space_id text,
    version bigint DEFAULT 0 NOT NULL,
    active_mode text,
    active_distance text,
    target_mode text,
    target_distance text,
    CONSTRAINT embedding_slots_active_distance_check CHECK (((active_distance IS NULL) OR (active_distance = ANY (ARRAY['COSINE'::text, 'L2'::text, 'INNER_PRODUCT'::text, 'L1'::text])))),
    CONSTRAINT embedding_slots_active_mode_check CHECK (((active_mode IS NULL) OR (active_mode = ANY (ARRAY['exact'::text, 'hnsw_vector'::text, 'hnsw_half'::text, 'hnsw_binary'::text, 'ivfflat_vector'::text, 'ivfflat_half'::text, 'ivfflat_binary'::text])))),
    CONSTRAINT embedding_slots_slot_check CHECK ((slot = ANY (ARRAY['code'::text, 'prose'::text]))),
    CONSTRAINT embedding_slots_target_distance_check CHECK (((target_distance IS NULL) OR (target_distance = ANY (ARRAY['COSINE'::text, 'L2'::text, 'INNER_PRODUCT'::text, 'L1'::text])))),
    CONSTRAINT embedding_slots_target_mode_check CHECK (((target_mode IS NULL) OR (target_mode = ANY (ARRAY['exact'::text, 'hnsw_vector'::text, 'hnsw_half'::text, 'hnsw_binary'::text, 'ivfflat_vector'::text, 'ivfflat_half'::text, 'ivfflat_binary'::text])))),
    CONSTRAINT embedding_slots_version_check CHECK ((version >= 0))
);


--
-- Name: embedding_source_revisions; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.embedding_source_revisions
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: embedding_spaces; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.embedding_spaces (
    id text NOT NULL,
    model_id text NOT NULL,
    model_revision text NOT NULL,
    dimensions integer NOT NULL,
    query_prefix text NOT NULL,
    document_prefix text NOT NULL,
    pooling text NOT NULL,
    normalization text NOT NULL,
    reduction text NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT embedding_spaces_dimensions_check CHECK (((dimensions >= 1) AND (dimensions <= 16000))),
    CONSTRAINT embedding_spaces_document_prefix_check CHECK ((length(document_prefix) <= 16384)),
    CONSTRAINT embedding_spaces_id_check CHECK ((id ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT embedding_spaces_model_id_check CHECK (((model_id = btrim(model_id)) AND ((length(model_id) >= 1) AND (length(model_id) <= 512)))),
    CONSTRAINT embedding_spaces_model_revision_check CHECK (((model_revision = btrim(model_revision)) AND ((length(model_revision) >= 1) AND (length(model_revision) <= 512)))),
    CONSTRAINT embedding_spaces_normalization_check CHECK ((normalization = ANY (ARRAY['none'::text, 'l2'::text]))),
    CONSTRAINT embedding_spaces_pooling_check CHECK (((pooling = btrim(pooling)) AND ((length(pooling) >= 1) AND (length(pooling) <= 512)))),
    CONSTRAINT embedding_spaces_query_prefix_check CHECK ((length(query_prefix) <= 16384)),
    CONSTRAINT embedding_spaces_reduction_check CHECK (((reduction = btrim(reduction)) AND ((length(reduction) >= 1) AND (length(reduction) <= 512))))
);


--
-- Name: entries; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.entries (
    conversation_id text NOT NULL,
    ordinal integer NOT NULL,
    kind text NOT NULL,
    role text,
    content text,
    tool_call_id text,
    tool_calls jsonb,
    superseded_by integer,
    turn_ordinal integer NOT NULL,
    handle uuid,
    recorded_at timestamp with time zone,
    took_ms bigint,
    ejected_at timestamp with time zone,
    export text,
    ejected_chars integer,
    learned_at timestamp with time zone,
    text_search tsvector GENERATED ALWAYS AS (to_tsvector('english'::regconfig, content)) STORED,
    invocation uuid,
    produced_by text,
    dispatch text,
    model_specifier text,
    model_pool text,
    wire_model text,
    completion text,
    finish_reason text,
    prompt_tokens integer,
    completion_tokens integer,
    reasoning_tokens integer,
    sent_at timestamp with time zone,
    fallback_reason text,
    first_token_ms bigint,
    speaker text,
    speaker_name text,
    outcome text,
    salients jsonb,
    job_id text,
    tool_observation jsonb,
    CONSTRAINT entries_a_duration_is_not_negative CHECK (((took_ms IS NULL) OR (took_ms >= 0))),
    CONSTRAINT entries_a_fallback_says_why CHECK (((dispatch IS DISTINCT FROM 'fallback'::text) OR (fallback_reason IS NOT NULL))),
    CONSTRAINT entries_a_first_token_belongs_to_a_call CHECK (((first_token_ms IS NULL) OR ((invocation IS NOT NULL) AND (first_token_ms >= 0)))),
    CONSTRAINT entries_a_fold_covers_what_came_before_it CHECK (((superseded_by IS NULL) OR (superseded_by > ordinal))),
    CONSTRAINT entries_a_model_call_is_recorded_whole CHECK ((((invocation IS NULL) = (produced_by IS NULL)) AND ((invocation IS NULL) = (dispatch IS NULL)) AND ((invocation IS NULL) = (model_specifier IS NULL)) AND ((invocation IS NULL) = (completion IS NULL)) AND ((invocation IS NULL) = (sent_at IS NULL)))),
    CONSTRAINT entries_a_refusal_is_not_blank CHECK (((kind <> 'refusal'::text) OR (content <> ''::text))),
    CONSTRAINT entries_a_refusal_names_its_call CHECK (((kind <> 'refusal'::text) OR (invocation IS NOT NULL))),
    CONSTRAINT entries_a_refusal_row_is_a_refused_completion CHECK (((kind <> 'refusal'::text) OR (completion = 'refused'::text))),
    CONSTRAINT entries_a_speaker_name_belongs_to_a_speaker CHECK (((speaker_name IS NULL) OR (speaker IS NOT NULL))),
    CONSTRAINT entries_a_summary_is_not_blank CHECK (((kind <> 'summary'::text) OR (content <> ''::text))),
    CONSTRAINT entries_a_tool_result_is_exactly_what_answers_a_call CHECK (((kind = 'tool_result'::text) = (tool_call_id IS NOT NULL))),
    CONSTRAINT entries_a_tool_result_is_not_blank CHECK (((kind <> 'tool_result'::text) OR (content <> ''::text))),
    CONSTRAINT entries_an_ejected_payload_is_gone CHECK (((content IS NULL) = (ejected_at IS NOT NULL))),
    CONSTRAINT entries_an_ejected_payload_keeps_its_size CHECK (((ejected_at IS NULL) = (ejected_chars IS NULL))),
    CONSTRAINT entries_an_ejected_size_is_a_length CHECK (((ejected_chars IS NULL) OR (ejected_chars >= 0))),
    CONSTRAINT entries_an_export_is_named CHECK (((export IS NULL) OR (export <> ''::text))),
    CONSTRAINT entries_an_utterance_is_not_blank CHECK (((kind <> 'utterance'::text) OR (content <> ''::text))),
    CONSTRAINT entries_are_numbered_from_one CHECK ((ordinal >= 1)),
    CONSTRAINT entries_belong_to_a_turn_numbered_from_one CHECK ((turn_ordinal >= 1)),
    CONSTRAINT entries_completion_is_known CHECK (((completion IS NULL) OR (completion = ANY (ARRAY['answered'::text, 'refused'::text, 'cut_off'::text, 'called_tools'::text])))),
    CONSTRAINT entries_dispatch_is_known CHECK (((dispatch IS NULL) OR (dispatch = ANY (ARRAY['primary'::text, 'fallback'::text])))),
    CONSTRAINT entries_job_id_named CHECK (((job_id IS NULL) OR (((length(job_id) >= 1) AND (length(job_id) <= 256)) AND (job_id = btrim(job_id))))),
    CONSTRAINT entries_kind_is_known CHECK ((kind = ANY (ARRAY['utterance'::text, 'answer'::text, 'tool_result'::text, 'summary'::text, 'attempt_failed'::text, 'runtime_note'::text, 'plan'::text, 'diagnostic'::text, 'refusal'::text, 'notice'::text, 'hook'::text, 'thinking'::text, 'turn_summary'::text]))),
    CONSTRAINT entries_only_a_completed_operation_is_timed CHECK (((took_ms IS NULL) OR (kind = ANY (ARRAY['answer'::text, 'tool_result'::text, 'refusal'::text])))),
    CONSTRAINT entries_only_a_model_says_something_by_a_call CHECK (((invocation IS NULL) OR (kind = ANY (ARRAY['answer'::text, 'refusal'::text])))),
    CONSTRAINT entries_only_a_tool_result_has_an_outcome CHECK (((outcome IS NULL) OR (kind = 'tool_result'::text))),
    CONSTRAINT entries_only_a_tool_result_is_addressable CHECK (((handle IS NULL) OR (kind = 'tool_result'::text))),
    CONSTRAINT entries_only_a_tool_result_is_ejected CHECK (((ejected_at IS NULL) OR (kind = 'tool_result'::text))),
    CONSTRAINT entries_only_an_answer_asks_for_tools CHECK (((tool_calls IS NULL) OR (kind = 'answer'::text))),
    CONSTRAINT entries_only_an_answer_names_salients CHECK (((salients IS NULL) OR (kind = 'answer'::text))),
    CONSTRAINT entries_only_an_ejected_payload_was_exported CHECK (((export IS NULL) OR (ejected_at IS NOT NULL))),
    CONSTRAINT entries_only_an_utterance_has_a_speaker CHECK (((speaker IS NULL) OR (kind = 'utterance'::text))),
    CONSTRAINT entries_role_matches_kind CHECK ((NOT (role IS DISTINCT FROM
CASE kind
    WHEN 'utterance'::text THEN 'user'::text
    WHEN 'answer'::text THEN 'assistant'::text
    WHEN 'tool_result'::text THEN 'tool'::text
    WHEN 'summary'::text THEN 'system'::text
    WHEN 'notice'::text THEN 'user'::text
    WHEN 'turn_summary'::text THEN 'user'::text
    ELSE NULL::text
END))),
    CONSTRAINT entries_speaker_is_known CHECK (((speaker IS NULL) OR (speaker = ANY (ARRAY['person'::text, 'harness'::text])))),
    CONSTRAINT entries_the_harness_names_its_source CHECK (((speaker IS DISTINCT FROM 'harness'::text) OR (speaker_name IS NOT NULL))),
    CONSTRAINT entries_token_counts_are_not_negative CHECK ((((prompt_tokens IS NULL) OR (prompt_tokens >= 0)) AND ((completion_tokens IS NULL) OR (completion_tokens >= 0)) AND ((reasoning_tokens IS NULL) OR (reasoning_tokens >= 0)))),
    CONSTRAINT entries_tool_observation_kind CHECK (((tool_observation IS NULL) OR ((kind = 'tool_result'::text) AND (ejected_at IS NULL) AND (jsonb_typeof(tool_observation) = 'object'::text))))
);


--
-- Name: TABLE entries; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.entries IS 'Every message a conversation has held, in order. A request to a model is a projection of these rows and never a construction, so what a model can be shown is exactly what is here. THIS TABLE HOLDS TOOL OUTPUT: file contents read from the operator''s disk, memory bodies, glob output, whole answers from delegated agents. The turns table deliberately holds none of that.';


--
-- Name: COLUMN entries.kind; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.entries.kind IS 'What kind of thing this is. utterance, answer, tool_result and summary project into a request as user, assistant, tool and system; notice -- the harness telling a bot about its speaker''s unread inbox -- and turn_summary -- what a fold inside a turn put where that turn''s older steps were -- project as user; attempt_failed, runtime_note, plan, diagnostic, refusal, hook and thinking are recorded and never reach a model. A kind with no role does not project, so a kind added without a decision is invisible rather than accidentally visible. A refusal is a model''s probable refusal that the agent''s fallback was dispatched to answer in its place. A thinking row is what a model streamed as reasoning during one call, just before what that call said.';


--
-- Name: COLUMN entries.content; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.entries.content IS 'The text. NULL means the payload was ejected -- see ejected_at and export -- and never that a tool returned nothing; blank is what a tool returning nothing would be, and entries_a_tool_result_is_not_blank refuses that for a result. A reader that treats NULL as empty is telling a model a tool does not work.';


--
-- Name: COLUMN entries.superseded_by; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.entries.superseded_by IS 'The ordinal of the summary entry that folded this one away, or NULL. The projection skips a superseded entry; nothing is deleted and no content is ever edited. Written at most once per row.';


--
-- Name: COLUMN entries.turn_ordinal; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.entries.turn_ordinal IS 'Which turn produced this entry, or for a summary the last turn it stands for. There is deliberately no foreign key to turns: a turn row is written when the turn ends and entries are written as it runs, and a run that died mid-call leaves entries and no turn row at all.';


--
-- Name: COLUMN entries.handle; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.entries.handle IS 'The address a model redeems this tool result at, or NULL for every other kind and for a result written before V13. A later turn is shown a reference in place of an earlier turn''s result -- same tool slot, same tool_call_id, content replaced by the tool, its arguments, the size and this handle -- and the content comes back only if the model asks for it. A random UUID and not (conversation_id, ordinal), because an ordinal is enumerable and this must not be: a model holding one handle must not be able to write down another. It survives a fold on purpose, which is what makes compaction non-destructive.';


--
-- Name: COLUMN entries.recorded_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.entries.recorded_at IS 'When this entry was written, which for every kind this server appends is when the thing it records finished. NULL for an entry written before V16. Written by EntryStore.append from an injected clock and never by its caller. DO NOT SUBTRACT TWO OF THESE AND CALL IT A DURATION: the gap between two entries contains everything that happened between them, including whole tool calls. took_ms is the measured number.';


--
-- Name: COLUMN entries.took_ms; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.entries.took_ms IS 'How long the operation that produced this entry took, in milliseconds, measured where the operation was performed -- around the model call for an answer, around the tool for a tool_result. NULL for a kind that records no operation, for an entry written before V16, and for an answer written by agents.Turn out of an Outcome rather than out of a completion. Zero is a real measurement of an operation that took under a millisecond, unlike turns.prompt_tokens where zero is refused.';


--
-- Name: COLUMN entries.learned_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.entries.learned_at IS 'When the learner was handed this row, or NULL for a row still waiting -- and for every row of a conversation that has never folded. Stamped by the system over the window it computed, never from an agent''s report of what it read. It gates nothing: retention does not consult it, and a payload may be ejected having never been learned.';


--
-- Name: COLUMN entries.text_search; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.entries.text_search IS 'The entry''s own text as english lexemes, computed by the database and writable by nothing -- so a lexical index that disagrees with the text it indexes is unreachable rather than merely unlikely. NULL for an ejected payload, which is therefore never a hit: the bytes are gone, and EntryStore.search counts those rows separately rather than letting them be silently missing. The query side must name the same text search configuration or it silently stops matching; EntryStore.SEARCH_SQL does, and a test compares the two spellings.';


--
-- Name: COLUMN entries.invocation; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.entries.invocation IS 'The id of the model call that produced this row, minted by the runtime. NULL for anything no model call produced, and for answers written before V39.';


--
-- Name: COLUMN entries.produced_by; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.entries.produced_by IS 'The agent whose run made the call. Execution provenance; never projected.';


--
-- Name: COLUMN entries.dispatch; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.entries.dispatch IS 'primary: the agent''s own model. fallback: the class its definition names under fallback.model, dispatched because the primary call was a probable refusal.';


--
-- Name: COLUMN entries.model_specifier; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.entries.model_specifier IS 'What the request named -- a class or a wire model -- as the agent file spells it.';


--
-- Name: COLUMN entries.model_pool; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.entries.model_pool IS 'The pool the dispatcher routed the call to, or NULL when it did not say.';


--
-- Name: COLUMN entries.wire_model; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.entries.wire_model IS 'The model that pool was asked for, or NULL when the dispatcher did not say.';


--
-- Name: COLUMN entries.completion; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.entries.completion IS 'What the completion was judged to be: answered, refused, cut_off (finish_reason length -- a token or context limit, which the endpoint does not distinguish) or called_tools. Judged by the harness, never by the model.';


--
-- Name: COLUMN entries.fallback_reason; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.entries.fallback_reason IS 'Why a fallback was taken, on the refusal that caused it and on every call the fallback made.';


--
-- Name: COLUMN entries.speaker; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.entries.speaker IS 'Who spoke this utterance: person or harness. NULL on every other kind, and on an utterance written before V57, which is read as a person''s.';


--
-- Name: COLUMN entries.speaker_name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.entries.speaker_name IS 'For a person, their handle (NULL when nobody knew it). For the harness, its source: "orchestration <id>", "approval <id>", "event <id>" or "harness".';


--
-- Name: COLUMN entries.outcome; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.entries.outcome IS 'A tool result''s outcome in a word, as the runtime told it (ToolLines); NULL before V62.';


--
-- Name: COLUMN entries.salients; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.entries.salients IS 'On an answer that asked for tools: call id -> the call''s salient argument; NULL before V62.';


--
-- Name: execution_allowance_claims; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.execution_allowance_claims (
    budget_id uuid NOT NULL,
    ordinal integer NOT NULL,
    call_limit integer,
    CONSTRAINT execution_allowance_claims_check CHECK (((call_limit > 0) AND (ordinal <= call_limit))),
    CONSTRAINT execution_allowance_claims_ordinal_check CHECK ((ordinal > 0))
);


--
-- Name: fetched_pages; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.fetched_pages (
    url_key text NOT NULL,
    url text NOT NULL,
    title text NOT NULL,
    text text NOT NULL,
    fetched_at timestamp with time zone NOT NULL,
    last_read timestamp with time zone NOT NULL,
    byte_size bigint NOT NULL,
    CONSTRAINT fetched_pages_a_key_is_a_sha_256 CHECK ((url_key ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT fetched_pages_a_url_is_not_blank CHECK ((url <> ''::text)),
    CONSTRAINT fetched_pages_byte_size_is_not_negative CHECK ((byte_size >= 0))
);


--
-- Name: TABLE fetched_pages; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.fetched_pages IS 'One fetched web page, keyed by the SHA-256 of its URL (host lowercased) and held whole so an agent can read it back in windows without the page being fetched again. Written once per fetch by FetchedPageStore.put, which replaces rather than adds a row on a repeated key; last_read moves forward on every touchRead. purgeExpired deletes only a row that is both past its TTL and not being read -- see that method''s own comment for why liveness is an eviction guard as well as a refetch guard. No etag, content_hash or version: this table backs a claim about what an agent saw, not about the current state of the page, and change detection was rejected rather than deferred (spec section 7).';


--
-- Name: firings; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.firings (
    id text NOT NULL,
    event text NOT NULL,
    data jsonb DEFAULT '{}'::jsonb NOT NULL,
    schedule text,
    fire_at timestamp with time zone,
    trigger text,
    target text,
    status text NOT NULL,
    superseded_by text,
    reason text,
    job_id text,
    arrived_at timestamp with time zone NOT NULL,
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    topic text,
    CONSTRAINT firings_a_tick_is_both_halves_or_neither CHECK (((schedule IS NULL) = (fire_at IS NULL))),
    CONSTRAINT firings_a_wake_is_no_triggers CHECK (((trigger IS NULL) OR (topic IS NULL))),
    CONSTRAINT firings_started_names_its_job CHECK (((status <> 'started'::text) OR (job_id IS NOT NULL))),
    CONSTRAINT firings_status_is_known CHECK ((status = ANY (ARRAY['queued'::text, 'started'::text, 'superseded'::text, 'refused'::text, 'unmatched'::text]))),
    CONSTRAINT firings_superseded_names_its_successor CHECK (((status = 'superseded'::text) = (superseded_by IS NOT NULL))),
    CONSTRAINT firings_unmatched_has_no_trigger CHECK (((status = 'unmatched'::text) = ((trigger IS NULL) AND (topic IS NULL))))
);


--
-- Name: hosted_tool_scope_mutations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.hosted_tool_scope_mutations (
    request_id uuid NOT NULL,
    binding_id uuid NOT NULL,
    actor text NOT NULL,
    operation text NOT NULL,
    receipt jsonb,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: hosted_tool_scope_workers; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.hosted_tool_scope_workers (
    binding_id uuid NOT NULL,
    registration uuid NOT NULL,
    binding_generation bigint NOT NULL,
    session text NOT NULL,
    socket text NOT NULL,
    server uuid NOT NULL,
    catalogue_after bigint NOT NULL,
    registered_at timestamp with time zone NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    CONSTRAINT hosted_tool_scope_workers_catalogue_after_check CHECK ((catalogue_after >= 0)),
    CONSTRAINT hosted_tool_scope_workers_check CHECK ((registered_at < expires_at))
);


--
-- Name: hosted_tool_scopes; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.hosted_tool_scopes (
    id uuid NOT NULL,
    project_id bigint,
    owner text NOT NULL,
    generation bigint NOT NULL,
    enabled boolean NOT NULL,
    deleted boolean NOT NULL,
    application boolean NOT NULL,
    scope text NOT NULL,
    route text NOT NULL,
    connection_id uuid NOT NULL,
    credential_subject text NOT NULL,
    definition jsonb NOT NULL,
    CONSTRAINT hosted_tool_scopes_check CHECK (((NOT deleted) OR (NOT enabled))),
    CONSTRAINT hosted_tool_scopes_check1 CHECK (((definition ->> 'scope'::text) = scope)),
    CONSTRAINT hosted_tool_scopes_check2 CHECK ((((definition ->> 'connection'::text))::uuid = connection_id)),
    CONSTRAINT hosted_tool_scopes_definition_check CHECK ((jsonb_typeof(definition) = 'object'::text)),
    CONSTRAINT hosted_tool_scopes_generation_check CHECK (((generation >= 1) AND (generation <= '9007199254740991'::bigint)))
);


--
-- Name: inference_accounting_events; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.inference_accounting_events (
    event_id uuid NOT NULL,
    journal_id uuid NOT NULL,
    journal_sequence bigint NOT NULL,
    call_id uuid NOT NULL,
    call_sequence bigint NOT NULL,
    event_data jsonb NOT NULL,
    CONSTRAINT inference_accounting_events_call_sequence_check CHECK ((call_sequence >= 1)),
    CONSTRAINT inference_accounting_events_journal_sequence_check CHECK ((journal_sequence >= 1))
);


--
-- Name: inference_accounting_instances; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.inference_accounting_instances (
    instance_id uuid NOT NULL,
    journal_id uuid NOT NULL,
    started_at timestamp with time zone NOT NULL,
    ended_at timestamp with time zone
);


--
-- Name: inference_accounting_journals; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.inference_accounting_journals (
    journal_id uuid NOT NULL,
    projected_sequence bigint DEFAULT 0 NOT NULL,
    tracking_started_at timestamp with time zone NOT NULL,
    last_projected_at timestamp with time zone,
    CONSTRAINT inference_accounting_journals_projected_sequence_check CHECK ((projected_sequence >= 0))
);


--
-- Name: inference_admissions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.inference_admissions (
    id uuid NOT NULL,
    operation_id uuid NOT NULL,
    budget_id uuid NOT NULL,
    ordinal integer NOT NULL,
    call_limit integer,
    submitted boolean DEFAULT false NOT NULL,
    accounting_call uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    preparation_revision uuid,
    preparation_generation bigint,
    preparation_targets text[],
    preparation_published boolean DEFAULT false NOT NULL,
    query_id uuid,
    query_task_hash text,
    CONSTRAINT inference_admissions_check CHECK (((call_limit > 0) AND (ordinal <= call_limit))),
    CONSTRAINT inference_admissions_check1 CHECK ((((preparation_revision IS NULL) AND (preparation_generation IS NULL) AND (preparation_targets IS NULL) AND (NOT preparation_published)) OR ((preparation_revision IS NOT NULL) AND (preparation_generation IS NOT NULL) AND (preparation_generation > 0) AND (preparation_targets IS NOT NULL) AND (cardinality(preparation_targets) > 0) AND ((NOT preparation_published) OR submitted)))),
    CONSTRAINT inference_admissions_check2 CHECK ((((query_id IS NULL) AND (query_task_hash IS NULL)) OR ((query_id IS NOT NULL) AND (query_task_hash IS NOT NULL) AND (query_task_hash ~ '^[a-f0-9]{64}$'::text) AND (preparation_revision IS NULL)))),
    CONSTRAINT inference_admissions_ordinal_check CHECK ((ordinal > 0))
);


--
-- Name: inference_attempts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.inference_attempts (
    attempt_id uuid NOT NULL,
    call_id uuid NOT NULL,
    attempt_number integer NOT NULL,
    started_at timestamp with time zone NOT NULL,
    ended_at timestamp with time zone,
    outcome text NOT NULL,
    provider_request_id text,
    http_status integer,
    finish_reason text,
    observation jsonb,
    input_tokens bigint,
    output_tokens bigint,
    provider_total_tokens bigint,
    cache_read_tokens bigint,
    cache_write_tokens bigint,
    reasoning_tokens bigint,
    usage_source text DEFAULT 'NONE'::text NOT NULL,
    usage_coverage text DEFAULT 'UNKNOWN'::text NOT NULL,
    normalization_version integer,
    cost_result jsonb,
    cost_kind text DEFAULT 'UNKNOWN'::text NOT NULL,
    cost_amount numeric,
    cost_currency text,
    price_version text,
    first_output_millis bigint,
    duration_millis bigint,
    start_accounting_millis bigint,
    CONSTRAINT inference_attempts_attempt_number_check CHECK ((attempt_number >= 1)),
    CONSTRAINT inference_attempts_cache_read_tokens_check CHECK ((cache_read_tokens >= 0)),
    CONSTRAINT inference_attempts_cache_write_tokens_check CHECK ((cache_write_tokens >= 0)),
    CONSTRAINT inference_attempts_check CHECK (((ended_at IS NULL) OR (ended_at >= started_at))),
    CONSTRAINT inference_attempts_check1 CHECK (((outcome = 'RUNNING'::text) = (ended_at IS NULL))),
    CONSTRAINT inference_attempts_check2 CHECK (((first_output_millis IS NULL) OR (first_output_millis <= duration_millis))),
    CONSTRAINT inference_attempts_cost_amount_check CHECK ((cost_amount >= (0)::numeric)),
    CONSTRAINT inference_attempts_duration_millis_check CHECK ((duration_millis >= 0)),
    CONSTRAINT inference_attempts_first_output_millis_check CHECK ((first_output_millis >= 0)),
    CONSTRAINT inference_attempts_http_status_check CHECK (((http_status >= 100) AND (http_status <= 599))),
    CONSTRAINT inference_attempts_input_tokens_check CHECK ((input_tokens >= 0)),
    CONSTRAINT inference_attempts_outcome_check CHECK ((outcome = ANY (ARRAY['RUNNING'::text, 'SUCCEEDED'::text, 'REFUSED'::text, 'FAILED'::text, 'CANCELLED'::text, 'INTERRUPTED'::text]))),
    CONSTRAINT inference_attempts_output_tokens_check CHECK ((output_tokens >= 0)),
    CONSTRAINT inference_attempts_provider_total_tokens_check CHECK ((provider_total_tokens >= 0)),
    CONSTRAINT inference_attempts_reasoning_tokens_check CHECK ((reasoning_tokens >= 0)),
    CONSTRAINT inference_attempts_start_accounting_millis_check CHECK ((start_accounting_millis >= 0))
);


--
-- Name: inference_calls; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.inference_calls (
    call_id uuid NOT NULL,
    instance_id uuid NOT NULL,
    attribution jsonb NOT NULL,
    account_handle text,
    project_id text,
    scope text NOT NULL,
    conversation_id text,
    root_conversation_id text,
    ancestor_conversation_ids text[] NOT NULL,
    run_id text,
    parent_run_id text,
    root_run_id text,
    ancestor_run_ids text[] NOT NULL,
    orchestration_id text,
    root_orchestration_id text,
    ancestor_orchestration_ids text[] NOT NULL,
    agent_name text,
    turn_ordinal bigint,
    step_ordinal bigint,
    operation text NOT NULL,
    attribution_status text NOT NULL,
    specifier text NOT NULL,
    pool text NOT NULL,
    wire_model text NOT NULL,
    model_family text,
    billing_route text NOT NULL,
    price_version text,
    lane text NOT NULL,
    created_at timestamp with time zone NOT NULL,
    last_event_at timestamp with time zone NOT NULL,
    ended_at timestamp with time zone,
    lifecycle text NOT NULL,
    attempt_count integer DEFAULT 0 NOT NULL,
    last_call_sequence bigint NOT NULL,
    preflight_observation jsonb,
    queue_millis bigint,
    admission_accounting_millis bigint,
    CONSTRAINT inference_calls_admission_accounting_millis_check CHECK ((admission_accounting_millis >= 0)),
    CONSTRAINT inference_calls_attempt_count_check CHECK ((attempt_count >= 0)),
    CONSTRAINT inference_calls_attribution_status_check CHECK ((attribution_status = ANY (ARRAY['ATTRIBUTED'::text, 'SYSTEM'::text, 'LEGACY_UNATTRIBUTED'::text]))),
    CONSTRAINT inference_calls_check CHECK ((((scope = 'PROJECT'::text) AND (project_id IS NOT NULL)) OR ((scope = 'GLOBAL'::text) AND (project_id IS NULL)))),
    CONSTRAINT inference_calls_check1 CHECK (((ended_at IS NULL) OR (ended_at >= created_at))),
    CONSTRAINT inference_calls_check2 CHECK (((lifecycle = ANY (ARRAY['QUEUED'::text, 'RUNNING'::text])) = (ended_at IS NULL))),
    CONSTRAINT inference_calls_lane_check CHECK ((lane = ANY (ARRAY['CHAT'::text, 'EMBEDDING'::text]))),
    CONSTRAINT inference_calls_last_call_sequence_check CHECK ((last_call_sequence >= 1)),
    CONSTRAINT inference_calls_lifecycle_check CHECK ((lifecycle = ANY (ARRAY['QUEUED'::text, 'RUNNING'::text, 'SUCCEEDED'::text, 'REFUSED'::text, 'FAILED'::text, 'CANCELLED'::text, 'INTERRUPTED'::text, 'NOT_DISPATCHED'::text]))),
    CONSTRAINT inference_calls_queue_millis_check CHECK ((queue_millis >= 0)),
    CONSTRAINT inference_calls_scope_check CHECK ((scope = ANY (ARRAY['PROJECT'::text, 'GLOBAL'::text]))),
    CONSTRAINT inference_calls_step_ordinal_check CHECK ((step_ordinal >= 0)),
    CONSTRAINT inference_calls_turn_ordinal_check CHECK ((turn_ordinal >= 0))
);


--
-- Name: inference_pricing_versions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.inference_pricing_versions (
    version text NOT NULL,
    billing_route text NOT NULL,
    wire_model text NOT NULL,
    currency text,
    mode text NOT NULL,
    rate_card jsonb NOT NULL,
    CONSTRAINT inference_pricing_versions_mode_check CHECK ((mode = ANY (ARRAY['TOKEN'::text, 'INCLUDED'::text, 'ZERO_RATE'::text, 'UNPRICED'::text]))),
    CONSTRAINT inference_pricing_versions_version_check CHECK ((length(version) = 64))
);


--
-- Name: inference_run_ownership; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.inference_run_ownership (
    conversation_id text NOT NULL,
    turn_ordinal bigint NOT NULL,
    attribution jsonb NOT NULL,
    CONSTRAINT inference_run_ownership_turn_ordinal_check CHECK ((turn_ordinal >= 1))
);


--
-- Name: information_acquisitions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.information_acquisitions (
    id uuid NOT NULL,
    account text NOT NULL,
    request_id uuid NOT NULL,
    scope text NOT NULL,
    project_name text,
    fingerprint text NOT NULL,
    url text NOT NULL,
    source_name text NOT NULL,
    caller_session text,
    log_id text,
    allowance_total integer NOT NULL,
    state text DEFAULT 'queued'::text NOT NULL,
    revision_id uuid,
    attempt integer DEFAULT 0 NOT NULL,
    token uuid,
    lease_until timestamp with time zone,
    error text,
    pre_gate jsonb,
    post_gate jsonb,
    finish_records jsonb,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    corpus text DEFAULT 'documents'::text NOT NULL,
    content_intent jsonb,
    CONSTRAINT information_acquisitions_allowance_total_check CHECK ((allowance_total > 0)),
    CONSTRAINT information_acquisitions_content_intent_check CHECK (((content_intent IS NULL) OR (jsonb_typeof(content_intent) = 'object'::text))),
    CONSTRAINT information_acquisitions_corpus_check CHECK ((corpus = ANY (ARRAY['documents'::text, 'code'::text]))),
    CONSTRAINT information_acquisitions_scope_check CHECK ((scope = ANY (ARRAY['personal'::text, 'project'::text]))),
    CONSTRAINT information_acquisitions_state_check CHECK ((state = ANY (ARRAY['queued'::text, 'running'::text, 'blocked'::text, 'failed'::text, 'succeeded'::text])))
);


--
-- Name: information_commands; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.information_commands (
    account text NOT NULL,
    request_id uuid NOT NULL,
    revision_id uuid NOT NULL,
    fingerprint text NOT NULL,
    state text NOT NULL,
    token uuid NOT NULL,
    lease_until timestamp with time zone NOT NULL,
    error text,
    CONSTRAINT information_commands_state_check CHECK ((state = ANY (ARRAY['reserved'::text, 'applied'::text, 'completed'::text, 'blocked'::text, 'failed'::text])))
);


--
-- Name: information_content_type_versions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.information_content_type_versions (
    id uuid NOT NULL,
    type_id uuid NOT NULL,
    ordinal integer NOT NULL,
    schema_definition jsonb NOT NULL,
    presentation jsonb NOT NULL,
    profile text NOT NULL,
    definition_hash text NOT NULL,
    state text DEFAULT 'draft'::text NOT NULL,
    created_by text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT information_content_type_versions_definition_hash_check CHECK ((definition_hash ~ '^[a-f0-9]{64}$'::text)),
    CONSTRAINT information_content_type_versions_ordinal_check CHECK ((ordinal > 0)),
    CONSTRAINT information_content_type_versions_presentation_check CHECK ((jsonb_typeof(presentation) = 'object'::text)),
    CONSTRAINT information_content_type_versions_profile_check CHECK ((profile = 'plowshare-document-metadata/1'::text)),
    CONSTRAINT information_content_type_versions_schema_definition_check CHECK ((jsonb_typeof(schema_definition) = 'object'::text)),
    CONSTRAINT information_content_type_versions_state_check CHECK ((state = ANY (ARRAY['draft'::text, 'active'::text, 'deprecated'::text])))
);


--
-- Name: information_content_types; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.information_content_types (
    id uuid NOT NULL,
    namespace text NOT NULL,
    slug text NOT NULL,
    label text NOT NULL,
    description text NOT NULL,
    created_by text NOT NULL,
    control_revision bigint DEFAULT 1 NOT NULL,
    next_version integer DEFAULT 1 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT information_content_types_control_revision_check CHECK ((control_revision > 0)),
    CONSTRAINT information_content_types_description_check CHECK ((length(description) <= 4096)),
    CONSTRAINT information_content_types_label_check CHECK (((length(label) >= 1) AND (length(label) <= 120))),
    CONSTRAINT information_content_types_next_version_check CHECK ((next_version > 0)),
    CONSTRAINT information_content_types_slug_check CHECK ((slug ~ '^[a-z]([a-z0-9-]{0,62}[a-z0-9])?$'::text))
);


--
-- Name: information_document_policies; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.information_document_policies (
    document_id uuid NOT NULL,
    owner_handle text,
    visibility text DEFAULT 'quarantined'::text NOT NULL,
    project_id bigint,
    assigned_at timestamp with time zone,
    CONSTRAINT information_document_policy_shape CHECK ((((visibility = 'quarantined'::text) AND (owner_handle IS NULL) AND (project_id IS NULL) AND (assigned_at IS NULL)) OR ((visibility = ANY (ARRAY['personal'::text, 'shared'::text])) AND (owner_handle IS NOT NULL) AND (project_id IS NULL) AND (assigned_at IS NOT NULL)) OR ((visibility = 'project'::text) AND (owner_handle IS NOT NULL) AND (project_id IS NOT NULL) AND (assigned_at IS NOT NULL))))
);


--
-- Name: information_evidence; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.information_evidence (
    id uuid NOT NULL,
    revision_id uuid NOT NULL,
    paragraph_id uuid,
    start_offset integer NOT NULL,
    end_offset integer NOT NULL,
    quote text NOT NULL,
    locator text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    owner_handle text,
    request_id uuid,
    CONSTRAINT information_evidence_check CHECK ((end_offset > start_offset)),
    CONSTRAINT information_evidence_quote_check CHECK ((quote <> ''::text)),
    CONSTRAINT information_evidence_start_offset_check CHECK ((start_offset >= 0))
);


--
-- Name: information_import_origins; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.information_import_origins (
    revision_id uuid NOT NULL,
    origin jsonb NOT NULL,
    redacted boolean DEFAULT false NOT NULL,
    CONSTRAINT information_import_origins_origin_check CHECK ((jsonb_typeof(origin) = 'object'::text))
);


--
-- Name: information_inputs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.information_inputs (
    derived_revision uuid NOT NULL,
    input_revision uuid NOT NULL,
    CONSTRAINT information_inputs_check CHECK ((derived_revision <> input_revision))
);


--
-- Name: information_job_inputs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.information_job_inputs (
    job_id text NOT NULL,
    owner_handle text NOT NULL,
    revision_id uuid NOT NULL,
    scope text NOT NULL,
    project_name text,
    include_shared boolean NOT NULL
);


--
-- Name: information_links; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.information_links (
    revision_id uuid NOT NULL,
    project_id bigint NOT NULL,
    actor_handle text NOT NULL
);


--
-- Name: information_model_steps; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.information_model_steps (
    revision_id uuid NOT NULL,
    generation bigint NOT NULL,
    stage_key text NOT NULL,
    stage text NOT NULL,
    owner_handle text NOT NULL,
    response jsonb NOT NULL,
    state text NOT NULL,
    log_id text,
    CONSTRAINT information_model_steps_state_check CHECK ((state = ANY (ARRAY['blocked'::text, 'ready'::text])))
);


--
-- Name: information_navigation_audit; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.information_navigation_audit (
    sequence bigint NOT NULL,
    account text NOT NULL,
    request_id uuid NOT NULL,
    action text NOT NULL,
    subject text NOT NULL,
    reason text NOT NULL,
    previous_value jsonb,
    next_value jsonb NOT NULL,
    recorded_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: information_navigation_audit_sequence_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.information_navigation_audit_sequence_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: information_navigation_audit_sequence_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.information_navigation_audit_sequence_seq OWNED BY public.information_navigation_audit.sequence;


--
-- Name: information_navigation_policies; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.information_navigation_policies (
    policy_key text NOT NULL,
    version bigint NOT NULL,
    settings jsonb NOT NULL,
    actor text NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT information_navigation_policies_settings_check CHECK ((jsonb_typeof(settings) = 'object'::text)),
    CONSTRAINT information_navigation_policies_version_check CHECK ((version > 0))
);


--
-- Name: information_navigation_receipts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.information_navigation_receipts (
    account text NOT NULL,
    request_id uuid NOT NULL,
    fingerprint text NOT NULL,
    result jsonb NOT NULL
);


--
-- Name: information_navigation_views; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.information_navigation_views (
    account text NOT NULL,
    id uuid NOT NULL,
    label text NOT NULL,
    path jsonb NOT NULL,
    version bigint NOT NULL,
    deleted boolean DEFAULT false NOT NULL,
    CONSTRAINT information_navigation_views_label_check CHECK (((length(label) >= 1) AND (length(label) <= 120))),
    CONSTRAINT information_navigation_views_version_check CHECK ((version > 0))
);


--
-- Name: information_payload_assignments; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.information_payload_assignments (
    id uuid NOT NULL,
    payload_id text NOT NULL,
    actor_handle text NOT NULL,
    owner_handle text,
    action text NOT NULL,
    inputs jsonb NOT NULL,
    reason text NOT NULL,
    recorded_at timestamp with time zone NOT NULL,
    CONSTRAINT information_payload_assignments_action_check CHECK ((action = ANY (ARRAY['inspect'::text, 'release'::text]))),
    CONSTRAINT information_payload_assignments_reason_check CHECK ((btrim(reason) <> ''::text))
);


--
-- Name: information_policy_assignments; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.information_policy_assignments (
    id uuid NOT NULL,
    document_id uuid NOT NULL,
    actor_handle text NOT NULL,
    owner_handle text NOT NULL,
    visibility text NOT NULL,
    project_name text,
    reason text NOT NULL,
    assigned_at timestamp with time zone NOT NULL,
    CONSTRAINT information_assignment_project CHECK ((((visibility = 'project'::text) AND (project_name IS NOT NULL) AND (btrim(project_name) <> ''::text)) OR ((visibility <> 'project'::text) AND (project_name IS NULL)))),
    CONSTRAINT information_policy_assignments_reason_check CHECK ((btrim(reason) <> ''::text)),
    CONSTRAINT information_policy_assignments_visibility_check CHECK ((visibility = ANY (ARRAY['personal'::text, 'project'::text, 'shared'::text])))
);


--
-- Name: information_projection_reuse; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.information_projection_reuse (
    revision_id uuid NOT NULL,
    stage text NOT NULL,
    origin_revision uuid NOT NULL,
    origin_generation bigint NOT NULL,
    fingerprint text NOT NULL,
    manifest_hash text NOT NULL,
    CONSTRAINT information_projection_reuse_fingerprint_check CHECK ((fingerprint ~ '^[a-f0-9]{64}$'::text)),
    CONSTRAINT information_projection_reuse_manifest_hash_check CHECK ((manifest_hash ~ '^[a-f0-9]{64}$'::text)),
    CONSTRAINT information_projection_reuse_origin_generation_check CHECK ((origin_generation > 0)),
    CONSTRAINT information_projection_reuse_stage_check CHECK ((stage = ANY (ARRAY['extract'::text, 'derive'::text, 'embed'::text, 'summarise'::text, 'summary_embed'::text, 'autoTag'::text, 'tagGroups'::text])))
);


--
-- Name: information_quarantined_payloads; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.information_quarantined_payloads (
    payload_id text NOT NULL,
    reason text NOT NULL
);


--
-- Name: information_reference_obligations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.information_reference_obligations (
    relationship uuid NOT NULL,
    source uuid NOT NULL,
    target uuid NOT NULL,
    pinned_revision uuid,
    incurred_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: information_relationships; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.information_relationships (
    id uuid NOT NULL,
    source uuid NOT NULL,
    target uuid NOT NULL,
    pinned_revision uuid,
    relation text NOT NULL,
    actor text NOT NULL,
    version bigint DEFAULT 1 NOT NULL,
    removed boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT information_relationships_relation_check CHECK ((relation ~ '^[a-z][a-z0-9_-]{0,63}$'::text)),
    CONSTRAINT information_relationships_version_check CHECK ((version > 0))
);


--
-- Name: information_report_citations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.information_report_citations (
    report_revision uuid NOT NULL,
    evidence_id uuid NOT NULL
);


--
-- Name: information_reports; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.information_reports (
    revision_id uuid NOT NULL,
    status text DEFAULT 'draft'::text NOT NULL,
    feedback_revision uuid,
    details jsonb DEFAULT '{"reviews": [], "findings": [], "objectives": [], "scopeChanges": []}'::jsonb NOT NULL,
    produced_by text,
    definition_hash text,
    finalised_at timestamp with time zone,
    CONSTRAINT information_reports_status_check CHECK ((status = ANY (ARRAY['draft'::text, 'final'::text, 'superseded'::text])))
);


--
-- Name: information_requests; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.information_requests (
    account text NOT NULL,
    request_id uuid NOT NULL,
    fingerprint text NOT NULL,
    revision_id uuid NOT NULL
);


--
-- Name: information_resource_policies; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.information_resource_policies (
    resource_id uuid NOT NULL,
    version bigint NOT NULL,
    audience text NOT NULL,
    privacy_principal uuid,
    private_only boolean DEFAULT false NOT NULL,
    needs_review boolean DEFAULT false NOT NULL,
    actor uuid,
    reason text NOT NULL,
    changed_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT information_resource_policies_audience_check CHECK ((audience = ANY (ARRAY['HOME'::text, 'GLOBAL_READ'::text, 'QUARANTINED'::text]))),
    CONSTRAINT information_resource_policies_reason_check CHECK ((reason = ANY (ARRAY['MIGRATION'::text, 'INITIAL'::text, 'AUDIENCE'::text, 'ASSIGNMENT'::text]))),
    CONSTRAINT information_resource_policies_version_check CHECK ((version > 0))
);


--
-- Name: information_resource_policy_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.information_resource_policy_history (
    resource_id uuid NOT NULL,
    version bigint NOT NULL,
    audience text NOT NULL,
    privacy_principal uuid,
    private_only boolean NOT NULL,
    needs_review boolean NOT NULL,
    actor uuid,
    reason text NOT NULL,
    recorded_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT information_resource_policy_history_version_check CHECK ((version > 0))
);


--
-- Name: information_resources; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.information_resources (
    id uuid NOT NULL,
    namespace text NOT NULL,
    source_name text NOT NULL,
    owner_handle text,
    project_id bigint,
    kind text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    tags jsonb DEFAULT '[]'::jsonb NOT NULL,
    tag_groups jsonb DEFAULT '{}'::jsonb NOT NULL,
    tag_groups_manual boolean DEFAULT false NOT NULL,
    home_scope_id uuid,
    created_by uuid,
    creator_evidence text DEFAULT 'unknown'::text NOT NULL,
    tags_version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT information_resources_check CHECK (((created_by IS NULL) = (creator_evidence = 'unknown'::text))),
    CONSTRAINT information_resources_creator_evidence_check CHECK ((creator_evidence = ANY (ARRAY['unknown'::text, 'admission_request'::text, 'intake_event'::text, 'authenticated_admission'::text]))),
    CONSTRAINT information_resources_kind_check CHECK ((kind = ANY (ARRAY['source'::text, 'report'::text]))),
    CONSTRAINT information_resources_source_name_check CHECK ((btrim(source_name) <> ''::text)),
    CONSTRAINT information_resources_tag_groups_check CHECK ((jsonb_typeof(tag_groups) = 'object'::text)),
    CONSTRAINT information_resources_tags_check CHECK (((jsonb_typeof(tags) = 'array'::text) AND (jsonb_array_length(tags) <= 32))),
    CONSTRAINT information_resources_tags_version_check CHECK (((tags_version >= 0) AND (tags_version <= '9007199254740991'::bigint)))
);


--
-- Name: information_reuse_preparations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.information_reuse_preparations (
    actor text NOT NULL,
    request_id uuid NOT NULL,
    payload jsonb NOT NULL
);


--
-- Name: information_revisions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.information_revisions (
    id uuid NOT NULL,
    resource_id uuid NOT NULL,
    ordinal integer NOT NULL,
    title text NOT NULL,
    media_type text NOT NULL,
    source_uri text,
    content_hash text NOT NULL,
    text_hash text,
    byte_size bigint NOT NULL,
    source_bytes bytea,
    extracted_text text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    availability text DEFAULT 'active'::text NOT NULL,
    excluded boolean DEFAULT false NOT NULL,
    generation bigint DEFAULT 1 NOT NULL,
    processing_log text,
    caller_session text,
    converter text,
    outline_top_level jsonb DEFAULT '[]'::jsonb NOT NULL,
    allowance_total integer DEFAULT 1000 NOT NULL,
    allowance_spent integer DEFAULT 0 NOT NULL,
    document_type text DEFAULT 'document'::text NOT NULL,
    document_subtype text DEFAULT 'text'::text NOT NULL,
    auto_tag jsonb DEFAULT '[]'::jsonb NOT NULL,
    auto_tag_generated boolean DEFAULT false NOT NULL,
    document_author text,
    document_author_source text,
    document_author_evidence text,
    auto_tag_requested boolean DEFAULT false NOT NULL,
    auto_tag_groups jsonb DEFAULT '{}'::jsonb NOT NULL,
    tag_groups_input_tags jsonb DEFAULT '[]'::jsonb NOT NULL,
    tag_groups_generated boolean DEFAULT false NOT NULL,
    processing_policy text DEFAULT 'automatic'::text NOT NULL,
    starting_revision uuid,
    revised_by text,
    created_by uuid,
    creator_evidence text DEFAULT 'unknown'::text NOT NULL,
    processing_budget_id uuid DEFAULT gen_random_uuid() NOT NULL,
    CONSTRAINT information_document_author_valid CHECK ((((document_author IS NULL) AND (document_author_source IS NULL) AND (document_author_evidence IS NULL)) OR ((document_author IS NOT NULL) AND ((length(btrim(document_author)) >= 1) AND (length(btrim(document_author)) <= 256)) AND (document_author_source IS NOT NULL) AND (document_author_source = ANY (ARRAY['person'::text, 'organisation'::text])) AND (document_author_evidence IS NOT NULL) AND ((length(btrim(document_author_evidence)) >= 1) AND (length(btrim(document_author_evidence)) <= 512))))),
    CONSTRAINT information_revisions_allowance_total_check CHECK ((allowance_total >= 0)),
    CONSTRAINT information_revisions_auto_tag_check CHECK (((jsonb_typeof(auto_tag) = 'array'::text) AND (jsonb_array_length(auto_tag) <= 32))),
    CONSTRAINT information_revisions_auto_tag_groups_check CHECK ((jsonb_typeof(auto_tag_groups) = 'object'::text)),
    CONSTRAINT information_revisions_availability_check CHECK ((availability = ANY (ARRAY['active'::text, 'excluded'::text, 'withdrawn'::text, 'deleted'::text]))),
    CONSTRAINT information_revisions_byte_size_check CHECK (((byte_size > 0) OR ((byte_size = 0) AND (document_type = 'code'::text)))),
    CONSTRAINT information_revisions_check CHECK (((allowance_spent >= 0) AND (allowance_spent <= allowance_total))),
    CONSTRAINT information_revisions_check1 CHECK (((created_by IS NULL) = (creator_evidence = 'unknown'::text))),
    CONSTRAINT information_revisions_creator_evidence_check CHECK ((creator_evidence = ANY (ARRAY['unknown'::text, 'admission_request'::text, 'intake_event'::text, 'authenticated_admission'::text]))),
    CONSTRAINT information_revisions_document_subtype_check CHECK ((document_subtype ~ '^[a-z][a-z0-9_]*$'::text)),
    CONSTRAINT information_revisions_document_type_check CHECK ((document_type = ANY (ARRAY['document'::text, 'code'::text]))),
    CONSTRAINT information_revisions_ordinal_check CHECK ((ordinal > 0)),
    CONSTRAINT information_revisions_processing_policy_check CHECK ((processing_policy = ANY (ARRAY['automatic'::text, 'explicit_only'::text]))),
    CONSTRAINT information_revisions_tag_groups_input_tags_check CHECK ((jsonb_typeof(tag_groups_input_tags) = 'array'::text))
);


--
-- Name: information_revision_allowances; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.information_revision_allowances AS
 SELECT r.id,
    r.resource_id,
    r.ordinal,
    r.title,
    r.media_type,
    r.source_uri,
    r.content_hash,
    r.text_hash,
    r.byte_size,
    r.source_bytes,
    r.extracted_text,
    r.created_at,
    r.availability,
    r.excluded,
    r.generation,
    r.processing_log,
    r.caller_session,
    r.converter,
    r.outline_top_level,
    a.call_limit AS allowance_total,
    a.spent AS allowance_spent,
    r.document_type,
    r.document_subtype,
    r.auto_tag,
    r.auto_tag_generated,
    r.document_author,
    r.document_author_source,
    r.document_author_evidence,
    r.auto_tag_requested,
    r.auto_tag_groups,
    r.tag_groups_input_tags,
    r.tag_groups_generated,
    r.processing_policy,
    r.starting_revision,
    r.revised_by,
    r.created_by,
    r.creator_evidence,
    r.processing_budget_id
   FROM (public.information_revisions r
     JOIN public.execution_allowances a ON ((a.id = r.processing_budget_id)));


--
-- Name: information_revision_content; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.information_revision_content (
    revision_id uuid NOT NULL,
    type_version uuid NOT NULL,
    profile text NOT NULL,
    definition_hash text NOT NULL,
    metadata_hash text NOT NULL,
    metadata jsonb NOT NULL,
    redacted boolean DEFAULT false NOT NULL,
    CONSTRAINT information_revision_content_definition_hash_check CHECK ((definition_hash ~ '^[a-f0-9]{64}$'::text)),
    CONSTRAINT information_revision_content_metadata_check CHECK ((jsonb_typeof(metadata) = 'object'::text)),
    CONSTRAINT information_revision_content_metadata_hash_check CHECK ((metadata_hash ~ '^[a-f0-9]{64}$'::text)),
    CONSTRAINT information_revision_content_profile_check CHECK ((profile = 'plowshare-document-metadata/1'::text))
);


--
-- Name: information_revision_events; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.information_revision_events (
    revision_id uuid NOT NULL,
    relay_project bigint NOT NULL,
    relay_position bigint NOT NULL,
    generation bigint NOT NULL,
    actor_handle text,
    stage text NOT NULL,
    action text NOT NULL,
    detail text DEFAULT ''::text NOT NULL,
    recorded_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT information_revision_events_generation_check CHECK ((generation > 0)),
    CONSTRAINT information_revision_events_relay_position_check CHECK ((relay_position > 0))
);


--
-- Name: information_stage_authorizations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.information_stage_authorizations (
    revision_id uuid NOT NULL,
    generation bigint NOT NULL,
    stage text NOT NULL,
    actor text NOT NULL,
    request_id uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT information_stage_authorizations_generation_check CHECK ((generation > 0)),
    CONSTRAINT information_stage_authorizations_stage_check CHECK ((stage = ANY (ARRAY['extract'::text, 'derive'::text, 'embed'::text, 'summarise'::text, 'summary_embed'::text, 'autoTag'::text, 'tagGroups'::text])))
);


--
-- Name: information_steps; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.information_steps (
    revision_id uuid NOT NULL,
    generation bigint NOT NULL,
    stage text NOT NULL,
    state text DEFAULT 'pending'::text NOT NULL,
    attempt integer DEFAULT 0 NOT NULL,
    lease_token uuid,
    lease_until timestamp with time zone,
    error text,
    fingerprint text,
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    CONSTRAINT information_steps_stage_check CHECK ((stage = ANY (ARRAY['extract'::text, 'derive'::text, 'embed'::text, 'summarise'::text, 'summary_embed'::text, 'autoTag'::text, 'tagGroups'::text]))),
    CONSTRAINT information_steps_state_check CHECK ((state = ANY (ARRAY['pending'::text, 'running'::text, 'ready'::text, 'failed'::text, 'blocked'::text, 'cancelled'::text, 'skipped'::text])))
);


--
-- Name: information_write_gates; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.information_write_gates (
    account text NOT NULL,
    request_id uuid NOT NULL,
    fingerprint text NOT NULL,
    operation text NOT NULL,
    state text DEFAULT 'reserved'::text NOT NULL,
    token uuid NOT NULL,
    lease_until timestamp with time zone NOT NULL,
    log_id text,
    pre_gate jsonb,
    post_gate jsonb,
    finish_records jsonb,
    response jsonb,
    error text,
    CONSTRAINT information_write_gates_state_check CHECK ((state = ANY (ARRAY['reserved'::text, 'approved'::text, 'completed'::text, 'blocked'::text])))
);


--
-- Name: invocation_image_admissions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.invocation_image_admissions (
    invocation_id uuid NOT NULL,
    ordinal integer NOT NULL,
    request_id uuid NOT NULL,
    image_id text NOT NULL,
    sha256 text NOT NULL,
    width integer NOT NULL,
    height integer NOT NULL,
    byte_length bigint NOT NULL,
    admitted_at timestamp with time zone NOT NULL,
    mime_type text NOT NULL,
    CONSTRAINT invocation_image_admissions_byte_length_check CHECK (((byte_length >= 1) AND (byte_length <= 2147483647))),
    CONSTRAINT invocation_image_admissions_check CHECK ((image_id = ('img_'::text || SUBSTRING(sha256 FROM 1 FOR 32)))),
    CONSTRAINT invocation_image_admissions_check1 CHECK ((((width)::bigint * height) <= 64000000)),
    CONSTRAINT invocation_image_admissions_height_check CHECK (((height >= 1) AND (height <= 32768))),
    CONSTRAINT invocation_image_admissions_image_id_check CHECK ((image_id ~ '^img_[0-9a-f]{32}$'::text)),
    CONSTRAINT invocation_image_admissions_mime_type_check CHECK ((mime_type = ANY (ARRAY['image/png'::text, 'image/jpeg'::text, 'image/webp'::text]))),
    CONSTRAINT invocation_image_admissions_ordinal_check CHECK (((ordinal >= 0) AND (ordinal <= 15))),
    CONSTRAINT invocation_image_admissions_sha256_check CHECK ((sha256 ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT invocation_image_admissions_width_check CHECK (((width >= 1) AND (width <= 32768)))
);


--
-- Name: jobs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.jobs (
    id text NOT NULL,
    agent text NOT NULL,
    project_id bigint,
    started_at timestamp with time zone NOT NULL,
    ended_at timestamp with time zone,
    ending text,
    steps integer,
    model_calls integer,
    conversation_id text,
    relay_causation jsonb,
    CONSTRAINT jobs_agent_is_named CHECK ((agent <> ''::text)),
    CONSTRAINT jobs_an_ended_run_says_how CHECK (((ended_at IS NULL) = (ending IS NULL))),
    CONSTRAINT jobs_an_ended_run_says_how_many_calls CHECK (((ended_at IS NULL) = (model_calls IS NULL))),
    CONSTRAINT jobs_an_ended_run_says_how_many_steps CHECK (((ended_at IS NULL) = (steps IS NULL))),
    CONSTRAINT jobs_counts_are_not_negative CHECK ((((steps IS NULL) OR (steps >= 0)) AND ((model_calls IS NULL) OR (model_calls >= 0)))),
    CONSTRAINT jobs_did_not_end_before_it_started CHECK (((ended_at IS NULL) OR (ended_at >= started_at))),
    CONSTRAINT jobs_ending_is_known CHECK (((ending IS NULL) OR (ending = ANY (ARRAY['ANSWERED'::text, 'TURN_CAP'::text, 'CALL_BUDGET'::text, 'CANCELLED'::text, 'STUCK'::text, 'UNAVAILABLE'::text, 'SUB_AGENT_FAILED'::text, 'SESSION_GONE'::text, 'AWAITING'::text, 'CALL_FAILURES'::text])))),
    CONSTRAINT jobs_id_named CHECK ((id <> ''::text)),
    CONSTRAINT jobs_relay_causation_check CHECK (public.relay_valid_causation(relay_causation))
);


--
-- Name: local_hook_sets; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.local_hook_sets (
    hash text NOT NULL,
    files jsonb NOT NULL,
    created_at timestamp with time zone NOT NULL,
    CONSTRAINT local_hook_sets_a_hash_is_a_sha256 CHECK ((hash ~ '^sha256:[0-9a-f]{64}$'::text)),
    CONSTRAINT local_hook_sets_files_are_a_list CHECK ((jsonb_typeof(files) = 'array'::text))
);


--
-- Name: memories; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.memories (
    id text NOT NULL,
    summary text NOT NULL,
    scope text NOT NULL,
    body text NOT NULL,
    state text NOT NULL,
    pinned boolean DEFAULT false NOT NULL,
    uses integer DEFAULT 0 NOT NULL,
    last_used timestamp with time zone,
    formed_at timestamp with time zone NOT NULL,
    formed_by text NOT NULL,
    formed_where text NOT NULL,
    supersedes text,
    superseded_by text,
    invalidated_at timestamp with time zone,
    invalidated_by text,
    invalidated_why text,
    embedding public.vector(768),
    project_id bigint,
    embedding_source_revision bigint DEFAULT 0 NOT NULL,
    code_embedding public.vector,
    code_space_id text,
    code_source_revision bigint,
    prose_embedding public.vector,
    prose_space_id text,
    prose_source_revision bigint,
    home_scope_id uuid NOT NULL,
    CONSTRAINT memories_embedding_source_revision_check CHECK ((embedding_source_revision >= 0)),
    CONSTRAINT memories_state_known CHECK ((state = ANY (ARRAY['active'::text, 'cold'::text, 'superseded'::text, 'invalidated'::text])))
);


--
-- Name: COLUMN memories.project_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.memories.project_id IS 'The project this memory belongs to, or NULL for the global tier. V1''s rule is unchanged: global is the absence of a project, not a project named ''global''.';


--
-- Name: memories_embedding_failures; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.memories_embedding_failures (
    source_id text NOT NULL,
    slot text NOT NULL,
    space_id text NOT NULL,
    source_revision bigint NOT NULL,
    attempts integer DEFAULT 1 NOT NULL,
    category text NOT NULL,
    retry_at timestamp with time zone NOT NULL,
    CONSTRAINT memories_embedding_failures_attempts_check CHECK ((attempts > 0)),
    CONSTRAINT memories_embedding_failures_category_check CHECK ((category ~ '^[A-Za-z][A-Za-z0-9]{0,79}$'::text)),
    CONSTRAINT memories_embedding_failures_source_revision_check CHECK ((source_revision >= 0))
);


--
-- Name: memories_embedding_staging; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.memories_embedding_staging (
    source_id text NOT NULL,
    slot text NOT NULL,
    space_id text NOT NULL,
    source_revision bigint NOT NULL,
    embedding public.vector NOT NULL,
    CONSTRAINT memories_embedding_staging_source_revision_check CHECK ((source_revision >= 0))
);


--
-- Name: memory_provenance; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.memory_provenance (
    memory_id text NOT NULL,
    conversation_id text NOT NULL,
    turn_ordinal integer NOT NULL,
    CONSTRAINT memory_provenance_turn_ordinal_check CHECK ((turn_ordinal >= 1))
);


--
-- Name: memory_reasons; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.memory_reasons (
    id bigint NOT NULL,
    memory_id text NOT NULL,
    filed_at timestamp with time zone NOT NULL,
    kind text NOT NULL,
    target_id text,
    reason text NOT NULL,
    CONSTRAINT memory_reasons_kind_known CHECK ((kind = ANY (ARRAY['new'::text, 'merged_into'::text, 'supersedes'::text]))),
    CONSTRAINT memory_reasons_merge_lands_on_its_target CHECK (((kind <> 'merged_into'::text) OR (target_id = memory_id))),
    CONSTRAINT memory_reasons_target_matches_kind CHECK (((kind = 'new'::text) = (target_id IS NULL)))
);


--
-- Name: memory_reasons_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.memory_reasons ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.memory_reasons_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: message_external_contexts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.message_external_contexts (
    id uuid NOT NULL,
    account text NOT NULL,
    project text NOT NULL,
    client text NOT NULL,
    agent text NOT NULL,
    sender text NOT NULL,
    recipient text NOT NULL
);


--
-- Name: message_external_tasks; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.message_external_tasks (
    id uuid NOT NULL,
    context uuid NOT NULL,
    account text NOT NULL,
    project text NOT NULL,
    client text NOT NULL,
    request_id uuid NOT NULL,
    request_context uuid,
    body text NOT NULL,
    command text,
    source jsonb NOT NULL,
    message text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: operation_admission_parents; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.operation_admission_parents (
    child uuid NOT NULL,
    parent uuid NOT NULL,
    CONSTRAINT operation_admission_parents_check CHECK ((child <> parent))
);


--
-- Name: operation_admission_support; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.operation_admission_support (
    operation uuid NOT NULL,
    action text NOT NULL,
    grant_id uuid NOT NULL,
    CONSTRAINT operation_admission_support_action_check CHECK ((action = ANY (ARRAY['READ'::text, 'WRITE'::text, 'CONVERSATION_CREATE'::text, 'RUN_MODEL'::text, 'SET_AUDIENCE'::text, 'MANAGE_ACCESS'::text, 'MANAGE_SCOPE'::text, 'ADMINISTER'::text])))
);


--
-- Name: operation_admissions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.operation_admissions (
    id uuid NOT NULL,
    executor uuid NOT NULL,
    executor_handle text NOT NULL,
    scope uuid NOT NULL,
    project_name text NOT NULL,
    source_account uuid NOT NULL,
    job_id text,
    sealed boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    global_read boolean DEFAULT false NOT NULL
);


--
-- Name: COLUMN operation_admissions.global_read; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.operation_admissions.global_read IS 'Captured Global knowledge READ ceiling, rechecked live. Never permits Global execution, writes, or reading Global conversations.';


--
-- Name: operation_conversations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.operation_conversations (
    conversation text NOT NULL,
    agent text NOT NULL,
    operation uuid NOT NULL,
    parent_conversation text,
    budget_conversation text,
    capabilities text NOT NULL,
    role_ceiling text DEFAULT 'CONTRIBUTOR'::text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    allowance_id uuid,
    CONSTRAINT operation_conversations_agent_check CHECK (((length(agent) >= 1) AND (length(agent) <= 1024))),
    CONSTRAINT operation_conversations_budget_conversation_check CHECK (((length(budget_conversation) >= 1) AND (length(budget_conversation) <= 1024))),
    CONSTRAINT operation_conversations_capabilities_check CHECK ((capabilities ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT operation_conversations_check CHECK (((parent_conversation IS NULL) OR (parent_conversation <> conversation))),
    CONSTRAINT operation_conversations_conversation_check CHECK (((length(conversation) >= 1) AND (length(conversation) <= 1024))),
    CONSTRAINT operation_conversations_role_ceiling_check CHECK ((role_ceiling = 'CONTRIBUTOR'::text))
);


--
-- Name: operation_inference_budgets; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.operation_inference_budgets (
    operation_id uuid NOT NULL,
    budget_id uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    source_conversation text
);


--
-- Name: orchestration_acceptance; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.orchestration_acceptance (
    orchestration text NOT NULL,
    "position" integer NOT NULL,
    line text NOT NULL,
    argv jsonb NOT NULL,
    stdin text,
    exit_code integer NOT NULL,
    expect text,
    side text NOT NULL,
    cwd text NOT NULL,
    consent text NOT NULL,
    approval text,
    set_at timestamp with time zone NOT NULL,
    runs_for integer,
    CONSTRAINT orchestration_acceptance_approval_when_asked CHECK (((consent = 'approval'::text) = (approval IS NOT NULL))),
    CONSTRAINT orchestration_acceptance_consent_is_known CHECK ((consent = ANY (ARRAY['open'::text, 'approval'::text]))),
    CONSTRAINT orchestration_acceptance_runs_for_is_seconds CHECK (((runs_for IS NULL) OR ((runs_for >= 1) AND (runs_for <= 600))))
);


--
-- Name: orchestration_acceptance_requirements; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.orchestration_acceptance_requirements (
    orchestration text NOT NULL,
    requirements text NOT NULL,
    set_at timestamp with time zone NOT NULL
);


--
-- Name: orchestration_checks; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.orchestration_checks (
    orchestration text NOT NULL,
    argv jsonb NOT NULL,
    side text NOT NULL,
    cwd text NOT NULL,
    consent text NOT NULL,
    approval text,
    set_at timestamp with time zone NOT NULL,
    CONSTRAINT orchestration_checks_an_approval_is_named_iff_asked CHECK (((consent = 'approval'::text) = (approval IS NOT NULL))),
    CONSTRAINT orchestration_checks_argv_is_a_command CHECK (((jsonb_typeof(argv) = 'array'::text) AND (jsonb_array_length(argv) > 0))),
    CONSTRAINT orchestration_checks_consent_is_known CHECK ((consent = ANY (ARRAY['open'::text, 'approval'::text]))),
    CONSTRAINT orchestration_checks_side_is_known CHECK ((side = ANY (ARRAY['server'::text, 'local'::text])))
);


--
-- Name: orchestration_concerns; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.orchestration_concerns (
    orchestration text NOT NULL,
    id text NOT NULL,
    about text NOT NULL,
    why text NOT NULL,
    raised text NOT NULL,
    state text NOT NULL,
    rounds integer DEFAULT 0 NOT NULL,
    question text,
    reason text,
    objection text,
    verdict text,
    finding text,
    person_check text,
    person_answer text,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT orchestration_concerns_asked_has_its_question CHECK (((state <> 'asked'::text) OR (question IS NOT NULL))),
    CONSTRAINT orchestration_concerns_checked_has_its_verdict CHECK (((state <> 'checked'::text) OR (verdict = ANY (ARRAY['holds'::text, 'does_not_hold'::text])))),
    CONSTRAINT orchestration_concerns_id_is_numbered CHECK ((id ~ '^c[1-9][0-9]*$'::text)),
    CONSTRAINT orchestration_concerns_raised_is_known CHECK ((raised = ANY (ARRAY['plan'::text, 'end'::text]))),
    CONSTRAINT orchestration_concerns_rounds_are_bounded CHECK (((rounds >= 0) AND (rounds <= 2))),
    CONSTRAINT orchestration_concerns_state_is_known CHECK ((state = ANY (ARRAY['open'::text, 'asked'::text, 'answered'::text, 'resolved'::text, 'for_the_person'::text, 'checked'::text]))),
    CONSTRAINT orchestration_concerns_verdict_is_known CHECK (((verdict IS NULL) OR (verdict = ANY (ARRAY['holds'::text, 'does_not_hold'::text, 'cannot_check'::text]))))
);


--
-- Name: orchestration_messages; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.orchestration_messages (
    id text NOT NULL,
    orchestration text NOT NULL,
    kind text NOT NULL,
    text text NOT NULL,
    author text NOT NULL,
    created_at timestamp with time zone NOT NULL,
    delivered_at timestamp with time zone,
    cap_kind text,
    structure jsonb,
    CONSTRAINT orchestration_messages_author_named CHECK ((author <> ''::text)),
    CONSTRAINT orchestration_messages_cap_kind_is_known CHECK (((cap_kind IS NULL) OR ((kind = 'answer'::text) AND (cap_kind = ANY (ARRAY['turn_cap'::text, 'call_budget'::text, 'stuck'::text, 'uncovered'::text, 'time_cap'::text, 'check_failures'::text, 'install'::text, 'concerns'::text, 'product_check'::text]))))),
    CONSTRAINT orchestration_messages_kind_is_known CHECK ((kind = ANY (ARRAY['question'::text, 'answer'::text]))),
    CONSTRAINT orchestration_messages_structure_is_an_object CHECK (((structure IS NULL) OR (jsonb_typeof(structure) = 'object'::text))),
    CONSTRAINT orchestration_messages_text_named CHECK ((text <> ''::text))
);


--
-- Name: orchestration_record; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.orchestration_record (
    root text NOT NULL,
    ordinal integer NOT NULL,
    at timestamp with time zone NOT NULL,
    run text NOT NULL,
    actor text NOT NULL,
    kind text NOT NULL,
    text text NOT NULL,
    detail text,
    conversation text,
    body text,
    CONSTRAINT orchestration_record_body_is_bounded CHECK (((body IS NULL) OR ((length(body) >= 1) AND (length(body) <= 16000)))),
    CONSTRAINT orchestration_record_detail_is_one_short_line CHECK (((detail IS NULL) OR ((length(detail) <= 400) AND (POSITION(('
'::text) IN (detail)) = 0)))),
    CONSTRAINT orchestration_record_kind_is_known CHECK ((kind = ANY (ARRAY['run_started'::text, 'run_ended'::text, 'run_resumed'::text, 'stage_moved'::text, 'phase_started'::text, 'phase_ended'::text, 'check_ran'::text, 'approval_asked'::text, 'approval_answered'::text, 'question_asked'::text, 'question_answered'::text, 'stalled'::text, 'call_failure'::text, 'delegated'::text, 'delegate_returned'::text, 'acceptance_ran'::text, 'cap_continued'::text, 'tool_call'::text, 'concern'::text]))),
    CONSTRAINT orchestration_record_numbered_from_one CHECK ((ordinal >= 1)),
    CONSTRAINT orchestration_record_text_is_one_short_line CHECK ((((length(text) >= 1) AND (length(text) <= 400)) AND (POSITION(('
'::text) IN (text)) = 0)))
);


--
-- Name: orchestration_resume_receipts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.orchestration_resume_receipts (
    account text NOT NULL,
    request_id uuid NOT NULL,
    run_id text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: orchestration_script_steps; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.orchestration_script_steps (
    conversation_id text NOT NULL,
    sequence integer NOT NULL,
    source_hash text NOT NULL,
    state jsonb NOT NULL,
    command jsonb NOT NULL,
    started_at timestamp with time zone,
    effective_arguments text,
    raw_result text,
    result text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    executed_at timestamp with time zone,
    completed_at timestamp with time zone,
    CONSTRAINT orchestration_script_steps_check CHECK (((result IS NULL) OR (raw_result IS NOT NULL))),
    CONSTRAINT orchestration_script_steps_sequence_check CHECK ((sequence >= 0))
);


--
-- Name: orchestration_start_receipts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.orchestration_start_receipts (
    account text NOT NULL,
    request_id uuid NOT NULL,
    payload jsonb NOT NULL,
    run_id text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--
-- Name: orchestrations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.orchestrations (
    id text NOT NULL,
    definition_name text NOT NULL,
    tier text NOT NULL,
    definition_hash text NOT NULL,
    stages jsonb NOT NULL,
    max_returns integer NOT NULL,
    returns_used integer DEFAULT 0 NOT NULL,
    project text,
    conductor_conversation text NOT NULL,
    caller_conversation text,
    caller_agent text NOT NULL,
    caller_handle text,
    state text NOT NULL,
    result text,
    failure text,
    restarts integer DEFAULT 0 NOT NULL,
    nudges integer DEFAULT 0 NOT NULL,
    result_delivered_at timestamp with time zone,
    created_at timestamp with time zone NOT NULL,
    ended_at timestamp with time zone,
    definition_source text NOT NULL,
    definition_origin text NOT NULL,
    caller_session text,
    pending_cap text,
    parent text,
    depth integer NOT NULL,
    waiting_for text,
    stalled_since timestamp with time zone,
    ended_in_prose boolean DEFAULT false NOT NULL,
    artifacts_dir text,
    phase_todo text,
    cap_continues integer DEFAULT 0 NOT NULL,
    verifier_refusals integer DEFAULT 0 NOT NULL,
    verifier_asked text,
    verifier_waived text,
    asking_since timestamp with time zone,
    asked_seconds bigint DEFAULT 0 NOT NULL,
    time_span_from bigint DEFAULT 0 NOT NULL,
    check_failures integer DEFAULT 0 NOT NULL,
    checker text,
    product_asked text,
    product_accepted text,
    relay_causation jsonb,
    CONSTRAINT orchestrations_a_cap_is_pending_only_while_asking CHECK (((pending_cap IS NULL) OR (state = 'asking'::text))),
    CONSTRAINT orchestrations_asked_seconds_not_negative CHECK ((asked_seconds >= 0)),
    CONSTRAINT orchestrations_cap_continues_counts CHECK ((cap_continues >= 0)),
    CONSTRAINT orchestrations_check_failures_not_negative CHECK ((check_failures >= 0)),
    CONSTRAINT orchestrations_depth_natural CHECK ((depth >= 0)),
    CONSTRAINT orchestrations_ended_iff_terminal CHECK (((state = ANY (ARRAY['finished'::text, 'failed'::text, 'capped'::text, 'cancelled'::text])) = (ended_at IS NOT NULL))),
    CONSTRAINT orchestrations_failure_only_when_it_stopped CHECK (((failure IS NULL) OR (state = ANY (ARRAY['failed'::text, 'capped'::text, 'cancelled'::text])))),
    CONSTRAINT orchestrations_max_returns_natural CHECK ((max_returns >= 0)),
    CONSTRAINT orchestrations_only_a_running_run_is_stalled CHECK (((stalled_since IS NULL) OR (state = 'running'::text))),
    CONSTRAINT orchestrations_parent_iff_nested CHECK (((parent IS NULL) = (depth = 0))),
    CONSTRAINT orchestrations_parent_is_not_itself CHECK (((parent IS NULL) OR (parent <> id))),
    CONSTRAINT orchestrations_pending_cap_is_known CHECK (((pending_cap IS NULL) OR (pending_cap = ANY (ARRAY['turn_cap'::text, 'call_budget'::text, 'stuck'::text, 'uncovered'::text, 'time_cap'::text, 'check_failures'::text, 'install'::text, 'concerns'::text, 'product_check'::text])))),
    CONSTRAINT orchestrations_product_accepted_is_a_digest CHECK (((product_accepted IS NULL) OR (product_accepted ~ '^[0-9a-f]{64}$'::text))),
    CONSTRAINT orchestrations_product_asked_is_a_digest CHECK (((product_asked IS NULL) OR (product_asked ~ '^[0-9a-f]{64}$'::text))),
    CONSTRAINT orchestrations_relay_causation_check CHECK (public.relay_valid_causation(relay_causation)),
    CONSTRAINT orchestrations_result_iff_finished CHECK (((state = 'finished'::text) = (result IS NOT NULL))),
    CONSTRAINT orchestrations_returns_within_limit CHECK (((returns_used >= 0) AND (returns_used <= max_returns))),
    CONSTRAINT orchestrations_state_is_known CHECK ((state = ANY (ARRAY['running'::text, 'asking'::text, 'waiting'::text, 'finished'::text, 'failed'::text, 'capped'::text, 'cancelled'::text]))),
    CONSTRAINT orchestrations_tier_is_known CHECK ((tier = ANY (ARRAY['PROJECT'::text, 'SESSION'::text, 'PERSONAL'::text, 'GLOBAL'::text, 'SHIPPED'::text]))),
    CONSTRAINT orchestrations_time_span_from_not_negative CHECK ((time_span_from >= 0)),
    CONSTRAINT orchestrations_verifier_asked_is_a_digest CHECK (((verifier_asked IS NULL) OR (verifier_asked ~ '^[0-9a-f]{64}$'::text))),
    CONSTRAINT orchestrations_verifier_refusals_not_negative CHECK ((verifier_refusals >= 0)),
    CONSTRAINT orchestrations_verifier_waived_is_a_digest CHECK (((verifier_waived IS NULL) OR (verifier_waived ~ '^[0-9a-f]{64}$'::text))),
    CONSTRAINT orchestrations_waiting_for_iff_waiting CHECK (((state = 'waiting'::text) = (waiting_for IS NOT NULL)))
);


--
-- Name: outgoing_peers; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.outgoing_peers (
    account text NOT NULL,
    project_key bigint NOT NULL,
    peer text NOT NULL,
    advertised_at timestamp with time zone DEFAULT now() NOT NULL,
    agent_card jsonb
);


--
-- Name: outgoing_work; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.outgoing_work (
    id uuid NOT NULL,
    account text NOT NULL,
    request_id uuid NOT NULL,
    peer text NOT NULL,
    project_id bigint,
    conversation text,
    message jsonb NOT NULL,
    state text NOT NULL,
    cancel_requested boolean DEFAULT false NOT NULL,
    worker text,
    lease_until timestamp with time zone,
    remote_task text,
    remote_context text,
    result jsonb,
    error text,
    revision bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT outgoing_work_state_check CHECK ((state = ANY (ARRAY['QUEUED'::text, 'DISPATCHED'::text, 'WORKING'::text, 'INPUT_REQUIRED'::text, 'AUTH_REQUIRED'::text, 'COMPLETED'::text, 'FAILED'::text, 'CANCELED'::text, 'REJECTED'::text, 'UNKNOWN'::text])))
);


--
-- Name: paragraphs; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.paragraphs (
    id uuid NOT NULL,
    document_id uuid NOT NULL,
    text text NOT NULL,
    content_hash text NOT NULL,
    occurrence integer NOT NULL,
    ordinal integer NOT NULL,
    summary text,
    section_id uuid,
    CONSTRAINT paragraphs_counted_from_one CHECK (((ordinal >= 1) AND (occurrence >= 1))),
    CONSTRAINT paragraphs_not_blank CHECK ((text <> ''::text)),
    CONSTRAINT paragraphs_summary_is_not_blank CHECK (((summary IS NULL) OR (summary <> ''::text)))
);


--
-- Name: COLUMN paragraphs.summary; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.paragraphs.summary IS 'One claim-bearing sentence about this paragraph, written by the paragraph_summariser agent, or NULL for a paragraph nothing has summarised yet. The only level of the cascade that reads raw text; every level above reads summaries, which is what gives the levels independent compression.';


--
-- Name: project_access_audit; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.project_access_audit (
    id bigint NOT NULL,
    occurred_at timestamp with time zone DEFAULT now() NOT NULL,
    project_id bigint NOT NULL,
    actor text NOT NULL,
    target text NOT NULL,
    action text NOT NULL,
    role text
);


--
-- Name: project_access_audit_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.project_access_audit_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: project_access_audit_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.project_access_audit_id_seq OWNED BY public.project_access_audit.id;


--
-- Name: project_members; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.project_members (
    project_id bigint NOT NULL,
    handle text NOT NULL,
    added_at timestamp with time zone DEFAULT now() NOT NULL,
    role text DEFAULT 'CONTRIBUTOR'::text NOT NULL,
    CONSTRAINT project_members_role_check CHECK ((role = ANY (ARRAY['VIEWER'::text, 'CONTRIBUTOR'::text, 'MANAGER'::text])))
);


--
-- Name: projects_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.projects ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.projects_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: proposals; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.proposals (
    id text NOT NULL,
    memory_id text NOT NULL,
    action text NOT NULL,
    reason text NOT NULL,
    state text NOT NULL,
    created_at timestamp with time zone NOT NULL,
    resolved_at timestamp with time zone,
    resolved_by text,
    resolution text,
    proposed_by text,
    CONSTRAINT proposals_action_named CHECK ((action <> ''::text)),
    CONSTRAINT proposals_proposer_named CHECK (((proposed_by IS NULL) OR (proposed_by <> ''::text))),
    CONSTRAINT proposals_reason_given CHECK ((reason <> ''::text)),
    CONSTRAINT proposals_resolver_named CHECK (((resolved_by IS NULL) OR (resolved_by <> ''::text))),
    CONSTRAINT proposals_settlement_matches_state CHECK ((((state = 'pending'::text) AND (resolved_at IS NULL) AND (resolved_by IS NULL) AND (resolution IS NULL)) OR ((state <> 'pending'::text) AND (resolved_at IS NOT NULL) AND (resolved_by IS NOT NULL)))),
    CONSTRAINT proposals_state_known CHECK ((state = ANY (ARRAY['pending'::text, 'accepted'::text, 'rejected'::text])))
);


--
-- Name: COLUMN proposals.proposed_by; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.proposals.proposed_by IS 'Who raised this question. NULL only on rows filed before this column existed, where nobody recorded it.';


--
-- Name: provider_connections; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.provider_connections (
    id uuid NOT NULL,
    project_id bigint,
    revision uuid NOT NULL,
    enabled boolean NOT NULL,
    deleted boolean DEFAULT false NOT NULL,
    definition jsonb NOT NULL,
    CONSTRAINT provider_connections_check CHECK (((NOT deleted) OR (NOT enabled))),
    CONSTRAINT provider_connections_definition_check CHECK ((jsonb_typeof(definition) = 'object'::text))
);


--
-- Name: provider_credential_receipts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.provider_credential_receipts (
    request_id uuid NOT NULL,
    actor text NOT NULL,
    project_name text NOT NULL,
    receipt jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT provider_credential_receipts_receipt_check CHECK ((jsonb_typeof(receipt) = 'object'::text))
);


--
-- Name: provider_credentials; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.provider_credentials (
    connection_id uuid NOT NULL,
    subject text NOT NULL,
    generation uuid NOT NULL,
    connection_revision uuid NOT NULL,
    state text NOT NULL,
    expires_at timestamp with time zone,
    key_id text,
    nonce text,
    ciphertext text,
    attempt uuid,
    state_hash text,
    initiator text,
    intent_expires_at timestamp with time zone,
    CONSTRAINT provider_credentials_check CHECK (((state = ANY (ARRAY['PENDING'::text, 'REFRESHING'::text])) = (attempt IS NOT NULL))),
    CONSTRAINT provider_credentials_check1 CHECK (((state = ANY (ARRAY['PENDING'::text, 'REFRESHING'::text])) = (intent_expires_at IS NOT NULL))),
    CONSTRAINT provider_credentials_check2 CHECK (((state = ANY (ARRAY['PENDING'::text, 'REFRESHING'::text])) = (initiator IS NOT NULL))),
    CONSTRAINT provider_credentials_check3 CHECK (((state = 'READY'::text) OR (expires_at IS NULL))),
    CONSTRAINT provider_credentials_check4 CHECK (((state = 'PENDING'::text) = (state_hash IS NOT NULL))),
    CONSTRAINT provider_credentials_check5 CHECK (((state = ANY (ARRAY['UNCERTAIN'::text, 'REVOKED'::text])) = (ciphertext IS NULL))),
    CONSTRAINT provider_credentials_check6 CHECK (((ciphertext IS NULL) = (key_id IS NULL))),
    CONSTRAINT provider_credentials_check7 CHECK (((ciphertext IS NULL) = (nonce IS NULL))),
    CONSTRAINT provider_credentials_ciphertext_check CHECK (((ciphertext IS NULL) OR ((length(ciphertext) >= 24) AND (length(ciphertext) <= 131072)))),
    CONSTRAINT provider_credentials_key_id_check CHECK (((length(key_id) >= 1) AND (length(key_id) <= 64))),
    CONSTRAINT provider_credentials_nonce_check CHECK (((nonce IS NULL) OR (length(nonce) = 16))),
    CONSTRAINT provider_credentials_state_check CHECK ((state = ANY (ARRAY['PENDING'::text, 'READY'::text, 'REFRESHING'::text, 'UNCERTAIN'::text, 'REVOKED'::text]))),
    CONSTRAINT provider_credentials_state_hash_check CHECK (((state_hash IS NULL) OR (state_hash ~ '^[0-9a-f]{64}$'::text)))
);


--
-- Name: relay_admissions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.relay_admissions (
    scope_key text NOT NULL,
    topic text NOT NULL,
    subscriber text NOT NULL,
    publication_position bigint NOT NULL,
    event_id text NOT NULL,
    publisher text NOT NULL,
    occurred_at timestamp with time zone NOT NULL,
    published_at timestamp with time zone NOT NULL,
    correlation_id text,
    causation_id text,
    payload_kind text NOT NULL,
    schema_version integer NOT NULL,
    routing_path text NOT NULL,
    routing_source text NOT NULL,
    routing_hash text NOT NULL,
    branch_count integer NOT NULL,
    admitted_at timestamp with time zone NOT NULL,
    relay_causation jsonb,
    payload_digest text NOT NULL,
    CONSTRAINT relay_admissions_branch_count_check CHECK (((branch_count >= 0) AND (branch_count <= 32))),
    CONSTRAINT relay_admissions_causation_id_check CHECK (((length(causation_id) >= 1) AND (length(causation_id) <= 256))),
    CONSTRAINT relay_admissions_correlation_id_check CHECK (((length(correlation_id) >= 1) AND (length(correlation_id) <= 256))),
    CONSTRAINT relay_admissions_event_id_check CHECK (((length(event_id) >= 1) AND (length(event_id) <= 256))),
    CONSTRAINT relay_admissions_payload_kind_check CHECK ((payload_kind = ANY (ARRAY['EMPTY'::text, 'TEXT'::text, 'SCHEDULE_DUE'::text, 'LIFECYCLE'::text, 'WAKE_REQUESTED'::text]))),
    CONSTRAINT relay_admissions_publication_position_check CHECK ((publication_position > 0)),
    CONSTRAINT relay_admissions_publisher_check CHECK (((length(publisher) >= 1) AND (length(publisher) <= 256))),
    CONSTRAINT relay_admissions_relay_causation_check CHECK (public.relay_valid_causation(relay_causation)),
    CONSTRAINT relay_admissions_routing_hash_check CHECK ((routing_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT relay_admissions_routing_path_check CHECK (((length(routing_path) >= 1) AND (length(routing_path) <= 256))),
    CONSTRAINT relay_admissions_routing_source_check CHECK ((octet_length(routing_source) <= 131072)),
    CONSTRAINT relay_admissions_schema_version_check CHECK ((schema_version = 1))
);


--
-- Name: relay_approval_gap_recoveries; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.relay_approval_gap_recoveries (
    scope_key text NOT NULL,
    topic text NOT NULL,
    subscriber text NOT NULL,
    expired_through bigint NOT NULL,
    pending_approvals bigint NOT NULL,
    recovered_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    CONSTRAINT relay_approval_gap_recoveries_expired_through_check CHECK ((expired_through > 0)),
    CONSTRAINT relay_approval_gap_recoveries_pending_approvals_check CHECK ((pending_approvals >= 0))
);


--
-- Name: relay_blob_publications; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.relay_blob_publications (
    scope_key text NOT NULL,
    topic text NOT NULL,
    "position" bigint NOT NULL,
    blob_scope text NOT NULL,
    sha256 text NOT NULL,
    claim_owner text NOT NULL,
    CONSTRAINT relay_blob_publications_check CHECK ((blob_scope = ('projects/'::text || SUBSTRING(scope_key FROM 9)))),
    CONSTRAINT relay_blob_publications_claim_owner_check CHECK ((claim_owner ~ '^[0-9a-f]{64}:[1-9][0-9]{0,18}$'::text)),
    CONSTRAINT relay_blob_publications_scope_key_check CHECK ((scope_key ~ '^project:[1-9][0-9]{0,18}$'::text))
);


--
-- Name: relay_consumer_leases; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.relay_consumer_leases (
    scope_key text NOT NULL,
    topic text NOT NULL,
    subscriber text NOT NULL,
    worker text NOT NULL,
    account text NOT NULL,
    epoch bigint NOT NULL,
    lease_until timestamp with time zone NOT NULL,
    CONSTRAINT relay_consumer_leases_account_check CHECK (((length(account) >= 1) AND (length(account) <= 256))),
    CONSTRAINT relay_consumer_leases_epoch_check CHECK ((epoch > 0)),
    CONSTRAINT relay_consumer_leases_worker_check CHECK (((length(worker) >= 1) AND (length(worker) <= 256)))
);


--
-- Name: relay_deliveries; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.relay_deliveries (
    id uuid NOT NULL,
    scope_key text NOT NULL,
    topic text NOT NULL,
    subscriber text NOT NULL,
    publication_position bigint NOT NULL,
    branch_index integer NOT NULL,
    branch_name text NOT NULL,
    receiver text NOT NULL,
    handler_path text,
    handler_source text,
    handler_hash text,
    state text DEFAULT 'READY'::text NOT NULL,
    fence bigint DEFAULT 0 NOT NULL,
    worker text,
    lease_until timestamp with time zone,
    updated_at timestamp with time zone NOT NULL,
    receipt_namespace text,
    receipt_id text,
    failure_code text,
    publish_to text,
    work_agent text,
    work_project text,
    work_definition text,
    CONSTRAINT relay_deliveries_branch_index_check CHECK (((branch_index >= 0) AND (branch_index <= 31))),
    CONSTRAINT relay_deliveries_branch_name_check CHECK (((length(branch_name) <= 160) AND (branch_name ~ '^[a-z][a-z0-9]*([._-][a-z0-9]+)*$'::text))),
    CONSTRAINT relay_deliveries_check CHECK ((((handler_path IS NULL) AND (handler_source IS NULL) AND (handler_hash IS NULL)) OR ((handler_path IS NOT NULL) AND (handler_source IS NOT NULL) AND (handler_hash IS NOT NULL)))),
    CONSTRAINT relay_deliveries_check1 CHECK ((((state = ANY (ARRAY['CLAIMED'::text, 'DISPATCHING'::text])) AND (worker IS NOT NULL) AND (fence > 0) AND (lease_until IS NOT NULL)) OR ((state <> ALL (ARRAY['CLAIMED'::text, 'DISPATCHING'::text])) AND (lease_until IS NULL)))),
    CONSTRAINT relay_deliveries_check2 CHECK ((((state = 'ACCEPTED'::text) AND (receipt_namespace IS NOT NULL) AND (receipt_id IS NOT NULL)) OR ((state <> 'ACCEPTED'::text) AND (receipt_namespace IS NULL) AND (receipt_id IS NULL)))),
    CONSTRAINT relay_deliveries_fence_check CHECK ((fence >= 0)),
    CONSTRAINT relay_deliveries_handler_hash_check CHECK ((handler_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT relay_deliveries_handler_path_check CHECK (((length(handler_path) >= 1) AND (length(handler_path) <= 256))),
    CONSTRAINT relay_deliveries_handler_source_check CHECK ((octet_length(handler_source) <= 131072)),
    CONSTRAINT relay_deliveries_receipt_id_check CHECK (((receipt_id IS NULL) OR ((length(receipt_id) >= 1) AND (length(receipt_id) <= 256)))),
    CONSTRAINT relay_deliveries_receipt_namespace_check CHECK (((receipt_namespace IS NULL) OR ((length(receipt_namespace) <= 160) AND (receipt_namespace ~ '^[a-z][a-z0-9]*([._-][a-z0-9]+)*$'::text)))),
    CONSTRAINT relay_deliveries_receiver_check CHECK (((length(receiver) <= 160) AND (receiver ~ '^[a-z][a-z0-9]*([._-][a-z0-9]+)*$'::text))),
    CONSTRAINT relay_deliveries_work_agent_check CHECK (((length(work_agent) >= 1) AND (length(work_agent) <= 256))),
    CONSTRAINT relay_deliveries_work_definition_check CHECK (((length(work_definition) >= 1) AND (length(work_definition) <= 256))),
    CONSTRAINT relay_deliveries_work_project_check CHECK (((length(work_project) >= 1) AND (length(work_project) <= 256))),
    CONSTRAINT relay_deliveries_worker_check CHECK (((length(worker) >= 1) AND (length(worker) <= 256))),
    CONSTRAINT relay_delivery_failure_bounded CHECK (((failure_code IS NULL) OR ((length(failure_code) <= 160) AND (failure_code ~ '^[a-z][a-z0-9]*([._-][a-z0-9]+)*$'::text)))),
    CONSTRAINT relay_delivery_failure_known CHECK (((state = ANY (ARRAY['FAILED'::text, 'UNCERTAIN'::text, 'ABANDONED'::text, 'ABANDONED_UNCERTAIN'::text])) = (failure_code IS NOT NULL))),
    CONSTRAINT relay_delivery_state_known CHECK ((state = ANY (ARRAY['READY'::text, 'CLAIMED'::text, 'DISPATCHING'::text, 'ACCEPTED'::text, 'FAILED'::text, 'UNCERTAIN'::text, 'ABANDONED'::text, 'ABANDONED_UNCERTAIN'::text]))),
    CONSTRAINT relay_publish_target_name CHECK (((publish_to IS NULL) OR ((length(publish_to) <= 1024) AND (publish_to ~ '^[a-z][a-z0-9]*([._-][a-z0-9]+)*$'::text)))),
    CONSTRAINT relay_publish_target_receiver CHECK (((receiver = 'relay.publish'::text) = (publish_to IS NOT NULL))),
    CONSTRAINT relay_work_destination CHECK ((((receiver = ANY (ARRAY['agent.run'::text, 'script.run'::text, 'orchestration.start'::text])) AND (work_agent IS NOT NULL) AND ((receiver = 'orchestration.start'::text) = (work_definition IS NOT NULL)) AND ((receiver = 'script.run'::text) = (handler_path IS NOT NULL))) OR ((receiver <> ALL (ARRAY['agent.run'::text, 'script.run'::text, 'orchestration.start'::text])) AND (work_agent IS NULL) AND (work_project IS NULL) AND (work_definition IS NULL))))
);


--
-- Name: relay_executions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.relay_executions (
    request_id uuid NOT NULL,
    account text NOT NULL,
    project_id bigint NOT NULL,
    handler_source text,
    handler_hash text,
    receipt_namespace text,
    receipt_id text,
    conversation_id text,
    started_at timestamp with time zone DEFAULT now() NOT NULL,
    accepted_at timestamp with time zone,
    CONSTRAINT relay_executions_account_check CHECK (((length(account) >= 1) AND (length(account) <= 256))),
    CONSTRAINT relay_executions_check CHECK (((handler_source IS NULL) = (handler_hash IS NULL))),
    CONSTRAINT relay_executions_check1 CHECK ((((receipt_namespace IS NULL) AND (receipt_id IS NULL) AND (accepted_at IS NULL)) OR ((receipt_namespace IS NOT NULL) AND (receipt_id IS NOT NULL) AND (accepted_at IS NOT NULL)))),
    CONSTRAINT relay_executions_handler_hash_check CHECK ((handler_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT relay_executions_handler_source_check CHECK ((octet_length(handler_source) <= 131072))
);


--
-- Name: relay_native_consumers; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.relay_native_consumers (
    scope_key text NOT NULL,
    topic text NOT NULL,
    subscriber text NOT NULL,
    target text NOT NULL,
    account text NOT NULL,
    project_id bigint NOT NULL,
    CONSTRAINT relay_native_consumers_scope_key_check CHECK ((scope_key = 'system'::text)),
    CONSTRAINT relay_native_consumers_target_check CHECK (((target ~~ 'conversation:%'::text) AND ((length(target) >= 14) AND (length(target) <= 1024)))),
    CONSTRAINT relay_native_consumers_topic_check CHECK ((topic = ANY (ARRAY['message.wake.requested'::text, 'board.wake.requested'::text])))
);


--
-- Name: relay_native_gap_recoveries; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.relay_native_gap_recoveries (
    scope_key text NOT NULL,
    topic text NOT NULL,
    subscriber text NOT NULL,
    expired_through bigint NOT NULL,
    pending_inbox bigint NOT NULL,
    recovered_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    CONSTRAINT relay_native_gap_recoveries_expired_through_check CHECK ((expired_through > 0)),
    CONSTRAINT relay_native_gap_recoveries_pending_inbox_check CHECK ((pending_inbox >= 0))
);


--
-- Name: relay_operator_receipts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.relay_operator_receipts (
    request_id uuid NOT NULL,
    project_id bigint NOT NULL,
    account text NOT NULL,
    fingerprint text NOT NULL,
    topic text NOT NULL,
    action text NOT NULL,
    subscriber text,
    delivery_id uuid,
    reason text NOT NULL,
    status text,
    seen_through bigint,
    completed_at timestamp with time zone,
    CONSTRAINT relay_operator_receipts_account_check CHECK (((length(account) >= 1) AND (length(account) <= 256))),
    CONSTRAINT relay_operator_receipts_action_check CHECK ((action = ANY (ARRAY['ACKNOWLEDGE_GAP'::text, 'RECONCILE'::text, 'ABANDON'::text, 'REMOVE_SUBSCRIPTION'::text, 'REMOVE_TOPIC'::text]))),
    CONSTRAINT relay_operator_receipts_check CHECK (((status IS NULL) = (completed_at IS NULL))),
    CONSTRAINT relay_operator_receipts_fingerprint_check CHECK ((fingerprint ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT relay_operator_receipts_reason_check CHECK (((length(reason) >= 1) AND (length(reason) <= 256))),
    CONSTRAINT relay_operator_receipts_seen_through_check CHECK ((seen_through >= 0))
);


--
-- Name: relay_payloads; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.relay_payloads (
    digest text NOT NULL,
    payload jsonb NOT NULL,
    byte_length bigint NOT NULL,
    ejection_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT relay_payloads_byte_length_check CHECK ((byte_length > 0)),
    CONSTRAINT relay_payloads_digest_check CHECK ((digest ~ '^[0-9a-f]{64}$'::text))
);


--
-- Name: TABLE relay_payloads; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.relay_payloads IS 'ejection_at defers the next examination. It never authorizes a deletion. Reachability is the only authority. A date that is too early costs one wasted check; a date that is too late leaves bytes for one more window. Neither can remove something referenced.';


--
-- Name: relay_publications; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.relay_publications (
    scope_key text NOT NULL,
    topic text NOT NULL,
    "position" bigint NOT NULL,
    event_id text NOT NULL,
    publisher text NOT NULL,
    occurred_at timestamp with time zone NOT NULL,
    published_at timestamp with time zone NOT NULL,
    correlation_id text,
    causation_id text,
    schema_version integer NOT NULL,
    relay_causation jsonb,
    payload_digest text NOT NULL,
    CONSTRAINT relay_publications_causation_id_check CHECK (((length(causation_id) >= 1) AND (length(causation_id) <= 256))),
    CONSTRAINT relay_publications_correlation_id_check CHECK (((length(correlation_id) >= 1) AND (length(correlation_id) <= 256))),
    CONSTRAINT relay_publications_event_id_check CHECK (((length(event_id) >= 1) AND (length(event_id) <= 256))),
    CONSTRAINT relay_publications_position_check CHECK (("position" > 0)),
    CONSTRAINT relay_publications_publisher_check CHECK (((length(publisher) >= 1) AND (length(publisher) <= 256))),
    CONSTRAINT relay_publications_relay_causation_check CHECK (public.relay_valid_causation(relay_causation)),
    CONSTRAINT relay_publications_schema_version_check CHECK ((schema_version = 1))
);


--
-- Name: relay_sdk_batches; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.relay_sdk_batches (
    scope_key text NOT NULL,
    topic text NOT NULL,
    subscriber text NOT NULL,
    account text NOT NULL,
    consumer_id uuid NOT NULL,
    batch_id uuid NOT NULL,
    fence bigint NOT NULL,
    through_position bigint NOT NULL,
    gap_through bigint,
    lease_until timestamp with time zone NOT NULL,
    acknowledged boolean DEFAULT false NOT NULL,
    CONSTRAINT relay_sdk_batches_account_check CHECK (((length(account) >= 1) AND (length(account) <= 256))),
    CONSTRAINT relay_sdk_batches_fence_check CHECK ((fence > 0)),
    CONSTRAINT relay_sdk_batches_gap_through_check CHECK ((gap_through >= 0)),
    CONSTRAINT relay_sdk_batches_through_position_check CHECK ((through_position >= 0))
);


--
-- Name: relay_source_events; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.relay_source_events (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    sequence bigint NOT NULL,
    project_id bigint,
    topic text NOT NULL,
    family text NOT NULL,
    subject text NOT NULL,
    state text NOT NULL,
    context text,
    related text,
    occurred_at timestamp with time zone NOT NULL,
    relay_causation jsonb,
    CONSTRAINT relay_source_events_check CHECK (((family <> 'WAKE_REQUESTED'::text) OR ((project_id IS NULL) AND (topic = ANY (ARRAY['message.wake.requested'::text, 'board.wake.requested'::text])) AND (state = ANY (ARRAY['MESSAGE'::text, 'BOARD'::text])) AND (context ~~ 'conversation:%'::text) AND (related IS NULL)))),
    CONSTRAINT relay_source_events_context_check CHECK (((context IS NULL) OR ((length(context) >= 1) AND (length(context) <= 1024)))),
    CONSTRAINT relay_source_events_family_check CHECK ((family = ANY (ARRAY['LIFECYCLE'::text, 'WAKE_REQUESTED'::text]))),
    CONSTRAINT relay_source_events_related_check CHECK (((related IS NULL) OR ((length(related) >= 1) AND (length(related) <= 1024)))),
    CONSTRAINT relay_source_events_relay_causation_check CHECK (public.relay_valid_causation(relay_causation)),
    CONSTRAINT relay_source_events_state_check CHECK (((length(state) >= 1) AND (length(state) <= 256))),
    CONSTRAINT relay_source_events_subject_check CHECK (((length(subject) >= 1) AND (length(subject) <= 1024))),
    CONSTRAINT relay_source_events_topic_check CHECK (((length(topic) <= 1024) AND (topic ~ '^[a-z][a-z0-9]*([._-][a-z0-9]+)*$'::text)))
);


--
-- Name: relay_source_events_sequence_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.relay_source_events ALTER COLUMN sequence ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.relay_source_events_sequence_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: relay_subscription_authority; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.relay_subscription_authority (
    scope_key text NOT NULL,
    topic text NOT NULL,
    subscriber text NOT NULL,
    account text NOT NULL,
    topic_generation uuid NOT NULL,
    established_at timestamp with time zone NOT NULL,
    CONSTRAINT relay_subscription_authority_account_check CHECK (((length(account) >= 1) AND (length(account) <= 256)))
);


--
-- Name: relay_subscriptions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.relay_subscriptions (
    scope_key text NOT NULL,
    topic text NOT NULL,
    subscriber text NOT NULL,
    seen_through bigint NOT NULL,
    seen_at timestamp with time zone NOT NULL,
    generation uuid DEFAULT gen_random_uuid() NOT NULL,
    reader_account text,
    CONSTRAINT relay_subscription_group_namespace CHECK (
CASE
    WHEN (reader_account IS NULL) THEN ((length(subscriber) <= 160) AND (subscriber ~ '^[a-z][a-z0-9]*([._-][a-z0-9]+)*$'::text))
    ELSE (((length(reader_account) >= 1) AND (length(reader_account) <= 256)) AND (POSITION((':'::text) IN (reader_account)) = 0) AND (length(subscriber) <= 256) AND starts_with(subscriber, (('reader:'::text || reader_account) || ':'::text)) AND (SUBSTRING(subscriber FROM (char_length((('reader:'::text || reader_account) || ':'::text)) + 1)) ~ '^[a-z][a-z0-9]*([._-][a-z0-9]+)*$'::text))
END),
    CONSTRAINT relay_subscriptions_seen_through_check CHECK ((seen_through >= 0))
);


--
-- Name: COLUMN relay_subscriptions.reader_account; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.relay_subscriptions.reader_account IS 'Read-tier owner, matching the account inside the group name; NULL for work-tier groups (0038).';


--
-- Name: relay_tool_invocations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.relay_tool_invocations (
    id uuid NOT NULL,
    project_id bigint NOT NULL,
    account text NOT NULL,
    run_id text NOT NULL,
    call_id text NOT NULL,
    fingerprint text NOT NULL,
    binding text NOT NULL,
    request text NOT NULL,
    occurred_at timestamp with time zone NOT NULL,
    causation jsonb NOT NULL,
    result text,
    completed_at timestamp with time zone,
    agent text,
    hosted jsonb,
    CONSTRAINT relay_tool_invocations_account_check CHECK (((length(account) >= 1) AND (length(account) <= 256))),
    CONSTRAINT relay_tool_invocations_binding_check CHECK (((length(binding) >= 1) AND (octet_length(convert_to(binding, 'UTF8'::name)) <= 314573824))),
    CONSTRAINT relay_tool_invocations_call_id_check CHECK (((length(call_id) >= 1) AND (length(call_id) <= 256))),
    CONSTRAINT relay_tool_invocations_causation_check CHECK (public.relay_valid_causation(causation)),
    CONSTRAINT relay_tool_invocations_check CHECK (((result IS NULL) = (completed_at IS NULL))),
    CONSTRAINT relay_tool_invocations_fingerprint_check CHECK ((fingerprint ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT relay_tool_invocations_hosted_check CHECK (((hosted IS NULL) OR (jsonb_typeof(hosted) = 'object'::text))),
    CONSTRAINT relay_tool_invocations_request_check CHECK (((length(request) >= 1) AND (length(request) <= 65536))),
    CONSTRAINT relay_tool_invocations_result_check CHECK (((length(result) >= 1) AND (length(result) <= 32768))),
    CONSTRAINT relay_tool_invocations_run_id_check CHECK (((length(run_id) >= 1) AND (length(run_id) <= 256)))
);


--
-- Name: relay_topics; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.relay_topics (
    scope_key text NOT NULL,
    name text NOT NULL,
    payload_kind text NOT NULL,
    retention_seconds bigint NOT NULL,
    max_records bigint,
    last_position bigint DEFAULT 0 NOT NULL,
    expired_through bigint DEFAULT 0 NOT NULL,
    project_id bigint,
    generation uuid DEFAULT gen_random_uuid() NOT NULL,
    CONSTRAINT relay_blob_project_scope CHECK (((payload_kind <> 'BLOB'::text) OR (scope_key <> 'system'::text))),
    CONSTRAINT relay_native_wake_family CHECK (((payload_kind = 'WAKE_REQUESTED'::text) = ((scope_key = 'system'::text) AND (name = ANY (ARRAY['message.wake.requested'::text, 'board.wake.requested'::text]))))),
    CONSTRAINT relay_topic_payload_family CHECK ((payload_kind = ANY (ARRAY['EMPTY'::text, 'TEXT'::text, 'BLOB'::text, 'SCHEDULE_DUE'::text, 'LIFECYCLE'::text, 'WAKE_REQUESTED'::text]))),
    CONSTRAINT relay_topic_scope_is_known CHECK ((((project_id IS NULL) AND (scope_key = 'system'::text)) OR ((project_id IS NOT NULL) AND (project_id > 0) AND (scope_key = ('project:'::text || (project_id)::text))))),
    CONSTRAINT relay_topics_check CHECK (((expired_through >= 0) AND (expired_through <= last_position))),
    CONSTRAINT relay_topics_last_position_check CHECK ((last_position >= 0)),
    CONSTRAINT relay_topics_max_records_check CHECK ((max_records > 0)),
    CONSTRAINT relay_topics_name_check CHECK (((length(name) <= 1024) AND (name ~ '^[a-z][a-z0-9]*([._-][a-z0-9]+)*$'::text))),
    CONSTRAINT relay_topics_retention_seconds_check CHECK (((retention_seconds >= 1) AND (retention_seconds <= 315360000)))
);


--
-- Name: retrieval_passages; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.retrieval_passages (
    source_type text NOT NULL,
    source_id text NOT NULL,
    "position" integer NOT NULL,
    passage text NOT NULL,
    embedding public.vector(768),
    embedding_source_revision bigint DEFAULT 0 NOT NULL,
    code_embedding public.vector,
    code_space_id text,
    code_source_revision bigint,
    prose_embedding public.vector,
    prose_space_id text,
    prose_source_revision bigint,
    CONSTRAINT retrieval_passages_embedding_source_revision_check CHECK ((embedding_source_revision >= 0))
);


--
-- Name: retrieval_passages_embedding_failures; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.retrieval_passages_embedding_failures (
    source_type text NOT NULL,
    source_id text NOT NULL,
    "position" integer NOT NULL,
    slot text NOT NULL,
    space_id text NOT NULL,
    source_revision bigint NOT NULL,
    attempts integer DEFAULT 1 NOT NULL,
    category text NOT NULL,
    retry_at timestamp with time zone NOT NULL,
    CONSTRAINT retrieval_passages_embedding_failures_attempts_check CHECK ((attempts > 0)),
    CONSTRAINT retrieval_passages_embedding_failures_category_check CHECK ((category ~ '^[A-Za-z][A-Za-z0-9]{0,79}$'::text)),
    CONSTRAINT retrieval_passages_embedding_failures_source_revision_check CHECK ((source_revision >= 0))
);


--
-- Name: retrieval_passages_embedding_staging; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.retrieval_passages_embedding_staging (
    source_type text NOT NULL,
    source_id text NOT NULL,
    "position" integer NOT NULL,
    slot text NOT NULL,
    space_id text NOT NULL,
    source_revision bigint NOT NULL,
    embedding public.vector NOT NULL,
    CONSTRAINT retrieval_passages_embedding_staging_source_revision_check CHECK ((source_revision >= 0))
);


--
-- Name: retrieval_sources; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.retrieval_sources (
    source_type text NOT NULL,
    source_id text NOT NULL,
    conversation_id text,
    ordinal integer,
    source_hash text NOT NULL,
    generation text,
    status text DEFAULT 'pending'::text NOT NULL,
    next_position integer DEFAULT 0 NOT NULL,
    attempts integer DEFAULT 0 NOT NULL,
    retry_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    last_error text,
    CONSTRAINT retrieval_sources_source_type_check CHECK ((source_type = ANY (ARRAY['entry'::text, 'digest'::text]))),
    CONSTRAINT retrieval_sources_status_check CHECK ((status = ANY (ARRAY['pending'::text, 'ready'::text, 'failed'::text])))
);


--
-- Name: run_approvals; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.run_approvals (
    id text NOT NULL,
    project_id bigint NOT NULL,
    conversation text NOT NULL,
    asked_in text NOT NULL,
    handle text,
    agent text NOT NULL,
    side text NOT NULL,
    argv jsonb NOT NULL,
    cwd text NOT NULL,
    reason text,
    state text NOT NULL,
    scope text,
    prefix jsonb,
    answered_by text,
    answered_at timestamp with time zone,
    delivered_at timestamp with time zone,
    created_at timestamp with time zone NOT NULL,
    commands jsonb,
    judged text,
    denial_reason text,
    CONSTRAINT run_approvals_an_answer_has_a_scope_or_is_a_denial CHECK ((((state = 'asked'::text) = (answered_at IS NULL)) AND ((state = ANY (ARRAY['allowed'::text, 'used'::text, 'revoked'::text])) = (scope IS NOT NULL)))),
    CONSTRAINT run_approvals_commands_is_a_set CHECK (((commands IS NULL) OR ((jsonb_typeof(commands) = 'array'::text) AND (jsonb_array_length(commands) > 0) AND (argv = '[]'::jsonb)))),
    CONSTRAINT run_approvals_only_a_project_approval_has_a_prefix CHECK (((scope = 'project'::text) = (prefix IS NOT NULL))),
    CONSTRAINT run_approvals_scope_is_known CHECK (((scope IS NULL) OR (scope = ANY (ARRAY['once'::text, 'conversation'::text, 'project'::text])))),
    CONSTRAINT run_approvals_side_is_known CHECK ((side = ANY (ARRAY['server'::text, 'local'::text]))),
    CONSTRAINT run_approvals_state_is_known CHECK ((state = ANY (ARRAY['asked'::text, 'allowed'::text, 'denied'::text, 'used'::text, 'revoked'::text])))
);


--
-- Name: runtime_config; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.runtime_config (
    key text NOT NULL,
    value text NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_by text NOT NULL,
    CONSTRAINT runtime_config_has_an_author CHECK ((updated_by <> ''::text)),
    CONSTRAINT runtime_config_key_is_named CHECK ((key <> ''::text))
);


--
-- Name: schedule_files; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.schedule_files (
    source_id bigint NOT NULL,
    name text NOT NULL,
    internal_name text NOT NULL,
    definition jsonb,
    status text NOT NULL,
    error text,
    paused_override boolean,
    CONSTRAINT schedule_files_status_check CHECK ((status = ANY (ARRAY['active'::text, 'refused'::text])))
);


--
-- Name: schedule_sources; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.schedule_sources (
    id bigint NOT NULL,
    account text NOT NULL,
    project_id bigint,
    source text NOT NULL,
    CONSTRAINT schedule_sources_source_check CHECK ((source = ANY (ARRAY['server'::text, 'workspace'::text])))
);


--
-- Name: schedule_sources_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.schedule_sources_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: schedule_sources_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.schedule_sources_id_seq OWNED BY public.schedule_sources.id;


--
-- Name: schedules; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.schedules (
    name text NOT NULL,
    cron text NOT NULL,
    zone text NOT NULL,
    emits text NOT NULL,
    paused boolean DEFAULT false NOT NULL,
    next_fire_at timestamp with time zone NOT NULL,
    defined_by text NOT NULL,
    defined_at timestamp with time zone DEFAULT now() NOT NULL,
    file_managed boolean DEFAULT false NOT NULL,
    CONSTRAINT schedules_cron_named CHECK ((cron <> ''::text)),
    CONSTRAINT schedules_emits_named CHECK ((emits <> ''::text)),
    CONSTRAINT schedules_name_named CHECK ((name <> ''::text)),
    CONSTRAINT schedules_zone_named CHECK ((zone <> ''::text))
);


--
-- Name: search_providers; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.search_providers (
    provider_key text NOT NULL,
    base_url text NOT NULL,
    name text NOT NULL,
    version text NOT NULL,
    description text,
    verbs text[] NOT NULL,
    cost_class text NOT NULL,
    network_tier text NOT NULL,
    max_results integer NOT NULL,
    max_query_length integer NOT NULL,
    domain_exclusion boolean NOT NULL,
    registered_at timestamp with time zone DEFAULT now() NOT NULL,
    last_health_at timestamp with time zone,
    last_health_status text,
    last_health_message text,
    consecutive_failures integer DEFAULT 0 NOT NULL,
    CONSTRAINT search_providers_a_base_url_has_no_trailing_slash CHECK ((base_url !~ '/$'::text)),
    CONSTRAINT search_providers_a_base_url_is_http CHECK ((base_url ~ '^https?://'::text)),
    CONSTRAINT search_providers_a_key_is_not_blank CHECK ((provider_key <> ''::text)),
    CONSTRAINT search_providers_declares_at_least_one_verb CHECK ((cardinality(verbs) > 0)),
    CONSTRAINT search_providers_failures_are_not_negative CHECK ((consecutive_failures >= 0))
);


--
-- Name: TABLE search_providers; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.search_providers IS 'Every provider process this server has been told to route search calls to, keyed by the provider''s own key. Written by registration (upsert) and by recordOutcome (health columns only). Carries no enabled flag, no rung and no timeout: ladder membership and per-rung policy are operator opinion and live in plowshare.search.ladder and its neighbouring runtime-config keys, not on this row. Spec section 4.';


--
-- Name: search_result_sets; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.search_result_sets (
    query_key text NOT NULL,
    provider_key text NOT NULL,
    hits jsonb NOT NULL,
    fetched_at timestamp with time zone NOT NULL,
    CONSTRAINT search_result_sets_a_key_is_a_sha_256 CHECK ((query_key ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT search_result_sets_a_provider_key_is_not_blank CHECK ((provider_key <> ''::text)),
    CONSTRAINT search_result_sets_hits_is_an_array CHECK ((jsonb_typeof(hits) = 'array'::text))
);


--
-- Name: TABLE search_result_sets; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.search_result_sets IS 'One search''s results, keyed by the SHA-256 of its normalised query text and requested max (never the query text itself), held so the model can page through them without the provider being asked again. Written once per search by ResultSetStore.put, which replaces rather than appends on a repeated key; read by get and purgeExpired against the live plowshare.search.result-set-ttl, never a per-row copy. This is a result set and not a query cache: its lifetime question is how long paging may continue, not how stale an answer is, and it is never read by a search other than the one that wrote it.';


--
-- Name: sections; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sections (
    id uuid NOT NULL,
    chapter_id uuid NOT NULL,
    document_id uuid NOT NULL,
    ordinal integer NOT NULL,
    title text,
    summary text,
    is_synthetic boolean DEFAULT false NOT NULL,
    CONSTRAINT sections_a_named_section_has_a_title CHECK ((is_synthetic OR ((title IS NOT NULL) AND (title <> ''::text)))),
    CONSTRAINT sections_counted_from_one CHECK ((ordinal >= 1)),
    CONSTRAINT sections_summary_is_not_blank CHECK (((summary IS NULL) OR (summary <> ''::text)))
);


--
-- Name: service_token_scopes; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.service_token_scopes (
    token_id uuid NOT NULL,
    project_id bigint NOT NULL,
    role text NOT NULL,
    CONSTRAINT service_token_scopes_role_check CHECK ((role = ANY (ARRAY['VIEWER'::text, 'CONTRIBUTOR'::text, 'MANAGER'::text])))
);


--
-- Name: service_tokens; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.service_tokens (
    id uuid NOT NULL,
    owner_handle text NOT NULL,
    name text NOT NULL,
    principal_handle text NOT NULL,
    digest text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    revoked_at timestamp with time zone,
    credential_issued boolean DEFAULT true NOT NULL,
    CONSTRAINT service_tokens_name_check CHECK (((length(name) >= 1) AND (length(name) <= 64)))
);


--
-- Name: skill_executions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.skill_executions (
    account_handle text NOT NULL,
    invocation uuid NOT NULL,
    payload text NOT NULL,
    parent_conversation text NOT NULL,
    conversation text,
    executor text NOT NULL,
    skill text NOT NULL,
    source text NOT NULL,
    origin text NOT NULL,
    tier text NOT NULL,
    mode text NOT NULL,
    state text DEFAULT 'claimed'::text NOT NULL,
    result text,
    context_snapshot jsonb,
    context_prompt text,
    source_through integer,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT skill_executions_mode_check CHECK ((mode = ANY (ARRAY['INHERITED'::text, 'SUMMARISED'::text, 'NEW'::text, 'DIRECT'::text]))),
    CONSTRAINT skill_executions_source_through_check CHECK ((source_through >= 0)),
    CONSTRAINT skill_executions_state_check CHECK ((state = ANY (ARRAY['claimed'::text, 'running'::text, 'awaiting'::text, 'finished'::text, 'failed'::text]))),
    CONSTRAINT skill_executions_tier_check CHECK ((tier = ANY (ARRAY['PROJECT'::text, 'SESSION'::text, 'PERSONAL'::text, 'GLOBAL'::text, 'SHIPPED'::text])))
);


--
-- Name: snapshot_cache_authority; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.snapshot_cache_authority
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: snapshot_cache_documents; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.snapshot_cache_documents
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: snapshot_cache_extractions; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.snapshot_cache_extractions
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: snapshot_cache_identity; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.snapshot_cache_identity (
    singleton boolean DEFAULT true NOT NULL,
    database_id uuid DEFAULT gen_random_uuid() NOT NULL,
    CONSTRAINT snapshot_cache_identity_singleton_check CHECK (singleton)
);


--
-- Name: snapshot_cache_knowledge_access; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.snapshot_cache_knowledge_access
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: snapshot_cache_knowledge_revocations; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.snapshot_cache_knowledge_revocations
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: snapshot_cache_observations; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.snapshot_cache_observations
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: snapshot_cache_progress; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.snapshot_cache_progress
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: snapshot_cache_queues; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.snapshot_cache_queues
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: snapshot_cache_readability; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.snapshot_cache_readability
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: snapshot_cache_relay; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.snapshot_cache_relay
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: snapshot_cache_schedules; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.snapshot_cache_schedules
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: snapshot_cache_search; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.snapshot_cache_search
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: snapshot_cache_transcripts; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.snapshot_cache_transcripts
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: snapshot_cache_types; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.snapshot_cache_types
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: snapshot_cache_writer_allocations; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.snapshot_cache_writer_allocations
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: snapshot_cache_writer_retention; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.snapshot_cache_writer_retention (
    singleton boolean DEFAULT true NOT NULL,
    through xid8 NOT NULL,
    CONSTRAINT snapshot_cache_writer_retention_singleton_check CHECK (singleton)
);


--
-- Name: snapshot_cache_writers; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.snapshot_cache_writers (
    xid xid8 NOT NULL,
    domain text NOT NULL,
    CONSTRAINT snapshot_cache_writers_domain_check CHECK ((domain = ANY (ARRAY['documents'::text, 'search'::text, 'schedules'::text, 'relay'::text, 'transcripts'::text, 'authority'::text, 'observations'::text, 'readability'::text, 'queues'::text, 'progress'::text, 'types'::text, 'extractions'::text, 'knowledge_access'::text, 'knowledge_revocations'::text])))
);


--
-- Name: suspended_run_calls; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.suspended_run_calls (
    sequence bigint NOT NULL,
    approval text NOT NULL,
    conversation text NOT NULL,
    session_id text,
    owner_handle text,
    side text NOT NULL,
    call_id text NOT NULL,
    arguments text NOT NULL,
    state text DEFAULT 'waiting'::text NOT NULL,
    released boolean DEFAULT false NOT NULL,
    result text,
    acknowledged_at timestamp with time zone,
    CONSTRAINT suspended_run_calls_check CHECK (((state = 'completed'::text) = (result IS NOT NULL))),
    CONSTRAINT suspended_run_calls_check1 CHECK (((acknowledged_at IS NULL) OR (state = 'completed'::text))),
    CONSTRAINT suspended_run_calls_check2 CHECK (((length(call_id) > 0) AND (length(arguments) > 0))),
    CONSTRAINT suspended_run_calls_side_check CHECK ((side = ANY (ARRAY['local'::text, 'server'::text]))),
    CONSTRAINT suspended_run_calls_state_check CHECK ((state = ANY (ARRAY['waiting'::text, 'started'::text, 'completed'::text])))
);


--
-- Name: suspended_run_calls_sequence_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.suspended_run_calls ALTER COLUMN sequence ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.suspended_run_calls_sequence_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: system_blocks; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.system_blocks (
    hash text NOT NULL,
    body text NOT NULL,
    CONSTRAINT system_blocks_a_block_is_not_blank CHECK ((body <> ''::text)),
    CONSTRAINT system_blocks_hash_is_a_sha_256 CHECK ((hash ~ '^[0-9a-f]{64}$'::text))
);


--
-- Name: TABLE system_blocks; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.system_blocks IS 'One row per distinct system block any turn has gone out with, keyed by the SHA-256 of its own bytes. Written once however many turns sent it; a turn that ran after somebody edited the agent''s file writes a second row. Nothing deletes from here: a block is still the record of what a turn was shown after the last turn referencing it is read for the last time.';


--
-- Name: system_operations; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.system_operations (
    id uuid NOT NULL,
    purpose text NOT NULL,
    scope_id uuid NOT NULL,
    grant_id uuid NOT NULL,
    agent text NOT NULL,
    model text NOT NULL,
    binding_hash text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT system_operations_binding_hash_check CHECK ((binding_hash ~ '^[a-f0-9]{64}$'::text)),
    CONSTRAINT system_operations_purpose_check CHECK ((purpose = ANY (ARRAY['DOCUMENT_PREPARATION'::text, 'DOCUMENT_ASK'::text, 'EMBEDDING_REPAIR'::text, 'CONVERSATION_COMPACTION'::text, 'SKILL_SUMMARY'::text, 'SCRIBE'::text, 'LEARNING'::text, 'MEMORY_DIGEST'::text, 'MEMORY_CURATION'::text, 'STUCK_ADVISOR'::text, 'CALL_VALIDATOR'::text, 'COMMAND_JUDGE'::text, 'SCHEDULE_READER'::text, 'SEMANTIC_QUERY'::text, 'MEMORY_WRITE'::text])))
);


--
-- Name: todo_notices; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.todo_notices (
    conversation text NOT NULL,
    list_hash text NOT NULL,
    compacted_through integer NOT NULL,
    noticed_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT todo_notices_compaction_is_an_ordinal_or_none CHECK ((compacted_through >= '-1'::integer))
);


--
-- Name: todos; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.todos (
    id text NOT NULL,
    conversation text NOT NULL,
    parent text,
    "position" integer NOT NULL,
    text text NOT NULL,
    status text NOT NULL,
    summary text,
    locked boolean DEFAULT false NOT NULL,
    stage_id text,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT todos_a_stage_is_locked CHECK (((stage_id IS NULL) OR locked)),
    CONSTRAINT todos_position_natural CHECK (("position" >= 0)),
    CONSTRAINT todos_status_is_known CHECK ((status = ANY (ARRAY['pending'::text, 'in_progress'::text, 'done'::text, 'dropped'::text]))),
    CONSTRAINT todos_text_named CHECK ((text <> ''::text))
);


--
-- Name: trajectory_image_references; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.trajectory_image_references (
    conversation_id text NOT NULL,
    entry_ordinal integer NOT NULL,
    slot integer NOT NULL,
    image_id text NOT NULL,
    blob_scope text NOT NULL,
    sha256 text NOT NULL,
    byte_length bigint NOT NULL,
    observe boolean NOT NULL,
    mime_type text NOT NULL,
    CONSTRAINT trajectory_image_references_byte_length_check CHECK (((byte_length >= 1) AND (byte_length <= 2147483647))),
    CONSTRAINT trajectory_image_references_check CHECK ((image_id = ('img_'::text || SUBSTRING(sha256 FROM 1 FOR 32)))),
    CONSTRAINT trajectory_image_references_image_id_check CHECK ((image_id ~ '^img_[0-9a-f]{32}$'::text)),
    CONSTRAINT trajectory_image_references_mime_type_check CHECK ((mime_type = ANY (ARRAY['image/png'::text, 'image/jpeg'::text, 'image/webp'::text]))),
    CONSTRAINT trajectory_image_references_slot_check CHECK (((slot >= 0) AND (slot <= 15)))
);


--
-- Name: triggers; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.triggers (
    name text NOT NULL,
    event text NOT NULL,
    project text,
    conversation text,
    agent text NOT NULL,
    task text NOT NULL,
    max_model_calls integer,
    max_turns integer,
    queue_cap integer DEFAULT 1 NOT NULL,
    paused boolean DEFAULT false NOT NULL,
    defined_by text NOT NULL,
    defined_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT triggers_agent_named CHECK ((agent <> ''::text)),
    CONSTRAINT triggers_event_named CHECK ((event <> ''::text)),
    CONSTRAINT triggers_limits_are_positive CHECK ((((max_model_calls IS NULL) OR (max_model_calls >= 1)) AND ((max_turns IS NULL) OR (max_turns >= 1)))),
    CONSTRAINT triggers_name_named CHECK ((name <> ''::text)),
    CONSTRAINT triggers_one_home CHECK (((project IS NULL) OR (conversation IS NULL))),
    CONSTRAINT triggers_queue_cap_is_at_least_one CHECK ((queue_cap >= 1)),
    CONSTRAINT triggers_task_named CHECK ((task <> ''::text))
);


--
-- Name: turns; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.turns (
    conversation_id text NOT NULL,
    ordinal integer NOT NULL,
    utterance text NOT NULL,
    answer text NOT NULL,
    ending text NOT NULL,
    prompt_tokens integer,
    agent text,
    system_block text,
    CONSTRAINT turns_a_turn_that_stopped_says_how CHECK (((ending = 'ANSWERED'::text) OR (answer <> ''::text))),
    CONSTRAINT turns_agent_is_named CHECK (((agent IS NULL) OR (agent <> ''::text))),
    CONSTRAINT turns_are_numbered_from_one CHECK ((ordinal >= 1)),
    CONSTRAINT turns_ending_is_known CHECK ((ending = ANY (ARRAY['ANSWERED'::text, 'TURN_CAP'::text, 'CALL_BUDGET'::text, 'CANCELLED'::text, 'STUCK'::text, 'UNAVAILABLE'::text, 'SUB_AGENT_FAILED'::text, 'SESSION_GONE'::text, 'AWAITING'::text, 'CALL_FAILURES'::text]))),
    CONSTRAINT turns_prompt_tokens_are_a_measurement CHECK (((prompt_tokens IS NULL) OR (prompt_tokens > 0))),
    CONSTRAINT turns_utterance_is_not_blank CHECK ((utterance <> ''::text))
);


--
-- Name: COLUMN turns.prompt_tokens; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.turns.prompt_tokens IS 'What this turn''s prompt cost, by the model''s own tokenizer, or NULL for a turn nobody measured. Never zero: absent and free are different facts, and compaction is decided from this number.';


--
-- Name: COLUMN turns.agent; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.turns.agent IS 'The agent that answered this turn, or NULL for a turn written before V17, when nothing recorded it and nothing can recover it. POST /v1/conversations/{id}/resume reads this rather than taking the agent from its caller.';


--
-- Name: COLUMN turns.system_block; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.turns.system_block IS 'The SHA-256 of the system block this turn was actually sent, into system_blocks, or NULL for a turn that did not record one -- every turn written before V32, and any turn whose block could not be stored. NULL is never filled in: a backfill from the agent''s current file would assert that a past turn was sent today''s prompt, which is the claim this column exists to stop. The projection route answers a NULL turn with the current definition and says on the wire that it did.';


--
-- Name: union_conflicts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.union_conflicts (
    project_id bigint NOT NULL,
    n integer NOT NULL,
    path text NOT NULL,
    base_blob text,
    ours_blob text,
    theirs_blob text,
    theirs_author text NOT NULL,
    run_id text,
    opened_at timestamp with time zone NOT NULL,
    resolved_at timestamp with time zone,
    resolution text,
    CONSTRAINT union_conflicts_a_resolution_has_a_time CHECK (((resolution IS NULL) = (resolved_at IS NULL))),
    CONSTRAINT union_conflicts_a_resolution_is_one_of_three CHECK (((resolution IS NULL) OR (resolution = ANY (ARRAY['mine'::text, 'theirs'::text, 'merged'::text]))))
);


--
-- Name: user_inbox; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.user_inbox (
    id text NOT NULL,
    handle text NOT NULL,
    firing text,
    conversation text,
    ending text,
    answer text NOT NULL,
    arrived_at timestamp with time zone NOT NULL,
    read_at timestamp with time zone,
    kind text DEFAULT 'run'::text NOT NULL,
    about text,
    settled_at timestamp with time zone,
    question_alert_attempted_at timestamp with time zone,
    CONSTRAINT user_inbox_a_run_names_where_it_ended CHECK (((kind <> 'run'::text) OR ((conversation IS NOT NULL) AND (ending IS NOT NULL)))),
    CONSTRAINT user_inbox_kind_is_known CHECK ((kind = ANY (ARRAY['run'::text, 'sync.conflict'::text, 'orchestration'::text, 'approval'::text, 'hook'::text, 'board'::text]))),
    CONSTRAINT user_inbox_only_a_question_is_settled CHECK (((settled_at IS NULL) OR (about IS NOT NULL)))
);


--
-- Name: acting_audit id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.acting_audit ALTER COLUMN id SET DEFAULT nextval('public.acting_audit_id_seq'::regclass);


--
-- Name: acting_context_audit id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.acting_context_audit ALTER COLUMN id SET DEFAULT nextval('public.acting_context_audit_id_seq'::regclass);


--
-- Name: admin_audit id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.admin_audit ALTER COLUMN id SET DEFAULT nextval('public.admin_audit_id_seq'::regclass);


--
-- Name: admin_pricing_history id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.admin_pricing_history ALTER COLUMN id SET DEFAULT nextval('public.admin_pricing_history_id_seq'::regclass);


--
-- Name: authority_grant_audit id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.authority_grant_audit ALTER COLUMN id SET DEFAULT nextval('public.authority_grant_audit_id_seq'::regclass);


--
-- Name: information_navigation_audit sequence; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_navigation_audit ALTER COLUMN sequence SET DEFAULT nextval('public.information_navigation_audit_sequence_seq'::regclass);


--
-- Name: project_access_audit id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_access_audit ALTER COLUMN id SET DEFAULT nextval('public.project_access_audit_id_seq'::regclass);


--
-- Name: schedule_sources id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.schedule_sources ALTER COLUMN id SET DEFAULT nextval('public.schedule_sources_id_seq'::regclass);


-- Seed the same built-ins as a fresh migration chain. The two admin rows are
-- disabled intrinsic accounts. The grant, audit time and database cache namespace
-- use the defaults so each installation gets its own identity and timestamps.
INSERT INTO public.admins
    (handle, password_hash, must_change_password, server_admin, bootstrap, enabled, account_kind)
VALUES
    ('SYSTEM', '!locked-built-in', false, false, false, false, 'BUILTIN'),
    ('GLOBAL', '!locked-built-in', false, false, false, false, 'BUILTIN');

INSERT INTO public.authority_accounts (id, legacy_handle, builtin) VALUES
	('00000000-0000-0000-0000-000000000001', 'SYSTEM', 'SYSTEM'),
	('00000000-0000-0000-0000-000000000004', 'GLOBAL', 'GLOBAL');

INSERT INTO public.authority_roles (id, name, assignable, intrinsic_account) VALUES
	('00000000-0000-0000-0000-000000000002', 'SYSTEM', false, '00000000-0000-0000-0000-000000000001'),
	('00000000-0000-0000-0000-000000000006', 'GLOBAL_READ', true, NULL),
	('00000000-0000-0000-0000-000000000007', 'ADMIN', true, NULL),
	('00000000-0000-0000-0000-000000000008', 'MANAGER', true, NULL),
	('00000000-0000-0000-0000-000000000009', 'CONTRIBUTOR', true, NULL),
	('00000000-0000-0000-0000-00000000000a', 'READER', true, NULL);

INSERT INTO public.authority_role_actions (role_id, role_version, profile, action) VALUES
	('00000000-0000-0000-0000-000000000002', 1, 'DEFAULT', 'READ'),
	('00000000-0000-0000-0000-000000000008', 1, 'DEFAULT', 'READ'),
	('00000000-0000-0000-0000-000000000009', 1, 'DEFAULT', 'READ'),
	('00000000-0000-0000-0000-00000000000a', 1, 'DEFAULT', 'READ'),
	('00000000-0000-0000-0000-000000000002', 1, 'DEFAULT', 'WRITE'),
	('00000000-0000-0000-0000-000000000008', 1, 'DEFAULT', 'WRITE'),
	('00000000-0000-0000-0000-000000000009', 1, 'DEFAULT', 'WRITE'),
	('00000000-0000-0000-0000-000000000002', 1, 'DEFAULT', 'MANAGE_ACCESS'),
	('00000000-0000-0000-0000-000000000007', 1, 'DEFAULT', 'MANAGE_ACCESS'),
	('00000000-0000-0000-0000-000000000008', 1, 'DEFAULT', 'MANAGE_ACCESS'),
	('00000000-0000-0000-0000-000000000002', 1, 'DEFAULT', 'MANAGE_SCOPE'),
	('00000000-0000-0000-0000-000000000007', 1, 'DEFAULT', 'MANAGE_SCOPE'),
	('00000000-0000-0000-0000-000000000008', 1, 'DEFAULT', 'MANAGE_SCOPE'),
	('00000000-0000-0000-0000-000000000002', 1, 'DEFAULT', 'SET_AUDIENCE'),
	('00000000-0000-0000-0000-000000000007', 1, 'DEFAULT', 'SET_AUDIENCE'),
	('00000000-0000-0000-0000-000000000008', 1, 'DEFAULT', 'SET_AUDIENCE'),
	('00000000-0000-0000-0000-000000000002', 1, 'DEFAULT', 'ADMINISTER'),
	('00000000-0000-0000-0000-000000000007', 1, 'DEFAULT', 'ADMINISTER'),
	('00000000-0000-0000-0000-000000000002', 1, 'DEFAULT', 'CONVERSATION_CREATE'),
	('00000000-0000-0000-0000-000000000008', 1, 'DEFAULT', 'CONVERSATION_CREATE'),
	('00000000-0000-0000-0000-000000000009', 1, 'DEFAULT', 'CONVERSATION_CREATE'),
	('00000000-0000-0000-0000-000000000002', 1, 'DEFAULT', 'RUN_MODEL'),
	('00000000-0000-0000-0000-000000000008', 1, 'DEFAULT', 'RUN_MODEL'),
	('00000000-0000-0000-0000-000000000009', 1, 'DEFAULT', 'RUN_MODEL'),
	('00000000-0000-0000-0000-000000000007', 1, 'GLOBAL_KNOWLEDGE', 'READ'),
	('00000000-0000-0000-0000-000000000007', 1, 'GLOBAL_KNOWLEDGE', 'WRITE'),
	('00000000-0000-0000-0000-000000000006', 1, 'DEFAULT', 'READ');

INSERT INTO public.authority_scopes (id, kind, project_id, account_id, builtin, restriction_reason, container) VALUES
	('00000000-0000-0000-0000-000000000003', 'ADMINISTRATION', NULL, '00000000-0000-0000-0000-000000000001', 'SYSTEM_ADMINISTRATION', NULL, NULL),
	('00000000-0000-0000-0000-000000000005', 'ACCOUNT_KNOWLEDGE', NULL, '00000000-0000-0000-0000-000000000004', 'GLOBAL_KNOWLEDGE', NULL, NULL);

INSERT INTO public.authority_grants
    (subject_account, scope_id, role_id, requested_role, coverage, issuer_account, provenance)
SELECT r.intrinsic_account, s.id, r.id, 'SYSTEM', 'ALL_SCOPES', r.intrinsic_account, 'ROOT_POLICY'
FROM public.authority_roles r
JOIN public.authority_scopes s ON s.kind = 'ADMINISTRATION' AND s.account_id = r.intrinsic_account
WHERE r.name = 'SYSTEM' AND NOT r.assignable;

INSERT INTO public.authority_grant_audit (grant_id, event, version, actor_account)
SELECT id, 'ISSUE', version, issuer_account
FROM public.authority_grants
WHERE provenance = 'ROOT_POLICY';

INSERT INTO public.embedding_slots (slot, active_space_id, target_space_id, version, active_mode, active_distance, target_mode, target_distance) VALUES
	('code', NULL, NULL, 0, NULL, NULL, NULL, NULL),
	('prose', NULL, NULL, 0, NULL, NULL, NULL, NULL);

INSERT INTO public.snapshot_cache_identity (singleton) VALUES (true);

INSERT INTO public.snapshot_cache_writer_retention (singleton, through) VALUES
	(true, '0');

-- Initialize the projection versions to their migrated positions.
SELECT pg_catalog.setval('public.snapshot_cache_authority', 1, true);
SELECT pg_catalog.setval('public.snapshot_cache_documents', 1, true);
SELECT pg_catalog.setval('public.snapshot_cache_extractions', 1, true);
SELECT pg_catalog.setval('public.snapshot_cache_knowledge_access', 1, true);
SELECT pg_catalog.setval('public.snapshot_cache_knowledge_revocations', 1, true);
SELECT pg_catalog.setval('public.snapshot_cache_observations', 1, true);
SELECT pg_catalog.setval('public.snapshot_cache_progress', 1, true);
SELECT pg_catalog.setval('public.snapshot_cache_queues', 1, true);
SELECT pg_catalog.setval('public.snapshot_cache_readability', 1, true);
SELECT pg_catalog.setval('public.snapshot_cache_relay', 1, true);
SELECT pg_catalog.setval('public.snapshot_cache_schedules', 1, true);
SELECT pg_catalog.setval('public.snapshot_cache_search', 1, true);
SELECT pg_catalog.setval('public.snapshot_cache_transcripts', 1, true);
SELECT pg_catalog.setval('public.snapshot_cache_types', 1, true);


--
-- Name: acting_audit acting_audit_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.acting_audit
    ADD CONSTRAINT acting_audit_pkey PRIMARY KEY (id);


--
-- Name: acting_audit acting_audit_request_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.acting_audit
    ADD CONSTRAINT acting_audit_request_id_key UNIQUE (request_id);


--
-- Name: acting_context_audit acting_context_audit_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.acting_context_audit
    ADD CONSTRAINT acting_context_audit_pkey PRIMARY KEY (id);


--
-- Name: acting_eligible_accounts acting_eligible_accounts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.acting_eligible_accounts
    ADD CONSTRAINT acting_eligible_accounts_pkey PRIMARY KEY (application, grant_name, account);


--
-- Name: acting_eligible_projects acting_eligible_projects_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.acting_eligible_projects
    ADD CONSTRAINT acting_eligible_projects_pkey PRIMARY KEY (application, grant_name, project_id);


--
-- Name: acting_grants acting_grants_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.acting_grants
    ADD CONSTRAINT acting_grants_pkey PRIMARY KEY (application, name);


--
-- Name: acting_mutation_receipts acting_mutation_receipts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.acting_mutation_receipts
    ADD CONSTRAINT acting_mutation_receipts_pkey PRIMARY KEY (request_id);


--
-- Name: acting_token_projects acting_token_projects_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.acting_token_projects
    ADD CONSTRAINT acting_token_projects_pkey PRIMARY KEY (token_id, project_id);


--
-- Name: acting_tokens acting_tokens_digest_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.acting_tokens
    ADD CONSTRAINT acting_tokens_digest_key UNIQUE (digest);


--
-- Name: acting_tokens acting_tokens_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.acting_tokens
    ADD CONSTRAINT acting_tokens_pkey PRIMARY KEY (id);


--
-- Name: acting_work_conversations acting_work_conversations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.acting_work_conversations
    ADD CONSTRAINT acting_work_conversations_pkey PRIMARY KEY (conversation);


--
-- Name: acting_work_receipts acting_work_receipts_job_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.acting_work_receipts
    ADD CONSTRAINT acting_work_receipts_job_key UNIQUE (job);


--
-- Name: acting_work_receipts acting_work_receipts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.acting_work_receipts
    ADD CONSTRAINT acting_work_receipts_pkey PRIMARY KEY (actor, request_id);


--
-- Name: admin_audit admin_audit_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.admin_audit
    ADD CONSTRAINT admin_audit_pkey PRIMARY KEY (id);


--
-- Name: admin_pricing_history admin_pricing_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.admin_pricing_history
    ADD CONSTRAINT admin_pricing_history_pkey PRIMARY KEY (id);


--
-- Name: admin_pricing admin_pricing_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.admin_pricing
    ADD CONSTRAINT admin_pricing_pkey PRIMARY KEY (billing_route, model);


--
-- Name: admins admins_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.admins
    ADD CONSTRAINT admins_pkey PRIMARY KEY (handle);


--
-- Name: application_deployment_heads application_deployment_heads_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.application_deployment_heads
    ADD CONSTRAINT application_deployment_heads_pkey PRIMARY KEY (project_id);


--
-- Name: application_deployment_receipts application_deployment_receipts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.application_deployment_receipts
    ADD CONSTRAINT application_deployment_receipts_pkey PRIMARY KEY (request_id);


--
-- Name: application_identities application_identities_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.application_identities
    ADD CONSTRAINT application_identities_pkey PRIMARY KEY (project_name);


--
-- Name: application_identities application_identities_project_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.application_identities
    ADD CONSTRAINT application_identities_project_id_key UNIQUE (project_id);


--
-- Name: application_identities application_identities_service_handle_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.application_identities
    ADD CONSTRAINT application_identities_service_handle_key UNIQUE (service_handle);


--
-- Name: application_identities application_identities_token_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.application_identities
    ADD CONSTRAINT application_identities_token_id_key UNIQUE (token_id);


--
-- Name: application_releases application_releases_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.application_releases
    ADD CONSTRAINT application_releases_pkey PRIMARY KEY (revision);


--
-- Name: application_releases application_releases_project_id_revision_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.application_releases
    ADD CONSTRAINT application_releases_project_id_revision_key UNIQUE (project_id, revision);


--
-- Name: application_token_receipts application_token_receipts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.application_token_receipts
    ADD CONSTRAINT application_token_receipts_pkey PRIMARY KEY (request_id);


--
-- Name: auth_session_chains auth_session_chains_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.auth_session_chains
    ADD CONSTRAINT auth_session_chains_pkey PRIMARY KEY (id);


--
-- Name: auth_session_grants auth_session_grants_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.auth_session_grants
    ADD CONSTRAINT auth_session_grants_pkey PRIMARY KEY (digest);


--
-- Name: authority_accounts authority_accounts_builtin_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.authority_accounts
    ADD CONSTRAINT authority_accounts_builtin_key UNIQUE (builtin);


--
-- Name: authority_accounts authority_accounts_legacy_handle_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.authority_accounts
    ADD CONSTRAINT authority_accounts_legacy_handle_key UNIQUE (legacy_handle);


--
-- Name: authority_accounts authority_accounts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.authority_accounts
    ADD CONSTRAINT authority_accounts_pkey PRIMARY KEY (id);


--
-- Name: authority_grant_audit authority_grant_audit_grant_id_version_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.authority_grant_audit
    ADD CONSTRAINT authority_grant_audit_grant_id_version_key UNIQUE (grant_id, version);


--
-- Name: authority_grant_audit authority_grant_audit_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.authority_grant_audit
    ADD CONSTRAINT authority_grant_audit_pkey PRIMARY KEY (id);


--
-- Name: authority_grants authority_grants_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.authority_grants
    ADD CONSTRAINT authority_grants_pkey PRIMARY KEY (id);


--
-- Name: authority_role_actions authority_role_actions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.authority_role_actions
    ADD CONSTRAINT authority_role_actions_pkey PRIMARY KEY (role_id, role_version, profile, action);


--
-- Name: authority_roles authority_roles_intrinsic_account_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.authority_roles
    ADD CONSTRAINT authority_roles_intrinsic_account_key UNIQUE (intrinsic_account);


--
-- Name: authority_roles authority_roles_name_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.authority_roles
    ADD CONSTRAINT authority_roles_name_key UNIQUE (name);


--
-- Name: authority_roles authority_roles_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.authority_roles
    ADD CONSTRAINT authority_roles_pkey PRIMARY KEY (id);


--
-- Name: authority_scopes authority_scopes_builtin_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.authority_scopes
    ADD CONSTRAINT authority_scopes_builtin_key UNIQUE (builtin);


--
-- Name: authority_scopes authority_scopes_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.authority_scopes
    ADD CONSTRAINT authority_scopes_pkey PRIMARY KEY (id);


--
-- Name: authority_scopes authority_scopes_project_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.authority_scopes
    ADD CONSTRAINT authority_scopes_project_id_key UNIQUE (project_id);


--
-- Name: binary_blob_aliases binary_blob_aliases_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.binary_blob_aliases
    ADD CONSTRAINT binary_blob_aliases_pkey PRIMARY KEY (scope, alias);


--
-- Name: binary_blob_claims binary_blob_claims_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.binary_blob_claims
    ADD CONSTRAINT binary_blob_claims_pkey PRIMARY KEY (scope, kind, owner, slot);


--
-- Name: binary_blobs binary_blobs_file_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.binary_blobs
    ADD CONSTRAINT binary_blobs_file_key UNIQUE (file);


--
-- Name: binary_blobs binary_blobs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.binary_blobs
    ADD CONSTRAINT binary_blobs_pkey PRIMARY KEY (scope, sha256);


--
-- Name: board_decisions board_decisions_child_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.board_decisions
    ADD CONSTRAINT board_decisions_child_key UNIQUE (child);


--
-- Name: board_decisions board_decisions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.board_decisions
    ADD CONSTRAINT board_decisions_pkey PRIMARY KEY (request);


--
-- Name: board_message_instances board_message_instances_conversation_agent_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.board_message_instances
    ADD CONSTRAINT board_message_instances_conversation_agent_key UNIQUE (conversation, agent);


--
-- Name: board_message_instances board_message_instances_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.board_message_instances
    ADD CONSTRAINT board_message_instances_pkey PRIMARY KEY (id);


--
-- Name: board_message_instances board_message_instances_topic_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.board_message_instances
    ADD CONSTRAINT board_message_instances_topic_key UNIQUE (topic);


--
-- Name: board_message_route_bindings board_message_route_bindings_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.board_message_route_bindings
    ADD CONSTRAINT board_message_route_bindings_pkey PRIMARY KEY (account, source_project, route_name);


--
-- Name: board_message_routes board_message_routes_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.board_message_routes
    ADD CONSTRAINT board_message_routes_pkey PRIMARY KEY (message);


--
-- Name: board_message_routes board_message_routes_sender_call_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.board_message_routes
    ADD CONSTRAINT board_message_routes_sender_call_id_key UNIQUE (sender, call_id);


--
-- Name: board_messages board_messages_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.board_messages
    ADD CONSTRAINT board_messages_pkey PRIMARY KEY (id);


--
-- Name: board_messages board_messages_seq_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.board_messages
    ADD CONSTRAINT board_messages_seq_key UNIQUE (seq);


--
-- Name: board_notices board_notices_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.board_notices
    ADD CONSTRAINT board_notices_pkey PRIMARY KEY (message);


--
-- Name: board_post_receipts board_post_receipts_message_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.board_post_receipts
    ADD CONSTRAINT board_post_receipts_message_id_key UNIQUE (message_id);


--
-- Name: board_post_receipts board_post_receipts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.board_post_receipts
    ADD CONSTRAINT board_post_receipts_pkey PRIMARY KEY (account, request_id);


--
-- Name: board_reads board_reads_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.board_reads
    ADD CONSTRAINT board_reads_pkey PRIMARY KEY (conversation, topic);


--
-- Name: board_seats board_seats_conversation_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.board_seats
    ADD CONSTRAINT board_seats_conversation_key UNIQUE (conversation);


--
-- Name: board_seats board_seats_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.board_seats
    ADD CONSTRAINT board_seats_pkey PRIMARY KEY (topic, occupant);


--
-- Name: board_topics board_topics_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.board_topics
    ADD CONSTRAINT board_topics_pkey PRIMARY KEY (id);


--
-- Name: chapters chapters_addressed_with_their_document; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chapters
    ADD CONSTRAINT chapters_addressed_with_their_document UNIQUE (id, document_id);


--
-- Name: chapters chapters_one_per_position; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chapters
    ADD CONSTRAINT chapters_one_per_position UNIQUE (document_id, ordinal) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: chapters chapters_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chapters
    ADD CONSTRAINT chapters_pkey PRIMARY KEY (id);


--
-- Name: chunks_embedding_failures chunks_embedding_failures_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chunks_embedding_failures
    ADD CONSTRAINT chunks_embedding_failures_pkey PRIMARY KEY (source_id, slot, space_id);


--
-- Name: chunks_embedding_staging chunks_embedding_staging_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chunks_embedding_staging
    ADD CONSTRAINT chunks_embedding_staging_pkey PRIMARY KEY (source_id, slot, space_id, source_revision);


--
-- Name: chunks chunks_one_per_ordinal; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chunks
    ADD CONSTRAINT chunks_one_per_ordinal UNIQUE (paragraph_id, ordinal) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: chunks chunks_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chunks
    ADD CONSTRAINT chunks_pkey PRIMARY KEY (id);


--
-- Name: citations citations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.citations
    ADD CONSTRAINT citations_pkey PRIMARY KEY (id);


--
-- Name: code_outlines code_outlines_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.code_outlines
    ADD CONSTRAINT code_outlines_pkey PRIMARY KEY (document_id);


--
-- Name: code_passages code_passages_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.code_passages
    ADD CONSTRAINT code_passages_pkey PRIMARY KEY (chunk_id);


--
-- Name: code_symbols code_symbols_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.code_symbols
    ADD CONSTRAINT code_symbols_pkey PRIMARY KEY (document_id, ordinal);


--
-- Name: code_workspace_indexes code_workspace_indexes_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.code_workspace_indexes
    ADD CONSTRAINT code_workspace_indexes_pkey PRIMARY KEY (workspace_id, source_key, source_hash, parser_version);


--
-- Name: code_workspace_mutations code_workspace_mutations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.code_workspace_mutations
    ADD CONSTRAINT code_workspace_mutations_pkey PRIMARY KEY (token);


--
-- Name: code_workspace_sources code_workspace_sources_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.code_workspace_sources
    ADD CONSTRAINT code_workspace_sources_pkey PRIMARY KEY (workspace_id, source_key);


--
-- Name: code_workspaces code_workspaces_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.code_workspaces
    ADD CONSTRAINT code_workspaces_pkey PRIMARY KEY (id);


--
-- Name: command_invocations command_invocations_account_handle_conversation_source_run_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.command_invocations
    ADD CONSTRAINT command_invocations_account_handle_conversation_source_run_key UNIQUE (account_handle, conversation, source_run);


--
-- Name: command_invocations command_invocations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.command_invocations
    ADD CONSTRAINT command_invocations_pkey PRIMARY KEY (account_handle, invocation);


--
-- Name: compactions compactions_one_per_reach_in_a_conversation; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.compactions
    ADD CONSTRAINT compactions_one_per_reach_in_a_conversation PRIMARY KEY (conversation_id, through_ordinal);


--
-- Name: contained_applications contained_application_request; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.contained_applications
    ADD CONSTRAINT contained_application_request UNIQUE (container, request_id);


--
-- Name: contained_applications contained_applications_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.contained_applications
    ADD CONSTRAINT contained_applications_pkey PRIMARY KEY (id);


--
-- Name: contained_applications contained_applications_project_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.contained_applications
    ADD CONSTRAINT contained_applications_project_id_key UNIQUE (project_id);


--
-- Name: contained_applications contained_applications_scope_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.contained_applications
    ADD CONSTRAINT contained_applications_scope_id_key UNIQUE (scope_id);


--
-- Name: contained_collections contained_collections_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.contained_collections
    ADD CONSTRAINT contained_collections_pkey PRIMARY KEY (child);


--
-- Name: contained_principals contained_principals_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.contained_principals
    ADD CONSTRAINT contained_principals_pkey PRIMARY KEY (id);


--
-- Name: contained_principals contained_principals_scope_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.contained_principals
    ADD CONSTRAINT contained_principals_scope_id_key UNIQUE (scope_id);


--
-- Name: conversations conversations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.conversations
    ADD CONSTRAINT conversations_pkey PRIMARY KEY (id);


--
-- Name: digest_children digest_children_child_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.digest_children
    ADD CONSTRAINT digest_children_child_id_key UNIQUE (child_id);


--
-- Name: digest_children digest_children_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.digest_children
    ADD CONSTRAINT digest_children_pkey PRIMARY KEY (parent_id, child_id);


--
-- Name: digest_memories digest_memories_memory_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.digest_memories
    ADD CONSTRAINT digest_memories_memory_id_key UNIQUE (memory_id);


--
-- Name: digest_memories digest_memories_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.digest_memories
    ADD CONSTRAINT digest_memories_pkey PRIMARY KEY (digest_id);


--
-- Name: digest_revisions digest_revisions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.digest_revisions
    ADD CONSTRAINT digest_revisions_pkey PRIMARY KEY (digest_id, revision);


--
-- Name: digest_spans digest_spans_conversation_id_summary_ordinal_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.digest_spans
    ADD CONSTRAINT digest_spans_conversation_id_summary_ordinal_key UNIQUE (conversation_id, summary_ordinal);


--
-- Name: digest_spans digest_spans_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.digest_spans
    ADD CONSTRAINT digest_spans_pkey PRIMARY KEY (digest_id);


--
-- Name: digests_embedding_failures digests_embedding_failures_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.digests_embedding_failures
    ADD CONSTRAINT digests_embedding_failures_pkey PRIMARY KEY (source_id, slot, space_id);


--
-- Name: digests_embedding_staging digests_embedding_staging_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.digests_embedding_staging
    ADD CONSTRAINT digests_embedding_staging_pkey PRIMARY KEY (source_id, slot, space_id, source_revision);


--
-- Name: digests digests_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.digests
    ADD CONSTRAINT digests_pkey PRIMARY KEY (id);


--
-- Name: document_references document_references_one_per_number; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_references
    ADD CONSTRAINT document_references_one_per_number UNIQUE (document_id, ref_num) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: document_references document_references_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_references
    ADD CONSTRAINT document_references_pkey PRIMARY KEY (id);


--
-- Name: documents_embedding_failures documents_embedding_failures_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.documents_embedding_failures
    ADD CONSTRAINT documents_embedding_failures_pkey PRIMARY KEY (source_id, slot, space_id);


--
-- Name: documents_embedding_staging documents_embedding_staging_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.documents_embedding_staging
    ADD CONSTRAINT documents_embedding_staging_pkey PRIMARY KEY (source_id, slot, space_id, source_revision);


--
-- Name: documents documents_namespaced_name; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.documents
    ADD CONSTRAINT documents_namespaced_name UNIQUE (information_namespace, source_name);


--
-- Name: documents documents_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.documents
    ADD CONSTRAINT documents_pkey PRIMARY KEY (id);


--
-- Name: embedding_input_limits embedding_input_limits_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.embedding_input_limits
    ADD CONSTRAINT embedding_input_limits_pkey PRIMARY KEY (space_id);


--
-- Name: embedding_queries embedding_queries_account_id_invocation_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.embedding_queries
    ADD CONSTRAINT embedding_queries_account_id_invocation_key UNIQUE (account_id, invocation);


--
-- Name: embedding_queries embedding_queries_budget_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.embedding_queries
    ADD CONSTRAINT embedding_queries_budget_id_key UNIQUE (budget_id);


--
-- Name: embedding_queries embedding_queries_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.embedding_queries
    ADD CONSTRAINT embedding_queries_pkey PRIMARY KEY (id);


--
-- Name: embedding_repair_attempts embedding_repair_attempts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.embedding_repair_attempts
    ADD CONSTRAINT embedding_repair_attempts_pkey PRIMARY KEY (id);


--
-- Name: embedding_repair_scans embedding_repair_scans_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.embedding_repair_scans
    ADD CONSTRAINT embedding_repair_scans_pkey PRIMARY KEY (slot, space_id, store);


--
-- Name: embedding_repair_work embedding_repair_work_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.embedding_repair_work
    ADD CONSTRAINT embedding_repair_work_pkey PRIMARY KEY (slot, space_id, store, source_type, source_id, "position");


--
-- Name: embedding_slots embedding_slots_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.embedding_slots
    ADD CONSTRAINT embedding_slots_pkey PRIMARY KEY (slot);


--
-- Name: embedding_spaces embedding_spaces_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.embedding_spaces
    ADD CONSTRAINT embedding_spaces_pkey PRIMARY KEY (id);


--
-- Name: entries entries_one_per_place_in_a_conversation; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.entries
    ADD CONSTRAINT entries_one_per_place_in_a_conversation PRIMARY KEY (conversation_id, ordinal);


--
-- Name: execution_allowance_claims execution_allowance_claims_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.execution_allowance_claims
    ADD CONSTRAINT execution_allowance_claims_pkey PRIMARY KEY (budget_id, ordinal);


--
-- Name: execution_allowances execution_allowances_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.execution_allowances
    ADD CONSTRAINT execution_allowances_pkey PRIMARY KEY (id);


--
-- Name: fetched_pages fetched_pages_are_named_by_their_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.fetched_pages
    ADD CONSTRAINT fetched_pages_are_named_by_their_key PRIMARY KEY (url_key);


--
-- Name: firings firings_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.firings
    ADD CONSTRAINT firings_pkey PRIMARY KEY (id);


--
-- Name: hosted_tool_scope_mutations hosted_tool_scope_mutations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.hosted_tool_scope_mutations
    ADD CONSTRAINT hosted_tool_scope_mutations_pkey PRIMARY KEY (request_id);


--
-- Name: hosted_tool_scope_workers hosted_tool_scope_workers_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.hosted_tool_scope_workers
    ADD CONSTRAINT hosted_tool_scope_workers_pkey PRIMARY KEY (binding_id);


--
-- Name: hosted_tool_scope_workers hosted_tool_scope_workers_registration_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.hosted_tool_scope_workers
    ADD CONSTRAINT hosted_tool_scope_workers_registration_key UNIQUE (registration);


--
-- Name: hosted_tool_scopes hosted_tool_scopes_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.hosted_tool_scopes
    ADD CONSTRAINT hosted_tool_scopes_pkey PRIMARY KEY (id);


--
-- Name: hosted_tool_scopes hosted_tool_scopes_route_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.hosted_tool_scopes
    ADD CONSTRAINT hosted_tool_scopes_route_key UNIQUE (route);


--
-- Name: inference_accounting_events inference_accounting_events_call_id_call_sequence_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inference_accounting_events
    ADD CONSTRAINT inference_accounting_events_call_id_call_sequence_key UNIQUE (call_id, call_sequence);


--
-- Name: inference_accounting_events inference_accounting_events_journal_id_journal_sequence_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inference_accounting_events
    ADD CONSTRAINT inference_accounting_events_journal_id_journal_sequence_key UNIQUE (journal_id, journal_sequence);


--
-- Name: inference_accounting_events inference_accounting_events_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inference_accounting_events
    ADD CONSTRAINT inference_accounting_events_pkey PRIMARY KEY (event_id);


--
-- Name: inference_accounting_instances inference_accounting_instances_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inference_accounting_instances
    ADD CONSTRAINT inference_accounting_instances_pkey PRIMARY KEY (instance_id);


--
-- Name: inference_accounting_journals inference_accounting_journals_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inference_accounting_journals
    ADD CONSTRAINT inference_accounting_journals_pkey PRIMARY KEY (journal_id);


--
-- Name: inference_admissions inference_admissions_accounting_call_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inference_admissions
    ADD CONSTRAINT inference_admissions_accounting_call_key UNIQUE (accounting_call);


--
-- Name: inference_admissions inference_admissions_budget_id_ordinal_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inference_admissions
    ADD CONSTRAINT inference_admissions_budget_id_ordinal_key UNIQUE (budget_id, ordinal);


--
-- Name: inference_admissions inference_admissions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inference_admissions
    ADD CONSTRAINT inference_admissions_pkey PRIMARY KEY (id);


--
-- Name: inference_attempts inference_attempts_call_id_attempt_number_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inference_attempts
    ADD CONSTRAINT inference_attempts_call_id_attempt_number_key UNIQUE (call_id, attempt_number);


--
-- Name: inference_attempts inference_attempts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inference_attempts
    ADD CONSTRAINT inference_attempts_pkey PRIMARY KEY (attempt_id);


--
-- Name: inference_calls inference_calls_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inference_calls
    ADD CONSTRAINT inference_calls_pkey PRIMARY KEY (call_id);


--
-- Name: inference_pricing_versions inference_pricing_versions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inference_pricing_versions
    ADD CONSTRAINT inference_pricing_versions_pkey PRIMARY KEY (version);


--
-- Name: inference_run_ownership inference_run_ownership_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inference_run_ownership
    ADD CONSTRAINT inference_run_ownership_pkey PRIMARY KEY (conversation_id, turn_ordinal);


--
-- Name: information_acquisitions information_acquisitions_account_request_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_acquisitions
    ADD CONSTRAINT information_acquisitions_account_request_id_key UNIQUE (account, request_id);


--
-- Name: information_acquisitions information_acquisitions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_acquisitions
    ADD CONSTRAINT information_acquisitions_pkey PRIMARY KEY (id);


--
-- Name: information_commands information_commands_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_commands
    ADD CONSTRAINT information_commands_pkey PRIMARY KEY (account, request_id);


--
-- Name: information_content_type_versions information_content_type_versions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_content_type_versions
    ADD CONSTRAINT information_content_type_versions_pkey PRIMARY KEY (id);


--
-- Name: information_content_type_versions information_content_type_versions_type_id_ordinal_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_content_type_versions
    ADD CONSTRAINT information_content_type_versions_type_id_ordinal_key UNIQUE (type_id, ordinal);


--
-- Name: information_content_types information_content_types_namespace_slug_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_content_types
    ADD CONSTRAINT information_content_types_namespace_slug_key UNIQUE (namespace, slug);


--
-- Name: information_content_types information_content_types_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_content_types
    ADD CONSTRAINT information_content_types_pkey PRIMARY KEY (id);


--
-- Name: information_document_policies information_document_policies_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_document_policies
    ADD CONSTRAINT information_document_policies_pkey PRIMARY KEY (document_id);


--
-- Name: information_evidence information_evidence_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_evidence
    ADD CONSTRAINT information_evidence_pkey PRIMARY KEY (id);


--
-- Name: information_evidence information_evidence_receipts; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_evidence
    ADD CONSTRAINT information_evidence_receipts UNIQUE (owner_handle, request_id);


--
-- Name: information_import_origins information_import_origins_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_import_origins
    ADD CONSTRAINT information_import_origins_pkey PRIMARY KEY (revision_id);


--
-- Name: information_inputs information_inputs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_inputs
    ADD CONSTRAINT information_inputs_pkey PRIMARY KEY (derived_revision, input_revision);


--
-- Name: information_job_inputs information_job_inputs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_job_inputs
    ADD CONSTRAINT information_job_inputs_pkey PRIMARY KEY (job_id, revision_id);


--
-- Name: information_links information_links_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_links
    ADD CONSTRAINT information_links_pkey PRIMARY KEY (revision_id, project_id);


--
-- Name: information_model_steps information_model_steps_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_model_steps
    ADD CONSTRAINT information_model_steps_pkey PRIMARY KEY (revision_id, generation, stage_key);


--
-- Name: information_navigation_audit information_navigation_audit_account_request_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_navigation_audit
    ADD CONSTRAINT information_navigation_audit_account_request_id_key UNIQUE (account, request_id);


--
-- Name: information_navigation_audit information_navigation_audit_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_navigation_audit
    ADD CONSTRAINT information_navigation_audit_pkey PRIMARY KEY (sequence);


--
-- Name: information_navigation_policies information_navigation_policies_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_navigation_policies
    ADD CONSTRAINT information_navigation_policies_pkey PRIMARY KEY (policy_key);


--
-- Name: information_navigation_receipts information_navigation_receipts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_navigation_receipts
    ADD CONSTRAINT information_navigation_receipts_pkey PRIMARY KEY (account, request_id);


--
-- Name: information_navigation_views information_navigation_views_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_navigation_views
    ADD CONSTRAINT information_navigation_views_pkey PRIMARY KEY (account, id);


--
-- Name: information_payload_assignments information_payload_assignments_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_payload_assignments
    ADD CONSTRAINT information_payload_assignments_pkey PRIMARY KEY (id);


--
-- Name: information_policy_assignments information_policy_assignments_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_policy_assignments
    ADD CONSTRAINT information_policy_assignments_pkey PRIMARY KEY (id);


--
-- Name: information_projection_reuse information_projection_reuse_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_projection_reuse
    ADD CONSTRAINT information_projection_reuse_pkey PRIMARY KEY (revision_id, stage);


--
-- Name: information_quarantined_payloads information_quarantined_payloads_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_quarantined_payloads
    ADD CONSTRAINT information_quarantined_payloads_pkey PRIMARY KEY (payload_id);


--
-- Name: information_reference_obligations information_reference_obligations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_reference_obligations
    ADD CONSTRAINT information_reference_obligations_pkey PRIMARY KEY (relationship);


--
-- Name: information_relationships information_relationships_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_relationships
    ADD CONSTRAINT information_relationships_pkey PRIMARY KEY (id);


--
-- Name: information_report_citations information_report_citations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_report_citations
    ADD CONSTRAINT information_report_citations_pkey PRIMARY KEY (report_revision, evidence_id);


--
-- Name: information_reports information_reports_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_reports
    ADD CONSTRAINT information_reports_pkey PRIMARY KEY (revision_id);


--
-- Name: information_requests information_requests_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_requests
    ADD CONSTRAINT information_requests_pkey PRIMARY KEY (account, request_id);


--
-- Name: information_resource_policies information_resource_policies_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_resource_policies
    ADD CONSTRAINT information_resource_policies_pkey PRIMARY KEY (resource_id);


--
-- Name: information_resource_policy_history information_resource_policy_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_resource_policy_history
    ADD CONSTRAINT information_resource_policy_history_pkey PRIMARY KEY (resource_id, version);


--
-- Name: information_resources information_resources_namespace_source_name_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_resources
    ADD CONSTRAINT information_resources_namespace_source_name_key UNIQUE (namespace, source_name);


--
-- Name: information_resources information_resources_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_resources
    ADD CONSTRAINT information_resources_pkey PRIMARY KEY (id);


--
-- Name: information_reuse_preparations information_reuse_preparations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_reuse_preparations
    ADD CONSTRAINT information_reuse_preparations_pkey PRIMARY KEY (actor, request_id);


--
-- Name: information_revision_content information_revision_content_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_revision_content
    ADD CONSTRAINT information_revision_content_pkey PRIMARY KEY (revision_id);


--
-- Name: information_revision_events information_revision_events_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_revision_events
    ADD CONSTRAINT information_revision_events_pkey PRIMARY KEY (revision_id, relay_project, relay_position);


--
-- Name: information_revisions information_revisions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_revisions
    ADD CONSTRAINT information_revisions_pkey PRIMARY KEY (id);


--
-- Name: information_revisions information_revisions_resource_id_ordinal_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_revisions
    ADD CONSTRAINT information_revisions_resource_id_ordinal_key UNIQUE (resource_id, ordinal);


--
-- Name: information_stage_authorizations information_stage_authorizations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_stage_authorizations
    ADD CONSTRAINT information_stage_authorizations_pkey PRIMARY KEY (revision_id, generation, stage);


--
-- Name: information_steps information_steps_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_steps
    ADD CONSTRAINT information_steps_pkey PRIMARY KEY (revision_id, generation, stage);


--
-- Name: information_write_gates information_write_gates_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_write_gates
    ADD CONSTRAINT information_write_gates_pkey PRIMARY KEY (account, request_id);


--
-- Name: invocation_image_admissions invocation_image_admissions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.invocation_image_admissions
    ADD CONSTRAINT invocation_image_admissions_pkey PRIMARY KEY (invocation_id, ordinal);


--
-- Name: invocation_image_admissions invocation_image_admissions_request_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.invocation_image_admissions
    ADD CONSTRAINT invocation_image_admissions_request_id_key UNIQUE (request_id);


--
-- Name: jobs jobs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.jobs
    ADD CONSTRAINT jobs_pkey PRIMARY KEY (id);


--
-- Name: local_hook_sets local_hook_sets_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.local_hook_sets
    ADD CONSTRAINT local_hook_sets_pkey PRIMARY KEY (hash);


--
-- Name: memories_embedding_failures memories_embedding_failures_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.memories_embedding_failures
    ADD CONSTRAINT memories_embedding_failures_pkey PRIMARY KEY (source_id, slot, space_id);


--
-- Name: memories_embedding_staging memories_embedding_staging_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.memories_embedding_staging
    ADD CONSTRAINT memories_embedding_staging_pkey PRIMARY KEY (source_id, slot, space_id, source_revision);


--
-- Name: memories memories_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.memories
    ADD CONSTRAINT memories_pkey PRIMARY KEY (id);


--
-- Name: memory_provenance memory_provenance_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.memory_provenance
    ADD CONSTRAINT memory_provenance_pkey PRIMARY KEY (memory_id, conversation_id, turn_ordinal);


--
-- Name: memory_reasons memory_reasons_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.memory_reasons
    ADD CONSTRAINT memory_reasons_pkey PRIMARY KEY (id);


--
-- Name: message_external_contexts message_external_contexts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.message_external_contexts
    ADD CONSTRAINT message_external_contexts_pkey PRIMARY KEY (id);


--
-- Name: message_external_contexts message_external_contexts_recipient_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.message_external_contexts
    ADD CONSTRAINT message_external_contexts_recipient_key UNIQUE (recipient);


--
-- Name: message_external_contexts message_external_contexts_sender_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.message_external_contexts
    ADD CONSTRAINT message_external_contexts_sender_key UNIQUE (sender);


--
-- Name: message_external_tasks message_external_tasks_account_project_client_request_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.message_external_tasks
    ADD CONSTRAINT message_external_tasks_account_project_client_request_id_key UNIQUE (account, project, client, request_id);


--
-- Name: message_external_tasks message_external_tasks_message_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.message_external_tasks
    ADD CONSTRAINT message_external_tasks_message_key UNIQUE (message);


--
-- Name: message_external_tasks message_external_tasks_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.message_external_tasks
    ADD CONSTRAINT message_external_tasks_pkey PRIMARY KEY (id);


--
-- Name: operation_admission_parents operation_admission_parents_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.operation_admission_parents
    ADD CONSTRAINT operation_admission_parents_pkey PRIMARY KEY (child);


--
-- Name: operation_admission_support operation_admission_support_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.operation_admission_support
    ADD CONSTRAINT operation_admission_support_pkey PRIMARY KEY (operation, action, grant_id);


--
-- Name: operation_admissions operation_admissions_job_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.operation_admissions
    ADD CONSTRAINT operation_admissions_job_id_key UNIQUE (job_id);


--
-- Name: operation_admissions operation_admissions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.operation_admissions
    ADD CONSTRAINT operation_admissions_pkey PRIMARY KEY (id);


--
-- Name: operation_conversations operation_conversations_operation_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.operation_conversations
    ADD CONSTRAINT operation_conversations_operation_key UNIQUE (operation);


--
-- Name: operation_conversations operation_conversations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.operation_conversations
    ADD CONSTRAINT operation_conversations_pkey PRIMARY KEY (conversation);


--
-- Name: operation_inference_budgets operation_inference_budgets_operation_id_budget_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.operation_inference_budgets
    ADD CONSTRAINT operation_inference_budgets_operation_id_budget_id_key UNIQUE (operation_id, budget_id);


--
-- Name: operation_inference_budgets operation_inference_budgets_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.operation_inference_budgets
    ADD CONSTRAINT operation_inference_budgets_pkey PRIMARY KEY (operation_id);


--
-- Name: orchestration_acceptance orchestration_acceptance_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.orchestration_acceptance
    ADD CONSTRAINT orchestration_acceptance_pkey PRIMARY KEY (orchestration, "position");


--
-- Name: orchestration_acceptance_requirements orchestration_acceptance_requirements_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.orchestration_acceptance_requirements
    ADD CONSTRAINT orchestration_acceptance_requirements_pkey PRIMARY KEY (orchestration);


--
-- Name: orchestration_checks orchestration_checks_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.orchestration_checks
    ADD CONSTRAINT orchestration_checks_pkey PRIMARY KEY (orchestration);


--
-- Name: orchestration_concerns orchestration_concerns_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.orchestration_concerns
    ADD CONSTRAINT orchestration_concerns_pkey PRIMARY KEY (orchestration, id);


--
-- Name: orchestration_messages orchestration_messages_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.orchestration_messages
    ADD CONSTRAINT orchestration_messages_pkey PRIMARY KEY (id);


--
-- Name: orchestration_record orchestration_record_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.orchestration_record
    ADD CONSTRAINT orchestration_record_pkey PRIMARY KEY (root, ordinal);


--
-- Name: orchestration_resume_receipts orchestration_resume_receipts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.orchestration_resume_receipts
    ADD CONSTRAINT orchestration_resume_receipts_pkey PRIMARY KEY (account, request_id);


--
-- Name: orchestration_script_steps orchestration_script_steps_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.orchestration_script_steps
    ADD CONSTRAINT orchestration_script_steps_pkey PRIMARY KEY (conversation_id, sequence);


--
-- Name: orchestration_start_receipts orchestration_start_receipts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.orchestration_start_receipts
    ADD CONSTRAINT orchestration_start_receipts_pkey PRIMARY KEY (account, request_id);


--
-- Name: orchestration_start_receipts orchestration_start_receipts_run_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.orchestration_start_receipts
    ADD CONSTRAINT orchestration_start_receipts_run_id_key UNIQUE (run_id);


--
-- Name: orchestrations orchestrations_conductor_conversation_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.orchestrations
    ADD CONSTRAINT orchestrations_conductor_conversation_key UNIQUE (conductor_conversation);


--
-- Name: orchestrations orchestrations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.orchestrations
    ADD CONSTRAINT orchestrations_pkey PRIMARY KEY (id);


--
-- Name: outgoing_peers outgoing_peers_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.outgoing_peers
    ADD CONSTRAINT outgoing_peers_pkey PRIMARY KEY (account, project_key, peer);


--
-- Name: outgoing_work outgoing_work_account_request_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.outgoing_work
    ADD CONSTRAINT outgoing_work_account_request_id_key UNIQUE (account, request_id);


--
-- Name: outgoing_work outgoing_work_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.outgoing_work
    ADD CONSTRAINT outgoing_work_pkey PRIMARY KEY (id);


--
-- Name: paragraphs paragraphs_one_per_position; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.paragraphs
    ADD CONSTRAINT paragraphs_one_per_position UNIQUE (document_id, ordinal) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: paragraphs paragraphs_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.paragraphs
    ADD CONSTRAINT paragraphs_pkey PRIMARY KEY (id);


--
-- Name: project_access_audit project_access_audit_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_access_audit
    ADD CONSTRAINT project_access_audit_pkey PRIMARY KEY (id);


--
-- Name: project_members project_members_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_members
    ADD CONSTRAINT project_members_pkey PRIMARY KEY (project_id, handle);


--
-- Name: projects projects_name_is_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.projects
    ADD CONSTRAINT projects_name_is_unique UNIQUE (name);


--
-- Name: projects projects_personal_owner_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.projects
    ADD CONSTRAINT projects_personal_owner_key UNIQUE (personal_owner);


--
-- Name: projects projects_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.projects
    ADD CONSTRAINT projects_pkey PRIMARY KEY (id);


--
-- Name: proposals proposals_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.proposals
    ADD CONSTRAINT proposals_pkey PRIMARY KEY (id);


--
-- Name: provider_connections provider_connections_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.provider_connections
    ADD CONSTRAINT provider_connections_pkey PRIMARY KEY (id);


--
-- Name: provider_credential_receipts provider_credential_receipts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.provider_credential_receipts
    ADD CONSTRAINT provider_credential_receipts_pkey PRIMARY KEY (request_id);


--
-- Name: provider_credentials provider_credentials_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.provider_credentials
    ADD CONSTRAINT provider_credentials_pkey PRIMARY KEY (connection_id, subject);


--
-- Name: provider_credentials provider_credentials_state_hash_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.provider_credentials
    ADD CONSTRAINT provider_credentials_state_hash_key UNIQUE (state_hash);


--
-- Name: relay_admissions relay_admissions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.relay_admissions
    ADD CONSTRAINT relay_admissions_pkey PRIMARY KEY (scope_key, topic, subscriber, publication_position);


--
-- Name: relay_approval_gap_recoveries relay_approval_gap_recoveries_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.relay_approval_gap_recoveries
    ADD CONSTRAINT relay_approval_gap_recoveries_pkey PRIMARY KEY (scope_key, topic, subscriber, expired_through);


--
-- Name: relay_blob_publications relay_blob_publications_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.relay_blob_publications
    ADD CONSTRAINT relay_blob_publications_pkey PRIMARY KEY (scope_key, topic, "position");


--
-- Name: relay_consumer_leases relay_consumer_leases_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.relay_consumer_leases
    ADD CONSTRAINT relay_consumer_leases_pkey PRIMARY KEY (scope_key, topic, subscriber);


--
-- Name: relay_deliveries relay_deliveries_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.relay_deliveries
    ADD CONSTRAINT relay_deliveries_pkey PRIMARY KEY (id);


--
-- Name: relay_deliveries relay_deliveries_project_id_topic_subscriber_publication_p_key1; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.relay_deliveries
    ADD CONSTRAINT relay_deliveries_project_id_topic_subscriber_publication_p_key1 UNIQUE (scope_key, topic, subscriber, publication_position, branch_name);


--
-- Name: relay_deliveries relay_deliveries_project_id_topic_subscriber_publication_po_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.relay_deliveries
    ADD CONSTRAINT relay_deliveries_project_id_topic_subscriber_publication_po_key UNIQUE (scope_key, topic, subscriber, publication_position, branch_index);


--
-- Name: relay_executions relay_executions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.relay_executions
    ADD CONSTRAINT relay_executions_pkey PRIMARY KEY (request_id);


--
-- Name: relay_native_consumers relay_native_consumers_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.relay_native_consumers
    ADD CONSTRAINT relay_native_consumers_pkey PRIMARY KEY (scope_key, topic, subscriber);


--
-- Name: relay_native_gap_recoveries relay_native_gap_recoveries_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.relay_native_gap_recoveries
    ADD CONSTRAINT relay_native_gap_recoveries_pkey PRIMARY KEY (scope_key, topic, subscriber, expired_through);


--
-- Name: relay_operator_receipts relay_operator_receipts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.relay_operator_receipts
    ADD CONSTRAINT relay_operator_receipts_pkey PRIMARY KEY (request_id);


--
-- Name: relay_payloads relay_payloads_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.relay_payloads
    ADD CONSTRAINT relay_payloads_pkey PRIMARY KEY (digest);


--
-- Name: relay_publications relay_publications_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.relay_publications
    ADD CONSTRAINT relay_publications_pkey PRIMARY KEY (scope_key, topic, "position");


--
-- Name: relay_publications relay_publications_project_id_topic_event_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.relay_publications
    ADD CONSTRAINT relay_publications_project_id_topic_event_id_key UNIQUE (scope_key, topic, event_id);


--
-- Name: relay_sdk_batches relay_sdk_batches_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.relay_sdk_batches
    ADD CONSTRAINT relay_sdk_batches_pkey PRIMARY KEY (scope_key, topic, subscriber);


--
-- Name: relay_source_events relay_source_events_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.relay_source_events
    ADD CONSTRAINT relay_source_events_pkey PRIMARY KEY (id);


--
-- Name: relay_source_events relay_source_events_sequence_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.relay_source_events
    ADD CONSTRAINT relay_source_events_sequence_key UNIQUE (sequence);


--
-- Name: relay_subscription_authority relay_subscription_authority_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.relay_subscription_authority
    ADD CONSTRAINT relay_subscription_authority_pkey PRIMARY KEY (scope_key, topic, subscriber, account);


--
-- Name: relay_subscriptions relay_subscriptions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.relay_subscriptions
    ADD CONSTRAINT relay_subscriptions_pkey PRIMARY KEY (scope_key, topic, subscriber);


--
-- Name: relay_tool_invocations relay_tool_invocations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.relay_tool_invocations
    ADD CONSTRAINT relay_tool_invocations_pkey PRIMARY KEY (id);


--
-- Name: relay_topics relay_topics_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.relay_topics
    ADD CONSTRAINT relay_topics_pkey PRIMARY KEY (scope_key, name);


--
-- Name: retrieval_passages_embedding_failures retrieval_passages_embedding_failures_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.retrieval_passages_embedding_failures
    ADD CONSTRAINT retrieval_passages_embedding_failures_pkey PRIMARY KEY (source_type, source_id, "position", slot, space_id);


--
-- Name: retrieval_passages_embedding_staging retrieval_passages_embedding_staging_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.retrieval_passages_embedding_staging
    ADD CONSTRAINT retrieval_passages_embedding_staging_pkey PRIMARY KEY (source_type, source_id, "position", slot, space_id, source_revision);


--
-- Name: retrieval_passages retrieval_passages_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.retrieval_passages
    ADD CONSTRAINT retrieval_passages_pkey PRIMARY KEY (source_type, source_id, "position");


--
-- Name: retrieval_sources retrieval_sources_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.retrieval_sources
    ADD CONSTRAINT retrieval_sources_pkey PRIMARY KEY (source_type, source_id);


--
-- Name: run_approvals run_approvals_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.run_approvals
    ADD CONSTRAINT run_approvals_pkey PRIMARY KEY (id);


--
-- Name: runtime_config runtime_config_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.runtime_config
    ADD CONSTRAINT runtime_config_pkey PRIMARY KEY (key);


--
-- Name: schedule_files schedule_files_internal_name_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.schedule_files
    ADD CONSTRAINT schedule_files_internal_name_key UNIQUE (internal_name);


--
-- Name: schedule_files schedule_files_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.schedule_files
    ADD CONSTRAINT schedule_files_pkey PRIMARY KEY (source_id, name);


--
-- Name: schedule_sources schedule_sources_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.schedule_sources
    ADD CONSTRAINT schedule_sources_pkey PRIMARY KEY (id);


--
-- Name: schedules schedules_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.schedules
    ADD CONSTRAINT schedules_pkey PRIMARY KEY (name);


--
-- Name: search_providers search_providers_are_named_by_their_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.search_providers
    ADD CONSTRAINT search_providers_are_named_by_their_key PRIMARY KEY (provider_key);


--
-- Name: search_result_sets search_result_sets_are_named_by_their_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.search_result_sets
    ADD CONSTRAINT search_result_sets_are_named_by_their_key PRIMARY KEY (query_key);


--
-- Name: sections sections_addressed_with_their_document; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sections
    ADD CONSTRAINT sections_addressed_with_their_document UNIQUE (id, document_id);


--
-- Name: sections sections_one_per_position; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sections
    ADD CONSTRAINT sections_one_per_position UNIQUE (chapter_id, ordinal) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: sections sections_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sections
    ADD CONSTRAINT sections_pkey PRIMARY KEY (id);


--
-- Name: service_token_scopes service_token_scopes_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_token_scopes
    ADD CONSTRAINT service_token_scopes_pkey PRIMARY KEY (token_id, project_id);


--
-- Name: service_tokens service_tokens_digest_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_tokens
    ADD CONSTRAINT service_tokens_digest_key UNIQUE (digest);


--
-- Name: service_tokens service_tokens_owner_handle_name_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_tokens
    ADD CONSTRAINT service_tokens_owner_handle_name_key UNIQUE (owner_handle, name);


--
-- Name: service_tokens service_tokens_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_tokens
    ADD CONSTRAINT service_tokens_pkey PRIMARY KEY (id);


--
-- Name: service_tokens service_tokens_principal_handle_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_tokens
    ADD CONSTRAINT service_tokens_principal_handle_key UNIQUE (principal_handle);


--
-- Name: skill_executions skill_executions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.skill_executions
    ADD CONSTRAINT skill_executions_pkey PRIMARY KEY (account_handle, invocation);


--
-- Name: snapshot_cache_identity snapshot_cache_identity_database_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.snapshot_cache_identity
    ADD CONSTRAINT snapshot_cache_identity_database_id_key UNIQUE (database_id);


--
-- Name: snapshot_cache_identity snapshot_cache_identity_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.snapshot_cache_identity
    ADD CONSTRAINT snapshot_cache_identity_pkey PRIMARY KEY (singleton);


--
-- Name: snapshot_cache_writer_retention snapshot_cache_writer_retention_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.snapshot_cache_writer_retention
    ADD CONSTRAINT snapshot_cache_writer_retention_pkey PRIMARY KEY (singleton);


--
-- Name: snapshot_cache_writers snapshot_cache_writers_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.snapshot_cache_writers
    ADD CONSTRAINT snapshot_cache_writers_pkey PRIMARY KEY (domain, xid);


--
-- Name: suspended_run_calls suspended_run_calls_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.suspended_run_calls
    ADD CONSTRAINT suspended_run_calls_pkey PRIMARY KEY (approval);


--
-- Name: suspended_run_calls suspended_run_calls_sequence_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.suspended_run_calls
    ADD CONSTRAINT suspended_run_calls_sequence_key UNIQUE (sequence);


--
-- Name: system_blocks system_blocks_are_named_by_their_hash; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.system_blocks
    ADD CONSTRAINT system_blocks_are_named_by_their_hash PRIMARY KEY (hash);


--
-- Name: system_operations system_operations_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.system_operations
    ADD CONSTRAINT system_operations_pkey PRIMARY KEY (id);


--
-- Name: todo_notices todo_notices_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.todo_notices
    ADD CONSTRAINT todo_notices_pkey PRIMARY KEY (conversation);


--
-- Name: todos todos_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.todos
    ADD CONSTRAINT todos_pkey PRIMARY KEY (id);


--
-- Name: trajectory_image_references trajectory_image_references_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.trajectory_image_references
    ADD CONSTRAINT trajectory_image_references_pkey PRIMARY KEY (conversation_id, entry_ordinal, slot);


--
-- Name: triggers triggers_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.triggers
    ADD CONSTRAINT triggers_pkey PRIMARY KEY (name);


--
-- Name: turns turns_one_per_place_in_a_conversation; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.turns
    ADD CONSTRAINT turns_one_per_place_in_a_conversation PRIMARY KEY (conversation_id, ordinal);


--
-- Name: union_conflicts union_conflicts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.union_conflicts
    ADD CONSTRAINT union_conflicts_pkey PRIMARY KEY (project_id, n);


--
-- Name: user_inbox user_inbox_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_inbox
    ADD CONSTRAINT user_inbox_pkey PRIMARY KEY (id);


--
-- Name: acting_tokens_subject; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX acting_tokens_subject ON public.acting_tokens USING btree (subject, created_at DESC, id);


--
-- Name: admin_audit_target; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX admin_audit_target ON public.admin_audit USING btree (target, id DESC);


--
-- Name: admins_one_bootstrap; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX admins_one_bootstrap ON public.admins USING btree (bootstrap) WHERE bootstrap;


--
-- Name: auth_session_chains_expiry; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX auth_session_chains_expiry ON public.auth_session_chains USING btree (expires_at);


--
-- Name: auth_session_grants_chain; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX auth_session_grants_chain ON public.auth_session_grants USING btree (chain_id);


--
-- Name: authority_current_admin_control; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX authority_current_admin_control ON public.authority_grants USING btree (scope_id, subject_account) WHERE ((provenance = 'ADMIN_CONTROL'::text) AND (revoked_at IS NULL));


--
-- Name: authority_current_membership; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX authority_current_membership ON public.authority_grants USING btree (scope_id, subject_account) WHERE ((provenance = 'MEMBERSHIP'::text) AND (revoked_at IS NULL));


--
-- Name: authority_grants_cache_expiry; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX authority_grants_cache_expiry ON public.authority_grants USING btree (expires_at) WHERE (expires_at IS NOT NULL);


--
-- Name: authority_grants_parent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX authority_grants_parent ON public.authority_grants USING btree (issuing_grant);


--
-- Name: authority_grants_recipient; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX authority_grants_recipient ON public.authority_grants USING btree (subject_account, subject_agent);


--
-- Name: authority_one_knowledge_scope; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX authority_one_knowledge_scope ON public.authority_scopes USING btree (account_id) WHERE (kind = 'ACCOUNT_KNOWLEDGE'::text);


--
-- Name: authority_one_personal_scope; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX authority_one_personal_scope ON public.authority_scopes USING btree (account_id) WHERE (kind = 'PERSONAL'::text);


--
-- Name: authority_one_root_policy; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX authority_one_root_policy ON public.authority_grants USING btree (provenance) WHERE (provenance = 'ROOT_POLICY'::text);


--
-- Name: authority_scope_container; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX authority_scope_container ON public.authority_scopes USING btree (container) WHERE (container IS NOT NULL);


--
-- Name: binary_blob_claims_object; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX binary_blob_claims_object ON public.binary_blob_claims USING btree (scope, sha256);


--
-- Name: binary_blobs_collection; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX binary_blobs_collection ON public.binary_blobs USING btree (state, unclaimed_at, scope, sha256) WHERE (state = ANY (ARRAY['READY'::text, 'DELETING'::text]));


--
-- Name: board_message_approval_continuation; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX board_message_approval_continuation ON public.firings USING btree (((data ->> 'message_approval'::text))) WHERE (data ? 'message_approval'::text);


--
-- Name: board_message_deadlines; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX board_message_deadlines ON public.board_message_routes USING btree (deadline_at) WHERE ((handled_at IS NULL) AND (deadline_at IS NOT NULL));


--
-- Name: board_message_default_instance; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX board_message_default_instance ON public.board_message_instances USING btree (account, project, agent) WHERE is_default;


--
-- Name: board_message_final_reply; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX board_message_final_reply ON public.board_message_routes USING btree (reply_to) WHERE final;


--
-- Name: board_message_instance_open_receipt; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX board_message_instance_open_receipt ON public.board_message_instances USING btree (account, project, request_id) WHERE (request_id IS NOT NULL);


--
-- Name: board_message_legacy_send_turn; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX board_message_legacy_send_turn ON public.board_message_routes USING btree (sender, call_id text_pattern_ops) WHERE (send_fingerprint IS NULL);


--
-- Name: board_message_one_handling_request; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX board_message_one_handling_request ON public.board_message_routes USING btree (recipient) WHERE ((started_at IS NOT NULL) AND (handled_at IS NULL));


--
-- Name: board_message_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX board_message_pending ON public.board_message_routes USING btree (recipient) WHERE (handled_at IS NULL);


--
-- Name: board_messages_by_topic; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX board_messages_by_topic ON public.board_messages USING btree (topic, seq);


--
-- Name: board_notices_owed; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX board_notices_owed ON public.board_notices USING btree (topic) WHERE (delivered_at IS NULL);


--
-- Name: board_topics_children; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX board_topics_children ON public.board_topics USING btree (parent) WHERE (parent IS NOT NULL);


--
-- Name: board_topics_open; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX board_topics_open ON public.board_topics USING btree (project) WHERE (closed_at IS NULL);


--
-- Name: board_topics_undelivered_resolutions; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX board_topics_undelivered_resolutions ON public.board_topics USING btree (closed_at) WHERE ((resolution IS NOT NULL) AND (resolution_delivered_at IS NULL));


--
-- Name: chunks_by_text; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chunks_by_text ON public.chunks USING gin (text_search);


--
-- Name: chunks_by_vector; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chunks_by_vector ON public.chunks USING hnsw (embedding public.vector_cosine_ops);


--
-- Name: chunks_code_space; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chunks_code_space ON public.chunks USING btree (code_space_id) WHERE (code_embedding IS NOT NULL);


--
-- Name: chunks_prose_space; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX chunks_prose_space ON public.chunks USING btree (prose_space_id) WHERE (prose_embedding IS NOT NULL);


--
-- Name: citations_by_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX citations_by_time ON public.citations USING btree (cited_at DESC, id);


--
-- Name: citations_made_in; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX citations_made_in ON public.citations USING btree (conversation_id, turn_ordinal);


--
-- Name: citations_of; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX citations_of ON public.citations USING btree (document_id, paragraph_id);


--
-- Name: code_symbols_by_name; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX code_symbols_by_name ON public.code_symbols USING btree (lower(name) text_pattern_ops, document_id, ordinal);


--
-- Name: code_workspace_indexes_revision; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX code_workspace_indexes_revision ON public.code_workspace_indexes USING btree (revision_id);


--
-- Name: code_workspace_mutations_expiry; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX code_workspace_mutations_expiry ON public.code_workspace_mutations USING btree (expires_at);


--
-- Name: code_workspace_mutations_project; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX code_workspace_mutations_project ON public.code_workspace_mutations USING btree (project_id);


--
-- Name: code_workspaces_due; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX code_workspaces_due ON public.code_workspaces USING btree (next_poll);


--
-- Name: code_workspaces_project; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX code_workspaces_project ON public.code_workspaces USING btree (project_id);


--
-- Name: command_invocations_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX command_invocations_pending ON public.command_invocations USING btree (conversation, created_at) WHERE (state = 'bound'::text);


--
-- Name: contained_application_container; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX contained_application_container ON public.contained_applications USING btree (container);


--
-- Name: contained_application_live; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX contained_application_live ON public.contained_applications USING btree (container) WHERE live;


--
-- Name: contained_collection_container; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX contained_collection_container ON public.contained_collections USING btree (container) WHERE (container IS NOT NULL);


--
-- Name: contained_collection_destruction; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX contained_collection_destruction ON public.contained_collections USING btree (container, root) WHERE (root IS NOT NULL);


--
-- Name: contained_collection_parent; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX contained_collection_parent ON public.contained_collections USING btree (container, scope) WHERE (scope IS NOT NULL);


--
-- Name: contained_collection_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX contained_collection_pending ON public.contained_collections USING btree (collected_at) WHERE (NOT source_removed);


--
-- Name: contained_collection_scope; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX contained_collection_scope ON public.contained_collections USING btree (scope) WHERE (scope IS NOT NULL);


--
-- Name: conversation_allowance_identity; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX conversation_allowance_identity ON public.conversations USING btree (budget_id);


--
-- Name: conversations_children; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX conversations_children ON public.conversations USING btree (parent_id) WHERE (parent_id IS NOT NULL);


--
-- Name: conversations_home; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX conversations_home ON public.conversations USING btree (project_id, origin, created_at);


--
-- Name: conversations_opened_by_call; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX conversations_opened_by_call ON public.conversations USING btree (parent_id, opened_by_call) WHERE (opened_by_call IS NOT NULL);


--
-- Name: conversations_under_retention; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX conversations_under_retention ON public.conversations USING btree (lifecycle, origin, created_at) WHERE (lifecycle IS NOT NULL);


--
-- Name: digests_code_space; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX digests_code_space ON public.digests USING btree (code_space_id) WHERE (code_embedding IS NOT NULL);


--
-- Name: digests_home_depth; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX digests_home_depth ON public.digests USING btree (project_id, depth);


--
-- Name: digests_prose_space; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX digests_prose_space ON public.digests USING btree (prose_space_id) WHERE (prose_embedding IS NOT NULL);


--
-- Name: documents_by_document_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX documents_by_document_type ON public.documents USING btree (document_type, ingested_at, id);


--
-- Name: documents_code_space; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX documents_code_space ON public.documents USING btree (code_space_id) WHERE (code_embedding IS NOT NULL);


--
-- Name: documents_prose_space; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX documents_prose_space ON public.documents USING btree (prose_space_id) WHERE (prose_embedding IS NOT NULL);


--
-- Name: embedding_repair_available; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX embedding_repair_available ON public.embedding_repair_work USING btree (available_at);


--
-- Name: embedding_repair_call; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX embedding_repair_call ON public.embedding_repair_attempts USING btree (call_id) WHERE (call_id IS NOT NULL);


--
-- Name: embedding_repair_due; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX embedding_repair_due ON public.embedding_repair_work USING btree (slot, space_id, store, available_at);


--
-- Name: embedding_repair_live_attempt; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX embedding_repair_live_attempt ON public.embedding_repair_attempts USING btree (slot, space_id, store, source_type, source_id, "position", source_revision) WHERE (phase = ANY (ARRAY['PREPARED'::text, 'ACCOUNTED'::text, 'QUEUED'::text, 'RUNNING'::text, 'ATTEMPT_STARTED'::text, 'RESPONSE_RECEIVED'::text, 'RESULT_VALIDATED'::text, 'PUBLISHING'::text]));


--
-- Name: embedding_repair_scans_due; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX embedding_repair_scans_due ON public.embedding_repair_scans USING btree (next_at);


--
-- Name: entries_awaiting_the_learner; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX entries_awaiting_the_learner ON public.entries USING btree (conversation_id) WHERE ((superseded_by IS NOT NULL) AND (learned_at IS NULL));


--
-- Name: entries_by_text; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX entries_by_text ON public.entries USING gin (text_search);


--
-- Name: entries_handle_addresses_one_row; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX entries_handle_addresses_one_row ON public.entries USING btree (handle);


--
-- Name: entries_job_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX entries_job_id ON public.entries USING btree (job_id) WHERE (job_id IS NOT NULL);


--
-- Name: entries_payloads_still_held; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX entries_payloads_still_held ON public.entries USING btree (conversation_id) WHERE ((kind = 'tool_result'::text) AND (ejected_at IS NULL));


--
-- Name: fetched_pages_by_fetched_at_and_last_read; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX fetched_pages_by_fetched_at_and_last_read ON public.fetched_pages USING btree (fetched_at, last_read);


--
-- Name: firings_by_arrival; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX firings_by_arrival ON public.firings USING btree (arrived_at);


--
-- Name: firings_by_topic; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX firings_by_topic ON public.firings USING btree (topic) WHERE (topic IS NOT NULL);


--
-- Name: firings_one_per_tick; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX firings_one_per_tick ON public.firings USING btree (schedule, fire_at, COALESCE(trigger, ''::text)) WHERE (schedule IS NOT NULL);


--
-- Name: firings_one_running_per_target; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX firings_one_running_per_target ON public.firings USING btree (target) WHERE ((status = 'started'::text) AND (finished_at IS NULL));


--
-- Name: firings_waiting; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX firings_waiting ON public.firings USING btree (target, arrived_at) WHERE (status = 'queued'::text);


--
-- Name: hosted_tool_scopes_owner_scope; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX hosted_tool_scopes_owner_scope ON public.hosted_tool_scopes USING btree (project_id, owner, scope) WHERE (NOT deleted);


--
-- Name: inference_attempts_call; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX inference_attempts_call ON public.inference_attempts USING btree (call_id);


--
-- Name: inference_calls_account_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX inference_calls_account_time ON public.inference_calls USING btree (account_handle, created_at);


--
-- Name: inference_calls_conversation_ancestry; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX inference_calls_conversation_ancestry ON public.inference_calls USING gin (ancestor_conversation_ids);


--
-- Name: inference_calls_conversation_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX inference_calls_conversation_time ON public.inference_calls USING btree (root_conversation_id, created_at);


--
-- Name: inference_calls_direct_conversation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX inference_calls_direct_conversation ON public.inference_calls USING btree (conversation_id);


--
-- Name: inference_calls_instance_open; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX inference_calls_instance_open ON public.inference_calls USING btree (instance_id) WHERE (ended_at IS NULL);


--
-- Name: inference_calls_model_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX inference_calls_model_time ON public.inference_calls USING btree (wire_model, created_at);


--
-- Name: inference_calls_orchestration_ancestry; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX inference_calls_orchestration_ancestry ON public.inference_calls USING gin (ancestor_orchestration_ids);


--
-- Name: inference_calls_project_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX inference_calls_project_time ON public.inference_calls USING btree (project_id, created_at);


--
-- Name: inference_calls_route_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX inference_calls_route_time ON public.inference_calls USING btree (billing_route, created_at);


--
-- Name: inference_calls_run_ancestry; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX inference_calls_run_ancestry ON public.inference_calls USING gin (ancestor_run_ids);


--
-- Name: inference_calls_run_time; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX inference_calls_run_time ON public.inference_calls USING btree (run_id, created_at);


--
-- Name: inference_preparation_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX inference_preparation_pending ON public.inference_admissions USING btree (preparation_revision, preparation_generation) WHERE ((preparation_revision IS NOT NULL) AND (NOT preparation_published));


--
-- Name: inference_query_batch; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX inference_query_batch ON public.inference_admissions USING btree (query_id, query_task_hash) WHERE (query_id IS NOT NULL);


--
-- Name: information_content_type_active; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX information_content_type_active ON public.information_content_type_versions USING btree (type_id) WHERE (state = 'active'::text);


--
-- Name: information_documents_by_owner; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX information_documents_by_owner ON public.information_document_policies USING btree (owner_handle, visibility);


--
-- Name: information_documents_by_project; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX information_documents_by_project ON public.information_document_policies USING btree (project_id, visibility);


--
-- Name: information_pending_steps; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX information_pending_steps ON public.information_steps USING btree (state, lease_until);


--
-- Name: information_processing_budget_identity; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX information_processing_budget_identity ON public.information_revisions USING btree (processing_budget_id);


--
-- Name: information_reference_obligations_source; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX information_reference_obligations_source ON public.information_reference_obligations USING btree (source);


--
-- Name: information_relationships_source; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX information_relationships_source ON public.information_relationships USING btree (source) WHERE (NOT removed);


--
-- Name: information_relationships_target; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX information_relationships_target ON public.information_relationships USING btree (target) WHERE (NOT removed);


--
-- Name: information_resources_home_name; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX information_resources_home_name ON public.information_resources USING btree (home_scope_id, source_name) WHERE (home_scope_id IS NOT NULL);


--
-- Name: information_resources_tags; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX information_resources_tags ON public.information_resources USING gin (tags);


--
-- Name: information_revisions_auto_tag; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX information_revisions_auto_tag ON public.information_revisions USING gin (auto_tag);


--
-- Name: information_revisions_by_document_type; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX information_revisions_by_document_type ON public.information_revisions USING btree (document_type, created_at, id);


--
-- Name: information_revisions_document_author; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX information_revisions_document_author ON public.information_revisions USING btree (document_author);


--
-- Name: information_steps_running_lease_window; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX information_steps_running_lease_window ON public.information_steps USING btree (lease_until) WHERE ((state = 'running'::text) AND (lease_until IS NOT NULL));


--
-- Name: information_tag_sweep_candidates; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX information_tag_sweep_candidates ON public.information_revisions USING btree (created_at, id) WHERE ((availability = 'active'::text) AND (NOT excluded) AND (NOT auto_tag_generated) AND (jsonb_array_length(auto_tag) = 0) AND (allowance_spent < allowance_total) AND (extracted_text IS NOT NULL) AND (btrim(extracted_text) <> ''::text));


--
-- Name: jobs_by_age; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX jobs_by_age ON public.jobs USING btree (started_at);


--
-- Name: memories_code_space; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX memories_code_space ON public.memories USING btree (code_space_id) WHERE (code_embedding IS NOT NULL);


--
-- Name: memories_home; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX memories_home ON public.memories USING btree (home_scope_id);


--
-- Name: memories_home_state; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX memories_home_state ON public.memories USING btree (project_id, state);


--
-- Name: memories_prose_space; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX memories_prose_space ON public.memories USING btree (prose_space_id) WHERE (prose_embedding IS NOT NULL);


--
-- Name: memory_reasons_memory; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX memory_reasons_memory ON public.memory_reasons USING btree (memory_id, filed_at);


--
-- Name: orchestration_acceptance_by_approval; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX orchestration_acceptance_by_approval ON public.orchestration_acceptance USING btree (approval) WHERE (approval IS NOT NULL);


--
-- Name: orchestration_messages_by_run; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX orchestration_messages_by_run ON public.orchestration_messages USING btree (orchestration, created_at);


--
-- Name: orchestration_messages_undelivered; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX orchestration_messages_undelivered ON public.orchestration_messages USING btree (created_at) WHERE (delivered_at IS NULL);


--
-- Name: orchestration_record_by_kind; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX orchestration_record_by_kind ON public.orchestration_record USING btree (root, kind, ordinal);


--
-- Name: orchestrations_by_caller_handle; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX orchestrations_by_caller_handle ON public.orchestrations USING btree (caller_handle, created_at DESC);


--
-- Name: orchestrations_live; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX orchestrations_live ON public.orchestrations USING btree (state) WHERE (ended_at IS NULL);


--
-- Name: orchestrations_live_by_caller_conversation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX orchestrations_live_by_caller_conversation ON public.orchestrations USING btree (caller_conversation) WHERE (ended_at IS NULL);


--
-- Name: orchestrations_live_children; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX orchestrations_live_children ON public.orchestrations USING btree (parent) WHERE (ended_at IS NULL);


--
-- Name: orchestrations_undelivered_results; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX orchestrations_undelivered_results ON public.orchestrations USING btree (ended_at) WHERE ((ended_at IS NOT NULL) AND (result_delivered_at IS NULL));


--
-- Name: outgoing_work_claim; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX outgoing_work_claim ON public.outgoing_work USING btree (account, state, lease_until);


--
-- Name: paragraphs_awaiting_a_summary; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX paragraphs_awaiting_a_summary ON public.paragraphs USING btree (document_id, ordinal) WHERE (summary IS NULL);


--
-- Name: paragraphs_in_section; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX paragraphs_in_section ON public.paragraphs USING btree (section_id, ordinal);


--
-- Name: paragraphs_matched_by; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX paragraphs_matched_by ON public.paragraphs USING btree (document_id, content_hash, occurrence);


--
-- Name: project_access_audit_project; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX project_access_audit_project ON public.project_access_audit USING btree (project_id, id DESC);


--
-- Name: proposals_memory; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX proposals_memory ON public.proposals USING btree (memory_id);


--
-- Name: proposals_one_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX proposals_one_pending ON public.proposals USING btree (memory_id, action) WHERE (state = 'pending'::text);


--
-- Name: provider_connections_project; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX provider_connections_project ON public.provider_connections USING btree (project_id, id);


--
-- Name: relay_admissions_payload_digest; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX relay_admissions_payload_digest ON public.relay_admissions USING btree (payload_digest);


--
-- Name: relay_admissions_retention; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX relay_admissions_retention ON public.relay_admissions USING btree (admitted_at);


--
-- Name: relay_approval_gap_recoveries_age; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX relay_approval_gap_recoveries_age ON public.relay_approval_gap_recoveries USING btree (recovered_at);


--
-- Name: relay_deliveries_expiring; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX relay_deliveries_expiring ON public.relay_deliveries USING btree (lease_until, id) WHERE (state = ANY (ARRAY['CLAIMED'::text, 'DISPATCHING'::text]));


--
-- Name: relay_deliveries_inspection; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX relay_deliveries_inspection ON public.relay_deliveries USING btree (scope_key, topic, publication_position DESC, branch_index, subscriber, id);


--
-- Name: relay_deliveries_ready; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX relay_deliveries_ready ON public.relay_deliveries USING btree (scope_key, topic, subscriber, publication_position, branch_index) WHERE (state = 'READY'::text);


--
-- Name: relay_native_consumers_target; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX relay_native_consumers_target ON public.relay_native_consumers USING btree (target);


--
-- Name: relay_native_gap_recoveries_age; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX relay_native_gap_recoveries_age ON public.relay_native_gap_recoveries USING btree (recovered_at);


--
-- Name: relay_operator_receipts_retention; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX relay_operator_receipts_retention ON public.relay_operator_receipts USING btree (completed_at);


--
-- Name: relay_payloads_ejection; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX relay_payloads_ejection ON public.relay_payloads USING btree (ejection_at);


--
-- Name: relay_publications_payload_digest; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX relay_publications_payload_digest ON public.relay_publications USING btree (payload_digest);


--
-- Name: relay_source_events_discovery; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX relay_source_events_discovery ON public.relay_source_events USING btree (COALESCE(('project:'::text || (project_id)::text), 'system'::text), topic, sequence);


--
-- Name: relay_source_events_partition; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX relay_source_events_partition ON public.relay_source_events USING btree (COALESCE(project_id, (0)::bigint), topic, sequence);


--
-- Name: relay_subscription_authority_account; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX relay_subscription_authority_account ON public.relay_subscription_authority USING btree (scope_key, account);


--
-- Name: relay_subscriptions_reader; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX relay_subscriptions_reader ON public.relay_subscriptions USING btree (scope_key, reader_account) WHERE (reader_account IS NOT NULL);


--
-- Name: relay_tool_invocations_owner; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX relay_tool_invocations_owner ON public.relay_tool_invocations USING btree (project_id, account, run_id);


--
-- Name: retrieval_passages_code_space; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX retrieval_passages_code_space ON public.retrieval_passages USING btree (code_space_id) WHERE (code_embedding IS NOT NULL);


--
-- Name: retrieval_passages_prose_space; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX retrieval_passages_prose_space ON public.retrieval_passages USING btree (prose_space_id) WHERE (prose_embedding IS NOT NULL);


--
-- Name: retrieval_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX retrieval_pending ON public.retrieval_sources USING btree (status, retry_at);


--
-- Name: run_approvals_awaiting_delivery; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX run_approvals_awaiting_delivery ON public.run_approvals USING btree (conversation) WHERE ((state = 'asked'::text) AND (delivered_at IS NULL));


--
-- Name: run_approvals_by_conversation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX run_approvals_by_conversation ON public.run_approvals USING btree (conversation, state);


--
-- Name: run_approvals_by_project; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX run_approvals_by_project ON public.run_approvals USING btree (project_id, side, state);


--
-- Name: run_approvals_project_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX run_approvals_project_pending ON public.run_approvals USING btree (project_id, id) WHERE (state = 'asked'::text);


--
-- Name: schedule_files_source_internal; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX schedule_files_source_internal ON public.schedule_files USING btree (source_id, internal_name);


--
-- Name: schedule_sources_global; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX schedule_sources_global ON public.schedule_sources USING btree (source) WHERE (project_id IS NULL);


--
-- Name: schedule_sources_owner_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX schedule_sources_owner_id ON public.schedule_sources USING btree (account, id);


--
-- Name: schedule_sources_project; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX schedule_sources_project ON public.schedule_sources USING btree (project_id, source) WHERE (project_id IS NOT NULL);


--
-- Name: schedules_due; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX schedules_due ON public.schedules USING btree (next_fire_at) WHERE (NOT paused);


--
-- Name: schedules_owner_name; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX schedules_owner_name ON public.schedules USING btree (defined_by, name);


--
-- Name: search_result_sets_by_fetched_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX search_result_sets_by_fetched_at ON public.search_result_sets USING btree (fetched_at);


--
-- Name: service_tokens_cache_expiry; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX service_tokens_cache_expiry ON public.service_tokens USING btree (expires_at) WHERE (expires_at IS NOT NULL);


--
-- Name: skill_executions_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX skill_executions_active ON public.skill_executions USING btree (conversation, created_at) WHERE (state = ANY (ARRAY['running'::text, 'awaiting'::text]));


--
-- Name: snapshot_cache_writers_retention; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX snapshot_cache_writers_retention ON public.snapshot_cache_writers USING btree (xid);


--
-- Name: suspended_run_calls_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX suspended_run_calls_pending ON public.suspended_run_calls USING btree (conversation, sequence) WHERE (acknowledged_at IS NULL);


--
-- Name: todos_by_conversation; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX todos_by_conversation ON public.todos USING btree (conversation, parent, "position");


--
-- Name: triggers_by_event; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX triggers_by_event ON public.triggers USING btree (event) WHERE (NOT paused);


--
-- Name: union_conflicts_open; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX union_conflicts_open ON public.union_conflicts USING btree (project_id) WHERE (resolved_at IS NULL);


--
-- Name: user_inbox_open_questions; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX user_inbox_open_questions ON public.user_inbox USING btree (about) WHERE ((about IS NOT NULL) AND (settled_at IS NULL));


--
-- Name: user_inbox_pending_approval_alert; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX user_inbox_pending_approval_alert ON public.user_inbox USING btree (about, handle, question_alert_attempted_at) WHERE ((kind = 'approval'::text) AND (read_at IS NULL) AND (settled_at IS NULL));


--
-- Name: user_inbox_unread; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX user_inbox_unread ON public.user_inbox USING btree (handle, arrived_at) WHERE ((read_at IS NULL) AND (settled_at IS NULL));


--
-- Name: documents 000_snapshot_cache_knowledge_born; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER "000_snapshot_cache_knowledge_born" AFTER INSERT ON public.documents FOR EACH ROW EXECUTE FUNCTION public.snapshot_cache_knowledge_born();


--
-- Name: information_resources 000_snapshot_cache_knowledge_born; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER "000_snapshot_cache_knowledge_born" AFTER INSERT ON public.information_resources FOR EACH ROW EXECUTE FUNCTION public.snapshot_cache_knowledge_born();


--
-- Name: information_revisions 000_snapshot_cache_knowledge_born; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER "000_snapshot_cache_knowledge_born" AFTER INSERT ON public.information_revisions FOR EACH ROW EXECUTE FUNCTION public.snapshot_cache_knowledge_born();


--
-- Name: board_topics attach_execution_allowance; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER attach_execution_allowance BEFORE INSERT ON public.board_topics FOR EACH ROW EXECUTE FUNCTION public.attach_execution_allowance();


--
-- Name: conversations attach_execution_allowance; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER attach_execution_allowance BEFORE INSERT ON public.conversations FOR EACH ROW EXECUTE FUNCTION public.attach_execution_allowance();


--
-- Name: embedding_queries attach_execution_allowance; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER attach_execution_allowance BEFORE INSERT ON public.embedding_queries FOR EACH ROW EXECUTE FUNCTION public.attach_execution_allowance();


--
-- Name: information_revisions attach_execution_allowance; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER attach_execution_allowance BEFORE INSERT ON public.information_revisions FOR EACH ROW EXECUTE FUNCTION public.attach_execution_allowance();


--
-- Name: admins authority_account_lifecycle; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER authority_account_lifecycle BEFORE INSERT OR DELETE OR UPDATE ON public.admins FOR EACH ROW EXECUTE FUNCTION public.authority_account_lifecycle();


--
-- Name: authority_accounts authority_account_mapping_locked; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER authority_account_mapping_locked BEFORE DELETE OR UPDATE ON public.authority_accounts FOR EACH ROW EXECUTE FUNCTION public.authority_account_mapping_locked();


--
-- Name: authority_grants authority_audit_grant; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER authority_audit_grant AFTER INSERT OR UPDATE ON public.authority_grants FOR EACH ROW EXECUTE FUNCTION public.authority_audit_grant();


--
-- Name: authority_grant_audit authority_grant_audit_locked; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER authority_grant_audit_locked BEFORE DELETE OR UPDATE ON public.authority_grant_audit FOR EACH ROW EXECUTE FUNCTION public.authority_role_locked();


--
-- Name: authority_grants authority_grant_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER authority_grant_insert BEFORE INSERT ON public.authority_grants FOR EACH ROW EXECUTE FUNCTION public.authority_grant_insert();


--
-- Name: authority_grants authority_grant_mutation; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER authority_grant_mutation BEFORE DELETE OR UPDATE ON public.authority_grants FOR EACH ROW EXECUTE FUNCTION public.authority_grant_mutation();


--
-- Name: admins authority_map_account; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER authority_map_account AFTER INSERT ON public.admins FOR EACH ROW EXECUTE FUNCTION public.authority_map_account();


--
-- Name: project_members authority_membership_changed; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER authority_membership_changed AFTER INSERT OR DELETE OR UPDATE ON public.project_members FOR EACH ROW EXECUTE FUNCTION public.authority_membership_changed();


--
-- Name: acting_tokens authority_no_builtin_acting; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER authority_no_builtin_acting BEFORE INSERT OR UPDATE ON public.acting_tokens FOR EACH ROW EXECUTE FUNCTION public.authority_no_builtin_acting();


--
-- Name: auth_session_chains authority_no_builtin_session; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER authority_no_builtin_session BEFORE INSERT OR UPDATE ON public.auth_session_chains FOR EACH ROW EXECUTE FUNCTION public.authority_no_builtin_session();


--
-- Name: service_tokens authority_no_builtin_token; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER authority_no_builtin_token BEFORE INSERT OR UPDATE ON public.service_tokens FOR EACH ROW EXECUTE FUNCTION public.authority_no_builtin_token();


--
-- Name: projects authority_project_inserted; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER authority_project_inserted AFTER INSERT OR UPDATE ON public.projects FOR EACH ROW EXECUTE FUNCTION public.authority_project_inserted();


--
-- Name: projects authority_project_lifecycle; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER authority_project_lifecycle BEFORE INSERT OR UPDATE ON public.projects FOR EACH ROW EXECUTE FUNCTION public.authority_project_lifecycle();


--
-- Name: authority_role_actions authority_role_actions_locked; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER authority_role_actions_locked BEFORE DELETE OR UPDATE ON public.authority_role_actions FOR EACH ROW EXECUTE FUNCTION public.authority_role_locked();


--
-- Name: authority_roles authority_role_locked; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER authority_role_locked BEFORE DELETE OR UPDATE ON public.authority_roles FOR EACH ROW EXECUTE FUNCTION public.authority_role_locked();


--
-- Name: authority_scopes authority_scope_classified; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER authority_scope_classified AFTER UPDATE ON public.authority_scopes FOR EACH ROW EXECUTE FUNCTION public.authority_scope_classified();


--
-- Name: authority_scopes authority_scope_container_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER authority_scope_container_immutable BEFORE UPDATE ON public.authority_scopes FOR EACH ROW EXECUTE FUNCTION public.authority_scope_container_immutable();


--
-- Name: authority_scopes authority_scope_locked; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER authority_scope_locked BEFORE DELETE OR UPDATE ON public.authority_scopes FOR EACH ROW EXECUTE FUNCTION public.authority_scope_locked();


--
-- Name: authority_scopes authority_scope_shape; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER authority_scope_shape BEFORE INSERT OR UPDATE ON public.authority_scopes FOR EACH ROW EXECUTE FUNCTION public.authority_scope_shape();


--
-- Name: admins authority_standard_role_locator; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER authority_standard_role_locator BEFORE INSERT OR UPDATE ON public.admins FOR EACH ROW EXECUTE FUNCTION public.authority_standard_role_locator();


--
-- Name: admins authority_z_admin_changed; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER authority_z_admin_changed AFTER INSERT OR UPDATE ON public.admins FOR EACH ROW EXECUTE FUNCTION public.authority_admin_changed();


--
-- Name: contained_applications contained_application_agreement; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER contained_application_agreement BEFORE INSERT OR UPDATE OF scope_id, container, project_id ON public.contained_applications FOR EACH ROW EXECUTE FUNCTION public.contained_application_agrees();


--
-- Name: conversations conversation_allowance_identity_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER conversation_allowance_identity_immutable BEFORE UPDATE ON public.conversations FOR EACH ROW EXECUTE FUNCTION public.conversation_allowance_identity_immutable();


--
-- Name: digest_children digest_edge; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER digest_edge BEFORE INSERT OR UPDATE ON public.digest_children FOR EACH ROW EXECUTE FUNCTION public.digest_validate_edge();


--
-- Name: entries digest_fold; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER digest_fold AFTER INSERT ON public.entries FOR EACH ROW EXECUTE FUNCTION public.digest_fold_written();


--
-- Name: memories digest_memory; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER digest_memory AFTER INSERT OR UPDATE ON public.memories FOR EACH ROW EXECUTE FUNCTION public.digest_memory_changed();


--
-- Name: chunks embedding_record_guard; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER embedding_record_guard BEFORE INSERT OR UPDATE ON public.chunks FOR EACH ROW EXECUTE FUNCTION public.embedding_record_guard();


--
-- Name: digests embedding_record_guard; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER embedding_record_guard BEFORE INSERT OR UPDATE ON public.digests FOR EACH ROW EXECUTE FUNCTION public.embedding_record_guard();


--
-- Name: documents embedding_record_guard; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER embedding_record_guard BEFORE INSERT OR UPDATE ON public.documents FOR EACH ROW EXECUTE FUNCTION public.embedding_record_guard();


--
-- Name: memories embedding_record_guard; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER embedding_record_guard BEFORE INSERT OR UPDATE ON public.memories FOR EACH ROW EXECUTE FUNCTION public.embedding_record_guard();


--
-- Name: retrieval_passages embedding_record_guard; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER embedding_record_guard BEFORE INSERT OR UPDATE ON public.retrieval_passages FOR EACH ROW EXECUTE FUNCTION public.embedding_record_guard();


--
-- Name: embedding_slots embedding_repair_profiles; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER embedding_repair_profiles AFTER UPDATE ON public.embedding_slots FOR EACH ROW EXECUTE FUNCTION public.embedding_repair_profiles();


--
-- Name: chunks_embedding_failures embedding_repair_progress; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER embedding_repair_progress AFTER INSERT OR DELETE OR UPDATE ON public.chunks_embedding_failures FOR EACH ROW EXECUTE FUNCTION public.embedding_repair_progress();


--
-- Name: chunks_embedding_staging embedding_repair_progress; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER embedding_repair_progress AFTER INSERT OR UPDATE ON public.chunks_embedding_staging FOR EACH ROW EXECUTE FUNCTION public.embedding_repair_progress();


--
-- Name: digests_embedding_failures embedding_repair_progress; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER embedding_repair_progress AFTER INSERT OR DELETE OR UPDATE ON public.digests_embedding_failures FOR EACH ROW EXECUTE FUNCTION public.embedding_repair_progress();


--
-- Name: digests_embedding_staging embedding_repair_progress; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER embedding_repair_progress AFTER INSERT OR UPDATE ON public.digests_embedding_staging FOR EACH ROW EXECUTE FUNCTION public.embedding_repair_progress();


--
-- Name: documents_embedding_failures embedding_repair_progress; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER embedding_repair_progress AFTER INSERT OR DELETE OR UPDATE ON public.documents_embedding_failures FOR EACH ROW EXECUTE FUNCTION public.embedding_repair_progress();


--
-- Name: documents_embedding_staging embedding_repair_progress; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER embedding_repair_progress AFTER INSERT OR UPDATE ON public.documents_embedding_staging FOR EACH ROW EXECUTE FUNCTION public.embedding_repair_progress();


--
-- Name: memories_embedding_failures embedding_repair_progress; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER embedding_repair_progress AFTER INSERT OR DELETE OR UPDATE ON public.memories_embedding_failures FOR EACH ROW EXECUTE FUNCTION public.embedding_repair_progress();


--
-- Name: memories_embedding_staging embedding_repair_progress; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER embedding_repair_progress AFTER INSERT OR UPDATE ON public.memories_embedding_staging FOR EACH ROW EXECUTE FUNCTION public.embedding_repair_progress();


--
-- Name: retrieval_passages_embedding_failures embedding_repair_progress; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER embedding_repair_progress AFTER INSERT OR DELETE OR UPDATE ON public.retrieval_passages_embedding_failures FOR EACH ROW EXECUTE FUNCTION public.embedding_repair_progress();


--
-- Name: retrieval_passages_embedding_staging embedding_repair_progress; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER embedding_repair_progress AFTER INSERT OR UPDATE ON public.retrieval_passages_embedding_staging FOR EACH ROW EXECUTE FUNCTION public.embedding_repair_progress();


--
-- Name: chunks embedding_repair_source; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER embedding_repair_source AFTER INSERT OR DELETE OR UPDATE ON public.chunks FOR EACH ROW EXECUTE FUNCTION public.embedding_repair_source_changed();


--
-- Name: digests embedding_repair_source; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER embedding_repair_source AFTER INSERT OR DELETE OR UPDATE ON public.digests FOR EACH ROW EXECUTE FUNCTION public.embedding_repair_source_changed();


--
-- Name: documents embedding_repair_source; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER embedding_repair_source AFTER INSERT OR DELETE OR UPDATE ON public.documents FOR EACH ROW EXECUTE FUNCTION public.embedding_repair_source_changed();


--
-- Name: memories embedding_repair_source; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER embedding_repair_source AFTER INSERT OR DELETE OR UPDATE ON public.memories FOR EACH ROW EXECUTE FUNCTION public.embedding_repair_source_changed();


--
-- Name: retrieval_passages embedding_repair_source; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER embedding_repair_source AFTER INSERT OR DELETE OR UPDATE ON public.retrieval_passages FOR EACH ROW EXECUTE FUNCTION public.embedding_repair_source_changed();


--
-- Name: chunks embedding_repair_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER embedding_repair_truncate AFTER TRUNCATE ON public.chunks FOR EACH STATEMENT EXECUTE FUNCTION public.embedding_repair_source_changed();


--
-- Name: digests embedding_repair_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER embedding_repair_truncate AFTER TRUNCATE ON public.digests FOR EACH STATEMENT EXECUTE FUNCTION public.embedding_repair_source_changed();


--
-- Name: documents embedding_repair_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER embedding_repair_truncate AFTER TRUNCATE ON public.documents FOR EACH STATEMENT EXECUTE FUNCTION public.embedding_repair_source_changed();


--
-- Name: memories embedding_repair_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER embedding_repair_truncate AFTER TRUNCATE ON public.memories FOR EACH STATEMENT EXECUTE FUNCTION public.embedding_repair_source_changed();


--
-- Name: retrieval_passages embedding_repair_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER embedding_repair_truncate AFTER TRUNCATE ON public.retrieval_passages FOR EACH STATEMENT EXECUTE FUNCTION public.embedding_repair_source_changed();


--
-- Name: embedding_slots embedding_slot_no_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER embedding_slot_no_truncate BEFORE TRUNCATE ON public.embedding_slots FOR EACH STATEMENT EXECUTE FUNCTION public.embedding_space_immutable();


--
-- Name: embedding_slots embedding_slot_version; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER embedding_slot_version BEFORE DELETE OR UPDATE ON public.embedding_slots FOR EACH ROW EXECUTE FUNCTION public.embedding_slot_version();


--
-- Name: embedding_spaces embedding_space_identity; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER embedding_space_identity BEFORE INSERT ON public.embedding_spaces FOR EACH ROW EXECUTE FUNCTION public.embedding_space_identity();


--
-- Name: embedding_spaces embedding_space_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER embedding_space_immutable BEFORE DELETE OR UPDATE ON public.embedding_spaces FOR EACH ROW EXECUTE FUNCTION public.embedding_space_immutable();


--
-- Name: embedding_spaces embedding_space_no_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER embedding_space_no_truncate BEFORE TRUNCATE ON public.embedding_spaces FOR EACH STATEMENT EXECUTE FUNCTION public.embedding_space_immutable();


--
-- Name: chunks_embedding_staging embedding_staging_guard; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER embedding_staging_guard BEFORE INSERT OR UPDATE ON public.chunks_embedding_staging FOR EACH ROW EXECUTE FUNCTION public.embedding_staging_guard();


--
-- Name: digests_embedding_staging embedding_staging_guard; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER embedding_staging_guard BEFORE INSERT OR UPDATE ON public.digests_embedding_staging FOR EACH ROW EXECUTE FUNCTION public.embedding_staging_guard();


--
-- Name: documents_embedding_staging embedding_staging_guard; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER embedding_staging_guard BEFORE INSERT OR UPDATE ON public.documents_embedding_staging FOR EACH ROW EXECUTE FUNCTION public.embedding_staging_guard();


--
-- Name: memories_embedding_staging embedding_staging_guard; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER embedding_staging_guard BEFORE INSERT OR UPDATE ON public.memories_embedding_staging FOR EACH ROW EXECUTE FUNCTION public.embedding_staging_guard();


--
-- Name: retrieval_passages_embedding_staging embedding_staging_guard; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER embedding_staging_guard BEFORE INSERT OR UPDATE ON public.retrieval_passages_embedding_staging FOR EACH ROW EXECUTE FUNCTION public.embedding_staging_guard();


--
-- Name: execution_allowance_claims execution_allowance_claim_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER execution_allowance_claim_immutable BEFORE DELETE OR UPDATE ON public.execution_allowance_claims FOR EACH ROW EXECUTE FUNCTION public.operation_lineage_immutable();


--
-- Name: execution_allowances execution_allowance_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER execution_allowance_immutable BEFORE DELETE OR UPDATE ON public.execution_allowances FOR EACH ROW EXECUTE FUNCTION public.execution_allowance_immutable();


--
-- Name: board_topics historical_allowance_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER historical_allowance_immutable AFTER UPDATE ON public.board_topics FOR EACH ROW EXECUTE FUNCTION public.historical_allowance_immutable();


--
-- Name: conversations historical_allowance_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER historical_allowance_immutable AFTER UPDATE ON public.conversations FOR EACH ROW EXECUTE FUNCTION public.historical_allowance_immutable();


--
-- Name: embedding_queries historical_allowance_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER historical_allowance_immutable AFTER UPDATE ON public.embedding_queries FOR EACH ROW EXECUTE FUNCTION public.historical_allowance_immutable();


--
-- Name: information_revisions historical_allowance_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER historical_allowance_immutable AFTER UPDATE ON public.information_revisions FOR EACH ROW EXECUTE FUNCTION public.historical_allowance_immutable();


--
-- Name: inference_admissions inference_admission_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER inference_admission_immutable BEFORE DELETE OR UPDATE ON public.inference_admissions FOR EACH ROW EXECUTE FUNCTION public.protect_inference_admission();


--
-- Name: information_document_policies information_audience_write_fence; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER information_audience_write_fence BEFORE UPDATE ON public.information_document_policies FOR EACH ROW EXECUTE FUNCTION public.information_audience_write_fence();


--
-- Name: information_content_types information_content_type_identity_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER information_content_type_identity_immutable BEFORE UPDATE ON public.information_content_types FOR EACH ROW EXECUTE FUNCTION public.information_content_type_identity_immutable();


--
-- Name: information_content_type_versions information_content_type_version_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER information_content_type_version_immutable BEFORE UPDATE ON public.information_content_type_versions FOR EACH ROW EXECUTE FUNCTION public.information_content_type_version_immutable();


--
-- Name: information_revisions information_document_type_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER information_document_type_immutable BEFORE UPDATE ON public.information_revisions FOR EACH ROW EXECUTE FUNCTION public.information_document_type_immutable();


--
-- Name: information_evidence information_evidence_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER information_evidence_immutable BEFORE UPDATE ON public.information_evidence FOR EACH ROW EXECUTE FUNCTION public.information_immutable_evidence();


--
-- Name: information_import_origins information_import_origin_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER information_import_origin_immutable BEFORE DELETE OR UPDATE ON public.information_import_origins FOR EACH ROW EXECUTE FUNCTION public.information_import_origin_immutable();


--
-- Name: information_revisions information_import_origin_redact; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER information_import_origin_redact AFTER UPDATE OF availability ON public.information_revisions FOR EACH ROW EXECUTE FUNCTION public.information_import_origin_redact();


--
-- Name: information_resources information_initial_root_policy; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER information_initial_root_policy AFTER INSERT ON public.information_resources FOR EACH ROW EXECUTE FUNCTION public.information_initial_root_policy();


--
-- Name: information_inputs information_input_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER information_input_immutable BEFORE DELETE OR UPDATE ON public.information_inputs FOR EACH ROW EXECUTE FUNCTION public.information_input_immutable();


--
-- Name: information_navigation_policies information_policy_obligations; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER information_policy_obligations AFTER INSERT OR UPDATE ON public.information_navigation_policies FOR EACH ROW EXECUTE FUNCTION public.information_navigation_obligations();


--
-- Name: information_revisions information_processing_budget_locked; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER information_processing_budget_locked BEFORE UPDATE ON public.information_revisions FOR EACH ROW EXECUTE FUNCTION public.information_processing_budget_locked();


--
-- Name: information_projection_reuse information_projection_reuse_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER information_projection_reuse_immutable BEFORE DELETE OR UPDATE ON public.information_projection_reuse FOR EACH ROW EXECUTE FUNCTION public.information_processing_provenance_immutable();


--
-- Name: information_relationships information_relationship_obligations; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER information_relationship_obligations AFTER INSERT OR UPDATE ON public.information_relationships FOR EACH ROW EXECUTE FUNCTION public.information_navigation_obligations();


--
-- Name: information_reports information_report_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER information_report_immutable BEFORE UPDATE ON public.information_reports FOR EACH ROW EXECUTE FUNCTION public.information_report_immutable();


--
-- Name: information_resources information_resource_identity; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER information_resource_identity BEFORE INSERT OR UPDATE ON public.information_resources FOR EACH ROW EXECUTE FUNCTION public.information_knowledge_identity();


--
-- Name: information_resource_policy_history information_resource_policy_history_guard; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER information_resource_policy_history_guard BEFORE DELETE OR UPDATE ON public.information_resource_policy_history FOR EACH ROW EXECUTE FUNCTION public.information_resource_policy_history_guard();


--
-- Name: information_resource_policies information_resource_policy_record; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER information_resource_policy_record BEFORE INSERT OR DELETE OR UPDATE ON public.information_resource_policies FOR EACH ROW EXECUTE FUNCTION public.information_resource_policy_record();


--
-- Name: information_reuse_preparations information_reuse_preparation_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER information_reuse_preparation_immutable BEFORE DELETE OR UPDATE ON public.information_reuse_preparations FOR EACH ROW EXECUTE FUNCTION public.information_reuse_preparation_immutable();


--
-- Name: information_revision_content information_revision_content_contract; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER information_revision_content_contract BEFORE INSERT ON public.information_revision_content FOR EACH ROW EXECUTE FUNCTION public.information_revision_content_contract();


--
-- Name: information_revision_content information_revision_content_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER information_revision_content_immutable BEFORE DELETE OR UPDATE ON public.information_revision_content FOR EACH ROW EXECUTE FUNCTION public.information_revision_content_immutable();


--
-- Name: information_revisions information_revision_creator; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER information_revision_creator BEFORE UPDATE ON public.information_revisions FOR EACH ROW EXECUTE FUNCTION public.information_knowledge_identity();


--
-- Name: information_revisions information_revision_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER information_revision_immutable BEFORE UPDATE ON public.information_revisions FOR EACH ROW EXECUTE FUNCTION public.information_revision_immutable();


--
-- Name: information_revisions information_revision_lineage_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER information_revision_lineage_immutable BEFORE UPDATE ON public.information_revisions FOR EACH ROW EXECUTE FUNCTION public.information_revision_lineage_immutable();


--
-- Name: information_stage_authorizations information_stage_authorizations_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER information_stage_authorizations_immutable BEFORE DELETE OR UPDATE ON public.information_stage_authorizations FOR EACH ROW EXECUTE FUNCTION public.information_processing_provenance_immutable();


--
-- Name: information_resources information_tags_version; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER information_tags_version BEFORE UPDATE ON public.information_resources FOR EACH ROW EXECUTE FUNCTION public.information_advance_tags_version();


--
-- Name: invocation_image_admissions invocation_blob_claim_release; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER invocation_blob_claim_release AFTER DELETE ON public.invocation_image_admissions FOR EACH ROW EXECUTE FUNCTION public.release_invocation_blob_claim();


--
-- Name: memories memory_knowledge_home; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER memory_knowledge_home BEFORE INSERT OR UPDATE ON public.memories FOR EACH ROW EXECUTE FUNCTION public.memory_knowledge_home();


--
-- Name: operation_admissions operation_admission_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER operation_admission_immutable BEFORE DELETE OR UPDATE ON public.operation_admissions FOR EACH ROW EXECUTE FUNCTION public.operation_admission_immutable();


--
-- Name: operation_conversations operation_conversation_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER operation_conversation_immutable BEFORE DELETE OR UPDATE ON public.operation_conversations FOR EACH ROW EXECUTE FUNCTION public.operation_lineage_immutable();


--
-- Name: operation_conversations operation_conversation_valid; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER operation_conversation_valid BEFORE INSERT ON public.operation_conversations FOR EACH ROW EXECUTE FUNCTION public.operation_conversation_valid();


--
-- Name: operation_admission_parents operation_global_read_parent_valid; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER operation_global_read_parent_valid BEFORE INSERT ON public.operation_admission_parents FOR EACH ROW EXECUTE FUNCTION public.operation_global_read_parent_valid();


--
-- Name: operation_inference_budgets operation_inference_budget_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER operation_inference_budget_immutable BEFORE DELETE OR UPDATE ON public.operation_inference_budgets FOR EACH ROW EXECUTE FUNCTION public.protect_operation_inference_budget();


--
-- Name: operation_admission_parents operation_parent_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER operation_parent_immutable BEFORE DELETE OR UPDATE ON public.operation_admission_parents FOR EACH ROW EXECUTE FUNCTION public.operation_lineage_immutable();


--
-- Name: operation_admission_parents operation_parent_valid; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER operation_parent_valid BEFORE INSERT ON public.operation_admission_parents FOR EACH ROW EXECUTE FUNCTION public.operation_parent_valid();


--
-- Name: operation_admission_support operation_support_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER operation_support_immutable BEFORE INSERT OR DELETE OR UPDATE ON public.operation_admission_support FOR EACH ROW EXECUTE FUNCTION public.operation_admission_immutable();


--
-- Name: information_revisions processing_log_allowance; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER processing_log_allowance AFTER UPDATE OF processing_log ON public.information_revisions FOR EACH ROW EXECUTE FUNCTION public.processing_log_allowance();


--
-- Name: projects project_host_withdrawal; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER project_host_withdrawal BEFORE DELETE ON public.projects FOR EACH ROW EXECUTE FUNCTION public.withdraw_deleted_project_tool_hosts();


--
-- Name: projects project_provider_grant_revocation; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER project_provider_grant_revocation BEFORE DELETE ON public.projects FOR EACH ROW EXECUTE FUNCTION public.revoke_deleted_project_provider_grants();


--
-- Name: embedding_queries protect_embedding_query; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER protect_embedding_query BEFORE DELETE OR UPDATE ON public.embedding_queries FOR EACH ROW EXECUTE FUNCTION public.protect_embedding_query();


--
-- Name: inference_admissions protect_preparation_claim; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER protect_preparation_claim BEFORE UPDATE ON public.inference_admissions FOR EACH ROW EXECUTE FUNCTION public.protect_preparation_claim();


--
-- Name: inference_admissions protect_query_claim; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER protect_query_claim BEFORE UPDATE ON public.inference_admissions FOR EACH ROW EXECUTE FUNCTION public.protect_query_claim();


--
-- Name: run_approvals relay_approval_changes; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER relay_approval_changes AFTER INSERT OR UPDATE OF state ON public.run_approvals FOR EACH ROW EXECUTE FUNCTION public.relay_capture_approval();


--
-- Name: relay_blob_publications relay_blob_claim_release; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER relay_blob_claim_release AFTER DELETE ON public.relay_blob_publications FOR EACH ROW EXECUTE FUNCTION public.release_relay_blob_claim();


--
-- Name: board_messages relay_board_message_changes; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER relay_board_message_changes AFTER INSERT ON public.board_messages FOR EACH ROW EXECUTE FUNCTION public.relay_capture_board_message();


--
-- Name: board_topics relay_board_topic_changes; Type: TRIGGER; Schema: public; Owner: -
--

CREATE CONSTRAINT TRIGGER relay_board_topic_changes AFTER INSERT OR UPDATE ON public.board_topics DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.relay_capture_board_topic();


--
-- Name: conversations relay_conversation_causation; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER relay_conversation_causation BEFORE INSERT ON public.conversations FOR EACH ROW EXECUTE FUNCTION public.relay_inherit_conversation();


--
-- Name: firings relay_firing_available; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER relay_firing_available AFTER INSERT OR UPDATE ON public.firings FOR EACH ROW EXECUTE FUNCTION public.relay_firing_wakeup();


--
-- Name: jobs relay_job_causation; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER relay_job_causation BEFORE INSERT ON public.jobs FOR EACH ROW EXECUTE FUNCTION public.relay_inherit_job();


--
-- Name: jobs relay_job_changes; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER relay_job_changes AFTER INSERT OR UPDATE OF ending, ended_at ON public.jobs FOR EACH ROW EXECUTE FUNCTION public.relay_capture_job();


--
-- Name: board_message_instances relay_message_instance_changes; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER relay_message_instance_changes AFTER INSERT OR UPDATE OF active, is_default ON public.board_message_instances FOR EACH ROW EXECUTE FUNCTION public.relay_capture_message_instance();


--
-- Name: board_message_routes relay_message_route_changes; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER relay_message_route_changes AFTER INSERT OR UPDATE OF handled_at ON public.board_message_routes FOR EACH ROW EXECUTE FUNCTION public.relay_capture_message_route();


--
-- Name: relay_native_consumers relay_native_available; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER relay_native_available AFTER INSERT ON public.relay_native_consumers REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.relay_work_available();


--
-- Name: orchestrations relay_orchestration_causation; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER relay_orchestration_causation BEFORE INSERT ON public.orchestrations FOR EACH ROW EXECUTE FUNCTION public.relay_inherit_orchestration();


--
-- Name: orchestrations relay_orchestration_changes; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER relay_orchestration_changes AFTER INSERT OR UPDATE OF state ON public.orchestrations FOR EACH ROW EXECUTE FUNCTION public.relay_capture_orchestration();


--
-- Name: relay_payloads relay_payloads_validate_bytes; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER relay_payloads_validate_bytes BEFORE INSERT OR UPDATE OF payload ON public.relay_payloads FOR EACH ROW EXECUTE FUNCTION public.relay_payload_validate_bytes();


--
-- Name: orchestration_messages relay_question_changes; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER relay_question_changes AFTER INSERT ON public.orchestration_messages FOR EACH ROW EXECUTE FUNCTION public.relay_capture_question();


--
-- Name: relay_source_events relay_source_available; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER relay_source_available AFTER INSERT ON public.relay_source_events REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.relay_work_available();


--
-- Name: relay_source_events relay_source_causation; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER relay_source_causation BEFORE INSERT ON public.relay_source_events FOR EACH ROW EXECUTE FUNCTION public.relay_inherit_source();


--
-- Name: project_members relay_subscription_authority_member_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER relay_subscription_authority_member_delete AFTER DELETE ON public.project_members REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.relay_subscription_authority_revoked();


--
-- Name: project_members relay_subscription_authority_member_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER relay_subscription_authority_member_insert AFTER INSERT ON public.project_members REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.relay_subscription_authority_revoked();


--
-- Name: project_members relay_subscription_authority_member_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER relay_subscription_authority_member_update AFTER UPDATE ON public.project_members REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.relay_subscription_authority_revoked();


--
-- Name: firings relay_wake_changes; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER relay_wake_changes AFTER INSERT ON public.firings FOR EACH ROW WHEN (((new.topic IS NOT NULL) AND (new.status = 'queued'::text))) EXECUTE FUNCTION public.relay_capture_wake();


--
-- Name: digests retrieval_digest; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER retrieval_digest AFTER INSERT OR DELETE OR UPDATE ON public.digests FOR EACH ROW EXECUTE FUNCTION public.retrieval_digest_changed();


--
-- Name: digests retrieval_digests_truncated; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER retrieval_digests_truncated AFTER TRUNCATE ON public.digests FOR EACH STATEMENT EXECUTE FUNCTION public.retrieval_sources_truncated();


--
-- Name: entries retrieval_entries_truncated; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER retrieval_entries_truncated AFTER TRUNCATE ON public.entries FOR EACH STATEMENT EXECUTE FUNCTION public.retrieval_sources_truncated();


--
-- Name: entries retrieval_entry; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER retrieval_entry AFTER INSERT OR DELETE OR UPDATE ON public.entries FOR EACH ROW EXECUTE FUNCTION public.retrieval_entry_changed();


--
-- Name: conversations shared_orchestration_source; Type: TRIGGER; Schema: public; Owner: -
--

CREATE CONSTRAINT TRIGGER shared_orchestration_source AFTER INSERT OR UPDATE ON public.conversations DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.require_shared_orchestration_source();


--
-- Name: admins snapshot_cache_changed_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_delete AFTER DELETE ON public.admins REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('authority');


--
-- Name: authority_accounts snapshot_cache_changed_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_delete AFTER DELETE ON public.authority_accounts REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('authority');


--
-- Name: authority_grants snapshot_cache_changed_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_delete AFTER DELETE ON public.authority_grants REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('authority');


--
-- Name: authority_role_actions snapshot_cache_changed_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_delete AFTER DELETE ON public.authority_role_actions REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('authority');


--
-- Name: authority_roles snapshot_cache_changed_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_delete AFTER DELETE ON public.authority_roles REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('authority');


--
-- Name: authority_scopes snapshot_cache_changed_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_delete AFTER DELETE ON public.authority_scopes REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('authority');


--
-- Name: chunks snapshot_cache_changed_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_delete AFTER DELETE ON public.chunks REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('search');


--
-- Name: chunks_embedding_staging snapshot_cache_changed_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_delete AFTER DELETE ON public.chunks_embedding_staging REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('search');


--
-- Name: documents snapshot_cache_changed_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_delete AFTER DELETE ON public.documents REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('search');


--
-- Name: documents_embedding_staging snapshot_cache_changed_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_delete AFTER DELETE ON public.documents_embedding_staging REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('search');


--
-- Name: embedding_slots snapshot_cache_changed_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_delete AFTER DELETE ON public.embedding_slots REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('search');


--
-- Name: embedding_spaces snapshot_cache_changed_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_delete AFTER DELETE ON public.embedding_spaces REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('search');


--
-- Name: execution_allowances snapshot_cache_changed_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_delete AFTER DELETE ON public.execution_allowances REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('progress');


--
-- Name: information_content_type_versions snapshot_cache_changed_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_delete AFTER DELETE ON public.information_content_type_versions REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('documents', 'search');


--
-- Name: information_content_types snapshot_cache_changed_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_delete AFTER DELETE ON public.information_content_types REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('documents');


--
-- Name: information_inputs snapshot_cache_changed_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_delete AFTER DELETE ON public.information_inputs REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('documents', 'search');


--
-- Name: information_links snapshot_cache_changed_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_delete AFTER DELETE ON public.information_links REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('documents', 'search');


--
-- Name: information_navigation_policies snapshot_cache_changed_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_delete AFTER DELETE ON public.information_navigation_policies REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('documents', 'search');


--
-- Name: information_reference_obligations snapshot_cache_changed_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_delete AFTER DELETE ON public.information_reference_obligations REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('documents', 'search');


--
-- Name: information_relationships snapshot_cache_changed_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_delete AFTER DELETE ON public.information_relationships REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('documents');


--
-- Name: information_reports snapshot_cache_changed_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_delete AFTER DELETE ON public.information_reports REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('documents', 'search');


--
-- Name: information_resource_policies snapshot_cache_changed_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_delete AFTER DELETE ON public.information_resource_policies REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('documents', 'search');


--
-- Name: information_resources snapshot_cache_changed_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_delete AFTER DELETE ON public.information_resources REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('documents', 'search');


--
-- Name: information_revision_content snapshot_cache_changed_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_delete AFTER DELETE ON public.information_revision_content REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('documents', 'search');


--
-- Name: information_revisions snapshot_cache_changed_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_delete AFTER DELETE ON public.information_revisions REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('documents', 'search');


--
-- Name: paragraphs snapshot_cache_changed_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_delete AFTER DELETE ON public.paragraphs REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('search');


--
-- Name: project_members snapshot_cache_changed_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_delete AFTER DELETE ON public.project_members REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('authority');


--
-- Name: projects snapshot_cache_changed_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_delete AFTER DELETE ON public.projects REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('documents', 'search', 'schedules', 'authority');


--
-- Name: schedule_files snapshot_cache_changed_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_delete AFTER DELETE ON public.schedule_files REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('schedules');


--
-- Name: schedule_sources snapshot_cache_changed_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_delete AFTER DELETE ON public.schedule_sources REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('schedules');


--
-- Name: service_token_scopes snapshot_cache_changed_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_delete AFTER DELETE ON public.service_token_scopes REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('authority');


--
-- Name: service_tokens snapshot_cache_changed_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_delete AFTER DELETE ON public.service_tokens REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('authority');


--
-- Name: admins snapshot_cache_changed_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_insert AFTER INSERT ON public.admins REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('authority');


--
-- Name: authority_accounts snapshot_cache_changed_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_insert AFTER INSERT ON public.authority_accounts REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('authority');


--
-- Name: authority_grants snapshot_cache_changed_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_insert AFTER INSERT ON public.authority_grants REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('authority');


--
-- Name: authority_role_actions snapshot_cache_changed_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_insert AFTER INSERT ON public.authority_role_actions REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('authority');


--
-- Name: authority_roles snapshot_cache_changed_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_insert AFTER INSERT ON public.authority_roles REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('authority');


--
-- Name: authority_scopes snapshot_cache_changed_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_insert AFTER INSERT ON public.authority_scopes REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('authority');


--
-- Name: chunks snapshot_cache_changed_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_insert AFTER INSERT ON public.chunks REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('search');


--
-- Name: chunks_embedding_staging snapshot_cache_changed_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_insert AFTER INSERT ON public.chunks_embedding_staging REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('search');


--
-- Name: documents snapshot_cache_changed_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_insert AFTER INSERT ON public.documents REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('search');


--
-- Name: documents_embedding_staging snapshot_cache_changed_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_insert AFTER INSERT ON public.documents_embedding_staging REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('search');


--
-- Name: embedding_slots snapshot_cache_changed_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_insert AFTER INSERT ON public.embedding_slots REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('search');


--
-- Name: embedding_spaces snapshot_cache_changed_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_insert AFTER INSERT ON public.embedding_spaces REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('search');


--
-- Name: execution_allowances snapshot_cache_changed_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_insert AFTER INSERT ON public.execution_allowances REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('progress');


--
-- Name: information_content_type_versions snapshot_cache_changed_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_insert AFTER INSERT ON public.information_content_type_versions REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('documents', 'search');


--
-- Name: information_content_types snapshot_cache_changed_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_insert AFTER INSERT ON public.information_content_types REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('documents');


--
-- Name: information_inputs snapshot_cache_changed_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_insert AFTER INSERT ON public.information_inputs REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('documents', 'search');


--
-- Name: information_links snapshot_cache_changed_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_insert AFTER INSERT ON public.information_links REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('documents', 'search');


--
-- Name: information_navigation_policies snapshot_cache_changed_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_insert AFTER INSERT ON public.information_navigation_policies REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('documents', 'search');


--
-- Name: information_reference_obligations snapshot_cache_changed_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_insert AFTER INSERT ON public.information_reference_obligations REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('documents', 'search');


--
-- Name: information_relationships snapshot_cache_changed_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_insert AFTER INSERT ON public.information_relationships REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('documents');


--
-- Name: information_reports snapshot_cache_changed_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_insert AFTER INSERT ON public.information_reports REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('documents', 'search');


--
-- Name: information_resource_policies snapshot_cache_changed_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_insert AFTER INSERT ON public.information_resource_policies REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('documents', 'search');


--
-- Name: information_resources snapshot_cache_changed_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_insert AFTER INSERT ON public.information_resources REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('documents', 'search');


--
-- Name: information_revision_content snapshot_cache_changed_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_insert AFTER INSERT ON public.information_revision_content REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('documents', 'search');


--
-- Name: information_revisions snapshot_cache_changed_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_insert AFTER INSERT ON public.information_revisions REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('documents', 'search');


--
-- Name: paragraphs snapshot_cache_changed_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_insert AFTER INSERT ON public.paragraphs REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('search');


--
-- Name: project_members snapshot_cache_changed_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_insert AFTER INSERT ON public.project_members REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('authority');


--
-- Name: projects snapshot_cache_changed_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_insert AFTER INSERT ON public.projects REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('documents', 'search', 'schedules', 'authority');


--
-- Name: schedule_files snapshot_cache_changed_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_insert AFTER INSERT ON public.schedule_files REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('schedules');


--
-- Name: schedule_sources snapshot_cache_changed_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_insert AFTER INSERT ON public.schedule_sources REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('schedules');


--
-- Name: service_token_scopes snapshot_cache_changed_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_insert AFTER INSERT ON public.service_token_scopes REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('authority');


--
-- Name: service_tokens snapshot_cache_changed_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_insert AFTER INSERT ON public.service_tokens REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('authority');


--
-- Name: admins snapshot_cache_changed_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_truncate AFTER TRUNCATE ON public.admins FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('authority');


--
-- Name: authority_accounts snapshot_cache_changed_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_truncate AFTER TRUNCATE ON public.authority_accounts FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('authority');


--
-- Name: authority_grants snapshot_cache_changed_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_truncate AFTER TRUNCATE ON public.authority_grants FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('authority');


--
-- Name: authority_role_actions snapshot_cache_changed_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_truncate AFTER TRUNCATE ON public.authority_role_actions FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('authority');


--
-- Name: authority_roles snapshot_cache_changed_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_truncate AFTER TRUNCATE ON public.authority_roles FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('authority');


--
-- Name: authority_scopes snapshot_cache_changed_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_truncate AFTER TRUNCATE ON public.authority_scopes FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('authority');


--
-- Name: chunks snapshot_cache_changed_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_truncate AFTER TRUNCATE ON public.chunks FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('search');


--
-- Name: chunks_embedding_staging snapshot_cache_changed_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_truncate AFTER TRUNCATE ON public.chunks_embedding_staging FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('search');


--
-- Name: documents snapshot_cache_changed_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_truncate AFTER TRUNCATE ON public.documents FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('search');


--
-- Name: documents_embedding_staging snapshot_cache_changed_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_truncate AFTER TRUNCATE ON public.documents_embedding_staging FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('search');


--
-- Name: embedding_slots snapshot_cache_changed_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_truncate AFTER TRUNCATE ON public.embedding_slots FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('search');


--
-- Name: embedding_spaces snapshot_cache_changed_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_truncate AFTER TRUNCATE ON public.embedding_spaces FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('search');


--
-- Name: execution_allowances snapshot_cache_changed_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_truncate AFTER TRUNCATE ON public.execution_allowances FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('progress');


--
-- Name: information_content_type_versions snapshot_cache_changed_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_truncate AFTER TRUNCATE ON public.information_content_type_versions FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('documents', 'search');


--
-- Name: information_content_types snapshot_cache_changed_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_truncate AFTER TRUNCATE ON public.information_content_types FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('documents');


--
-- Name: information_inputs snapshot_cache_changed_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_truncate AFTER TRUNCATE ON public.information_inputs FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('documents', 'search');


--
-- Name: information_links snapshot_cache_changed_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_truncate AFTER TRUNCATE ON public.information_links FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('documents', 'search');


--
-- Name: information_navigation_policies snapshot_cache_changed_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_truncate AFTER TRUNCATE ON public.information_navigation_policies FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('documents', 'search');


--
-- Name: information_reference_obligations snapshot_cache_changed_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_truncate AFTER TRUNCATE ON public.information_reference_obligations FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('documents', 'search');


--
-- Name: information_relationships snapshot_cache_changed_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_truncate AFTER TRUNCATE ON public.information_relationships FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('documents');


--
-- Name: information_reports snapshot_cache_changed_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_truncate AFTER TRUNCATE ON public.information_reports FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('documents', 'search');


--
-- Name: information_resource_policies snapshot_cache_changed_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_truncate AFTER TRUNCATE ON public.information_resource_policies FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('documents', 'search');


--
-- Name: information_resources snapshot_cache_changed_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_truncate AFTER TRUNCATE ON public.information_resources FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('documents', 'search');


--
-- Name: information_revision_content snapshot_cache_changed_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_truncate AFTER TRUNCATE ON public.information_revision_content FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('documents', 'search');


--
-- Name: information_revisions snapshot_cache_changed_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_truncate AFTER TRUNCATE ON public.information_revisions FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('documents', 'search');


--
-- Name: paragraphs snapshot_cache_changed_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_truncate AFTER TRUNCATE ON public.paragraphs FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('search');


--
-- Name: project_members snapshot_cache_changed_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_truncate AFTER TRUNCATE ON public.project_members FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('authority');


--
-- Name: projects snapshot_cache_changed_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_truncate AFTER TRUNCATE ON public.projects FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('documents', 'search', 'schedules', 'authority');


--
-- Name: schedule_files snapshot_cache_changed_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_truncate AFTER TRUNCATE ON public.schedule_files FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('schedules');


--
-- Name: schedule_sources snapshot_cache_changed_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_truncate AFTER TRUNCATE ON public.schedule_sources FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('schedules');


--
-- Name: service_token_scopes snapshot_cache_changed_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_truncate AFTER TRUNCATE ON public.service_token_scopes FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('authority');


--
-- Name: service_tokens snapshot_cache_changed_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_truncate AFTER TRUNCATE ON public.service_tokens FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('authority');


--
-- Name: admins snapshot_cache_changed_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_update AFTER UPDATE ON public.admins REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('authority');


--
-- Name: authority_accounts snapshot_cache_changed_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_update AFTER UPDATE ON public.authority_accounts REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('authority');


--
-- Name: authority_grants snapshot_cache_changed_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_update AFTER UPDATE ON public.authority_grants REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('authority');


--
-- Name: authority_role_actions snapshot_cache_changed_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_update AFTER UPDATE ON public.authority_role_actions REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('authority');


--
-- Name: authority_roles snapshot_cache_changed_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_update AFTER UPDATE ON public.authority_roles REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('authority');


--
-- Name: authority_scopes snapshot_cache_changed_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_update AFTER UPDATE ON public.authority_scopes REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('authority');


--
-- Name: chunks snapshot_cache_changed_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_update AFTER UPDATE ON public.chunks REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('search');


--
-- Name: chunks_embedding_staging snapshot_cache_changed_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_update AFTER UPDATE ON public.chunks_embedding_staging REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('search');


--
-- Name: documents snapshot_cache_changed_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_update AFTER UPDATE ON public.documents REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('search');


--
-- Name: documents_embedding_staging snapshot_cache_changed_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_update AFTER UPDATE ON public.documents_embedding_staging REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('search');


--
-- Name: embedding_slots snapshot_cache_changed_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_update AFTER UPDATE ON public.embedding_slots REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('search');


--
-- Name: embedding_spaces snapshot_cache_changed_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_update AFTER UPDATE ON public.embedding_spaces REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('search');


--
-- Name: execution_allowances snapshot_cache_changed_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_update AFTER UPDATE ON public.execution_allowances REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('progress');


--
-- Name: information_content_type_versions snapshot_cache_changed_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_update AFTER UPDATE ON public.information_content_type_versions REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('documents', 'search');


--
-- Name: information_content_types snapshot_cache_changed_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_update AFTER UPDATE ON public.information_content_types REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('documents');


--
-- Name: information_inputs snapshot_cache_changed_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_update AFTER UPDATE ON public.information_inputs REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('documents', 'search');


--
-- Name: information_links snapshot_cache_changed_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_update AFTER UPDATE ON public.information_links REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('documents', 'search');


--
-- Name: information_navigation_policies snapshot_cache_changed_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_update AFTER UPDATE ON public.information_navigation_policies REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('documents', 'search');


--
-- Name: information_reference_obligations snapshot_cache_changed_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_update AFTER UPDATE ON public.information_reference_obligations REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('documents', 'search');


--
-- Name: information_relationships snapshot_cache_changed_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_update AFTER UPDATE ON public.information_relationships REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('documents');


--
-- Name: information_reports snapshot_cache_changed_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_update AFTER UPDATE ON public.information_reports REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('documents', 'search');


--
-- Name: information_resource_policies snapshot_cache_changed_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_update AFTER UPDATE ON public.information_resource_policies REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('documents', 'search');


--
-- Name: information_resources snapshot_cache_changed_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_update AFTER UPDATE ON public.information_resources REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('documents', 'search');


--
-- Name: information_revision_content snapshot_cache_changed_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_update AFTER UPDATE ON public.information_revision_content REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('documents', 'search');


--
-- Name: information_revisions snapshot_cache_changed_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_update AFTER UPDATE ON public.information_revisions REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('documents', 'search');


--
-- Name: paragraphs snapshot_cache_changed_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_update AFTER UPDATE ON public.paragraphs REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('search');


--
-- Name: project_members snapshot_cache_changed_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_update AFTER UPDATE ON public.project_members REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('authority');


--
-- Name: projects snapshot_cache_changed_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_update AFTER UPDATE ON public.projects REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('documents', 'search', 'schedules', 'authority');


--
-- Name: schedule_files snapshot_cache_changed_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_update AFTER UPDATE ON public.schedule_files REFERENCING OLD TABLE AS previous_rows NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('schedules');


--
-- Name: schedule_sources snapshot_cache_changed_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_update AFTER UPDATE ON public.schedule_sources REFERENCING OLD TABLE AS previous_rows NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('schedules');


--
-- Name: service_token_scopes snapshot_cache_changed_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_update AFTER UPDATE ON public.service_token_scopes REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('authority');


--
-- Name: service_tokens snapshot_cache_changed_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_changed_update AFTER UPDATE ON public.service_tokens REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('authority');


--
-- Name: information_revisions snapshot_cache_extractions_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_extractions_delete AFTER DELETE ON public.information_revisions REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_extractions_changed();


--
-- Name: information_revisions snapshot_cache_extractions_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_extractions_insert AFTER INSERT ON public.information_revisions REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_extractions_changed();


--
-- Name: information_revisions snapshot_cache_extractions_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_extractions_truncate AFTER TRUNCATE ON public.information_revisions FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_extractions_changed();


--
-- Name: information_revisions snapshot_cache_extractions_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_extractions_update AFTER UPDATE ON public.information_revisions REFERENCING OLD TABLE AS previous_rows NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_extractions_changed();


--
-- Name: information_revision_events snapshot_cache_history_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_history_delete AFTER DELETE ON public.information_revision_events REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_history_changed();


--
-- Name: information_revision_events snapshot_cache_history_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_history_insert AFTER INSERT ON public.information_revision_events REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_history_changed();


--
-- Name: information_revision_events snapshot_cache_history_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_history_truncate AFTER TRUNCATE ON public.information_revision_events FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_history_changed();


--
-- Name: information_revision_events snapshot_cache_history_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_history_update AFTER UPDATE ON public.information_revision_events REFERENCING OLD TABLE AS previous_rows NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_history_changed();


--
-- Name: snapshot_cache_identity snapshot_cache_identity_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_identity_immutable BEFORE DELETE OR UPDATE OR TRUNCATE ON public.snapshot_cache_identity FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_identity_immutable();


--
-- Name: information_document_policies snapshot_cache_knowledge_access_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_knowledge_access_delete AFTER DELETE ON public.information_document_policies REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_knowledge_access_changed();


--
-- Name: information_inputs snapshot_cache_knowledge_access_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_knowledge_access_delete AFTER DELETE ON public.information_inputs REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_knowledge_access_changed();


--
-- Name: information_links snapshot_cache_knowledge_access_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_knowledge_access_delete AFTER DELETE ON public.information_links REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_knowledge_access_changed();


--
-- Name: information_navigation_policies snapshot_cache_knowledge_access_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_knowledge_access_delete AFTER DELETE ON public.information_navigation_policies REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_knowledge_access_changed();


--
-- Name: information_reference_obligations snapshot_cache_knowledge_access_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_knowledge_access_delete AFTER DELETE ON public.information_reference_obligations REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_knowledge_access_changed();


--
-- Name: information_resource_policies snapshot_cache_knowledge_access_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_knowledge_access_delete AFTER DELETE ON public.information_resource_policies REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_knowledge_access_changed();


--
-- Name: information_resources snapshot_cache_knowledge_access_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_knowledge_access_delete AFTER DELETE ON public.information_resources REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_knowledge_access_changed();


--
-- Name: information_revisions snapshot_cache_knowledge_access_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_knowledge_access_delete AFTER DELETE ON public.information_revisions REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_knowledge_access_changed();


--
-- Name: information_document_policies snapshot_cache_knowledge_access_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_knowledge_access_insert AFTER INSERT ON public.information_document_policies REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_knowledge_access_changed();


--
-- Name: information_inputs snapshot_cache_knowledge_access_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_knowledge_access_insert AFTER INSERT ON public.information_inputs REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_knowledge_access_changed();


--
-- Name: information_links snapshot_cache_knowledge_access_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_knowledge_access_insert AFTER INSERT ON public.information_links REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_knowledge_access_changed();


--
-- Name: information_navigation_policies snapshot_cache_knowledge_access_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_knowledge_access_insert AFTER INSERT ON public.information_navigation_policies REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_knowledge_access_changed();


--
-- Name: information_reference_obligations snapshot_cache_knowledge_access_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_knowledge_access_insert AFTER INSERT ON public.information_reference_obligations REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_knowledge_access_changed();


--
-- Name: information_resource_policies snapshot_cache_knowledge_access_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_knowledge_access_insert AFTER INSERT ON public.information_resource_policies REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_knowledge_access_changed();


--
-- Name: information_resources snapshot_cache_knowledge_access_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_knowledge_access_insert AFTER INSERT ON public.information_resources REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_knowledge_access_changed();


--
-- Name: information_revisions snapshot_cache_knowledge_access_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_knowledge_access_insert AFTER INSERT ON public.information_revisions REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_knowledge_access_changed();


--
-- Name: information_document_policies snapshot_cache_knowledge_access_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_knowledge_access_truncate AFTER TRUNCATE ON public.information_document_policies FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_knowledge_access_changed();


--
-- Name: information_inputs snapshot_cache_knowledge_access_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_knowledge_access_truncate AFTER TRUNCATE ON public.information_inputs FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_knowledge_access_changed();


--
-- Name: information_links snapshot_cache_knowledge_access_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_knowledge_access_truncate AFTER TRUNCATE ON public.information_links FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_knowledge_access_changed();


--
-- Name: information_navigation_policies snapshot_cache_knowledge_access_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_knowledge_access_truncate AFTER TRUNCATE ON public.information_navigation_policies FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_knowledge_access_changed();


--
-- Name: information_reference_obligations snapshot_cache_knowledge_access_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_knowledge_access_truncate AFTER TRUNCATE ON public.information_reference_obligations FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_knowledge_access_changed();


--
-- Name: information_resource_policies snapshot_cache_knowledge_access_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_knowledge_access_truncate AFTER TRUNCATE ON public.information_resource_policies FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_knowledge_access_changed();


--
-- Name: information_resources snapshot_cache_knowledge_access_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_knowledge_access_truncate AFTER TRUNCATE ON public.information_resources FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_knowledge_access_changed();


--
-- Name: information_revisions snapshot_cache_knowledge_access_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_knowledge_access_truncate AFTER TRUNCATE ON public.information_revisions FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_knowledge_access_changed();


--
-- Name: information_document_policies snapshot_cache_knowledge_access_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_knowledge_access_update AFTER UPDATE ON public.information_document_policies REFERENCING OLD TABLE AS previous_rows NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_knowledge_access_changed();


--
-- Name: information_inputs snapshot_cache_knowledge_access_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_knowledge_access_update AFTER UPDATE ON public.information_inputs REFERENCING OLD TABLE AS previous_rows NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_knowledge_access_changed();


--
-- Name: information_links snapshot_cache_knowledge_access_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_knowledge_access_update AFTER UPDATE ON public.information_links REFERENCING OLD TABLE AS previous_rows NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_knowledge_access_changed();


--
-- Name: information_navigation_policies snapshot_cache_knowledge_access_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_knowledge_access_update AFTER UPDATE ON public.information_navigation_policies REFERENCING OLD TABLE AS previous_rows NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_knowledge_access_changed();


--
-- Name: information_reference_obligations snapshot_cache_knowledge_access_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_knowledge_access_update AFTER UPDATE ON public.information_reference_obligations REFERENCING OLD TABLE AS previous_rows NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_knowledge_access_changed();


--
-- Name: information_resource_policies snapshot_cache_knowledge_access_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_knowledge_access_update AFTER UPDATE ON public.information_resource_policies REFERENCING OLD TABLE AS previous_rows NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_knowledge_access_changed();


--
-- Name: information_resources snapshot_cache_knowledge_access_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_knowledge_access_update AFTER UPDATE ON public.information_resources REFERENCING OLD TABLE AS previous_rows NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_knowledge_access_changed();


--
-- Name: information_revisions snapshot_cache_knowledge_access_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_knowledge_access_update AFTER UPDATE ON public.information_revisions REFERENCING OLD TABLE AS previous_rows NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_knowledge_access_changed();


--
-- Name: acting_work_conversations snapshot_cache_observation_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_observation_delete AFTER DELETE ON public.acting_work_conversations REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_observation_changed();


--
-- Name: chapters snapshot_cache_observation_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_observation_delete AFTER DELETE ON public.chapters REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_observation_changed();


--
-- Name: inference_accounting_instances snapshot_cache_observation_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_observation_delete AFTER DELETE ON public.inference_accounting_instances REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_observation_changed();


--
-- Name: inference_accounting_journals snapshot_cache_observation_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_observation_delete AFTER DELETE ON public.inference_accounting_journals REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_observation_changed();


--
-- Name: inference_attempts snapshot_cache_observation_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_observation_delete AFTER DELETE ON public.inference_attempts REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_observation_changed();


--
-- Name: inference_calls snapshot_cache_observation_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_observation_delete AFTER DELETE ON public.inference_calls REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_observation_changed();


--
-- Name: inference_pricing_versions snapshot_cache_observation_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_observation_delete AFTER DELETE ON public.inference_pricing_versions REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_observation_changed();


--
-- Name: inference_run_ownership snapshot_cache_observation_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_observation_delete AFTER DELETE ON public.inference_run_ownership REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_observation_changed();


--
-- Name: information_acquisitions snapshot_cache_observation_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_observation_delete AFTER DELETE ON public.information_acquisitions REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_observation_changed();


--
-- Name: jobs snapshot_cache_observation_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_observation_delete AFTER DELETE ON public.jobs REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_observation_changed();


--
-- Name: sections snapshot_cache_observation_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_observation_delete AFTER DELETE ON public.sections REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_observation_changed();


--
-- Name: acting_work_conversations snapshot_cache_observation_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_observation_insert AFTER INSERT ON public.acting_work_conversations REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_observation_changed();


--
-- Name: chapters snapshot_cache_observation_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_observation_insert AFTER INSERT ON public.chapters REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_observation_changed();


--
-- Name: inference_accounting_instances snapshot_cache_observation_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_observation_insert AFTER INSERT ON public.inference_accounting_instances REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_observation_changed();


--
-- Name: inference_accounting_journals snapshot_cache_observation_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_observation_insert AFTER INSERT ON public.inference_accounting_journals REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_observation_changed();


--
-- Name: inference_attempts snapshot_cache_observation_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_observation_insert AFTER INSERT ON public.inference_attempts REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_observation_changed();


--
-- Name: inference_calls snapshot_cache_observation_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_observation_insert AFTER INSERT ON public.inference_calls REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_observation_changed();


--
-- Name: inference_pricing_versions snapshot_cache_observation_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_observation_insert AFTER INSERT ON public.inference_pricing_versions REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_observation_changed();


--
-- Name: inference_run_ownership snapshot_cache_observation_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_observation_insert AFTER INSERT ON public.inference_run_ownership REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_observation_changed();


--
-- Name: information_acquisitions snapshot_cache_observation_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_observation_insert AFTER INSERT ON public.information_acquisitions REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_observation_changed();


--
-- Name: jobs snapshot_cache_observation_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_observation_insert AFTER INSERT ON public.jobs REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_observation_changed();


--
-- Name: sections snapshot_cache_observation_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_observation_insert AFTER INSERT ON public.sections REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_observation_changed();


--
-- Name: acting_work_conversations snapshot_cache_observation_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_observation_truncate AFTER TRUNCATE ON public.acting_work_conversations FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_observation_changed();


--
-- Name: chapters snapshot_cache_observation_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_observation_truncate AFTER TRUNCATE ON public.chapters FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_observation_changed();


--
-- Name: inference_accounting_instances snapshot_cache_observation_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_observation_truncate AFTER TRUNCATE ON public.inference_accounting_instances FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_observation_changed();


--
-- Name: inference_accounting_journals snapshot_cache_observation_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_observation_truncate AFTER TRUNCATE ON public.inference_accounting_journals FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_observation_changed();


--
-- Name: inference_attempts snapshot_cache_observation_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_observation_truncate AFTER TRUNCATE ON public.inference_attempts FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_observation_changed();


--
-- Name: inference_calls snapshot_cache_observation_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_observation_truncate AFTER TRUNCATE ON public.inference_calls FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_observation_changed();


--
-- Name: inference_pricing_versions snapshot_cache_observation_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_observation_truncate AFTER TRUNCATE ON public.inference_pricing_versions FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_observation_changed();


--
-- Name: inference_run_ownership snapshot_cache_observation_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_observation_truncate AFTER TRUNCATE ON public.inference_run_ownership FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_observation_changed();


--
-- Name: information_acquisitions snapshot_cache_observation_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_observation_truncate AFTER TRUNCATE ON public.information_acquisitions FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_observation_changed();


--
-- Name: jobs snapshot_cache_observation_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_observation_truncate AFTER TRUNCATE ON public.jobs FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_observation_changed();


--
-- Name: sections snapshot_cache_observation_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_observation_truncate AFTER TRUNCATE ON public.sections FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_observation_changed();


--
-- Name: acting_work_conversations snapshot_cache_observation_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_observation_update AFTER UPDATE ON public.acting_work_conversations REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_observation_changed();


--
-- Name: chapters snapshot_cache_observation_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_observation_update AFTER UPDATE ON public.chapters REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_observation_changed();


--
-- Name: inference_accounting_instances snapshot_cache_observation_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_observation_update AFTER UPDATE ON public.inference_accounting_instances REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_observation_changed();


--
-- Name: inference_accounting_journals snapshot_cache_observation_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_observation_update AFTER UPDATE ON public.inference_accounting_journals REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_observation_changed();


--
-- Name: inference_attempts snapshot_cache_observation_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_observation_update AFTER UPDATE ON public.inference_attempts REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_observation_changed();


--
-- Name: inference_calls snapshot_cache_observation_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_observation_update AFTER UPDATE ON public.inference_calls REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_observation_changed();


--
-- Name: inference_pricing_versions snapshot_cache_observation_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_observation_update AFTER UPDATE ON public.inference_pricing_versions REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_observation_changed();


--
-- Name: inference_run_ownership snapshot_cache_observation_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_observation_update AFTER UPDATE ON public.inference_run_ownership REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_observation_changed();


--
-- Name: information_acquisitions snapshot_cache_observation_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_observation_update AFTER UPDATE ON public.information_acquisitions REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_observation_changed();


--
-- Name: jobs snapshot_cache_observation_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_observation_update AFTER UPDATE ON public.jobs REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_observation_changed();


--
-- Name: sections snapshot_cache_observation_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_observation_update AFTER UPDATE ON public.sections REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_observation_changed();


--
-- Name: conversations snapshot_cache_readability_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_readability_delete AFTER DELETE ON public.conversations REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_readability_changed();


--
-- Name: information_document_policies snapshot_cache_readability_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_readability_delete AFTER DELETE ON public.information_document_policies REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_readability_changed();


--
-- Name: information_inputs snapshot_cache_readability_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_readability_delete AFTER DELETE ON public.information_inputs REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_readability_changed();


--
-- Name: information_job_inputs snapshot_cache_readability_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_readability_delete AFTER DELETE ON public.information_job_inputs REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_readability_changed();


--
-- Name: information_links snapshot_cache_readability_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_readability_delete AFTER DELETE ON public.information_links REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_readability_changed();


--
-- Name: information_navigation_policies snapshot_cache_readability_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_readability_delete AFTER DELETE ON public.information_navigation_policies REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_readability_changed();


--
-- Name: information_quarantined_payloads snapshot_cache_readability_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_readability_delete AFTER DELETE ON public.information_quarantined_payloads REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_readability_changed();


--
-- Name: information_reference_obligations snapshot_cache_readability_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_readability_delete AFTER DELETE ON public.information_reference_obligations REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_readability_changed();


--
-- Name: information_resource_policies snapshot_cache_readability_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_readability_delete AFTER DELETE ON public.information_resource_policies REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_readability_changed();


--
-- Name: information_resources snapshot_cache_readability_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_readability_delete AFTER DELETE ON public.information_resources REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_readability_changed();


--
-- Name: information_revisions snapshot_cache_readability_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_readability_delete AFTER DELETE ON public.information_revisions REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_readability_changed();


--
-- Name: orchestrations snapshot_cache_readability_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_readability_delete AFTER DELETE ON public.orchestrations REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_readability_changed();


--
-- Name: conversations snapshot_cache_readability_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_readability_insert AFTER INSERT ON public.conversations REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_readability_changed();


--
-- Name: information_document_policies snapshot_cache_readability_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_readability_insert AFTER INSERT ON public.information_document_policies REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_readability_changed();


--
-- Name: information_inputs snapshot_cache_readability_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_readability_insert AFTER INSERT ON public.information_inputs REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_readability_changed();


--
-- Name: information_job_inputs snapshot_cache_readability_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_readability_insert AFTER INSERT ON public.information_job_inputs REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_readability_changed();


--
-- Name: information_links snapshot_cache_readability_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_readability_insert AFTER INSERT ON public.information_links REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_readability_changed();


--
-- Name: information_navigation_policies snapshot_cache_readability_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_readability_insert AFTER INSERT ON public.information_navigation_policies REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_readability_changed();


--
-- Name: information_quarantined_payloads snapshot_cache_readability_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_readability_insert AFTER INSERT ON public.information_quarantined_payloads REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_readability_changed();


--
-- Name: information_reference_obligations snapshot_cache_readability_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_readability_insert AFTER INSERT ON public.information_reference_obligations REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_readability_changed();


--
-- Name: information_resource_policies snapshot_cache_readability_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_readability_insert AFTER INSERT ON public.information_resource_policies REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_readability_changed();


--
-- Name: information_resources snapshot_cache_readability_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_readability_insert AFTER INSERT ON public.information_resources REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_readability_changed();


--
-- Name: information_revisions snapshot_cache_readability_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_readability_insert AFTER INSERT ON public.information_revisions REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_readability_changed();


--
-- Name: orchestrations snapshot_cache_readability_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_readability_insert AFTER INSERT ON public.orchestrations REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_readability_changed();


--
-- Name: conversations snapshot_cache_readability_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_readability_truncate AFTER TRUNCATE ON public.conversations FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_readability_changed();


--
-- Name: information_document_policies snapshot_cache_readability_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_readability_truncate AFTER TRUNCATE ON public.information_document_policies FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_readability_changed();


--
-- Name: information_inputs snapshot_cache_readability_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_readability_truncate AFTER TRUNCATE ON public.information_inputs FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_readability_changed();


--
-- Name: information_job_inputs snapshot_cache_readability_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_readability_truncate AFTER TRUNCATE ON public.information_job_inputs FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_readability_changed();


--
-- Name: information_links snapshot_cache_readability_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_readability_truncate AFTER TRUNCATE ON public.information_links FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_readability_changed();


--
-- Name: information_navigation_policies snapshot_cache_readability_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_readability_truncate AFTER TRUNCATE ON public.information_navigation_policies FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_readability_changed();


--
-- Name: information_quarantined_payloads snapshot_cache_readability_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_readability_truncate AFTER TRUNCATE ON public.information_quarantined_payloads FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_readability_changed();


--
-- Name: information_reference_obligations snapshot_cache_readability_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_readability_truncate AFTER TRUNCATE ON public.information_reference_obligations FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_readability_changed();


--
-- Name: information_resource_policies snapshot_cache_readability_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_readability_truncate AFTER TRUNCATE ON public.information_resource_policies FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_readability_changed();


--
-- Name: information_resources snapshot_cache_readability_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_readability_truncate AFTER TRUNCATE ON public.information_resources FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_readability_changed();


--
-- Name: information_revisions snapshot_cache_readability_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_readability_truncate AFTER TRUNCATE ON public.information_revisions FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_readability_changed();


--
-- Name: orchestrations snapshot_cache_readability_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_readability_truncate AFTER TRUNCATE ON public.orchestrations FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_readability_changed();


--
-- Name: conversations snapshot_cache_readability_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_readability_update AFTER UPDATE ON public.conversations FOR EACH ROW WHEN (((old.id IS DISTINCT FROM new.id) OR (old.parent_id IS DISTINCT FROM new.parent_id))) EXECUTE FUNCTION public.snapshot_cache_readability_changed();


--
-- Name: information_document_policies snapshot_cache_readability_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_readability_update AFTER UPDATE ON public.information_document_policies REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_readability_changed();


--
-- Name: information_inputs snapshot_cache_readability_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_readability_update AFTER UPDATE ON public.information_inputs REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_readability_changed();


--
-- Name: information_job_inputs snapshot_cache_readability_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_readability_update AFTER UPDATE ON public.information_job_inputs REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_readability_changed();


--
-- Name: information_links snapshot_cache_readability_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_readability_update AFTER UPDATE ON public.information_links REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_readability_changed();


--
-- Name: information_navigation_policies snapshot_cache_readability_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_readability_update AFTER UPDATE ON public.information_navigation_policies REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_readability_changed();


--
-- Name: information_quarantined_payloads snapshot_cache_readability_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_readability_update AFTER UPDATE ON public.information_quarantined_payloads REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_readability_changed();


--
-- Name: information_reference_obligations snapshot_cache_readability_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_readability_update AFTER UPDATE ON public.information_reference_obligations REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_readability_changed();


--
-- Name: information_resource_policies snapshot_cache_readability_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_readability_update AFTER UPDATE ON public.information_resource_policies REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_readability_changed();


--
-- Name: information_resources snapshot_cache_readability_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_readability_update AFTER UPDATE ON public.information_resources FOR EACH ROW WHEN ((((old.id IS DISTINCT FROM new.id) OR (old.home_scope_id IS DISTINCT FROM new.home_scope_id)) OR (old.project_id IS DISTINCT FROM new.project_id))) EXECUTE FUNCTION public.snapshot_cache_readability_changed();


--
-- Name: information_revisions snapshot_cache_readability_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_readability_update AFTER UPDATE ON public.information_revisions FOR EACH ROW WHEN ((((((old.id IS DISTINCT FROM new.id) OR (old.resource_id IS DISTINCT FROM new.resource_id)) OR (old.ordinal IS DISTINCT FROM new.ordinal)) OR (old.availability IS DISTINCT FROM new.availability)) OR (old.excluded IS DISTINCT FROM new.excluded))) EXECUTE FUNCTION public.snapshot_cache_readability_changed();


--
-- Name: orchestrations snapshot_cache_readability_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_readability_update AFTER UPDATE ON public.orchestrations FOR EACH ROW WHEN (((((old.id IS DISTINCT FROM new.id) OR (old.parent IS DISTINCT FROM new.parent)) OR (old.caller_conversation IS DISTINCT FROM new.caller_conversation)) OR (old.conductor_conversation IS DISTINCT FROM new.conductor_conversation))) EXECUTE FUNCTION public.snapshot_cache_readability_changed();


--
-- Name: conversations snapshot_cache_relay_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_relay_delete AFTER DELETE ON public.conversations REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_relay_changed();


--
-- Name: projects snapshot_cache_relay_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_relay_delete AFTER DELETE ON public.projects REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_relay_changed();


--
-- Name: relay_admissions snapshot_cache_relay_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_relay_delete AFTER DELETE ON public.relay_admissions REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_relay_changed();


--
-- Name: relay_deliveries snapshot_cache_relay_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_relay_delete AFTER DELETE ON public.relay_deliveries REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_relay_changed();


--
-- Name: relay_executions snapshot_cache_relay_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_relay_delete AFTER DELETE ON public.relay_executions REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_relay_changed();


--
-- Name: relay_native_gap_recoveries snapshot_cache_relay_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_relay_delete AFTER DELETE ON public.relay_native_gap_recoveries REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_relay_changed();


--
-- Name: relay_publications snapshot_cache_relay_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_relay_delete AFTER DELETE ON public.relay_publications REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_relay_changed();


--
-- Name: relay_subscriptions snapshot_cache_relay_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_relay_delete AFTER DELETE ON public.relay_subscriptions REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_relay_changed();


--
-- Name: relay_topics snapshot_cache_relay_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_relay_delete AFTER DELETE ON public.relay_topics REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_relay_changed();


--
-- Name: conversations snapshot_cache_relay_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_relay_insert AFTER INSERT ON public.conversations REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_relay_changed();


--
-- Name: projects snapshot_cache_relay_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_relay_insert AFTER INSERT ON public.projects REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_relay_changed();


--
-- Name: relay_admissions snapshot_cache_relay_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_relay_insert AFTER INSERT ON public.relay_admissions REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_relay_changed();


--
-- Name: relay_deliveries snapshot_cache_relay_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_relay_insert AFTER INSERT ON public.relay_deliveries REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_relay_changed();


--
-- Name: relay_executions snapshot_cache_relay_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_relay_insert AFTER INSERT ON public.relay_executions REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_relay_changed();


--
-- Name: relay_native_gap_recoveries snapshot_cache_relay_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_relay_insert AFTER INSERT ON public.relay_native_gap_recoveries REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_relay_changed();


--
-- Name: relay_publications snapshot_cache_relay_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_relay_insert AFTER INSERT ON public.relay_publications REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_relay_changed();


--
-- Name: relay_subscriptions snapshot_cache_relay_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_relay_insert AFTER INSERT ON public.relay_subscriptions REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_relay_changed();


--
-- Name: relay_topics snapshot_cache_relay_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_relay_insert AFTER INSERT ON public.relay_topics REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_relay_changed();


--
-- Name: conversations snapshot_cache_relay_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_relay_truncate AFTER TRUNCATE ON public.conversations FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_relay_changed();


--
-- Name: projects snapshot_cache_relay_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_relay_truncate AFTER TRUNCATE ON public.projects FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_relay_changed();


--
-- Name: relay_admissions snapshot_cache_relay_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_relay_truncate AFTER TRUNCATE ON public.relay_admissions FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_relay_changed();


--
-- Name: relay_deliveries snapshot_cache_relay_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_relay_truncate AFTER TRUNCATE ON public.relay_deliveries FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_relay_changed();


--
-- Name: relay_executions snapshot_cache_relay_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_relay_truncate AFTER TRUNCATE ON public.relay_executions FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_relay_changed();


--
-- Name: relay_native_gap_recoveries snapshot_cache_relay_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_relay_truncate AFTER TRUNCATE ON public.relay_native_gap_recoveries FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_relay_changed();


--
-- Name: relay_publications snapshot_cache_relay_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_relay_truncate AFTER TRUNCATE ON public.relay_publications FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_relay_changed();


--
-- Name: relay_subscriptions snapshot_cache_relay_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_relay_truncate AFTER TRUNCATE ON public.relay_subscriptions FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_relay_changed();


--
-- Name: relay_topics snapshot_cache_relay_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_relay_truncate AFTER TRUNCATE ON public.relay_topics FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_relay_changed();


--
-- Name: conversations snapshot_cache_relay_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_relay_update AFTER UPDATE ON public.conversations REFERENCING OLD TABLE AS old_rows NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_relay_changed();


--
-- Name: projects snapshot_cache_relay_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_relay_update AFTER UPDATE ON public.projects REFERENCING OLD TABLE AS old_rows NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_relay_changed();


--
-- Name: relay_admissions snapshot_cache_relay_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_relay_update AFTER UPDATE ON public.relay_admissions REFERENCING OLD TABLE AS old_rows NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_relay_changed();


--
-- Name: relay_deliveries snapshot_cache_relay_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_relay_update AFTER UPDATE ON public.relay_deliveries REFERENCING OLD TABLE AS old_rows NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_relay_changed();


--
-- Name: relay_executions snapshot_cache_relay_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_relay_update AFTER UPDATE ON public.relay_executions REFERENCING OLD TABLE AS old_rows NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_relay_changed();


--
-- Name: relay_native_gap_recoveries snapshot_cache_relay_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_relay_update AFTER UPDATE ON public.relay_native_gap_recoveries REFERENCING OLD TABLE AS old_rows NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_relay_changed();


--
-- Name: relay_publications snapshot_cache_relay_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_relay_update AFTER UPDATE ON public.relay_publications REFERENCING OLD TABLE AS old_rows NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_relay_changed();


--
-- Name: relay_subscriptions snapshot_cache_relay_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_relay_update AFTER UPDATE ON public.relay_subscriptions REFERENCING OLD TABLE AS old_rows NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_relay_changed();


--
-- Name: relay_topics snapshot_cache_relay_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_relay_update AFTER UPDATE ON public.relay_topics REFERENCING OLD TABLE AS old_rows NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_relay_changed();


--
-- Name: information_steps snapshot_cache_steps_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_steps_delete AFTER DELETE ON public.information_steps REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_information_steps_changed();


--
-- Name: information_steps snapshot_cache_steps_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_steps_insert AFTER INSERT ON public.information_steps REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_information_steps_changed();


--
-- Name: information_steps snapshot_cache_steps_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_steps_truncate AFTER TRUNCATE ON public.information_steps FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_information_steps_changed();


--
-- Name: information_steps snapshot_cache_steps_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_steps_update AFTER UPDATE ON public.information_steps REFERENCING OLD TABLE AS previous_rows NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_information_steps_changed();


--
-- Name: conversations snapshot_cache_transcript_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_transcript_delete AFTER DELETE ON public.conversations REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_transcript_changed();


--
-- Name: entries snapshot_cache_transcript_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_transcript_delete AFTER DELETE ON public.entries REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_transcript_changed();


--
-- Name: information_job_inputs snapshot_cache_transcript_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_transcript_delete AFTER DELETE ON public.information_job_inputs REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_transcript_changed();


--
-- Name: information_quarantined_payloads snapshot_cache_transcript_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_transcript_delete AFTER DELETE ON public.information_quarantined_payloads REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_transcript_changed();


--
-- Name: orchestrations snapshot_cache_transcript_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_transcript_delete AFTER DELETE ON public.orchestrations REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_transcript_changed();


--
-- Name: conversations snapshot_cache_transcript_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_transcript_insert AFTER INSERT ON public.conversations REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_transcript_changed();


--
-- Name: entries snapshot_cache_transcript_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_transcript_insert AFTER INSERT ON public.entries REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_transcript_changed();


--
-- Name: information_job_inputs snapshot_cache_transcript_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_transcript_insert AFTER INSERT ON public.information_job_inputs REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_transcript_changed();


--
-- Name: information_quarantined_payloads snapshot_cache_transcript_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_transcript_insert AFTER INSERT ON public.information_quarantined_payloads REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_transcript_changed();


--
-- Name: orchestrations snapshot_cache_transcript_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_transcript_insert AFTER INSERT ON public.orchestrations REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_transcript_changed();


--
-- Name: conversations snapshot_cache_transcript_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_transcript_truncate AFTER TRUNCATE ON public.conversations FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_transcript_changed();


--
-- Name: entries snapshot_cache_transcript_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_transcript_truncate AFTER TRUNCATE ON public.entries FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_transcript_changed();


--
-- Name: information_job_inputs snapshot_cache_transcript_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_transcript_truncate AFTER TRUNCATE ON public.information_job_inputs FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_transcript_changed();


--
-- Name: information_quarantined_payloads snapshot_cache_transcript_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_transcript_truncate AFTER TRUNCATE ON public.information_quarantined_payloads FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_transcript_changed();


--
-- Name: orchestrations snapshot_cache_transcript_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_transcript_truncate AFTER TRUNCATE ON public.orchestrations FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_transcript_changed();


--
-- Name: conversations snapshot_cache_transcript_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_transcript_update AFTER UPDATE ON public.conversations REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_transcript_changed();


--
-- Name: entries snapshot_cache_transcript_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_transcript_update AFTER UPDATE ON public.entries REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_transcript_changed();


--
-- Name: information_job_inputs snapshot_cache_transcript_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_transcript_update AFTER UPDATE ON public.information_job_inputs REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_transcript_changed();


--
-- Name: information_quarantined_payloads snapshot_cache_transcript_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_transcript_update AFTER UPDATE ON public.information_quarantined_payloads REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_transcript_changed();


--
-- Name: orchestrations snapshot_cache_transcript_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_transcript_update AFTER UPDATE ON public.orchestrations REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_transcript_changed();


--
-- Name: information_content_type_versions snapshot_cache_types_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_types_delete AFTER DELETE ON public.information_content_type_versions REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('types');


--
-- Name: information_content_types snapshot_cache_types_delete; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_types_delete AFTER DELETE ON public.information_content_types REFERENCING OLD TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('types');


--
-- Name: information_content_type_versions snapshot_cache_types_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_types_insert AFTER INSERT ON public.information_content_type_versions REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('types');


--
-- Name: information_content_types snapshot_cache_types_insert; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_types_insert AFTER INSERT ON public.information_content_types REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('types');


--
-- Name: information_content_type_versions snapshot_cache_types_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_types_truncate AFTER TRUNCATE ON public.information_content_type_versions FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('types');


--
-- Name: information_content_types snapshot_cache_types_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_types_truncate AFTER TRUNCATE ON public.information_content_types FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('types');


--
-- Name: information_content_type_versions snapshot_cache_types_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_types_update AFTER UPDATE ON public.information_content_type_versions REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('types');


--
-- Name: information_content_types snapshot_cache_types_update; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER snapshot_cache_types_update AFTER UPDATE ON public.information_content_types REFERENCING NEW TABLE AS changed_rows FOR EACH STATEMENT EXECUTE FUNCTION public.snapshot_cache_source_changed('types');


--
-- Name: system_operations system_operation_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER system_operation_immutable BEFORE DELETE OR UPDATE ON public.system_operations FOR EACH ROW EXECUTE FUNCTION public.protect_system_operation();


--
-- Name: trajectory_image_references trajectory_blob_claim_release; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trajectory_blob_claim_release AFTER DELETE ON public.trajectory_image_references FOR EACH ROW EXECUTE FUNCTION public.release_trajectory_blob_claim();


--
-- Name: entries trajectory_image_ejection; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trajectory_image_ejection BEFORE UPDATE OF ejected_at ON public.entries FOR EACH ROW EXECUTE FUNCTION public.eject_trajectory_images();


--
-- Name: acting_audit acting_audit_actor_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.acting_audit
    ADD CONSTRAINT acting_audit_actor_fkey FOREIGN KEY (actor) REFERENCES public.admins(handle);


--
-- Name: acting_audit acting_audit_application_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.acting_audit
    ADD CONSTRAINT acting_audit_application_fkey FOREIGN KEY (application) REFERENCES public.application_identities(project_name);


--
-- Name: acting_audit acting_audit_request_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.acting_audit
    ADD CONSTRAINT acting_audit_request_id_fkey FOREIGN KEY (request_id) REFERENCES public.acting_mutation_receipts(request_id);


--
-- Name: acting_audit acting_audit_revision_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.acting_audit
    ADD CONSTRAINT acting_audit_revision_fkey FOREIGN KEY (revision) REFERENCES public.application_releases(revision);


--
-- Name: acting_audit acting_audit_token_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.acting_audit
    ADD CONSTRAINT acting_audit_token_id_fkey FOREIGN KEY (token_id) REFERENCES public.acting_tokens(id);


--
-- Name: acting_context_audit acting_context_audit_actor_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.acting_context_audit
    ADD CONSTRAINT acting_context_audit_actor_fkey FOREIGN KEY (actor) REFERENCES public.admins(handle);


--
-- Name: acting_context_audit acting_context_audit_subject_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.acting_context_audit
    ADD CONSTRAINT acting_context_audit_subject_fkey FOREIGN KEY (subject) REFERENCES public.admins(handle);


--
-- Name: acting_eligible_accounts acting_eligible_accounts_account_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.acting_eligible_accounts
    ADD CONSTRAINT acting_eligible_accounts_account_fkey FOREIGN KEY (account) REFERENCES public.admins(handle);


--
-- Name: acting_eligible_accounts acting_eligible_accounts_application_grant_name_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.acting_eligible_accounts
    ADD CONSTRAINT acting_eligible_accounts_application_grant_name_fkey FOREIGN KEY (application, grant_name) REFERENCES public.acting_grants(application, name);


--
-- Name: acting_eligible_projects acting_eligible_projects_application_grant_name_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.acting_eligible_projects
    ADD CONSTRAINT acting_eligible_projects_application_grant_name_fkey FOREIGN KEY (application, grant_name) REFERENCES public.acting_grants(application, name);


--
-- Name: acting_eligible_projects acting_eligible_projects_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.acting_eligible_projects
    ADD CONSTRAINT acting_eligible_projects_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;


--
-- Name: acting_grants acting_grants_application_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.acting_grants
    ADD CONSTRAINT acting_grants_application_fkey FOREIGN KEY (application) REFERENCES public.application_identities(project_name);


--
-- Name: acting_grants acting_grants_revision_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.acting_grants
    ADD CONSTRAINT acting_grants_revision_fkey FOREIGN KEY (revision) REFERENCES public.application_releases(revision);


--
-- Name: acting_mutation_receipts acting_mutation_receipts_actor_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.acting_mutation_receipts
    ADD CONSTRAINT acting_mutation_receipts_actor_fkey FOREIGN KEY (actor) REFERENCES public.admins(handle);


--
-- Name: acting_mutation_receipts acting_mutation_receipts_application_grant_name_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.acting_mutation_receipts
    ADD CONSTRAINT acting_mutation_receipts_application_grant_name_fkey FOREIGN KEY (application, grant_name) REFERENCES public.acting_grants(application, name);


--
-- Name: acting_mutation_receipts acting_mutation_receipts_token_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.acting_mutation_receipts
    ADD CONSTRAINT acting_mutation_receipts_token_id_fkey FOREIGN KEY (token_id) REFERENCES public.acting_tokens(id);


--
-- Name: acting_token_projects acting_token_projects_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.acting_token_projects
    ADD CONSTRAINT acting_token_projects_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;


--
-- Name: acting_token_projects acting_token_projects_token_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.acting_token_projects
    ADD CONSTRAINT acting_token_projects_token_id_fkey FOREIGN KEY (token_id) REFERENCES public.acting_tokens(id);


--
-- Name: acting_tokens acting_tokens_actor_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.acting_tokens
    ADD CONSTRAINT acting_tokens_actor_fkey FOREIGN KEY (actor) REFERENCES public.admins(handle);


--
-- Name: acting_tokens acting_tokens_application_grant_name_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.acting_tokens
    ADD CONSTRAINT acting_tokens_application_grant_name_fkey FOREIGN KEY (application, grant_name) REFERENCES public.acting_grants(application, name);


--
-- Name: acting_tokens acting_tokens_subject_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.acting_tokens
    ADD CONSTRAINT acting_tokens_subject_fkey FOREIGN KEY (subject) REFERENCES public.admins(handle);


--
-- Name: acting_work_conversations acting_work_conversations_actor_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.acting_work_conversations
    ADD CONSTRAINT acting_work_conversations_actor_fkey FOREIGN KEY (actor) REFERENCES public.admins(handle);


--
-- Name: acting_work_conversations acting_work_conversations_application_grant_name_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.acting_work_conversations
    ADD CONSTRAINT acting_work_conversations_application_grant_name_fkey FOREIGN KEY (application, grant_name) REFERENCES public.acting_grants(application, name);


--
-- Name: acting_work_conversations acting_work_conversations_proof_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.acting_work_conversations
    ADD CONSTRAINT acting_work_conversations_proof_fkey FOREIGN KEY (proof) REFERENCES public.acting_tokens(id);


--
-- Name: acting_work_conversations acting_work_conversations_subject_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.acting_work_conversations
    ADD CONSTRAINT acting_work_conversations_subject_fkey FOREIGN KEY (subject) REFERENCES public.admins(handle);


--
-- Name: acting_work_receipts acting_work_receipts_actor_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.acting_work_receipts
    ADD CONSTRAINT acting_work_receipts_actor_fkey FOREIGN KEY (actor) REFERENCES public.admins(handle);


--
-- Name: acting_work_receipts acting_work_receipts_conversation_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.acting_work_receipts
    ADD CONSTRAINT acting_work_receipts_conversation_fkey FOREIGN KEY (conversation) REFERENCES public.acting_work_conversations(conversation);


--
-- Name: application_identities application_current_credential_receipt; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.application_identities
    ADD CONSTRAINT application_current_credential_receipt FOREIGN KEY (credential_request_id) REFERENCES public.application_token_receipts(request_id) DEFERRABLE INITIALLY DEFERRED;


--
-- Name: application_deployment_heads application_deployment_heads_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.application_deployment_heads
    ADD CONSTRAINT application_deployment_heads_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id);


--
-- Name: application_deployment_heads application_deployment_heads_project_id_revision_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.application_deployment_heads
    ADD CONSTRAINT application_deployment_heads_project_id_revision_fkey FOREIGN KEY (project_id, revision) REFERENCES public.application_releases(project_id, revision);


--
-- Name: application_deployment_receipts application_deployment_receipts_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.application_deployment_receipts
    ADD CONSTRAINT application_deployment_receipts_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id);


--
-- Name: application_deployment_receipts application_deployment_receipts_project_id_revision_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.application_deployment_receipts
    ADD CONSTRAINT application_deployment_receipts_project_id_revision_fkey FOREIGN KEY (project_id, revision) REFERENCES public.application_releases(project_id, revision);


--
-- Name: application_identities application_identities_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.application_identities
    ADD CONSTRAINT application_identities_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE SET NULL;


--
-- Name: application_identities application_identities_reserved_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.application_identities
    ADD CONSTRAINT application_identities_reserved_by_fkey FOREIGN KEY (reserved_by) REFERENCES public.admins(handle);


--
-- Name: application_identities application_identities_service_handle_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.application_identities
    ADD CONSTRAINT application_identities_service_handle_fkey FOREIGN KEY (service_handle) REFERENCES public.admins(handle);


--
-- Name: application_identities application_identities_token_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.application_identities
    ADD CONSTRAINT application_identities_token_id_fkey FOREIGN KEY (token_id) REFERENCES public.service_tokens(id);


--
-- Name: application_releases application_releases_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.application_releases
    ADD CONSTRAINT application_releases_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id);


--
-- Name: application_token_receipts application_token_receipts_actor_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.application_token_receipts
    ADD CONSTRAINT application_token_receipts_actor_fkey FOREIGN KEY (actor) REFERENCES public.admins(handle);


--
-- Name: application_token_receipts application_token_receipts_project_name_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.application_token_receipts
    ADD CONSTRAINT application_token_receipts_project_name_fkey FOREIGN KEY (project_name) REFERENCES public.application_identities(project_name);


--
-- Name: application_token_receipts application_token_receipts_token_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.application_token_receipts
    ADD CONSTRAINT application_token_receipts_token_id_fkey FOREIGN KEY (token_id) REFERENCES public.service_tokens(id);


--
-- Name: auth_session_chains auth_session_chains_handle_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.auth_session_chains
    ADD CONSTRAINT auth_session_chains_handle_fkey FOREIGN KEY (handle) REFERENCES public.admins(handle) ON DELETE CASCADE;


--
-- Name: auth_session_grants auth_session_grants_chain_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.auth_session_grants
    ADD CONSTRAINT auth_session_grants_chain_id_fkey FOREIGN KEY (chain_id) REFERENCES public.auth_session_chains(id) ON DELETE CASCADE;


--
-- Name: authority_accounts authority_accounts_legacy_handle_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.authority_accounts
    ADD CONSTRAINT authority_accounts_legacy_handle_fkey FOREIGN KEY (legacy_handle) REFERENCES public.admins(handle) ON UPDATE CASCADE ON DELETE CASCADE;


--
-- Name: authority_grant_audit authority_grant_audit_actor_account_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.authority_grant_audit
    ADD CONSTRAINT authority_grant_audit_actor_account_fkey FOREIGN KEY (actor_account) REFERENCES public.authority_accounts(id);


--
-- Name: authority_grant_audit authority_grant_audit_authority_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.authority_grant_audit
    ADD CONSTRAINT authority_grant_audit_authority_fkey FOREIGN KEY (authority) REFERENCES public.authority_grants(id);


--
-- Name: authority_grant_audit authority_grant_audit_grant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.authority_grant_audit
    ADD CONSTRAINT authority_grant_audit_grant_id_fkey FOREIGN KEY (grant_id) REFERENCES public.authority_grants(id);


--
-- Name: authority_grants authority_grants_issuer_account_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.authority_grants
    ADD CONSTRAINT authority_grants_issuer_account_fkey FOREIGN KEY (issuer_account) REFERENCES public.authority_accounts(id);


--
-- Name: authority_grants authority_grants_issuing_grant_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.authority_grants
    ADD CONSTRAINT authority_grants_issuing_grant_fkey FOREIGN KEY (issuing_grant) REFERENCES public.authority_grants(id);


--
-- Name: authority_grants authority_grants_issuing_token_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.authority_grants
    ADD CONSTRAINT authority_grants_issuing_token_fkey FOREIGN KEY (issuing_token) REFERENCES public.service_tokens(id);


--
-- Name: authority_grants authority_grants_revoked_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.authority_grants
    ADD CONSTRAINT authority_grants_revoked_by_fkey FOREIGN KEY (revoked_by) REFERENCES public.authority_accounts(id);


--
-- Name: authority_grants authority_grants_revoking_grant_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.authority_grants
    ADD CONSTRAINT authority_grants_revoking_grant_fkey FOREIGN KEY (revoking_grant) REFERENCES public.authority_grants(id);


--
-- Name: authority_grants authority_grants_role_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.authority_grants
    ADD CONSTRAINT authority_grants_role_id_fkey FOREIGN KEY (role_id) REFERENCES public.authority_roles(id);


--
-- Name: authority_grants authority_grants_subject_account_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.authority_grants
    ADD CONSTRAINT authority_grants_subject_account_fkey FOREIGN KEY (subject_account) REFERENCES public.authority_accounts(id);


--
-- Name: authority_role_actions authority_role_actions_role_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.authority_role_actions
    ADD CONSTRAINT authority_role_actions_role_id_fkey FOREIGN KEY (role_id) REFERENCES public.authority_roles(id);


--
-- Name: authority_roles authority_roles_intrinsic_account_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.authority_roles
    ADD CONSTRAINT authority_roles_intrinsic_account_fkey FOREIGN KEY (intrinsic_account) REFERENCES public.authority_accounts(id);


--
-- Name: authority_scopes authority_scope_container_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.authority_scopes
    ADD CONSTRAINT authority_scope_container_fkey FOREIGN KEY (container) REFERENCES public.authority_scopes(id) ON DELETE RESTRICT;


--
-- Name: authority_scopes authority_scopes_account_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.authority_scopes
    ADD CONSTRAINT authority_scopes_account_id_fkey FOREIGN KEY (account_id) REFERENCES public.authority_accounts(id) ON DELETE CASCADE;


--
-- Name: authority_scopes authority_scopes_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.authority_scopes
    ADD CONSTRAINT authority_scopes_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;


--
-- Name: binary_blob_aliases binary_blob_aliases_scope_sha256_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.binary_blob_aliases
    ADD CONSTRAINT binary_blob_aliases_scope_sha256_fkey FOREIGN KEY (scope, sha256) REFERENCES public.binary_blobs(scope, sha256);


--
-- Name: binary_blob_claims binary_blob_claims_scope_sha256_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.binary_blob_claims
    ADD CONSTRAINT binary_blob_claims_scope_sha256_fkey FOREIGN KEY (scope, sha256) REFERENCES public.binary_blobs(scope, sha256);


--
-- Name: board_decisions board_decisions_child_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.board_decisions
    ADD CONSTRAINT board_decisions_child_fkey FOREIGN KEY (child) REFERENCES public.board_topics(id);


--
-- Name: board_decisions board_decisions_request_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.board_decisions
    ADD CONSTRAINT board_decisions_request_fkey FOREIGN KEY (request) REFERENCES public.board_messages(id);


--
-- Name: board_message_instances board_message_instances_account_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.board_message_instances
    ADD CONSTRAINT board_message_instances_account_fkey FOREIGN KEY (account) REFERENCES public.admins(handle);


--
-- Name: board_message_instances board_message_instances_conversation_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.board_message_instances
    ADD CONSTRAINT board_message_instances_conversation_fkey FOREIGN KEY (conversation) REFERENCES public.conversations(id);


--
-- Name: board_message_instances board_message_instances_topic_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.board_message_instances
    ADD CONSTRAINT board_message_instances_topic_fkey FOREIGN KEY (topic) REFERENCES public.board_topics(id);


--
-- Name: board_message_route_bindings board_message_route_bindings_account_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.board_message_route_bindings
    ADD CONSTRAINT board_message_route_bindings_account_fkey FOREIGN KEY (account) REFERENCES public.admins(handle);


--
-- Name: board_message_route_bindings board_message_route_bindings_instance_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.board_message_route_bindings
    ADD CONSTRAINT board_message_route_bindings_instance_fkey FOREIGN KEY (instance) REFERENCES public.board_message_instances(id);


--
-- Name: board_message_routes board_message_routes_message_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.board_message_routes
    ADD CONSTRAINT board_message_routes_message_fkey FOREIGN KEY (message) REFERENCES public.board_messages(id);


--
-- Name: board_message_routes board_message_routes_recipient_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.board_message_routes
    ADD CONSTRAINT board_message_routes_recipient_fkey FOREIGN KEY (recipient) REFERENCES public.board_message_instances(id);


--
-- Name: board_message_routes board_message_routes_reply_to_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.board_message_routes
    ADD CONSTRAINT board_message_routes_reply_to_fkey FOREIGN KEY (reply_to) REFERENCES public.board_message_routes(message);


--
-- Name: board_message_routes board_message_routes_sender_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.board_message_routes
    ADD CONSTRAINT board_message_routes_sender_fkey FOREIGN KEY (sender) REFERENCES public.board_message_instances(id);


--
-- Name: board_messages board_messages_conversation_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.board_messages
    ADD CONSTRAINT board_messages_conversation_fkey FOREIGN KEY (conversation) REFERENCES public.conversations(id);


--
-- Name: board_messages board_messages_reply_to_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.board_messages
    ADD CONSTRAINT board_messages_reply_to_fkey FOREIGN KEY (reply_to) REFERENCES public.board_messages(id);


--
-- Name: board_messages board_messages_topic_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.board_messages
    ADD CONSTRAINT board_messages_topic_fkey FOREIGN KEY (topic) REFERENCES public.board_topics(id);


--
-- Name: board_notices board_notices_message_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.board_notices
    ADD CONSTRAINT board_notices_message_fkey FOREIGN KEY (message) REFERENCES public.board_messages(id);


--
-- Name: board_notices board_notices_topic_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.board_notices
    ADD CONSTRAINT board_notices_topic_fkey FOREIGN KEY (topic) REFERENCES public.board_topics(id);


--
-- Name: board_notices board_notices_wake_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.board_notices
    ADD CONSTRAINT board_notices_wake_fkey FOREIGN KEY (wake) REFERENCES public.firings(id);


--
-- Name: board_post_receipts board_post_receipts_account_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.board_post_receipts
    ADD CONSTRAINT board_post_receipts_account_fkey FOREIGN KEY (account) REFERENCES public.admins(handle);


--
-- Name: board_post_receipts board_post_receipts_message_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.board_post_receipts
    ADD CONSTRAINT board_post_receipts_message_id_fkey FOREIGN KEY (message_id) REFERENCES public.board_messages(id);


--
-- Name: board_reads board_reads_conversation_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.board_reads
    ADD CONSTRAINT board_reads_conversation_fkey FOREIGN KEY (conversation) REFERENCES public.conversations(id);


--
-- Name: board_reads board_reads_seen_through_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.board_reads
    ADD CONSTRAINT board_reads_seen_through_fkey FOREIGN KEY (seen_through) REFERENCES public.board_messages(id);


--
-- Name: board_reads board_reads_topic_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.board_reads
    ADD CONSTRAINT board_reads_topic_fkey FOREIGN KEY (topic) REFERENCES public.board_topics(id);


--
-- Name: board_seats board_seats_conversation_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.board_seats
    ADD CONSTRAINT board_seats_conversation_fkey FOREIGN KEY (conversation) REFERENCES public.conversations(id);


--
-- Name: board_seats board_seats_seen_through_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.board_seats
    ADD CONSTRAINT board_seats_seen_through_fkey FOREIGN KEY (seen_through) REFERENCES public.board_messages(id);


--
-- Name: board_seats board_seats_topic_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.board_seats
    ADD CONSTRAINT board_seats_topic_fkey FOREIGN KEY (topic) REFERENCES public.board_topics(id);


--
-- Name: board_topics board_topics_account_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.board_topics
    ADD CONSTRAINT board_topics_account_fkey FOREIGN KEY (account) REFERENCES public.admins(handle);


--
-- Name: board_topics board_topics_allowance_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.board_topics
    ADD CONSTRAINT board_topics_allowance_id_fkey FOREIGN KEY (allowance_id) REFERENCES public.execution_allowances(id);


--
-- Name: board_topics board_topics_origin_conversation_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.board_topics
    ADD CONSTRAINT board_topics_origin_conversation_fkey FOREIGN KEY (origin_conversation) REFERENCES public.conversations(id);


--
-- Name: board_topics board_topics_parent_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.board_topics
    ADD CONSTRAINT board_topics_parent_fkey FOREIGN KEY (parent) REFERENCES public.board_topics(id);


--
-- Name: board_topics board_topics_quiet_through_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.board_topics
    ADD CONSTRAINT board_topics_quiet_through_fkey FOREIGN KEY (quiet_through) REFERENCES public.board_messages(id);


--
-- Name: board_topics board_topics_root_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.board_topics
    ADD CONSTRAINT board_topics_root_fkey FOREIGN KEY (root) REFERENCES public.board_topics(id);


--
-- Name: chapters chapters_document_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chapters
    ADD CONSTRAINT chapters_document_id_fkey FOREIGN KEY (document_id) REFERENCES public.documents(id) ON DELETE CASCADE;


--
-- Name: chunks chunks_code_space_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chunks
    ADD CONSTRAINT chunks_code_space_id_fkey FOREIGN KEY (code_space_id) REFERENCES public.embedding_spaces(id);


--
-- Name: chunks_embedding_failures chunks_embedding_failures_slot_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chunks_embedding_failures
    ADD CONSTRAINT chunks_embedding_failures_slot_fkey FOREIGN KEY (slot) REFERENCES public.embedding_slots(slot);


--
-- Name: chunks_embedding_failures chunks_embedding_failures_source_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chunks_embedding_failures
    ADD CONSTRAINT chunks_embedding_failures_source_id_fkey FOREIGN KEY (source_id) REFERENCES public.chunks(id) ON DELETE CASCADE;


--
-- Name: chunks_embedding_failures chunks_embedding_failures_space_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chunks_embedding_failures
    ADD CONSTRAINT chunks_embedding_failures_space_id_fkey FOREIGN KEY (space_id) REFERENCES public.embedding_spaces(id);


--
-- Name: chunks_embedding_staging chunks_embedding_staging_slot_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chunks_embedding_staging
    ADD CONSTRAINT chunks_embedding_staging_slot_fkey FOREIGN KEY (slot) REFERENCES public.embedding_slots(slot);


--
-- Name: chunks_embedding_staging chunks_embedding_staging_source_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chunks_embedding_staging
    ADD CONSTRAINT chunks_embedding_staging_source_id_fkey FOREIGN KEY (source_id) REFERENCES public.chunks(id) ON DELETE CASCADE;


--
-- Name: chunks_embedding_staging chunks_embedding_staging_space_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chunks_embedding_staging
    ADD CONSTRAINT chunks_embedding_staging_space_id_fkey FOREIGN KEY (space_id) REFERENCES public.embedding_spaces(id);


--
-- Name: chunks chunks_paragraph_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chunks
    ADD CONSTRAINT chunks_paragraph_id_fkey FOREIGN KEY (paragraph_id) REFERENCES public.paragraphs(id) ON DELETE CASCADE;


--
-- Name: chunks chunks_prose_space_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chunks
    ADD CONSTRAINT chunks_prose_space_id_fkey FOREIGN KEY (prose_space_id) REFERENCES public.embedding_spaces(id);


--
-- Name: citations citations_conversation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.citations
    ADD CONSTRAINT citations_conversation_id_fkey FOREIGN KEY (conversation_id) REFERENCES public.conversations(id);


--
-- Name: citations citations_document_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.citations
    ADD CONSTRAINT citations_document_id_fkey FOREIGN KEY (document_id) REFERENCES public.documents(id) ON DELETE SET NULL;


--
-- Name: citations citations_paragraph_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.citations
    ADD CONSTRAINT citations_paragraph_id_fkey FOREIGN KEY (paragraph_id) REFERENCES public.paragraphs(id) ON DELETE SET NULL;


--
-- Name: code_outlines code_outlines_document_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.code_outlines
    ADD CONSTRAINT code_outlines_document_id_fkey FOREIGN KEY (document_id) REFERENCES public.documents(id) ON DELETE CASCADE;


--
-- Name: code_passages code_passages_chunk_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.code_passages
    ADD CONSTRAINT code_passages_chunk_id_fkey FOREIGN KEY (chunk_id) REFERENCES public.chunks(id) ON DELETE CASCADE;


--
-- Name: code_passages code_passages_document_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.code_passages
    ADD CONSTRAINT code_passages_document_id_fkey FOREIGN KEY (document_id) REFERENCES public.documents(id) ON DELETE CASCADE;


--
-- Name: code_symbols code_symbols_document_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.code_symbols
    ADD CONSTRAINT code_symbols_document_id_fkey FOREIGN KEY (document_id) REFERENCES public.code_outlines(document_id) ON DELETE CASCADE;


--
-- Name: code_symbols code_symbols_document_id_parent_ordinal_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.code_symbols
    ADD CONSTRAINT code_symbols_document_id_parent_ordinal_fkey FOREIGN KEY (document_id, parent_ordinal) REFERENCES public.code_symbols(document_id, ordinal);


--
-- Name: code_workspace_indexes code_workspace_indexes_revision_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.code_workspace_indexes
    ADD CONSTRAINT code_workspace_indexes_revision_id_fkey FOREIGN KEY (revision_id) REFERENCES public.information_revisions(id) ON DELETE CASCADE;


--
-- Name: code_workspace_indexes code_workspace_indexes_workspace_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.code_workspace_indexes
    ADD CONSTRAINT code_workspace_indexes_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES public.code_workspaces(id) ON DELETE CASCADE;


--
-- Name: code_workspace_mutations code_workspace_mutations_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.code_workspace_mutations
    ADD CONSTRAINT code_workspace_mutations_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;


--
-- Name: code_workspace_sources code_workspace_sources_revision_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.code_workspace_sources
    ADD CONSTRAINT code_workspace_sources_revision_id_fkey FOREIGN KEY (revision_id) REFERENCES public.information_revisions(id) ON DELETE SET NULL;


--
-- Name: code_workspace_sources code_workspace_sources_workspace_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.code_workspace_sources
    ADD CONSTRAINT code_workspace_sources_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES public.code_workspaces(id) ON DELETE CASCADE;


--
-- Name: code_workspaces code_workspaces_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.code_workspaces
    ADD CONSTRAINT code_workspaces_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;


--
-- Name: command_invocations command_invocations_account_handle_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.command_invocations
    ADD CONSTRAINT command_invocations_account_handle_fkey FOREIGN KEY (account_handle) REFERENCES public.admins(handle);


--
-- Name: command_invocations command_invocations_conversation_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.command_invocations
    ADD CONSTRAINT command_invocations_conversation_fkey FOREIGN KEY (conversation) REFERENCES public.conversations(id) ON DELETE CASCADE;


--
-- Name: compactions compactions_reach_a_turn_that_was_spoken; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.compactions
    ADD CONSTRAINT compactions_reach_a_turn_that_was_spoken FOREIGN KEY (conversation_id, through_ordinal) REFERENCES public.turns(conversation_id, ordinal);


--
-- Name: contained_applications contained_applications_container_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.contained_applications
    ADD CONSTRAINT contained_applications_container_fkey FOREIGN KEY (container) REFERENCES public.authority_scopes(id) ON DELETE RESTRICT;


--
-- Name: contained_applications contained_applications_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.contained_applications
    ADD CONSTRAINT contained_applications_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;


--
-- Name: contained_applications contained_applications_scope_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.contained_applications
    ADD CONSTRAINT contained_applications_scope_id_fkey FOREIGN KEY (scope_id) REFERENCES public.authority_scopes(id) ON DELETE CASCADE;


--
-- Name: contained_principals contained_principals_scope_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.contained_principals
    ADD CONSTRAINT contained_principals_scope_id_fkey FOREIGN KEY (scope_id) REFERENCES public.authority_scopes(id) ON DELETE CASCADE;


--
-- Name: conversations conversations_a_child_names_a_conversation; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.conversations
    ADD CONSTRAINT conversations_a_child_names_a_conversation FOREIGN KEY (parent_id) REFERENCES public.conversations(id);


--
-- Name: conversations conversations_allowance_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.conversations
    ADD CONSTRAINT conversations_allowance_id_fkey FOREIGN KEY (allowance_id) REFERENCES public.execution_allowances(id);


--
-- Name: conversations conversations_an_opening_is_a_block_this_table_holds; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.conversations
    ADD CONSTRAINT conversations_an_opening_is_a_block_this_table_holds FOREIGN KEY (opening_block) REFERENCES public.system_blocks(hash);


--
-- Name: conversations conversations_local_hooks_are_a_set_this_table_holds; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.conversations
    ADD CONSTRAINT conversations_local_hooks_are_a_set_this_table_holds FOREIGN KEY (local_hooks) REFERENCES public.local_hook_sets(hash);


--
-- Name: conversations conversations_project_exists; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.conversations
    ADD CONSTRAINT conversations_project_exists FOREIGN KEY (project_id) REFERENCES public.projects(id);


--
-- Name: digest_children digest_children_child_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.digest_children
    ADD CONSTRAINT digest_children_child_id_fkey FOREIGN KEY (child_id) REFERENCES public.digests(id);


--
-- Name: digest_children digest_children_parent_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.digest_children
    ADD CONSTRAINT digest_children_parent_id_fkey FOREIGN KEY (parent_id) REFERENCES public.digests(id);


--
-- Name: digest_memories digest_memories_digest_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.digest_memories
    ADD CONSTRAINT digest_memories_digest_id_fkey FOREIGN KEY (digest_id) REFERENCES public.digests(id);


--
-- Name: digest_memories digest_memories_memory_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.digest_memories
    ADD CONSTRAINT digest_memories_memory_id_fkey FOREIGN KEY (memory_id) REFERENCES public.memories(id);


--
-- Name: digest_revisions digest_revisions_digest_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.digest_revisions
    ADD CONSTRAINT digest_revisions_digest_id_fkey FOREIGN KEY (digest_id) REFERENCES public.digests(id);


--
-- Name: digest_spans digest_spans_conversation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.digest_spans
    ADD CONSTRAINT digest_spans_conversation_id_fkey FOREIGN KEY (conversation_id) REFERENCES public.conversations(id);


--
-- Name: digest_spans digest_spans_digest_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.digest_spans
    ADD CONSTRAINT digest_spans_digest_id_fkey FOREIGN KEY (digest_id) REFERENCES public.digests(id);


--
-- Name: digests digests_code_space_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.digests
    ADD CONSTRAINT digests_code_space_id_fkey FOREIGN KEY (code_space_id) REFERENCES public.embedding_spaces(id);


--
-- Name: digests_embedding_failures digests_embedding_failures_slot_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.digests_embedding_failures
    ADD CONSTRAINT digests_embedding_failures_slot_fkey FOREIGN KEY (slot) REFERENCES public.embedding_slots(slot);


--
-- Name: digests_embedding_failures digests_embedding_failures_source_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.digests_embedding_failures
    ADD CONSTRAINT digests_embedding_failures_source_id_fkey FOREIGN KEY (source_id) REFERENCES public.digests(id) ON DELETE CASCADE;


--
-- Name: digests_embedding_failures digests_embedding_failures_space_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.digests_embedding_failures
    ADD CONSTRAINT digests_embedding_failures_space_id_fkey FOREIGN KEY (space_id) REFERENCES public.embedding_spaces(id);


--
-- Name: digests_embedding_staging digests_embedding_staging_slot_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.digests_embedding_staging
    ADD CONSTRAINT digests_embedding_staging_slot_fkey FOREIGN KEY (slot) REFERENCES public.embedding_slots(slot);


--
-- Name: digests_embedding_staging digests_embedding_staging_source_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.digests_embedding_staging
    ADD CONSTRAINT digests_embedding_staging_source_id_fkey FOREIGN KEY (source_id) REFERENCES public.digests(id) ON DELETE CASCADE;


--
-- Name: digests_embedding_staging digests_embedding_staging_space_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.digests_embedding_staging
    ADD CONSTRAINT digests_embedding_staging_space_id_fkey FOREIGN KEY (space_id) REFERENCES public.embedding_spaces(id);


--
-- Name: digests digests_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.digests
    ADD CONSTRAINT digests_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id);


--
-- Name: digests digests_prose_space_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.digests
    ADD CONSTRAINT digests_prose_space_id_fkey FOREIGN KEY (prose_space_id) REFERENCES public.embedding_spaces(id);


--
-- Name: document_references document_references_document_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.document_references
    ADD CONSTRAINT document_references_document_id_fkey FOREIGN KEY (document_id) REFERENCES public.documents(id) ON DELETE CASCADE;


--
-- Name: documents documents_code_space_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.documents
    ADD CONSTRAINT documents_code_space_id_fkey FOREIGN KEY (code_space_id) REFERENCES public.embedding_spaces(id);


--
-- Name: documents_embedding_failures documents_embedding_failures_slot_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.documents_embedding_failures
    ADD CONSTRAINT documents_embedding_failures_slot_fkey FOREIGN KEY (slot) REFERENCES public.embedding_slots(slot);


--
-- Name: documents_embedding_failures documents_embedding_failures_source_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.documents_embedding_failures
    ADD CONSTRAINT documents_embedding_failures_source_id_fkey FOREIGN KEY (source_id) REFERENCES public.documents(id) ON DELETE CASCADE;


--
-- Name: documents_embedding_failures documents_embedding_failures_space_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.documents_embedding_failures
    ADD CONSTRAINT documents_embedding_failures_space_id_fkey FOREIGN KEY (space_id) REFERENCES public.embedding_spaces(id);


--
-- Name: documents_embedding_staging documents_embedding_staging_slot_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.documents_embedding_staging
    ADD CONSTRAINT documents_embedding_staging_slot_fkey FOREIGN KEY (slot) REFERENCES public.embedding_slots(slot);


--
-- Name: documents_embedding_staging documents_embedding_staging_source_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.documents_embedding_staging
    ADD CONSTRAINT documents_embedding_staging_source_id_fkey FOREIGN KEY (source_id) REFERENCES public.documents(id) ON DELETE CASCADE;


--
-- Name: documents_embedding_staging documents_embedding_staging_space_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.documents_embedding_staging
    ADD CONSTRAINT documents_embedding_staging_space_id_fkey FOREIGN KEY (space_id) REFERENCES public.embedding_spaces(id);


--
-- Name: documents documents_prose_space_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.documents
    ADD CONSTRAINT documents_prose_space_id_fkey FOREIGN KEY (prose_space_id) REFERENCES public.embedding_spaces(id);


--
-- Name: embedding_input_limits embedding_input_limits_space_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.embedding_input_limits
    ADD CONSTRAINT embedding_input_limits_space_id_fkey FOREIGN KEY (space_id) REFERENCES public.embedding_spaces(id);


--
-- Name: embedding_repair_attempts embedding_repair_attempts_slot_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.embedding_repair_attempts
    ADD CONSTRAINT embedding_repair_attempts_slot_fkey FOREIGN KEY (slot) REFERENCES public.embedding_slots(slot);


--
-- Name: embedding_repair_attempts embedding_repair_attempts_space_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.embedding_repair_attempts
    ADD CONSTRAINT embedding_repair_attempts_space_id_fkey FOREIGN KEY (space_id) REFERENCES public.embedding_spaces(id);


--
-- Name: embedding_repair_scans embedding_repair_scans_slot_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.embedding_repair_scans
    ADD CONSTRAINT embedding_repair_scans_slot_fkey FOREIGN KEY (slot) REFERENCES public.embedding_slots(slot);


--
-- Name: embedding_repair_scans embedding_repair_scans_space_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.embedding_repair_scans
    ADD CONSTRAINT embedding_repair_scans_space_id_fkey FOREIGN KEY (space_id) REFERENCES public.embedding_spaces(id);


--
-- Name: embedding_repair_work embedding_repair_work_slot_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.embedding_repair_work
    ADD CONSTRAINT embedding_repair_work_slot_fkey FOREIGN KEY (slot) REFERENCES public.embedding_slots(slot);


--
-- Name: embedding_repair_work embedding_repair_work_space_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.embedding_repair_work
    ADD CONSTRAINT embedding_repair_work_space_id_fkey FOREIGN KEY (space_id) REFERENCES public.embedding_spaces(id);


--
-- Name: embedding_slots embedding_slots_active_space_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.embedding_slots
    ADD CONSTRAINT embedding_slots_active_space_id_fkey FOREIGN KEY (active_space_id) REFERENCES public.embedding_spaces(id);


--
-- Name: embedding_slots embedding_slots_target_space_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.embedding_slots
    ADD CONSTRAINT embedding_slots_target_space_id_fkey FOREIGN KEY (target_space_id) REFERENCES public.embedding_spaces(id);


--
-- Name: entries entries_are_superseded_by_an_entry; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.entries
    ADD CONSTRAINT entries_are_superseded_by_an_entry FOREIGN KEY (conversation_id, superseded_by) REFERENCES public.entries(conversation_id, ordinal);


--
-- Name: entries entries_belong_to_a_conversation; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.entries
    ADD CONSTRAINT entries_belong_to_a_conversation FOREIGN KEY (conversation_id) REFERENCES public.conversations(id);


--
-- Name: execution_allowance_claims execution_allowance_claims_budget_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.execution_allowance_claims
    ADD CONSTRAINT execution_allowance_claims_budget_id_fkey FOREIGN KEY (budget_id) REFERENCES public.execution_allowances(id);


--
-- Name: firings firings_topic_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.firings
    ADD CONSTRAINT firings_topic_fkey FOREIGN KEY (topic) REFERENCES public.board_topics(id);


--
-- Name: hosted_tool_scope_mutations hosted_tool_scope_mutations_actor_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.hosted_tool_scope_mutations
    ADD CONSTRAINT hosted_tool_scope_mutations_actor_fkey FOREIGN KEY (actor) REFERENCES public.admins(handle);


--
-- Name: hosted_tool_scope_mutations hosted_tool_scope_mutations_binding_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.hosted_tool_scope_mutations
    ADD CONSTRAINT hosted_tool_scope_mutations_binding_id_fkey FOREIGN KEY (binding_id) REFERENCES public.hosted_tool_scopes(id);


--
-- Name: hosted_tool_scope_workers hosted_tool_scope_workers_binding_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.hosted_tool_scope_workers
    ADD CONSTRAINT hosted_tool_scope_workers_binding_id_fkey FOREIGN KEY (binding_id) REFERENCES public.hosted_tool_scopes(id);


--
-- Name: hosted_tool_scopes hosted_tool_scopes_connection_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.hosted_tool_scopes
    ADD CONSTRAINT hosted_tool_scopes_connection_id_fkey FOREIGN KEY (connection_id) REFERENCES public.provider_connections(id);


--
-- Name: hosted_tool_scopes hosted_tool_scopes_credential_subject_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.hosted_tool_scopes
    ADD CONSTRAINT hosted_tool_scopes_credential_subject_fkey FOREIGN KEY (credential_subject) REFERENCES public.admins(handle);


--
-- Name: hosted_tool_scopes hosted_tool_scopes_owner_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.hosted_tool_scopes
    ADD CONSTRAINT hosted_tool_scopes_owner_fkey FOREIGN KEY (owner) REFERENCES public.admins(handle);


--
-- Name: hosted_tool_scopes hosted_tool_scopes_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.hosted_tool_scopes
    ADD CONSTRAINT hosted_tool_scopes_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE SET NULL;


--
-- Name: inference_accounting_events inference_accounting_events_call_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inference_accounting_events
    ADD CONSTRAINT inference_accounting_events_call_id_fkey FOREIGN KEY (call_id) REFERENCES public.inference_calls(call_id);


--
-- Name: inference_accounting_events inference_accounting_events_journal_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inference_accounting_events
    ADD CONSTRAINT inference_accounting_events_journal_id_fkey FOREIGN KEY (journal_id) REFERENCES public.inference_accounting_journals(journal_id);


--
-- Name: inference_accounting_instances inference_accounting_instances_journal_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inference_accounting_instances
    ADD CONSTRAINT inference_accounting_instances_journal_id_fkey FOREIGN KEY (journal_id) REFERENCES public.inference_accounting_journals(journal_id);


--
-- Name: inference_admissions inference_admissions_operation_id_budget_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inference_admissions
    ADD CONSTRAINT inference_admissions_operation_id_budget_id_fkey FOREIGN KEY (operation_id, budget_id) REFERENCES public.operation_inference_budgets(operation_id, budget_id);


--
-- Name: inference_admissions inference_admissions_preparation_revision_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inference_admissions
    ADD CONSTRAINT inference_admissions_preparation_revision_fkey FOREIGN KEY (preparation_revision) REFERENCES public.information_revisions(id);


--
-- Name: inference_admissions inference_admissions_query_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inference_admissions
    ADD CONSTRAINT inference_admissions_query_id_fkey FOREIGN KEY (query_id) REFERENCES public.embedding_queries(id);


--
-- Name: inference_attempts inference_attempts_call_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inference_attempts
    ADD CONSTRAINT inference_attempts_call_id_fkey FOREIGN KEY (call_id) REFERENCES public.inference_calls(call_id);


--
-- Name: inference_attempts inference_attempts_price_version_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inference_attempts
    ADD CONSTRAINT inference_attempts_price_version_fkey FOREIGN KEY (price_version) REFERENCES public.inference_pricing_versions(version);


--
-- Name: inference_calls inference_calls_instance_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inference_calls
    ADD CONSTRAINT inference_calls_instance_id_fkey FOREIGN KEY (instance_id) REFERENCES public.inference_accounting_instances(instance_id);


--
-- Name: inference_calls inference_calls_price_version_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.inference_calls
    ADD CONSTRAINT inference_calls_price_version_fkey FOREIGN KEY (price_version) REFERENCES public.inference_pricing_versions(version);


--
-- Name: information_acquisitions information_acquisitions_account_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_acquisitions
    ADD CONSTRAINT information_acquisitions_account_fkey FOREIGN KEY (account) REFERENCES public.admins(handle);


--
-- Name: information_acquisitions information_acquisitions_revision_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_acquisitions
    ADD CONSTRAINT information_acquisitions_revision_id_fkey FOREIGN KEY (revision_id) REFERENCES public.information_revisions(id);


--
-- Name: information_commands information_commands_account_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_commands
    ADD CONSTRAINT information_commands_account_fkey FOREIGN KEY (account) REFERENCES public.admins(handle);


--
-- Name: information_commands information_commands_revision_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_commands
    ADD CONSTRAINT information_commands_revision_id_fkey FOREIGN KEY (revision_id) REFERENCES public.information_revisions(id);


--
-- Name: information_content_type_versions information_content_type_versions_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_content_type_versions
    ADD CONSTRAINT information_content_type_versions_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.admins(handle);


--
-- Name: information_content_type_versions information_content_type_versions_type_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_content_type_versions
    ADD CONSTRAINT information_content_type_versions_type_id_fkey FOREIGN KEY (type_id) REFERENCES public.information_content_types(id);


--
-- Name: information_content_types information_content_types_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_content_types
    ADD CONSTRAINT information_content_types_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.admins(handle);


--
-- Name: information_document_policies information_document_policies_document_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_document_policies
    ADD CONSTRAINT information_document_policies_document_id_fkey FOREIGN KEY (document_id) REFERENCES public.documents(id) ON DELETE CASCADE;


--
-- Name: information_document_policies information_document_policies_owner_handle_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_document_policies
    ADD CONSTRAINT information_document_policies_owner_handle_fkey FOREIGN KEY (owner_handle) REFERENCES public.admins(handle);


--
-- Name: information_document_policies information_document_policies_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_document_policies
    ADD CONSTRAINT information_document_policies_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id);


--
-- Name: information_evidence information_evidence_owner_handle_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_evidence
    ADD CONSTRAINT information_evidence_owner_handle_fkey FOREIGN KEY (owner_handle) REFERENCES public.admins(handle);


--
-- Name: information_evidence information_evidence_revision_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_evidence
    ADD CONSTRAINT information_evidence_revision_id_fkey FOREIGN KEY (revision_id) REFERENCES public.information_revisions(id);


--
-- Name: information_import_origins information_import_origins_revision_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_import_origins
    ADD CONSTRAINT information_import_origins_revision_id_fkey FOREIGN KEY (revision_id) REFERENCES public.information_revisions(id);


--
-- Name: information_inputs information_inputs_derived_revision_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_inputs
    ADD CONSTRAINT information_inputs_derived_revision_fkey FOREIGN KEY (derived_revision) REFERENCES public.information_revisions(id);


--
-- Name: information_inputs information_inputs_input_revision_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_inputs
    ADD CONSTRAINT information_inputs_input_revision_fkey FOREIGN KEY (input_revision) REFERENCES public.information_revisions(id);


--
-- Name: information_job_inputs information_job_inputs_owner_handle_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_job_inputs
    ADD CONSTRAINT information_job_inputs_owner_handle_fkey FOREIGN KEY (owner_handle) REFERENCES public.admins(handle);


--
-- Name: information_job_inputs information_job_inputs_revision_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_job_inputs
    ADD CONSTRAINT information_job_inputs_revision_id_fkey FOREIGN KEY (revision_id) REFERENCES public.information_revisions(id);


--
-- Name: information_links information_links_actor_handle_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_links
    ADD CONSTRAINT information_links_actor_handle_fkey FOREIGN KEY (actor_handle) REFERENCES public.admins(handle);


--
-- Name: information_links information_links_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_links
    ADD CONSTRAINT information_links_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id);


--
-- Name: information_links information_links_revision_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_links
    ADD CONSTRAINT information_links_revision_id_fkey FOREIGN KEY (revision_id) REFERENCES public.information_revisions(id);


--
-- Name: information_model_steps information_model_steps_owner_handle_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_model_steps
    ADD CONSTRAINT information_model_steps_owner_handle_fkey FOREIGN KEY (owner_handle) REFERENCES public.admins(handle);


--
-- Name: information_model_steps information_model_steps_revision_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_model_steps
    ADD CONSTRAINT information_model_steps_revision_id_fkey FOREIGN KEY (revision_id) REFERENCES public.information_revisions(id);


--
-- Name: information_navigation_audit information_navigation_audit_account_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_navigation_audit
    ADD CONSTRAINT information_navigation_audit_account_fkey FOREIGN KEY (account) REFERENCES public.admins(handle);


--
-- Name: information_navigation_policies information_navigation_policies_actor_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_navigation_policies
    ADD CONSTRAINT information_navigation_policies_actor_fkey FOREIGN KEY (actor) REFERENCES public.admins(handle);


--
-- Name: information_navigation_receipts information_navigation_receipts_account_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_navigation_receipts
    ADD CONSTRAINT information_navigation_receipts_account_fkey FOREIGN KEY (account) REFERENCES public.admins(handle);


--
-- Name: information_navigation_views information_navigation_views_account_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_navigation_views
    ADD CONSTRAINT information_navigation_views_account_fkey FOREIGN KEY (account) REFERENCES public.admins(handle);


--
-- Name: information_payload_assignments information_payload_assignments_actor_handle_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_payload_assignments
    ADD CONSTRAINT information_payload_assignments_actor_handle_fkey FOREIGN KEY (actor_handle) REFERENCES public.admins(handle);


--
-- Name: information_payload_assignments information_payload_assignments_owner_handle_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_payload_assignments
    ADD CONSTRAINT information_payload_assignments_owner_handle_fkey FOREIGN KEY (owner_handle) REFERENCES public.admins(handle);


--
-- Name: information_policy_assignments information_policy_assignments_actor_handle_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_policy_assignments
    ADD CONSTRAINT information_policy_assignments_actor_handle_fkey FOREIGN KEY (actor_handle) REFERENCES public.admins(handle);


--
-- Name: information_policy_assignments information_policy_assignments_owner_handle_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_policy_assignments
    ADD CONSTRAINT information_policy_assignments_owner_handle_fkey FOREIGN KEY (owner_handle) REFERENCES public.admins(handle);


--
-- Name: information_projection_reuse information_projection_reuse_origin_revision_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_projection_reuse
    ADD CONSTRAINT information_projection_reuse_origin_revision_fkey FOREIGN KEY (origin_revision) REFERENCES public.information_revisions(id);


--
-- Name: information_projection_reuse information_projection_reuse_revision_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_projection_reuse
    ADD CONSTRAINT information_projection_reuse_revision_id_fkey FOREIGN KEY (revision_id) REFERENCES public.information_revisions(id);


--
-- Name: information_reference_obligations information_reference_obligations_pinned_revision_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_reference_obligations
    ADD CONSTRAINT information_reference_obligations_pinned_revision_fkey FOREIGN KEY (pinned_revision) REFERENCES public.information_revisions(id);


--
-- Name: information_reference_obligations information_reference_obligations_relationship_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_reference_obligations
    ADD CONSTRAINT information_reference_obligations_relationship_fkey FOREIGN KEY (relationship) REFERENCES public.information_relationships(id);


--
-- Name: information_reference_obligations information_reference_obligations_source_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_reference_obligations
    ADD CONSTRAINT information_reference_obligations_source_fkey FOREIGN KEY (source) REFERENCES public.information_resources(id);


--
-- Name: information_reference_obligations information_reference_obligations_target_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_reference_obligations
    ADD CONSTRAINT information_reference_obligations_target_fkey FOREIGN KEY (target) REFERENCES public.information_resources(id);


--
-- Name: information_relationships information_relationships_actor_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_relationships
    ADD CONSTRAINT information_relationships_actor_fkey FOREIGN KEY (actor) REFERENCES public.admins(handle);


--
-- Name: information_relationships information_relationships_pinned_revision_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_relationships
    ADD CONSTRAINT information_relationships_pinned_revision_fkey FOREIGN KEY (pinned_revision) REFERENCES public.information_revisions(id);


--
-- Name: information_relationships information_relationships_source_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_relationships
    ADD CONSTRAINT information_relationships_source_fkey FOREIGN KEY (source) REFERENCES public.information_resources(id);


--
-- Name: information_relationships information_relationships_target_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_relationships
    ADD CONSTRAINT information_relationships_target_fkey FOREIGN KEY (target) REFERENCES public.information_resources(id);


--
-- Name: information_report_citations information_report_citations_evidence_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_report_citations
    ADD CONSTRAINT information_report_citations_evidence_id_fkey FOREIGN KEY (evidence_id) REFERENCES public.information_evidence(id);


--
-- Name: information_report_citations information_report_citations_report_revision_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_report_citations
    ADD CONSTRAINT information_report_citations_report_revision_fkey FOREIGN KEY (report_revision) REFERENCES public.information_reports(revision_id);


--
-- Name: information_reports information_reports_feedback_revision_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_reports
    ADD CONSTRAINT information_reports_feedback_revision_fkey FOREIGN KEY (feedback_revision) REFERENCES public.information_revisions(id);


--
-- Name: information_reports information_reports_revision_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_reports
    ADD CONSTRAINT information_reports_revision_id_fkey FOREIGN KEY (revision_id) REFERENCES public.information_revisions(id);


--
-- Name: information_requests information_requests_account_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_requests
    ADD CONSTRAINT information_requests_account_fkey FOREIGN KEY (account) REFERENCES public.admins(handle);


--
-- Name: information_requests information_requests_revision_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_requests
    ADD CONSTRAINT information_requests_revision_id_fkey FOREIGN KEY (revision_id) REFERENCES public.information_revisions(id);


--
-- Name: information_resource_policies information_resource_policies_actor_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_resource_policies
    ADD CONSTRAINT information_resource_policies_actor_fkey FOREIGN KEY (actor) REFERENCES public.authority_accounts(id);


--
-- Name: information_resource_policies information_resource_policies_privacy_principal_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_resource_policies
    ADD CONSTRAINT information_resource_policies_privacy_principal_fkey FOREIGN KEY (privacy_principal) REFERENCES public.authority_accounts(id);


--
-- Name: information_resource_policies information_resource_policies_resource_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_resource_policies
    ADD CONSTRAINT information_resource_policies_resource_id_fkey FOREIGN KEY (resource_id) REFERENCES public.information_resources(id);


--
-- Name: information_resource_policy_history information_resource_policy_history_actor_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_resource_policy_history
    ADD CONSTRAINT information_resource_policy_history_actor_fkey FOREIGN KEY (actor) REFERENCES public.authority_accounts(id);


--
-- Name: information_resource_policy_history information_resource_policy_history_privacy_principal_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_resource_policy_history
    ADD CONSTRAINT information_resource_policy_history_privacy_principal_fkey FOREIGN KEY (privacy_principal) REFERENCES public.authority_accounts(id);


--
-- Name: information_resource_policy_history information_resource_policy_history_resource_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_resource_policy_history
    ADD CONSTRAINT information_resource_policy_history_resource_id_fkey FOREIGN KEY (resource_id) REFERENCES public.information_resources(id);


--
-- Name: information_resources information_resources_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_resources
    ADD CONSTRAINT information_resources_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.authority_accounts(id);


--
-- Name: information_resources information_resources_home_scope_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_resources
    ADD CONSTRAINT information_resources_home_scope_id_fkey FOREIGN KEY (home_scope_id) REFERENCES public.authority_scopes(id);


--
-- Name: information_resources information_resources_owner_handle_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_resources
    ADD CONSTRAINT information_resources_owner_handle_fkey FOREIGN KEY (owner_handle) REFERENCES public.admins(handle);


--
-- Name: information_resources information_resources_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_resources
    ADD CONSTRAINT information_resources_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id);


--
-- Name: information_reuse_preparations information_reuse_preparations_actor_request_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_reuse_preparations
    ADD CONSTRAINT information_reuse_preparations_actor_request_id_fkey FOREIGN KEY (actor, request_id) REFERENCES public.information_write_gates(account, request_id);


--
-- Name: information_revision_content information_revision_content_revision_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_revision_content
    ADD CONSTRAINT information_revision_content_revision_id_fkey FOREIGN KEY (revision_id) REFERENCES public.information_revisions(id);


--
-- Name: information_revision_content information_revision_content_type_version_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_revision_content
    ADD CONSTRAINT information_revision_content_type_version_fkey FOREIGN KEY (type_version) REFERENCES public.information_content_type_versions(id);


--
-- Name: information_revision_events information_revision_events_actor_handle_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_revision_events
    ADD CONSTRAINT information_revision_events_actor_handle_fkey FOREIGN KEY (actor_handle) REFERENCES public.admins(handle);


--
-- Name: information_revision_events information_revision_events_relay_project_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_revision_events
    ADD CONSTRAINT information_revision_events_relay_project_fkey FOREIGN KEY (relay_project) REFERENCES public.projects(id) ON DELETE CASCADE;


--
-- Name: information_revision_events information_revision_events_revision_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_revision_events
    ADD CONSTRAINT information_revision_events_revision_id_fkey FOREIGN KEY (revision_id) REFERENCES public.information_revisions(id) ON DELETE CASCADE;


--
-- Name: information_revisions information_revisions_created_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_revisions
    ADD CONSTRAINT information_revisions_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.authority_accounts(id);


--
-- Name: information_revisions information_revisions_resource_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_revisions
    ADD CONSTRAINT information_revisions_resource_id_fkey FOREIGN KEY (resource_id) REFERENCES public.information_resources(id);


--
-- Name: information_revisions information_revisions_revised_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_revisions
    ADD CONSTRAINT information_revisions_revised_by_fkey FOREIGN KEY (revised_by) REFERENCES public.admins(handle);


--
-- Name: information_revisions information_revisions_starting_revision_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_revisions
    ADD CONSTRAINT information_revisions_starting_revision_fkey FOREIGN KEY (starting_revision) REFERENCES public.information_revisions(id);


--
-- Name: information_stage_authorizations information_stage_authorizations_actor_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_stage_authorizations
    ADD CONSTRAINT information_stage_authorizations_actor_fkey FOREIGN KEY (actor) REFERENCES public.admins(handle);


--
-- Name: information_stage_authorizations information_stage_authorizations_revision_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_stage_authorizations
    ADD CONSTRAINT information_stage_authorizations_revision_id_fkey FOREIGN KEY (revision_id) REFERENCES public.information_revisions(id);


--
-- Name: information_steps information_steps_revision_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_steps
    ADD CONSTRAINT information_steps_revision_id_fkey FOREIGN KEY (revision_id) REFERENCES public.information_revisions(id);


--
-- Name: information_write_gates information_write_gates_account_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.information_write_gates
    ADD CONSTRAINT information_write_gates_account_fkey FOREIGN KEY (account) REFERENCES public.admins(handle);


--
-- Name: invocation_image_admissions invocation_image_admissions_invocation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.invocation_image_admissions
    ADD CONSTRAINT invocation_image_admissions_invocation_id_fkey FOREIGN KEY (invocation_id) REFERENCES public.relay_tool_invocations(id) ON DELETE CASCADE;


--
-- Name: jobs jobs_conversation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.jobs
    ADD CONSTRAINT jobs_conversation_id_fkey FOREIGN KEY (conversation_id) REFERENCES public.conversations(id) ON DELETE SET NULL;


--
-- Name: jobs jobs_project_exists; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.jobs
    ADD CONSTRAINT jobs_project_exists FOREIGN KEY (project_id) REFERENCES public.projects(id);


--
-- Name: memories memories_code_space_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.memories
    ADD CONSTRAINT memories_code_space_id_fkey FOREIGN KEY (code_space_id) REFERENCES public.embedding_spaces(id);


--
-- Name: memories_embedding_failures memories_embedding_failures_slot_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.memories_embedding_failures
    ADD CONSTRAINT memories_embedding_failures_slot_fkey FOREIGN KEY (slot) REFERENCES public.embedding_slots(slot);


--
-- Name: memories_embedding_failures memories_embedding_failures_source_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.memories_embedding_failures
    ADD CONSTRAINT memories_embedding_failures_source_id_fkey FOREIGN KEY (source_id) REFERENCES public.memories(id) ON DELETE CASCADE;


--
-- Name: memories_embedding_failures memories_embedding_failures_space_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.memories_embedding_failures
    ADD CONSTRAINT memories_embedding_failures_space_id_fkey FOREIGN KEY (space_id) REFERENCES public.embedding_spaces(id);


--
-- Name: memories_embedding_staging memories_embedding_staging_slot_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.memories_embedding_staging
    ADD CONSTRAINT memories_embedding_staging_slot_fkey FOREIGN KEY (slot) REFERENCES public.embedding_slots(slot);


--
-- Name: memories_embedding_staging memories_embedding_staging_source_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.memories_embedding_staging
    ADD CONSTRAINT memories_embedding_staging_source_id_fkey FOREIGN KEY (source_id) REFERENCES public.memories(id) ON DELETE CASCADE;


--
-- Name: memories_embedding_staging memories_embedding_staging_space_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.memories_embedding_staging
    ADD CONSTRAINT memories_embedding_staging_space_id_fkey FOREIGN KEY (space_id) REFERENCES public.embedding_spaces(id);


--
-- Name: memories memories_home_scope_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.memories
    ADD CONSTRAINT memories_home_scope_id_fkey FOREIGN KEY (home_scope_id) REFERENCES public.authority_scopes(id);


--
-- Name: memories memories_project_exists; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.memories
    ADD CONSTRAINT memories_project_exists FOREIGN KEY (project_id) REFERENCES public.projects(id);


--
-- Name: memories memories_prose_space_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.memories
    ADD CONSTRAINT memories_prose_space_id_fkey FOREIGN KEY (prose_space_id) REFERENCES public.embedding_spaces(id);


--
-- Name: memory_provenance memory_provenance_conversation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.memory_provenance
    ADD CONSTRAINT memory_provenance_conversation_id_fkey FOREIGN KEY (conversation_id) REFERENCES public.conversations(id);


--
-- Name: memory_provenance memory_provenance_memory_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.memory_provenance
    ADD CONSTRAINT memory_provenance_memory_id_fkey FOREIGN KEY (memory_id) REFERENCES public.memories(id);


--
-- Name: memory_reasons memory_reasons_memory_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.memory_reasons
    ADD CONSTRAINT memory_reasons_memory_id_fkey FOREIGN KEY (memory_id) REFERENCES public.memories(id);


--
-- Name: memory_reasons memory_reasons_target_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.memory_reasons
    ADD CONSTRAINT memory_reasons_target_id_fkey FOREIGN KEY (target_id) REFERENCES public.memories(id);


--
-- Name: message_external_contexts message_external_contexts_account_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.message_external_contexts
    ADD CONSTRAINT message_external_contexts_account_fkey FOREIGN KEY (account) REFERENCES public.admins(handle);


--
-- Name: message_external_contexts message_external_contexts_recipient_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.message_external_contexts
    ADD CONSTRAINT message_external_contexts_recipient_fkey FOREIGN KEY (recipient) REFERENCES public.board_message_instances(id);


--
-- Name: message_external_contexts message_external_contexts_sender_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.message_external_contexts
    ADD CONSTRAINT message_external_contexts_sender_fkey FOREIGN KEY (sender) REFERENCES public.board_message_instances(id);


--
-- Name: message_external_tasks message_external_tasks_account_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.message_external_tasks
    ADD CONSTRAINT message_external_tasks_account_fkey FOREIGN KEY (account) REFERENCES public.admins(handle);


--
-- Name: message_external_tasks message_external_tasks_context_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.message_external_tasks
    ADD CONSTRAINT message_external_tasks_context_fkey FOREIGN KEY (context) REFERENCES public.message_external_contexts(id);


--
-- Name: message_external_tasks message_external_tasks_message_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.message_external_tasks
    ADD CONSTRAINT message_external_tasks_message_fkey FOREIGN KEY (message) REFERENCES public.board_message_routes(message);


--
-- Name: operation_admission_parents operation_admission_parents_child_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.operation_admission_parents
    ADD CONSTRAINT operation_admission_parents_child_fkey FOREIGN KEY (child) REFERENCES public.operation_admissions(id);


--
-- Name: operation_admission_parents operation_admission_parents_parent_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.operation_admission_parents
    ADD CONSTRAINT operation_admission_parents_parent_fkey FOREIGN KEY (parent) REFERENCES public.operation_admissions(id);


--
-- Name: operation_admission_support operation_admission_support_grant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.operation_admission_support
    ADD CONSTRAINT operation_admission_support_grant_id_fkey FOREIGN KEY (grant_id) REFERENCES public.authority_grants(id);


--
-- Name: operation_admission_support operation_admission_support_operation_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.operation_admission_support
    ADD CONSTRAINT operation_admission_support_operation_fkey FOREIGN KEY (operation) REFERENCES public.operation_admissions(id);


--
-- Name: operation_admissions operation_admissions_executor_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.operation_admissions
    ADD CONSTRAINT operation_admissions_executor_fkey FOREIGN KEY (executor) REFERENCES public.authority_accounts(id);


--
-- Name: operation_admissions operation_admissions_source_account_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.operation_admissions
    ADD CONSTRAINT operation_admissions_source_account_fkey FOREIGN KEY (source_account) REFERENCES public.authority_accounts(id);


--
-- Name: operation_conversations operation_conversations_allowance_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.operation_conversations
    ADD CONSTRAINT operation_conversations_allowance_id_fkey FOREIGN KEY (allowance_id) REFERENCES public.execution_allowances(id);


--
-- Name: operation_conversations operation_conversations_operation_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.operation_conversations
    ADD CONSTRAINT operation_conversations_operation_fkey FOREIGN KEY (operation) REFERENCES public.operation_admissions(id);


--
-- Name: orchestration_messages orchestration_messages_orchestration_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.orchestration_messages
    ADD CONSTRAINT orchestration_messages_orchestration_fkey FOREIGN KEY (orchestration) REFERENCES public.orchestrations(id);


--
-- Name: orchestration_resume_receipts orchestration_resume_receipts_account_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.orchestration_resume_receipts
    ADD CONSTRAINT orchestration_resume_receipts_account_fkey FOREIGN KEY (account) REFERENCES public.admins(handle);


--
-- Name: orchestration_resume_receipts orchestration_resume_receipts_run_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.orchestration_resume_receipts
    ADD CONSTRAINT orchestration_resume_receipts_run_id_fkey FOREIGN KEY (run_id) REFERENCES public.orchestrations(id);


--
-- Name: orchestration_script_steps orchestration_script_steps_conversation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.orchestration_script_steps
    ADD CONSTRAINT orchestration_script_steps_conversation_id_fkey FOREIGN KEY (conversation_id) REFERENCES public.conversations(id) ON DELETE CASCADE;


--
-- Name: orchestration_start_receipts orchestration_start_receipts_account_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.orchestration_start_receipts
    ADD CONSTRAINT orchestration_start_receipts_account_fkey FOREIGN KEY (account) REFERENCES public.admins(handle);


--
-- Name: orchestration_start_receipts orchestration_start_receipts_run_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.orchestration_start_receipts
    ADD CONSTRAINT orchestration_start_receipts_run_id_fkey FOREIGN KEY (run_id) REFERENCES public.orchestrations(id);


--
-- Name: orchestrations orchestrations_caller_conversation_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.orchestrations
    ADD CONSTRAINT orchestrations_caller_conversation_fkey FOREIGN KEY (caller_conversation) REFERENCES public.conversations(id);


--
-- Name: orchestrations orchestrations_caller_handle_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.orchestrations
    ADD CONSTRAINT orchestrations_caller_handle_fkey FOREIGN KEY (caller_handle) REFERENCES public.admins(handle);


--
-- Name: orchestrations orchestrations_conductor_conversation_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.orchestrations
    ADD CONSTRAINT orchestrations_conductor_conversation_fkey FOREIGN KEY (conductor_conversation) REFERENCES public.conversations(id);


--
-- Name: orchestrations orchestrations_parent_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.orchestrations
    ADD CONSTRAINT orchestrations_parent_fkey FOREIGN KEY (parent) REFERENCES public.orchestrations(id);


--
-- Name: orchestrations orchestrations_waiting_for_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.orchestrations
    ADD CONSTRAINT orchestrations_waiting_for_fkey FOREIGN KEY (waiting_for) REFERENCES public.orchestrations(id);


--
-- Name: outgoing_peers outgoing_peers_account_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.outgoing_peers
    ADD CONSTRAINT outgoing_peers_account_fkey FOREIGN KEY (account) REFERENCES public.admins(handle);


--
-- Name: outgoing_work outgoing_work_account_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.outgoing_work
    ADD CONSTRAINT outgoing_work_account_fkey FOREIGN KEY (account) REFERENCES public.admins(handle);


--
-- Name: outgoing_work outgoing_work_conversation_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.outgoing_work
    ADD CONSTRAINT outgoing_work_conversation_fkey FOREIGN KEY (conversation) REFERENCES public.conversations(id) ON DELETE SET NULL;


--
-- Name: outgoing_work outgoing_work_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.outgoing_work
    ADD CONSTRAINT outgoing_work_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;


--
-- Name: paragraphs paragraphs_are_in_their_sections_document; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.paragraphs
    ADD CONSTRAINT paragraphs_are_in_their_sections_document FOREIGN KEY (section_id, document_id) REFERENCES public.sections(id, document_id) ON DELETE SET NULL (section_id);


--
-- Name: paragraphs paragraphs_document_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.paragraphs
    ADD CONSTRAINT paragraphs_document_id_fkey FOREIGN KEY (document_id) REFERENCES public.documents(id) ON DELETE CASCADE;


--
-- Name: project_access_audit project_access_audit_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_access_audit
    ADD CONSTRAINT project_access_audit_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;


--
-- Name: project_members project_members_handle_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_members
    ADD CONSTRAINT project_members_handle_fkey FOREIGN KEY (handle) REFERENCES public.admins(handle);


--
-- Name: project_members project_members_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_members
    ADD CONSTRAINT project_members_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;


--
-- Name: projects projects_personal_owner_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.projects
    ADD CONSTRAINT projects_personal_owner_fkey FOREIGN KEY (personal_owner) REFERENCES public.admins(handle);


--
-- Name: proposals proposals_memory_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.proposals
    ADD CONSTRAINT proposals_memory_id_fkey FOREIGN KEY (memory_id) REFERENCES public.memories(id);


--
-- Name: provider_connections provider_connections_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.provider_connections
    ADD CONSTRAINT provider_connections_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE SET NULL;


--
-- Name: provider_credential_receipts provider_credential_receipts_actor_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.provider_credential_receipts
    ADD CONSTRAINT provider_credential_receipts_actor_fkey FOREIGN KEY (actor) REFERENCES public.admins(handle);


--
-- Name: provider_credentials provider_credentials_connection_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.provider_credentials
    ADD CONSTRAINT provider_credentials_connection_id_fkey FOREIGN KEY (connection_id) REFERENCES public.provider_connections(id);


--
-- Name: provider_credentials provider_credentials_initiator_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.provider_credentials
    ADD CONSTRAINT provider_credentials_initiator_fkey FOREIGN KEY (initiator) REFERENCES public.admins(handle);


--
-- Name: provider_credentials provider_credentials_subject_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.provider_credentials
    ADD CONSTRAINT provider_credentials_subject_fkey FOREIGN KEY (subject) REFERENCES public.admins(handle);


--
-- Name: relay_admissions relay_admission_subscription; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.relay_admissions
    ADD CONSTRAINT relay_admission_subscription FOREIGN KEY (scope_key, topic, subscriber) REFERENCES public.relay_subscriptions(scope_key, topic, subscriber) ON DELETE CASCADE;


--
-- Name: relay_admissions relay_admissions_payload_digest_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.relay_admissions
    ADD CONSTRAINT relay_admissions_payload_digest_fkey FOREIGN KEY (payload_digest) REFERENCES public.relay_payloads(digest);


--
-- Name: relay_approval_gap_recoveries relay_approval_gap_recoveries_scope_key_topic_subscriber_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.relay_approval_gap_recoveries
    ADD CONSTRAINT relay_approval_gap_recoveries_scope_key_topic_subscriber_fkey FOREIGN KEY (scope_key, topic, subscriber) REFERENCES public.relay_subscriptions(scope_key, topic, subscriber) ON DELETE CASCADE;


--
-- Name: relay_blob_publications relay_blob_publications_blob_scope_sha256_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.relay_blob_publications
    ADD CONSTRAINT relay_blob_publications_blob_scope_sha256_fkey FOREIGN KEY (blob_scope, sha256) REFERENCES public.binary_blobs(scope, sha256);


--
-- Name: relay_blob_publications relay_blob_publications_scope_key_topic_position_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.relay_blob_publications
    ADD CONSTRAINT relay_blob_publications_scope_key_topic_position_fkey FOREIGN KEY (scope_key, topic, "position") REFERENCES public.relay_publications(scope_key, topic, "position") ON DELETE CASCADE;


--
-- Name: relay_consumer_leases relay_consumer_leases_scope_key_topic_subscriber_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.relay_consumer_leases
    ADD CONSTRAINT relay_consumer_leases_scope_key_topic_subscriber_fkey FOREIGN KEY (scope_key, topic, subscriber) REFERENCES public.relay_subscriptions(scope_key, topic, subscriber) ON DELETE CASCADE;


--
-- Name: relay_deliveries relay_delivery_admission; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.relay_deliveries
    ADD CONSTRAINT relay_delivery_admission FOREIGN KEY (scope_key, topic, subscriber, publication_position) REFERENCES public.relay_admissions(scope_key, topic, subscriber, publication_position) ON DELETE CASCADE;


--
-- Name: relay_executions relay_executions_conversation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.relay_executions
    ADD CONSTRAINT relay_executions_conversation_id_fkey FOREIGN KEY (conversation_id) REFERENCES public.conversations(id) ON DELETE SET NULL;


--
-- Name: relay_executions relay_executions_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.relay_executions
    ADD CONSTRAINT relay_executions_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;


--
-- Name: relay_native_consumers relay_native_consumers_account_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.relay_native_consumers
    ADD CONSTRAINT relay_native_consumers_account_fkey FOREIGN KEY (account) REFERENCES public.admins(handle) ON DELETE CASCADE;


--
-- Name: relay_native_consumers relay_native_consumers_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.relay_native_consumers
    ADD CONSTRAINT relay_native_consumers_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;


--
-- Name: relay_native_consumers relay_native_consumers_scope_key_topic_subscriber_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.relay_native_consumers
    ADD CONSTRAINT relay_native_consumers_scope_key_topic_subscriber_fkey FOREIGN KEY (scope_key, topic, subscriber) REFERENCES public.relay_subscriptions(scope_key, topic, subscriber) ON DELETE CASCADE;


--
-- Name: relay_native_gap_recoveries relay_native_gap_recoveries_scope_key_topic_subscriber_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.relay_native_gap_recoveries
    ADD CONSTRAINT relay_native_gap_recoveries_scope_key_topic_subscriber_fkey FOREIGN KEY (scope_key, topic, subscriber) REFERENCES public.relay_native_consumers(scope_key, topic, subscriber) ON DELETE CASCADE;


--
-- Name: relay_operator_receipts relay_operator_receipts_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.relay_operator_receipts
    ADD CONSTRAINT relay_operator_receipts_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;


--
-- Name: relay_publications relay_publication_topic; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.relay_publications
    ADD CONSTRAINT relay_publication_topic FOREIGN KEY (scope_key, topic) REFERENCES public.relay_topics(scope_key, name) ON DELETE CASCADE;


--
-- Name: relay_publications relay_publications_payload_digest_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.relay_publications
    ADD CONSTRAINT relay_publications_payload_digest_fkey FOREIGN KEY (payload_digest) REFERENCES public.relay_payloads(digest);


--
-- Name: relay_sdk_batches relay_sdk_batches_scope_key_topic_subscriber_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.relay_sdk_batches
    ADD CONSTRAINT relay_sdk_batches_scope_key_topic_subscriber_fkey FOREIGN KEY (scope_key, topic, subscriber) REFERENCES public.relay_subscriptions(scope_key, topic, subscriber) ON DELETE CASCADE;


--
-- Name: relay_source_events relay_source_events_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.relay_source_events
    ADD CONSTRAINT relay_source_events_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;


--
-- Name: relay_subscription_authority relay_subscription_authority_scope_key_topic_subscriber_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.relay_subscription_authority
    ADD CONSTRAINT relay_subscription_authority_scope_key_topic_subscriber_fkey FOREIGN KEY (scope_key, topic, subscriber) REFERENCES public.relay_subscriptions(scope_key, topic, subscriber) ON DELETE CASCADE;


--
-- Name: relay_subscriptions relay_subscription_topic; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.relay_subscriptions
    ADD CONSTRAINT relay_subscription_topic FOREIGN KEY (scope_key, topic) REFERENCES public.relay_topics(scope_key, name) ON DELETE CASCADE;


--
-- Name: relay_tool_invocations relay_tool_invocations_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.relay_tool_invocations
    ADD CONSTRAINT relay_tool_invocations_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;


--
-- Name: relay_topics relay_topics_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.relay_topics
    ADD CONSTRAINT relay_topics_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;


--
-- Name: retrieval_passages retrieval_passages_code_space_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.retrieval_passages
    ADD CONSTRAINT retrieval_passages_code_space_id_fkey FOREIGN KEY (code_space_id) REFERENCES public.embedding_spaces(id);


--
-- Name: retrieval_passages_embedding_staging retrieval_passages_embedding__source_type_source_id_positi_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.retrieval_passages_embedding_staging
    ADD CONSTRAINT retrieval_passages_embedding__source_type_source_id_positi_fkey FOREIGN KEY (source_type, source_id, "position") REFERENCES public.retrieval_passages(source_type, source_id, "position") ON DELETE CASCADE;


--
-- Name: retrieval_passages_embedding_failures retrieval_passages_embedding_failures_slot_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.retrieval_passages_embedding_failures
    ADD CONSTRAINT retrieval_passages_embedding_failures_slot_fkey FOREIGN KEY (slot) REFERENCES public.embedding_slots(slot);


--
-- Name: retrieval_passages_embedding_failures retrieval_passages_embedding_failures_space_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.retrieval_passages_embedding_failures
    ADD CONSTRAINT retrieval_passages_embedding_failures_space_id_fkey FOREIGN KEY (space_id) REFERENCES public.embedding_spaces(id);


--
-- Name: retrieval_passages_embedding_failures retrieval_passages_embedding_source_type_source_id_positi_fkey1; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.retrieval_passages_embedding_failures
    ADD CONSTRAINT retrieval_passages_embedding_source_type_source_id_positi_fkey1 FOREIGN KEY (source_type, source_id, "position") REFERENCES public.retrieval_passages(source_type, source_id, "position") ON DELETE CASCADE;


--
-- Name: retrieval_passages_embedding_staging retrieval_passages_embedding_staging_slot_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.retrieval_passages_embedding_staging
    ADD CONSTRAINT retrieval_passages_embedding_staging_slot_fkey FOREIGN KEY (slot) REFERENCES public.embedding_slots(slot);


--
-- Name: retrieval_passages_embedding_staging retrieval_passages_embedding_staging_space_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.retrieval_passages_embedding_staging
    ADD CONSTRAINT retrieval_passages_embedding_staging_space_id_fkey FOREIGN KEY (space_id) REFERENCES public.embedding_spaces(id);


--
-- Name: retrieval_passages retrieval_passages_prose_space_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.retrieval_passages
    ADD CONSTRAINT retrieval_passages_prose_space_id_fkey FOREIGN KEY (prose_space_id) REFERENCES public.embedding_spaces(id);


--
-- Name: retrieval_passages retrieval_passages_source_type_source_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.retrieval_passages
    ADD CONSTRAINT retrieval_passages_source_type_source_id_fkey FOREIGN KEY (source_type, source_id) REFERENCES public.retrieval_sources(source_type, source_id) ON DELETE CASCADE;


--
-- Name: run_approvals run_approvals_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.run_approvals
    ADD CONSTRAINT run_approvals_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;


--
-- Name: schedule_files schedule_files_source_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.schedule_files
    ADD CONSTRAINT schedule_files_source_id_fkey FOREIGN KEY (source_id) REFERENCES public.schedule_sources(id) ON DELETE CASCADE;


--
-- Name: schedule_sources schedule_sources_account_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.schedule_sources
    ADD CONSTRAINT schedule_sources_account_fkey FOREIGN KEY (account) REFERENCES public.admins(handle);


--
-- Name: schedule_sources schedule_sources_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.schedule_sources
    ADD CONSTRAINT schedule_sources_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;


--
-- Name: schedules schedules_defined_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.schedules
    ADD CONSTRAINT schedules_defined_by_fkey FOREIGN KEY (defined_by) REFERENCES public.admins(handle);


--
-- Name: sections sections_are_in_their_chapters_document; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sections
    ADD CONSTRAINT sections_are_in_their_chapters_document FOREIGN KEY (chapter_id, document_id) REFERENCES public.chapters(id, document_id) ON DELETE CASCADE;


--
-- Name: service_token_scopes service_token_scopes_project_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_token_scopes
    ADD CONSTRAINT service_token_scopes_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id);


--
-- Name: service_token_scopes service_token_scopes_token_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_token_scopes
    ADD CONSTRAINT service_token_scopes_token_id_fkey FOREIGN KEY (token_id) REFERENCES public.service_tokens(id);


--
-- Name: service_tokens service_tokens_owner_handle_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_tokens
    ADD CONSTRAINT service_tokens_owner_handle_fkey FOREIGN KEY (owner_handle) REFERENCES public.admins(handle);


--
-- Name: service_tokens service_tokens_principal_handle_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.service_tokens
    ADD CONSTRAINT service_tokens_principal_handle_fkey FOREIGN KEY (principal_handle) REFERENCES public.admins(handle);


--
-- Name: skill_executions skill_executions_account_handle_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.skill_executions
    ADD CONSTRAINT skill_executions_account_handle_fkey FOREIGN KEY (account_handle) REFERENCES public.admins(handle);


--
-- Name: skill_executions skill_executions_conversation_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.skill_executions
    ADD CONSTRAINT skill_executions_conversation_fkey FOREIGN KEY (conversation) REFERENCES public.conversations(id) ON DELETE CASCADE;


--
-- Name: skill_executions skill_executions_parent_conversation_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.skill_executions
    ADD CONSTRAINT skill_executions_parent_conversation_fkey FOREIGN KEY (parent_conversation) REFERENCES public.conversations(id) ON DELETE CASCADE;


--
-- Name: suspended_run_calls suspended_run_calls_approval_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.suspended_run_calls
    ADD CONSTRAINT suspended_run_calls_approval_fkey FOREIGN KEY (approval) REFERENCES public.run_approvals(id) ON DELETE CASCADE;


--
-- Name: suspended_run_calls suspended_run_calls_conversation_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.suspended_run_calls
    ADD CONSTRAINT suspended_run_calls_conversation_fkey FOREIGN KEY (conversation) REFERENCES public.conversations(id) ON DELETE CASCADE;


--
-- Name: system_operations system_operations_grant_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.system_operations
    ADD CONSTRAINT system_operations_grant_id_fkey FOREIGN KEY (grant_id) REFERENCES public.authority_grants(id);


--
-- Name: todos todos_parent_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.todos
    ADD CONSTRAINT todos_parent_fkey FOREIGN KEY (parent) REFERENCES public.todos(id);


--
-- Name: trajectory_image_references trajectory_image_references_blob_scope_sha256_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.trajectory_image_references
    ADD CONSTRAINT trajectory_image_references_blob_scope_sha256_fkey FOREIGN KEY (blob_scope, sha256) REFERENCES public.binary_blobs(scope, sha256);


--
-- Name: trajectory_image_references trajectory_image_references_conversation_id_entry_ordinal_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.trajectory_image_references
    ADD CONSTRAINT trajectory_image_references_conversation_id_entry_ordinal_fkey FOREIGN KEY (conversation_id, entry_ordinal) REFERENCES public.entries(conversation_id, ordinal) ON DELETE CASCADE;


--
-- Name: triggers triggers_defined_by_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.triggers
    ADD CONSTRAINT triggers_defined_by_fkey FOREIGN KEY (defined_by) REFERENCES public.admins(handle);


--
-- Name: turns turns_a_recorded_block_is_one_this_table_holds; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.turns
    ADD CONSTRAINT turns_a_recorded_block_is_one_this_table_holds FOREIGN KEY (system_block) REFERENCES public.system_blocks(hash);


--
-- Name: turns turns_belong_to_a_conversation; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.turns
    ADD CONSTRAINT turns_belong_to_a_conversation FOREIGN KEY (conversation_id) REFERENCES public.conversations(id);


--
-- Name: user_inbox user_inbox_firing_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_inbox
    ADD CONSTRAINT user_inbox_firing_fkey FOREIGN KEY (firing) REFERENCES public.firings(id);


--
-- Name: user_inbox user_inbox_handle_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.user_inbox
    ADD CONSTRAINT user_inbox_handle_fkey FOREIGN KEY (handle) REFERENCES public.admins(handle);

-- Preserve V205's explicit PUBLIC revokes even when the database owner
-- has changed its default privileges.
REVOKE ALL ON public.snapshot_cache_writers, public.snapshot_cache_writer_retention FROM PUBLIC;
REVOKE ALL ON SEQUENCE public.snapshot_cache_writer_allocations FROM PUBLIC;
