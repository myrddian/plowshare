-- Containment as a fifth authority scope kind. Additive: no existing row, kind, trigger or
-- grant is rewritten, and every pre-existing scope keeps container NULL by construction.
-- Containment is a relation over existing projects and existing authority records; this adds no
-- second authoritative store.

ALTER TABLE authority_scopes ADD COLUMN container UUID;

-- RESTRICT, not CASCADE. Destroying a container must destroy its descendants through the
-- capability that owns their lifecycle -- projects, principals, source areas and allowances --
-- so the schema refuses loudly while a descendant scope still exists rather than silently
-- collecting the authority rows and leaving the rest orphaned. Project deletion cascades into
-- authority_scopes, so this also orders parent destruction after child destruction.
ALTER TABLE authority_scopes ADD CONSTRAINT authority_scope_container_fkey
    FOREIGN KEY (container) REFERENCES authority_scopes(id) ON DELETE RESTRICT;
CREATE INDEX authority_scope_container ON authority_scopes(container) WHERE container IS NOT NULL;

-- The two per-kind constraints V155 declared inline are unnamed, so they are located by their
-- definition rather than by a generated name, and a shape this migration does not recognise
-- aborts instead of being silently replaced.
DO $$
DECLARE kind_check TEXT[]; shape_check TEXT[];
BEGIN
    -- The kind-domain constraint is the only one that names kind and nothing else; the per-kind
    -- shape constraint is the only one that pairs project_id with account_id without touching
    -- restriction_reason or builtin. Each must match exactly once.
    SELECT array_agg(conname) INTO kind_check FROM pg_constraint
        WHERE conrelid='authority_scopes'::regclass AND contype='c'
        AND pg_get_constraintdef(oid) LIKE '%kind = ANY%'
        AND pg_get_constraintdef(oid) NOT LIKE '%project_id%'
        AND pg_get_constraintdef(oid) NOT LIKE '%account_id%';
    SELECT array_agg(conname) INTO shape_check FROM pg_constraint
        WHERE conrelid='authority_scopes'::regclass AND contype='c'
        AND pg_get_constraintdef(oid) LIKE '%project_id IS NOT NULL%'
        AND pg_get_constraintdef(oid) LIKE '%account_id IS NULL%'
        AND pg_get_constraintdef(oid) NOT LIKE '%restriction_reason%'
        AND pg_get_constraintdef(oid) NOT LIKE '%builtin%';
    IF coalesce(array_length(kind_check,1),0)<>1 OR coalesce(array_length(shape_check,1),0)<>1 THEN
        RAISE EXCEPTION 'authority_scopes kind or per-kind shape constraint is not the expected one';
    END IF;
    EXECUTE format('ALTER TABLE authority_scopes DROP CONSTRAINT %I', kind_check[1]);
    EXECUTE format('ALTER TABLE authority_scopes DROP CONSTRAINT %I', shape_check[1]);
END $$;

ALTER TABLE authority_scopes ADD CONSTRAINT authority_scope_kind CHECK (
    kind IN ('PROJECT','PERSONAL','ACCOUNT_KNOWLEDGE','ADMINISTRATION','RESTRICTED','CONTAINED'));

-- The pre-existing four branches are reproduced verbatim; CONTAINED is added beside them, and
-- the container column is bound to exactly one kind in the same constraint so no kind can
-- acquire a container by omission. Explicit IS NULL / IS NOT NULL branches keep SQL CHECK's
-- null truth from accepting a malformed row, as V155 already does.
ALTER TABLE authority_scopes ADD CONSTRAINT authority_scope_kind_shape CHECK (
    ((kind='PROJECT' AND project_id IS NOT NULL AND account_id IS NULL) OR
        (kind='PERSONAL' AND project_id IS NOT NULL AND account_id IS NOT NULL) OR
        (kind IN ('ACCOUNT_KNOWLEDGE','ADMINISTRATION') AND project_id IS NULL AND account_id IS NOT NULL) OR
        (kind='RESTRICTED' AND project_id IS NOT NULL AND account_id IS NULL) OR
        (kind='CONTAINED' AND project_id IS NOT NULL AND account_id IS NULL AND container IS NOT NULL))
    AND (kind='CONTAINED' OR container IS NULL));

-- A scope that contained itself would make the containment walk unbounded and would let a child
-- answer as its own container. The deeper cycle guard belongs with the resolution that walks the
-- relation; this is the one case a row-local CHECK can see.
ALTER TABLE authority_scopes ADD CONSTRAINT authority_scope_not_its_own_container
    CHECK (container IS NULL OR container<>id);

-- Containment must not be something a later UPDATE can undo.
--
-- V155:174-187 defines authority_map_project as an upsert keyed on the UNIQUE authority_scopes
-- .project_id and drives it from authority_project_inserted, an AFTER INSERT *OR UPDATE* trigger
-- on projects. Left alone, a contained project would (a) auto-acquire a fully addressable PROJECT
-- scope that no caller named, (b) make the explicit CONTAINED insert collide on that unique key,
-- and (c) have any later UPDATE of its projects row silently flip an existing CONTAINED scope back
-- to PROJECT through DO UPDATE SET kind=EXCLUDED.kind -- which authority_scope_locked permits,
-- because it only requires pg_trigger_depth()>=2 and an unchanged project_id. (c) is the dangerous
-- one: it is not an escape hatch but retroactive, silent loss of containment.
--
-- A contained project is therefore identified by a reserved 'contained:' name prefix and excluded
-- from the auto-mapping by name, exactly as 'personal:%' and 'client:%' already are. The prefix is
-- a fixed-length classification marker and is constant at any depth; containment ancestry lives in
-- the container column, so no readable path grows with depth and ADR 0021's capacity bound is
-- untouched. The trigger is not taught to mint a CONTAINED scope, because it has no container
-- value available and so could not produce a valid one: creation inserts the scope explicitly with
-- its container.
CREATE OR REPLACE FUNCTION authority_map_project(project BIGINT) RETURNS void LANGUAGE SQL AS $$
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

-- The pre-existing three branches are reproduced verbatim from V155:124-145. The PROJECT branch
-- additionally excludes 'contained:%', so a contained project cannot be given an ordinary project
-- scope even by an explicit insert that bypasses the mapping function; the new CONTAINED branch is
-- its mirror image. The container-existence check skips the self-reference case deliberately: that
-- row is rejected by authority_scope_not_its_own_container, which gives the precise refusal, and
-- the FK would otherwise be satisfied by the inserting row itself.
CREATE OR REPLACE FUNCTION authority_scope_shape() RETURNS trigger LANGUAGE plpgsql AS $$
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

-- Widening the Java-side exclusion was only half the fence. V156 declares four RESTRICTED-only
-- predicates in SQL, three of which are live functions that would have admitted a contained scope,
-- and one of which mints grants entirely inside the database where no Java predicate can see it.
-- Each function below is reproduced verbatim from V156 with exactly one predicate changed, so the
-- difference from the original is a one-line diff in each case.
--
-- The fourth, the one-time MEMBERSHIP backfill INSERT at V156:254, is deliberately left alone: it
-- is not a function, it runs once during V156, and no contained scope can exist at that point even
-- on a fresh database, so it is inert.

-- 1/3. authority_live_grants(): a grant against a contained scope must never be live. Without
-- this, a grant that reached the table by any route would be honoured by every live-grant read.
CREATE OR REPLACE FUNCTION authority_live_grants() RETURNS TABLE(id UUID, depth INTEGER) LANGUAGE SQL STABLE AS $$
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

-- 2/3. authority_grant_insert(): the database's own admission check for a new grant. The Java
-- repository refuses a contained target before it gets here, but this is the backstop that holds
-- for any writer that does not go through it.
CREATE OR REPLACE FUNCTION authority_grant_insert() RETURNS trigger LANGUAGE plpgsql AS $$
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

-- 3/3. authority_membership_changed(): the most consequential of the three. A project_members row
-- for a child project resolved the project's scope by kind<>'RESTRICTED' and minted a MEMBERSHIP
-- grant against it from inside an AFTER INSERT OR UPDATE OR DELETE trigger -- no application code
-- involved, so the repository exclusion could not see it, let alone refuse it. Resolving nothing
-- for a contained scope makes the trigger return without minting anything, which is its existing
-- behaviour for a restricted scope.
CREATE OR REPLACE FUNCTION authority_membership_changed() RETURNS trigger LANGUAGE plpgsql AS $$
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
