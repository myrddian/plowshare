-- Additive identity substrate. No resource, nullable home, grant or audience is rewritten.
-- Fixed UUIDs are protocol identities, never credentials or permission bypasses.
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM admins WHERE lower(handle) IN ('system','global')
        OR handle IN ('00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002',
            '00000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-000000000004',
            '00000000-0000-0000-0000-000000000005','00000000-0000-0000-0000-000000000006')) THEN
        RAISE EXCEPTION 'Reserved authority identity collides with an existing account; resolve explicitly before migration';
    END IF;
END $$;

ALTER TABLE admins DROP CONSTRAINT admins_account_kind_check;
ALTER TABLE admins ADD CONSTRAINT admins_account_kind_check
    CHECK (account_kind IN ('USER','SERVICE','SERVICE_TOKEN','BUILTIN'));
ALTER TABLE admins ADD CONSTRAINT builtin_accounts_are_locked CHECK (account_kind<>'BUILTIN' OR (
    handle IN ('SYSTEM','GLOBAL') AND NOT enabled AND NOT bootstrap AND NOT server_admin
    AND NOT must_change_password AND session_version=0 AND password_hash='!locked-built-in'));

CREATE FUNCTION authority_reserved_id(id UUID) RETURNS BOOLEAN LANGUAGE SQL IMMUTABLE AS $$
    SELECT id IN ('00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002',
        '00000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-000000000004',
        '00000000-0000-0000-0000-000000000005','00000000-0000-0000-0000-000000000006');
$$;

CREATE TABLE authority_accounts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid() CHECK (id<>'00000000-0000-0000-0000-000000000000'),
    legacy_handle TEXT NOT NULL UNIQUE REFERENCES admins(handle) ON UPDATE CASCADE ON DELETE CASCADE,
    builtin TEXT UNIQUE CHECK (builtin IN ('SYSTEM','GLOBAL'))
);
-- Explicit IS NOT NULL branches keep SQL CHECK's null truth from accepting malformed built-ins.
ALTER TABLE authority_accounts ADD CONSTRAINT authority_account_registry_shape CHECK (
    (builtin IS NULL AND NOT authority_reserved_id(id)) OR
    (builtin IS NOT NULL AND ((builtin='SYSTEM' AND id='00000000-0000-0000-0000-000000000001' AND legacy_handle='SYSTEM') OR
        (builtin='GLOBAL' AND id='00000000-0000-0000-0000-000000000004' AND legacy_handle='GLOBAL'))));

CREATE TABLE authority_roles (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    assignable BOOLEAN NOT NULL,
    intrinsic_account UUID UNIQUE REFERENCES authority_accounts(id),
    CHECK ((id='00000000-0000-0000-0000-000000000002' AND name='SYSTEM' AND NOT assignable
            AND intrinsic_account IS NOT NULL AND intrinsic_account='00000000-0000-0000-0000-000000000001') OR
        (id='00000000-0000-0000-0000-000000000006' AND name='GLOBAL_READ' AND assignable AND intrinsic_account IS NULL))
);

CREATE TABLE authority_scopes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid() CHECK (id<>'00000000-0000-0000-0000-000000000000'),
    kind TEXT NOT NULL CHECK (kind IN ('PROJECT','PERSONAL','ACCOUNT_KNOWLEDGE','ADMINISTRATION','RESTRICTED')),
    project_id BIGINT UNIQUE REFERENCES projects(id) ON DELETE CASCADE,
    account_id UUID REFERENCES authority_accounts(id) ON DELETE CASCADE,
    builtin TEXT UNIQUE CHECK (builtin IN ('SYSTEM_ADMINISTRATION','GLOBAL_KNOWLEDGE')),
    restriction_reason TEXT,
    CHECK ((kind='PROJECT' AND project_id IS NOT NULL AND account_id IS NULL) OR
        (kind='PERSONAL' AND project_id IS NOT NULL AND account_id IS NOT NULL) OR
        (kind IN ('ACCOUNT_KNOWLEDGE','ADMINISTRATION') AND project_id IS NULL AND account_id IS NOT NULL) OR
        (kind='RESTRICTED' AND project_id IS NOT NULL AND account_id IS NULL)),
    CHECK ((kind='RESTRICTED' AND restriction_reason IS NOT NULL AND length(restriction_reason)>0) OR
        (kind<>'RESTRICTED' AND restriction_reason IS NULL)),
    CHECK ((builtin IS NULL AND NOT authority_reserved_id(id) AND kind<>'ADMINISTRATION') OR
        (builtin IS NOT NULL AND (
            (builtin='SYSTEM_ADMINISTRATION' AND id='00000000-0000-0000-0000-000000000003'
                AND kind='ADMINISTRATION' AND account_id='00000000-0000-0000-0000-000000000001') OR
            (builtin='GLOBAL_KNOWLEDGE' AND id='00000000-0000-0000-0000-000000000005'
                AND kind='ACCOUNT_KNOWLEDGE' AND account_id='00000000-0000-0000-0000-000000000004'))))
);
CREATE UNIQUE INDEX authority_one_knowledge_scope ON authority_scopes(account_id) WHERE kind='ACCOUNT_KNOWLEDGE';
CREATE UNIQUE INDEX authority_one_personal_scope ON authority_scopes(account_id) WHERE kind='PERSONAL';

CREATE FUNCTION authority_account_lifecycle() RETURNS trigger LANGUAGE plpgsql AS $$
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
CREATE TRIGGER authority_account_lifecycle BEFORE INSERT OR UPDATE OR DELETE ON admins
    FOR EACH ROW EXECUTE FUNCTION authority_account_lifecycle();

CREATE FUNCTION authority_account_mapping_locked() RETURNS trigger LANGUAGE plpgsql AS $$
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
CREATE TRIGGER authority_account_mapping_locked BEFORE UPDATE OR DELETE ON authority_accounts
    FOR EACH ROW EXECUTE FUNCTION authority_account_mapping_locked();

CREATE FUNCTION authority_role_locked() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Built-in role identities are immutable; SYSTEM is intrinsic and non-assignable'; END $$;
CREATE TRIGGER authority_role_locked BEFORE UPDATE OR DELETE ON authority_roles
    FOR EACH ROW EXECUTE FUNCTION authority_role_locked();

CREATE FUNCTION authority_scope_locked() RETURNS trigger LANGUAGE plpgsql AS $$
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
CREATE TRIGGER authority_scope_locked BEFORE UPDATE OR DELETE ON authority_scopes
    FOR EACH ROW EXECUTE FUNCTION authority_scope_locked();

CREATE FUNCTION authority_scope_shape() RETURNS trigger LANGUAGE plpgsql AS $$
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
        AND p.personal_owner IS NULL AND p.name NOT LIKE 'personal:%' AND p.name NOT LIKE 'client:%') THEN
        RAISE EXCEPTION 'Project scope requires an ordinary project';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER authority_scope_shape BEFORE INSERT OR UPDATE ON authority_scopes
    FOR EACH ROW EXECUTE FUNCTION authority_scope_shape();

-- Both backfill and future inserts retain the original handle/key. UUID generation here is only
-- for ordinary records, in the same transaction. Built-ins always use the reserved registry.
CREATE FUNCTION authority_map_account() RETURNS trigger LANGUAGE plpgsql AS $$
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
CREATE TRIGGER authority_map_account AFTER INSERT ON admins FOR EACH ROW EXECUTE FUNCTION authority_map_account();
INSERT INTO authority_accounts(legacy_handle) SELECT handle FROM admins;
INSERT INTO authority_scopes(kind,account_id)
    SELECT 'ACCOUNT_KNOWLEDGE',i.id FROM authority_accounts i JOIN admins a ON a.handle=i.legacy_handle
    WHERE a.account_kind IN ('USER','SERVICE');

CREATE FUNCTION authority_map_project(project BIGINT) RETURNS void LANGUAGE SQL AS $$
    INSERT INTO authority_scopes(kind,project_id,account_id,restriction_reason)
    SELECT CASE WHEN (p.personal_owner IS NULL AND p.name NOT LIKE 'personal:%' AND p.name NOT LIKE 'client:%') THEN 'PROJECT'
            WHEN a.account_kind='USER' THEN 'PERSONAL' ELSE 'RESTRICTED' END,
        p.id,CASE WHEN a.account_kind='USER' THEN i.id ELSE NULL END,
        CASE WHEN (p.personal_owner IS NULL AND p.name NOT LIKE 'personal:%' AND p.name NOT LIKE 'client:%')
            OR a.account_kind='USER' THEN NULL ELSE 'legacy_target_requires_explicit_classification' END
    FROM projects p LEFT JOIN admins a ON a.handle=p.personal_owner
        LEFT JOIN authority_accounts i ON i.legacy_handle=a.handle WHERE p.id=project
    ON CONFLICT (project_id) DO UPDATE SET kind=EXCLUDED.kind,account_id=EXCLUDED.account_id,restriction_reason=EXCLUDED.restriction_reason
    WHERE (authority_scopes.kind,authority_scopes.account_id,authority_scopes.restriction_reason)
        IS DISTINCT FROM (EXCLUDED.kind,EXCLUDED.account_id,EXCLUDED.restriction_reason);
$$;
SELECT authority_map_project(id) FROM projects;
CREATE FUNCTION authority_project_lifecycle() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.personal_owner IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM admins WHERE handle=NEW.personal_owner AND account_kind='USER') THEN
        RAISE EXCEPTION 'Only USER accounts have Personal projects';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER authority_project_lifecycle BEFORE INSERT OR UPDATE ON projects
    FOR EACH ROW EXECUTE FUNCTION authority_project_lifecycle();
CREATE FUNCTION authority_project_inserted() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN PERFORM authority_map_project(NEW.id); RETURN NEW; END $$;
CREATE TRIGGER authority_project_inserted AFTER INSERT OR UPDATE ON projects
    FOR EACH ROW EXECUTE FUNCTION authority_project_inserted();

-- Credential stores must never retain a builtin identity, even through a raw repository caller.
CREATE FUNCTION authority_no_builtin_session() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM admins WHERE handle=NEW.handle AND account_kind='BUILTIN') THEN
        RAISE EXCEPTION 'Built-in accounts cannot have login sessions';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER authority_no_builtin_session BEFORE INSERT OR UPDATE ON auth_session_chains
    FOR EACH ROW EXECUTE FUNCTION authority_no_builtin_session();
CREATE FUNCTION authority_no_builtin_token() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM admins WHERE handle IN (NEW.owner_handle,NEW.principal_handle) AND account_kind='BUILTIN') THEN
        RAISE EXCEPTION 'Built-in accounts cannot issue or hold service credentials';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER authority_no_builtin_token BEFORE INSERT OR UPDATE ON service_tokens
    FOR EACH ROW EXECUTE FUNCTION authority_no_builtin_token();
CREATE FUNCTION authority_no_builtin_acting() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM admins WHERE handle IN (NEW.actor,NEW.subject) AND account_kind='BUILTIN') THEN
        RAISE EXCEPTION 'Built-in accounts cannot be selected for acting';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER authority_no_builtin_acting BEFORE INSERT OR UPDATE ON acting_tokens
    FOR EACH ROW EXECUTE FUNCTION authority_no_builtin_acting();

CREATE FUNCTION authority_builtin_registry_valid() RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
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
CREATE FUNCTION authority_bootstrap_builtins() RETURNS void LANGUAGE plpgsql AS $$
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
SELECT authority_bootstrap_builtins();
