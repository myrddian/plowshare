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
ALTER TABLE authority_scopes ADD CONSTRAINT authority_scope_shape CHECK (
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
