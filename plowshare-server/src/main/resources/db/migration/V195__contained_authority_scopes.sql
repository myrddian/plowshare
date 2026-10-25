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
