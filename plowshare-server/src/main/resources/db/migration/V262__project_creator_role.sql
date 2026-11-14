-- PROJECT_CREATOR: a role that lets an account create new projects and does nothing else.
--
-- The role is granted only at one new, built-in, resource-free scope of kind PROJECT_CREATION, so
-- it can never be read as authority over an existing project, the administration scope or any
-- knowledge home. Its single action is PROJECT_CREATE; it issues nothing. Only an active server
-- administrator, exercising their own live ADMIN_CONTROL grant at the ADMINISTRATION scope, may
-- issue it, and (as V260 established for every person-issued grant) the grant hangs off the
-- SYSTEM root, so it outlives the issuing administrator's later demotion.
--
-- Reserved identities: ...000b is the PROJECT_CREATION scope, ...000c the PROJECT_CREATOR role.
-- Shipped migrations are not edited; every constraint below is replaced by a superset of its
-- previous definition.

-- Reserved identities must never adopt an existing account, scope or role (as V156 guarded ...07-...0a).
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM admins WHERE lower(handle) IN (
        '00000000-0000-0000-0000-00000000000b','00000000-0000-0000-0000-00000000000c'))
        OR EXISTS (SELECT 1 FROM authority_accounts WHERE id IN (
        '00000000-0000-0000-0000-00000000000b','00000000-0000-0000-0000-00000000000c'))
        OR EXISTS (SELECT 1 FROM authority_scopes WHERE id IN (
        '00000000-0000-0000-0000-00000000000b','00000000-0000-0000-0000-00000000000c'))
        OR EXISTS (SELECT 1 FROM authority_roles WHERE id IN (
        '00000000-0000-0000-0000-00000000000b','00000000-0000-0000-0000-00000000000c')
            OR name='PROJECT_CREATOR') THEN
        RAISE EXCEPTION 'Project creator identity collision requires explicit resolution';
    END IF;
END $$;

CREATE OR REPLACE FUNCTION authority_reserved_id(id UUID) RETURNS BOOLEAN LANGUAGE SQL IMMUTABLE AS $$
    SELECT id IN ('00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002',
        '00000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-000000000004',
        '00000000-0000-0000-0000-000000000005','00000000-0000-0000-0000-000000000006',
        '00000000-0000-0000-0000-000000000007','00000000-0000-0000-0000-000000000008',
        '00000000-0000-0000-0000-000000000009','00000000-0000-0000-0000-00000000000a',
        '00000000-0000-0000-0000-00000000000b','00000000-0000-0000-0000-00000000000c');
$$;

-- The ordinary-account lifecycle keeps refusing reserved UUID handles, now including ...0b/...0c,
-- matching BuiltInIdentities.RESERVED in Java.
CREATE OR REPLACE FUNCTION authority_standard_role_locator() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF lower(NEW.handle) IN ('00000000-0000-0000-0000-000000000007','00000000-0000-0000-0000-000000000008',
        '00000000-0000-0000-0000-000000000009','00000000-0000-0000-0000-00000000000a',
        '00000000-0000-0000-0000-00000000000b','00000000-0000-0000-0000-00000000000c') THEN
        RAISE EXCEPTION 'Reserved role identity cannot be an ordinary account';
    END IF;
    RETURN NEW;
END $$;

-- Role registry: the V156 shape plus the PROJECT_CREATOR row (assignable, no intrinsic account).
ALTER TABLE authority_roles DROP CONSTRAINT authority_role_registry_shape;
ALTER TABLE authority_roles ADD CONSTRAINT authority_role_registry_shape CHECK (
    (id='00000000-0000-0000-0000-000000000002' AND name='SYSTEM' AND NOT assignable
        AND intrinsic_account IS NOT NULL AND intrinsic_account='00000000-0000-0000-0000-000000000001') OR
    (assignable AND intrinsic_account IS NULL AND (
        (id='00000000-0000-0000-0000-000000000006' AND name='GLOBAL_READ') OR
        (id='00000000-0000-0000-0000-000000000007' AND name='ADMIN') OR
        (id='00000000-0000-0000-0000-000000000008' AND name='MANAGER') OR
        (id='00000000-0000-0000-0000-000000000009' AND name='CONTRIBUTOR') OR
        (id='00000000-0000-0000-0000-00000000000a' AND name='READER') OR
        (id='00000000-0000-0000-0000-00000000000c' AND name='PROJECT_CREATOR'))));

-- The three remaining unnamed CHECKs (V155 builtin domain and built-in shape, V156 action domain,
-- V156 requested_role domain) are located by definition, as V216 does, and a shape this migration
-- does not recognise aborts instead of being silently replaced.
DO $$
DECLARE builtin_domain TEXT[]; builtin_shape TEXT[]; action_domain TEXT[]; requested_domain TEXT[];
BEGIN
    SELECT array_agg(conname) INTO builtin_domain FROM pg_constraint
        WHERE conrelid='authority_scopes'::regclass AND contype='c'
        AND pg_get_constraintdef(oid) LIKE '%builtin = ANY%'
        AND pg_get_constraintdef(oid) NOT LIKE '%kind%'
        AND pg_get_constraintdef(oid) NOT LIKE '%authority_reserved_id%';
    SELECT array_agg(conname) INTO builtin_shape FROM pg_constraint
        WHERE conrelid='authority_scopes'::regclass AND contype='c'
        AND pg_get_constraintdef(oid) LIKE '%builtin IS NULL%'
        AND pg_get_constraintdef(oid) LIKE '%authority_reserved_id%'
        AND pg_get_constraintdef(oid) LIKE '%SYSTEM_ADMINISTRATION%'
        AND pg_get_constraintdef(oid) LIKE '%GLOBAL_KNOWLEDGE%';
    SELECT array_agg(conname) INTO action_domain FROM pg_constraint
        WHERE conrelid='authority_role_actions'::regclass AND contype='c'
        AND pg_get_constraintdef(oid) LIKE '%action = ANY%'
        AND pg_get_constraintdef(oid) LIKE '%RUN_MODEL%';
    SELECT array_agg(conname) INTO requested_domain FROM pg_constraint
        WHERE conrelid='authority_grants'::regclass AND contype='c'
        AND pg_get_constraintdef(oid) LIKE '%requested_role = ANY%'
        AND pg_get_constraintdef(oid) LIKE '%CONTRIBUTOR%';
    IF coalesce(array_length(builtin_domain,1),0)<>1 OR coalesce(array_length(builtin_shape,1),0)<>1
        OR coalesce(array_length(action_domain,1),0)<>1 OR coalesce(array_length(requested_domain,1),0)<>1 THEN
        RAISE EXCEPTION 'authority builtin, action or requested-role constraint is not the expected one';
    END IF;
    EXECUTE format('ALTER TABLE authority_scopes DROP CONSTRAINT %I', builtin_domain[1]);
    EXECUTE format('ALTER TABLE authority_scopes DROP CONSTRAINT %I', builtin_shape[1]);
    EXECUTE format('ALTER TABLE authority_role_actions DROP CONSTRAINT %I', action_domain[1]);
    EXECUTE format('ALTER TABLE authority_grants DROP CONSTRAINT %I', requested_domain[1]);
END $$;

ALTER TABLE authority_role_actions ADD CONSTRAINT authority_role_action_domain CHECK (
    action IN ('READ','WRITE','MANAGE_ACCESS','MANAGE_SCOPE','SET_AUDIENCE','ADMINISTER',
        'CONVERSATION_CREATE','RUN_MODEL','PROJECT_CREATE'));
ALTER TABLE authority_grants ADD CONSTRAINT authority_grant_requested_role CHECK (
    requested_role IN ('SYSTEM','ADMIN','MANAGER','CONTRIBUTOR','READER','PROJECT_CREATOR'));

ALTER TABLE authority_scopes ADD CONSTRAINT authority_scope_builtin CHECK (
    builtin IN ('SYSTEM_ADMINISTRATION','GLOBAL_KNOWLEDGE','PROJECT_CREATION'));
-- V155's two built-in branches verbatim, plus PROJECT_CREATION. As ADMINISTRATION already is, the
-- new kind is refused to every non-built-in row, so the reserved row is the only creation scope.
ALTER TABLE authority_scopes ADD CONSTRAINT authority_scope_builtin_shape CHECK (
    (builtin IS NULL AND NOT authority_reserved_id(id) AND kind NOT IN ('ADMINISTRATION','PROJECT_CREATION')) OR
    (builtin IS NOT NULL AND (
        (builtin='SYSTEM_ADMINISTRATION' AND id='00000000-0000-0000-0000-000000000003'
            AND kind='ADMINISTRATION' AND account_id='00000000-0000-0000-0000-000000000001') OR
        (builtin='GLOBAL_KNOWLEDGE' AND id='00000000-0000-0000-0000-000000000005'
            AND kind='ACCOUNT_KNOWLEDGE' AND account_id='00000000-0000-0000-0000-000000000004') OR
        (builtin='PROJECT_CREATION' AND id='00000000-0000-0000-0000-00000000000b'
            AND kind='PROJECT_CREATION' AND account_id='00000000-0000-0000-0000-000000000001'))));

ALTER TABLE authority_scopes DROP CONSTRAINT authority_scope_kind;
ALTER TABLE authority_scopes ADD CONSTRAINT authority_scope_kind CHECK (
    kind IN ('PROJECT','PERSONAL','ACCOUNT_KNOWLEDGE','ADMINISTRATION','RESTRICTED','CONTAINED','PROJECT_CREATION'));
-- V216's branches verbatim, plus PROJECT_CREATION: owned by SYSTEM, never tied to a project.
ALTER TABLE authority_scopes DROP CONSTRAINT authority_scope_kind_shape;
ALTER TABLE authority_scopes ADD CONSTRAINT authority_scope_kind_shape CHECK (
    ((kind='PROJECT' AND project_id IS NOT NULL AND account_id IS NULL) OR
        (kind='PERSONAL' AND project_id IS NOT NULL AND account_id IS NOT NULL) OR
        (kind IN ('ACCOUNT_KNOWLEDGE','ADMINISTRATION') AND project_id IS NULL AND account_id IS NOT NULL) OR
        (kind='RESTRICTED' AND project_id IS NOT NULL AND account_id IS NULL) OR
        (kind='CONTAINED' AND project_id IS NOT NULL AND account_id IS NULL AND container IS NOT NULL) OR
        (kind='PROJECT_CREATION' AND project_id IS NULL AND account_id IS NOT NULL
            AND account_id='00000000-0000-0000-0000-000000000001'))
    AND (kind='CONTAINED' OR container IS NULL));

INSERT INTO authority_roles(id,name,assignable) VALUES ('00000000-0000-0000-0000-00000000000c','PROJECT_CREATOR',TRUE);
INSERT INTO authority_role_actions(role_id,role_version,profile,action)
    VALUES ('00000000-0000-0000-0000-00000000000c',1,'DEFAULT','PROJECT_CREATE');
INSERT INTO authority_scopes(id,kind,account_id,builtin)
    VALUES ('00000000-0000-0000-0000-00000000000b','PROJECT_CREATION','00000000-0000-0000-0000-000000000001','PROJECT_CREATION');

-- One live creator grant per account. An expired grant still occupies the slot until it is
-- revoked, so reissue is always an explicit revoke-then-issue.
CREATE UNIQUE INDEX authority_one_project_creator ON authority_grants(subject_account)
    WHERE scope_id='00000000-0000-0000-0000-00000000000b' AND revoked_at IS NULL;

-- V158's registry check plus the creator role, its single action row and the creation scope.
-- JdbcIdentityRepository.verifyBuiltIns refuses to start the server if this is false.
CREATE OR REPLACE FUNCTION authority_builtin_registry_valid() RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
 SELECT authority_identity_registry_valid()
   AND (SELECT count(*)=5 FROM authority_roles WHERE name IN ('ADMIN','MANAGER','CONTRIBUTOR','READER','PROJECT_CREATOR') AND assignable)
   AND (SELECT count(*)=27 FROM authority_role_actions a JOIN authority_roles r ON r.id=a.role_id WHERE r.name<>'GLOBAL_READ')
   AND (SELECT count(*)=1 FROM authority_role_actions a JOIN authority_roles r ON r.id=a.role_id WHERE r.name='GLOBAL_READ' AND a.action='READ' AND a.role_version=1 AND a.profile='DEFAULT')
   AND (SELECT count(*)=1 FROM authority_role_actions a JOIN authority_roles r ON r.id=a.role_id
        WHERE r.id='00000000-0000-0000-0000-00000000000c' AND r.name='PROJECT_CREATOR' AND r.assignable
        AND r.intrinsic_account IS NULL AND a.action='PROJECT_CREATE' AND a.role_version=1 AND a.profile='DEFAULT')
   AND (SELECT count(*)=1 FROM authority_role_actions a WHERE a.role_id='00000000-0000-0000-0000-00000000000c')
   AND (SELECT count(*)=1 FROM authority_scopes WHERE id='00000000-0000-0000-0000-00000000000b'
        AND kind='PROJECT_CREATION' AND builtin='PROJECT_CREATION'
        AND account_id='00000000-0000-0000-0000-000000000001' AND project_id IS NULL)
   AND (SELECT count(*)=1 FROM authority_grants g JOIN authority_roles r ON r.id=g.role_id
        JOIN authority_scopes s ON s.id=g.scope_id WHERE g.provenance='ROOT_POLICY' AND g.revoked_at IS NULL AND g.expires_at IS NULL
        AND g.subject_account=r.intrinsic_account AND g.issuer_account=r.intrinsic_account
        AND g.subject_agent IS NULL AND g.issuer_agent IS NULL AND g.issuing_grant IS NULL
        AND g.coverage='ALL_SCOPES' AND s.kind='ADMINISTRATION' AND s.account_id=r.intrinsic_account
        AND g.version=1 AND NOT g.manager_forced);
$$;

-- Whether an account may create projects: an active, non-bootstrap USER holding a live (unrevoked,
-- unexpired, SYSTEM-rooted) PROJECT_CREATOR grant at the creation scope. Server administration is
-- deliberately not consulted: administrators hold the role only when it is granted to them.
-- Positional $1 because a SQL function's column names (admins.handle) shadow its parameter names.
CREATE FUNCTION authority_may_create_projects(handle TEXT) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
    SELECT account_active($1) AND EXISTS(
        SELECT 1 FROM authority_grants g JOIN authority_live_grants() l ON l.id=g.id
        JOIN authority_roles r ON r.id=g.role_id
        JOIN authority_accounts a ON a.id=g.subject_account
        JOIN admins u ON u.handle=a.legacy_handle
        WHERE a.legacy_handle=$1 AND r.name='PROJECT_CREATOR'
            AND g.scope_id='00000000-0000-0000-0000-00000000000b' AND g.subject_agent IS NULL
            AND NOT u.bootstrap AND u.account_kind='USER');
$$;

-- authority_grant_insert(): V260's definition with the PROJECT_CREATOR branch added. Every V260
-- line is kept unchanged and in order; the additions are the `creator` variable, the branch, and
-- the IF NOT creator ... END IF that wraps V260's role-ceiling and scope-coverage checks.
CREATE OR REPLACE FUNCTION authority_grant_insert() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE parent authority_grants; parent_role TEXT; requested TEXT; effective TEXT; destination authority_scopes;
    subject_kind TEXT; issuer_kind TEXT; parent_depth INTEGER; rooted BOOLEAN;
    creator BOOLEAN;
BEGIN
    IF NEW.provenance='ROOT_POLICY' THEN RAISE EXCEPTION 'Root policy cannot be assigned'; END IF;
    IF NEW.exercised_grant IS NULL THEN
        PERFORM authority_lock_lineage(NEW.issuing_grant);
        SELECT * INTO parent FROM authority_grants WHERE id=NEW.issuing_grant;
        SELECT depth INTO parent_depth FROM authority_live_grants() WHERE id=parent.id;
    ELSE
        -- A person cannot exercise a grant for themselves: otherwise a temporary (expiring or
        -- revocable-with-its-issuer) grant could reissue itself as a permanent SYSTEM-rooted one.
        IF NEW.subject_account=NEW.issuer_account THEN RAISE EXCEPTION 'Cannot exercise a grant for yourself'; END IF;
        -- Share-lock the exercised lineage so a concurrent revocation of the exercised grant or
        -- any ancestor cannot commit between this check and the new grant's commit. The root
        -- itself is immutable (authority_grant_mutation refuses ROOT_POLICY updates).
        PERFORM authority_lock_lineage(NEW.exercised_grant);
        SELECT * INTO parent FROM authority_grants WHERE id=NEW.exercised_grant;
        -- One live-grant evaluation answers both questions: the exercised grant's depth, and
        -- whether the issuing grant is the live root (depth 0 is only ever a ROOT_POLICY grant).
        SELECT max(l.depth) FILTER (WHERE l.id=NEW.exercised_grant),
            coalesce(bool_or(l.id=NEW.issuing_grant AND l.depth=0 AND g.provenance='ROOT_POLICY'),FALSE)
            INTO parent_depth,rooted
            FROM authority_live_grants() l JOIN authority_grants g ON g.id=l.id
            WHERE l.id IN (NEW.exercised_grant,NEW.issuing_grant);
        IF NOT rooted THEN RAISE EXCEPTION 'Exercised issuance must be rooted at the system policy'; END IF;
    END IF;
    IF parent_depth IS NULL OR parent_depth>=32 THEN RAISE EXCEPTION 'Issuing authority is not live or chain is too deep'; END IF;
    IF NEW.exercised_grant IS NULL THEN
        IF NOT ((parent.subject_account=NEW.issuer_account AND parent.subject_agent IS NOT DISTINCT FROM NEW.issuer_agent AND NEW.issuing_token IS NULL)
            OR (NEW.issuing_token IS NOT NULL AND NEW.issuer_agent IS NULL AND parent.subject_agent IS NULL AND EXISTS(
                SELECT 1 FROM service_tokens t JOIN authority_accounts owner ON owner.legacy_handle=t.owner_handle
                JOIN authority_accounts principal ON principal.legacy_handle=t.principal_handle
                JOIN service_token_scopes ts ON ts.token_id=t.id JOIN authority_scopes s ON s.project_id=ts.project_id
                WHERE t.id=NEW.issuing_token AND owner.id=parent.subject_account AND principal.id=NEW.issuer_account
                    AND s.id=NEW.scope_id AND ts.role='MANAGER' AND account_active(t.principal_handle)
                    AND NEW.expires_at IS NOT NULL AND NEW.expires_at<=t.expires_at))) THEN
            RAISE EXCEPTION 'Issuing grant belongs to a different principal'; END IF;
    ELSIF NOT (parent.subject_account=NEW.issuer_account AND parent.subject_agent IS NULL) THEN
        -- issuer_agent and issuing_token are refused for this branch by the CHECK constraint.
        RAISE EXCEPTION 'Issuing grant belongs to a different principal';
    END IF;
    SELECT name INTO parent_role FROM authority_roles WHERE id=parent.role_id;
    SELECT name INTO effective FROM authority_roles WHERE id=NEW.role_id;
    SELECT * INTO destination FROM authority_scopes WHERE id=NEW.scope_id;
    IF NOT FOUND THEN RAISE EXCEPTION 'Grant scope is unavailable'; END IF;
    SELECT u.account_kind INTO subject_kind FROM authority_accounts a JOIN admins u ON u.handle=a.legacy_handle WHERE a.id=NEW.subject_account;
    SELECT u.account_kind INTO issuer_kind FROM authority_accounts a JOIN admins u ON u.handle=a.legacy_handle WHERE a.id=NEW.issuer_account;
    IF NEW.exercised_grant IS NOT NULL AND issuer_kind IS DISTINCT FROM 'USER' THEN
        RAISE EXCEPTION 'Exercised issuance requires a person issuer'; END IF;
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
    -- V262: PROJECT_CREATOR exists only at the PROJECT_CREATION scope and that scope holds only
    -- PROJECT_CREATOR, so either fact puts the grant on this branch and both must hold. No issuing
    -- grant covers the creation scope and ADMIN is not a ceiling over a role outside the chain, so
    -- for this role (and only this role) the branch replaces the general role-ceiling and
    -- scope-coverage checks below: the authority is the issuer's own live ADMIN_CONTROL grant at
    -- ADMINISTRATION, exercised on V260's SYSTEM-rooted path (which already proved that grant live,
    -- held by issuer_account with no agent, the issuing grant the live root, the issuer a USER and
    -- not the subject). The issuer must also be an active, non-bootstrap server administrator now.
    -- IS NOT TRUE so an unknown (NULL) fact refuses rather than admits.
    creator := coalesce(effective='PROJECT_CREATOR' OR destination.kind='PROJECT_CREATION',FALSE);
    IF creator AND (effective='PROJECT_CREATOR' AND destination.kind='PROJECT_CREATION'
        AND NEW.requested_role='PROJECT_CREATOR'
        AND subject_kind='USER' AND NEW.subject_agent IS NULL
        AND NEW.coverage='SELF' AND NEW.provenance='ROLE'
        AND NEW.exercised_grant IS NOT NULL AND parent.provenance='ADMIN_CONTROL' AND parent_role='ADMIN'
        AND parent.scope_id='00000000-0000-0000-0000-000000000003' AND parent.subject_agent IS NULL
        AND EXISTS(SELECT 1 FROM authority_accounts a JOIN admins u ON u.handle=a.legacy_handle
            WHERE a.id=NEW.issuer_account AND u.account_kind='USER' AND u.server_admin
                AND NOT u.bootstrap AND account_active(u.handle))) IS NOT TRUE THEN
        RAISE EXCEPTION 'Role exceeds issuing authority'; END IF;
    -- V260's role-ceiling and scope-coverage checks follow byte-for-byte (deliberately not
    -- re-indented, so a line diff against V260 shows only additions); PROJECT_CREATOR skips them.
    IF NOT creator THEN
    IF effective IS DISTINCT FROM requested OR parent_role NOT IN ('SYSTEM','ADMIN','MANAGER')
        OR (parent_role='MANAGER' AND effective='ADMIN') THEN RAISE EXCEPTION 'Role exceeds issuing authority'; END IF;
    IF NOT (parent.coverage='ALL_SCOPES' OR
        parent.scope_id=NEW.scope_id OR
        (parent.coverage='ORDINARY_PROJECT_CONTROL' AND destination.kind='PROJECT')) THEN
        RAISE EXCEPTION 'Grant exceeds issuing scope'; END IF;
    END IF;
    IF NEW.coverage<>'SELF' AND NOT (NEW.provenance='ADMIN_CONTROL' AND parent.provenance='ROOT_POLICY'
        AND NEW.coverage='ORDINARY_PROJECT_CONTROL' AND destination.kind='ADMINISTRATION' AND effective='ADMIN') THEN
        RAISE EXCEPTION 'Unsupported descendant coverage'; END IF;
    -- An exercised grant hangs off the root, not the exercised grant, so it does not inherit the
    -- exercised grant's expiry.
    IF NEW.exercised_grant IS NULL AND parent.expires_at IS NOT NULL AND (NEW.expires_at IS NULL OR NEW.expires_at>parent.expires_at) THEN
        RAISE EXCEPTION 'Grant outlives issuing authority'; END IF;
    IF NEW.version<>1 OR NEW.revoked_at IS NOT NULL THEN RAISE EXCEPTION 'Invalid initial grant state'; END IF;
    RETURN NEW;
END $$;

