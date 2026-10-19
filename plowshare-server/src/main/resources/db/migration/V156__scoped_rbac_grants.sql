-- Scoped RBAC, additive helper vocabulary; existing wire ProjectRole names stay unchanged.
-- Reserved role identities must never adopt an existing account or scope.
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM admins WHERE lower(handle) IN (
        '00000000-0000-0000-0000-000000000007','00000000-0000-0000-0000-000000000008',
        '00000000-0000-0000-0000-000000000009','00000000-0000-0000-0000-00000000000a'))
        OR EXISTS (SELECT 1 FROM authority_accounts WHERE id IN (
        '00000000-0000-0000-0000-000000000007','00000000-0000-0000-0000-000000000008',
        '00000000-0000-0000-0000-000000000009','00000000-0000-0000-0000-00000000000a'))
        OR EXISTS (SELECT 1 FROM authority_scopes WHERE id IN (
        '00000000-0000-0000-0000-000000000007','00000000-0000-0000-0000-000000000008',
        '00000000-0000-0000-0000-000000000009','00000000-0000-0000-0000-00000000000a')) THEN
        RAISE EXCEPTION 'Standard role identity collision requires explicit resolution';
    END IF;
END $$;
CREATE OR REPLACE FUNCTION authority_reserved_id(id UUID) RETURNS BOOLEAN LANGUAGE SQL IMMUTABLE AS $$
    SELECT id IN ('00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002',
        '00000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-000000000004',
        '00000000-0000-0000-0000-000000000005','00000000-0000-0000-0000-000000000006',
        '00000000-0000-0000-0000-000000000007','00000000-0000-0000-0000-000000000008',
        '00000000-0000-0000-0000-000000000009','00000000-0000-0000-0000-00000000000a');
$$;
ALTER TABLE authority_roles DROP CONSTRAINT authority_roles_check;
ALTER TABLE authority_roles ADD CONSTRAINT authority_role_registry_shape CHECK (
    (id='00000000-0000-0000-0000-000000000002' AND name='SYSTEM' AND NOT assignable
        AND intrinsic_account IS NOT NULL AND intrinsic_account='00000000-0000-0000-0000-000000000001') OR
    (assignable AND intrinsic_account IS NULL AND (
        (id='00000000-0000-0000-0000-000000000006' AND name='GLOBAL_READ') OR
        (id='00000000-0000-0000-0000-000000000007' AND name='ADMIN') OR
        (id='00000000-0000-0000-0000-000000000008' AND name='MANAGER') OR
        (id='00000000-0000-0000-0000-000000000009' AND name='CONTRIBUTOR') OR
        (id='00000000-0000-0000-0000-00000000000a' AND name='READER'))));
INSERT INTO authority_roles(id,name,assignable) VALUES
    ('00000000-0000-0000-0000-000000000007','ADMIN',TRUE),
    ('00000000-0000-0000-0000-000000000008','MANAGER',TRUE),
    ('00000000-0000-0000-0000-000000000009','CONTRIBUTOR',TRUE),
    ('00000000-0000-0000-0000-00000000000a','READER',TRUE);

-- Versioned, migration-owned action policy. No arbitrary action-set grant write surface.
CREATE TABLE authority_role_actions (
    role_id UUID NOT NULL REFERENCES authority_roles(id),
    role_version INTEGER NOT NULL CHECK (role_version=1),
    profile TEXT NOT NULL CHECK (profile IN ('DEFAULT','GLOBAL_KNOWLEDGE')),
    action TEXT NOT NULL CHECK (action IN ('READ','WRITE','MANAGE_ACCESS','MANAGE_SCOPE',
        'SET_AUDIENCE','ADMINISTER','CONVERSATION_CREATE','RUN_MODEL')),
    PRIMARY KEY(role_id,role_version,profile,action)
);
INSERT INTO authority_role_actions
SELECT r.id,1,'DEFAULT',a.action FROM authority_roles r CROSS JOIN
    (VALUES ('READ'),('WRITE'),('MANAGE_ACCESS'),('MANAGE_SCOPE'),('SET_AUDIENCE'),
        ('ADMINISTER'),('CONVERSATION_CREATE'),('RUN_MODEL')) a(action)
WHERE r.name='SYSTEM'
    OR (r.name='ADMIN' AND a.action IN ('MANAGE_ACCESS','MANAGE_SCOPE','SET_AUDIENCE','ADMINISTER'))
    OR (r.name='MANAGER' AND a.action<>'ADMINISTER')
    OR (r.name='CONTRIBUTOR' AND a.action IN ('READ','WRITE','CONVERSATION_CREATE','RUN_MODEL'))
    OR (r.name='READER' AND a.action='READ');
INSERT INTO authority_role_actions SELECT id,1,'GLOBAL_KNOWLEDGE',a.action
FROM authority_roles CROSS JOIN (VALUES ('READ'),('WRITE')) a(action) WHERE name='ADMIN';
CREATE TRIGGER authority_role_actions_locked BEFORE UPDATE OR DELETE ON authority_role_actions
    FOR EACH ROW EXECUTE FUNCTION authority_role_locked();

CREATE TABLE authority_grants (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subject_account UUID NOT NULL REFERENCES authority_accounts(id),
    subject_agent TEXT CHECK (subject_agent ~ '^[A-Za-z0-9][A-Za-z0-9_.:/-]{0,127}$'),
    scope_id UUID NOT NULL, -- Retain the UUID after source project deletion; live checks require its mapping.
    role_id UUID NOT NULL REFERENCES authority_roles(id),
    requested_role TEXT NOT NULL CHECK (requested_role IN ('SYSTEM','ADMIN','MANAGER','CONTRIBUTOR','READER')),
    role_version INTEGER NOT NULL DEFAULT 1 CHECK (role_version=1),
    coverage TEXT NOT NULL DEFAULT 'SELF' CHECK (coverage IN ('SELF','ORDINARY_PROJECT_CONTROL','ALL_SCOPES')),
    issuer_account UUID NOT NULL REFERENCES authority_accounts(id),
    issuer_agent TEXT CHECK (issuer_agent ~ '^[A-Za-z0-9][A-Za-z0-9_.:/-]{0,127}$'),
    issuing_grant UUID REFERENCES authority_grants(id),
    issuing_token UUID REFERENCES service_tokens(id),
    manager_forced BOOLEAN NOT NULL DEFAULT FALSE,
    provenance TEXT NOT NULL CHECK (provenance IN ('ROOT_POLICY','MEMBERSHIP','LEGACY_ADMIN_DATA','ADMIN_CONTROL','ROLE')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    revoked_by UUID REFERENCES authority_accounts(id),
    revoked_by_agent TEXT,
    revoking_grant UUID REFERENCES authority_grants(id),
    version BIGINT NOT NULL DEFAULT 1 CHECK (version>0),
    CHECK (expires_at IS NULL OR expires_at>created_at),
    CHECK (issuing_grant IS DISTINCT FROM id),
    CHECK ((provenance='ROOT_POLICY')=(issuing_grant IS NULL))
);
CREATE UNIQUE INDEX authority_one_root_policy ON authority_grants(provenance) WHERE provenance='ROOT_POLICY';
CREATE UNIQUE INDEX authority_current_membership ON authority_grants(scope_id,subject_account)
    WHERE provenance='MEMBERSHIP' AND revoked_at IS NULL;
CREATE UNIQUE INDEX authority_current_admin_control ON authority_grants(scope_id,subject_account)
    WHERE provenance='ADMIN_CONTROL' AND revoked_at IS NULL;
CREATE INDEX authority_grants_recipient ON authority_grants(subject_account,subject_agent);
CREATE INDEX authority_grants_parent ON authority_grants(issuing_grant);
CREATE TABLE authority_grant_audit (
    id BIGSERIAL PRIMARY KEY,
    grant_id UUID NOT NULL REFERENCES authority_grants(id),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    event TEXT NOT NULL CHECK (event IN ('ISSUE','REVOKE')),
    version BIGINT NOT NULL,
    actor_account UUID NOT NULL REFERENCES authority_accounts(id),
    actor_agent TEXT,
    authority UUID REFERENCES authority_grants(id),
    UNIQUE(grant_id,version)
);

-- The only root seed is a persisted policy binding to the intrinsic role/account relationship.
INSERT INTO authority_grants(subject_account,scope_id,role_id,requested_role,coverage,issuer_account,provenance)
SELECT r.intrinsic_account,s.id,r.id,'SYSTEM','ALL_SCOPES',r.intrinsic_account,'ROOT_POLICY'
FROM authority_roles r JOIN authority_scopes s ON s.kind='ADMINISTRATION' AND s.account_id=r.intrinsic_account
WHERE r.name='SYSTEM' AND NOT r.assignable;
INSERT INTO authority_grant_audit(grant_id,event,version,actor_account)
SELECT id,'ISSUE',version,issuer_account FROM authority_grants;

-- Every chain is bounded and read afresh. Disabled/revoked ancestors stop descendants.
CREATE FUNCTION authority_live_grants() RETURNS TABLE(id UUID, depth INTEGER) LANGUAGE SQL STABLE AS $$
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
            AND s.kind<>'RESTRICTED' AND account_active(u.handle) AND NOT u.bootstrap
            AND (s.kind<>'PERSONAL' OR s.account_id=g.subject_account)
            AND (g.issuing_token IS NULL OR EXISTS(SELECT 1 FROM service_tokens t JOIN service_token_scopes ts ON ts.token_id=t.id
                JOIN projects p ON p.id=ts.project_id
                WHERE t.id=g.issuing_token AND ts.project_id=s.project_id AND ts.role='MANAGER'
                    AND account_active(t.principal_handle)))
            AND (g.provenance NOT IN ('ADMIN_CONTROL','LEGACY_ADMIN_DATA') OR (u.account_kind='USER' AND u.server_admin))
    ) SELECT * FROM live;
$$;
CREATE VIEW authority_grant_records AS
SELECT g.*,r.name AS effective_role,a.legacy_handle AS subject_handle,p.name AS project_name,
    ARRAY(SELECT a.action FROM authority_role_actions a WHERE a.role_id=g.role_id AND a.role_version=g.role_version
        AND (a.profile='DEFAULT' OR a.profile=s.builtin) ORDER BY a.action) AS actions
FROM authority_grants g JOIN authority_roles r ON r.id=g.role_id LEFT JOIN authority_scopes s ON s.id=g.scope_id
JOIN authority_accounts a ON a.id=g.subject_account LEFT JOIN projects p ON p.id=s.project_id;

-- Shared by issuance and revocation: lock source authority before its live check.
CREATE FUNCTION authority_lock_lineage(authority UUID) RETURNS VOID LANGUAGE plpgsql AS $$
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

CREATE FUNCTION authority_grant_insert() RETURNS trigger LANGUAGE plpgsql AS $$
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
    IF subject_kind NOT IN ('USER','SERVICE') OR destination.kind='RESTRICTED'
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
CREATE TRIGGER authority_grant_insert BEFORE INSERT ON authority_grants FOR EACH ROW EXECUTE FUNCTION authority_grant_insert();
CREATE FUNCTION authority_grant_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Grant history cannot be deleted'; END IF;
    IF OLD.provenance='ROOT_POLICY' OR OLD.revoked_at IS NOT NULL OR NEW.revoked_at IS NULL OR NEW.version<>OLD.version+1
        OR (to_jsonb(NEW)-'revoked_at'-'version'-'revoked_by'-'revoked_by_agent'-'revoking_grant') IS DISTINCT FROM (to_jsonb(OLD)-'revoked_at'-'version'-'revoked_by'-'revoked_by_agent'-'revoking_grant') THEN
        RAISE EXCEPTION 'Grant is immutable except for one versioned revocation'; END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER authority_grant_mutation BEFORE UPDATE OR DELETE ON authority_grants FOR EACH ROW EXECUTE FUNCTION authority_grant_mutation();
CREATE FUNCTION authority_audit_grant() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO authority_grant_audit(grant_id,event,version,actor_account,actor_agent,authority)
    VALUES(NEW.id,CASE WHEN TG_OP='INSERT' THEN 'ISSUE' ELSE 'REVOKE' END,NEW.version,coalesce(NEW.revoked_by,NEW.issuer_account),
        CASE WHEN TG_OP='INSERT' THEN NEW.issuer_agent ELSE NEW.revoked_by_agent END,
        coalesce(NEW.revoking_grant,NEW.issuing_grant));
    RETURN NEW;
END $$;
CREATE TRIGGER authority_audit_grant AFTER INSERT OR UPDATE ON authority_grants FOR EACH ROW EXECUTE FUNCTION authority_audit_grant();
CREATE TRIGGER authority_grant_audit_locked BEFORE UPDATE OR DELETE ON authority_grant_audit
    FOR EACH ROW EXECUTE FUNCTION authority_role_locked();

-- Existing source writers bootstrap membership; human role-management records the real issuer
-- before updating the compatibility row, so this bridge must not replace that lineage.
CREATE FUNCTION authority_membership_changed() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE sid UUID; account UUID; next_role TEXT; root authority_grants;
BEGIN
    SELECT s.id INTO sid FROM authority_scopes s WHERE s.project_id=CASE WHEN TG_OP='DELETE' THEN OLD.project_id ELSE NEW.project_id END AND s.kind<>'RESTRICTED';
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
CREATE TRIGGER authority_membership_changed AFTER INSERT OR UPDATE OR DELETE ON project_members FOR EACH ROW EXECUTE FUNCTION authority_membership_changed();
INSERT INTO authority_grants(subject_account,scope_id,role_id,requested_role,issuer_account,issuing_grant,provenance)
SELECT a.id,s.id,r.id,r.name,root.subject_account,root.id,'MEMBERSHIP'
FROM project_members m JOIN authority_accounts a ON a.legacy_handle=m.handle
JOIN authority_scopes s ON s.project_id=m.project_id AND s.kind<>'RESTRICTED'
JOIN authority_roles r ON r.name=CASE WHEN m.role='VIEWER' THEN 'READER' ELSE m.role END
CROSS JOIN authority_grants root WHERE root.provenance='ROOT_POLICY';

-- Snapshot the old administrator data authority. No new account/project inherits it.
INSERT INTO authority_grants(subject_account,scope_id,role_id,requested_role,issuer_account,issuing_grant,provenance)
SELECT a.id,s.id,r.id,r.name,root.subject_account,root.id,'LEGACY_ADMIN_DATA'
FROM authority_accounts a JOIN admins u ON u.handle=a.legacy_handle AND u.account_kind='USER' AND u.server_admin AND NOT u.bootstrap
CROSS JOIN authority_scopes s CROSS JOIN authority_roles r CROSS JOIN authority_grants root
WHERE s.kind='PROJECT' AND r.name='MANAGER' AND root.provenance='ROOT_POLICY';

CREATE FUNCTION authority_refresh_admin(account UUID) RETURNS VOID LANGUAGE plpgsql AS $$
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
SELECT authority_refresh_admin(id) FROM authority_accounts;
CREATE FUNCTION authority_admin_changed() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='INSERT' OR (OLD.server_admin,OLD.bootstrap,OLD.account_kind) IS DISTINCT FROM (NEW.server_admin,NEW.bootstrap,NEW.account_kind) THEN
        PERFORM authority_refresh_admin(id) FROM authority_accounts WHERE legacy_handle=NEW.handle;
    END IF;
    RETURN NULL;
END $$;
-- Alphabetically follows authority_map_account so the durable identity is already present.
CREATE TRIGGER authority_z_admin_changed AFTER INSERT OR UPDATE ON admins FOR EACH ROW EXECUTE FUNCTION authority_admin_changed();

CREATE FUNCTION authority_project_role(project TEXT, account TEXT) RETURNS TEXT LANGUAGE SQL STABLE AS $$
    SELECT CASE WHEN bool_or(r.name='MANAGER') THEN 'MANAGER' WHEN bool_or(r.name='CONTRIBUTOR') THEN 'CONTRIBUTOR'
        WHEN bool_or(r.name='READER') THEN 'VIEWER' END
    FROM authority_grants g JOIN authority_live_grants() l ON l.id=g.id
    JOIN authority_accounts a ON a.id=g.subject_account AND a.legacy_handle=account
    JOIN authority_scopes s ON s.id=g.scope_id JOIN projects p ON p.id=s.project_id AND p.name=project
    JOIN authority_roles r ON r.id=g.role_id
    WHERE g.subject_agent IS NULL AND g.coverage='SELF'
        AND (s.kind='PROJECT' OR (s.kind='PERSONAL' AND s.account_id=a.id));
$$;
CREATE FUNCTION authority_project_management_grant(project TEXT, account TEXT) RETURNS UUID LANGUAGE SQL STABLE AS $$
    SELECT g.id FROM authority_grants g JOIN authority_live_grants() l ON l.id=g.id
    JOIN authority_accounts a ON a.id=g.subject_account AND a.legacy_handle=account
    JOIN authority_roles r ON r.id=g.role_id JOIN authority_scopes s ON s.id=g.scope_id
    JOIN authority_scopes target ON target.project_id=(SELECT id FROM projects WHERE name=project)
    WHERE g.subject_agent IS NULL AND ((g.scope_id=target.id AND r.name IN ('MANAGER','ADMIN'))
        OR (g.coverage='ORDINARY_PROJECT_CONTROL' AND target.kind='PROJECT' AND r.name='ADMIN'))
    ORDER BY (r.name='ADMIN') DESC,g.created_at,g.id LIMIT 1;
$$;
CREATE OR REPLACE FUNCTION service_project_role(project TEXT, account TEXT) RETURNS TEXT LANGUAGE SQL STABLE AS $$
    SELECT CASE WHEN effective.role='VIEWER' OR s.role='VIEWER' THEN 'VIEWER'
        WHEN effective.role='CONTRIBUTOR' OR s.role='CONTRIBUTOR' THEN 'CONTRIBUTOR' ELSE 'MANAGER' END
    FROM service_tokens t JOIN service_token_scopes s ON s.token_id=t.id
    JOIN projects p ON p.id=s.project_id AND p.name=project AND p.personal_owner IS NULL
        AND p.name NOT LIKE 'client:%' AND p.name NOT LIKE 'personal:%'
    CROSS JOIN LATERAL (SELECT authority_project_role(project,t.owner_handle) AS role) effective
    WHERE t.principal_handle=account AND account_active(account) AND effective.role IS NOT NULL;
$$;
CREATE OR REPLACE FUNCTION information_project_readable(target BIGINT, account TEXT) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
    SELECT EXISTS (SELECT 1 FROM projects p WHERE p.id=target AND (
        authority_project_role(p.name,account) IS NOT NULL OR service_project_role(p.name,account) IS NOT NULL
        OR (p.name LIKE 'client:%' AND EXISTS(SELECT 1 FROM project_members m JOIN admins a ON a.handle=m.handle
            WHERE m.project_id=p.id AND m.handle=account AND account_active(account) AND NOT a.bootstrap))));
$$;

ALTER FUNCTION authority_builtin_registry_valid() RENAME TO authority_identity_registry_valid;
CREATE FUNCTION authority_builtin_registry_valid() RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
    SELECT authority_identity_registry_valid()
        AND (SELECT count(*)=4 FROM authority_roles WHERE name IN ('ADMIN','MANAGER','CONTRIBUTOR','READER') AND assignable)
        AND (SELECT count(*)=26 FROM authority_role_actions)
        AND (SELECT count(*)=1 FROM authority_grants g JOIN authority_roles r ON r.id=g.role_id
            JOIN authority_scopes s ON s.id=g.scope_id
            WHERE g.provenance='ROOT_POLICY' AND g.revoked_at IS NULL AND g.expires_at IS NULL
                AND g.subject_account=r.intrinsic_account AND g.issuer_account=r.intrinsic_account
                AND g.subject_agent IS NULL AND g.issuer_agent IS NULL AND g.issuing_grant IS NULL
                AND g.coverage='ALL_SCOPES' AND s.kind='ADMINISTRATION' AND s.account_id=r.intrinsic_account
                AND g.version=1 AND NOT g.manager_forced);
$$;

-- Standard-role UUID selectors are reserved in ordinary account lifecycle too.
CREATE FUNCTION authority_standard_role_locator() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF lower(NEW.handle) IN ('00000000-0000-0000-0000-000000000007','00000000-0000-0000-0000-000000000008',
        '00000000-0000-0000-0000-000000000009','00000000-0000-0000-0000-00000000000a') THEN
        RAISE EXCEPTION 'Reserved role identity cannot be an ordinary account';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER authority_standard_role_locator BEFORE INSERT OR UPDATE ON admins
    FOR EACH ROW EXECUTE FUNCTION authority_standard_role_locator();

-- Called only from the existing membership repository inside its project transaction.
CREATE FUNCTION authority_issue_membership(project TEXT, target TEXT, actor TEXT, next_role TEXT) RETURNS UUID LANGUAGE plpgsql AS $$
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

CREATE FUNCTION authority_scope_classified() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.kind='PERSONAL' AND OLD.kind='RESTRICTED' THEN
        UPDATE project_members SET role=role WHERE project_id=NEW.project_id
            AND handle=(SELECT legacy_handle FROM authority_accounts WHERE id=NEW.account_id);
    END IF;
    RETURN NULL;
END $$;
CREATE TRIGGER authority_scope_classified AFTER UPDATE ON authority_scopes
    FOR EACH ROW EXECUTE FUNCTION authority_scope_classified();

CREATE FUNCTION authority_project_manager_count(scope UUID) RETURNS BIGINT LANGUAGE SQL STABLE AS $$
    SELECT count(*) FROM authority_grants g JOIN authority_live_grants() l ON l.id=g.id
    JOIN authority_roles r ON r.id=g.role_id
    WHERE g.scope_id=scope AND r.name='MANAGER' AND g.subject_agent IS NULL;
$$;
CREATE FUNCTION authority_membership_authority(project TEXT, actor TEXT) RETURNS UUID LANGUAGE SQL STABLE AS $$
    SELECT coalesce(authority_project_management_grant(project,actor),
        (SELECT authority_project_management_grant(project,t.owner_handle) FROM service_tokens t
            WHERE t.principal_handle=actor AND service_project_role(project,actor)='MANAGER'));
$$;
