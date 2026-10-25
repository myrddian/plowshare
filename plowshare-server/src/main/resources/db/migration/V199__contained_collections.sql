-- Collection history for contained Applications: what was removed, when, and whether its source
-- area is actually gone.
--
-- ADR 0042 claims collection is observable. Before this table it was not: a destroyed child's
-- rows are removed by the two-hop cascade (projects -> authority_scopes -> contained_applications
-- and contained_principals), so after a destruction there was nothing left to read, and the
-- staged-source removal that follows the transaction was silent. "No orphan survived" was a claim
-- no operator could check and no recovery pass could act on.
--
-- This is deliberately the one table in the containment relation with NO FOREIGN KEY AT ALL.
-- Everything it describes is being deleted as it is written -- the scope, the project, the record
-- and the principal all go in the same cascade -- so a constraint into any of them would delete
-- the history along with the thing it is history of. The columns are therefore plain values,
-- retained after their referents are gone, which is the whole point of a collection record.
--
-- It is NOT a release. ADR 0005's application_releases records what may be activated, and a
-- destroyed child may not be activated at all: there is no digest, no file count and no
-- destination here, and nothing in this migration touches application_releases or
-- application_deployment_heads. Promotion of a contained child to a retained release is a separate
-- explicit act and is not represented anywhere in this schema.
--
-- Additive: no existing row, constraint, trigger or function is changed.
CREATE TABLE contained_collections (
    -- The destroyed child's contained_applications.id, so a container can match a collection to
    -- the child it destroyed. For a SWEPT stage there was no committed record to name, so this is
    -- a freshly generated identifier for the collection itself and not a claim about any child.
    child UUID PRIMARY KEY,

    -- The scope that held it, retained as a plain value: by the time anyone reads this row the
    -- scope it names has been cascaded away, which is exactly when the history is wanted.
    container UUID,

    project TEXT NOT NULL,
    revision UUID,

    -- The source area this row accounts for. source_removed is a separate column, not an implied
    -- consequence, because the filesystem cannot join the transaction: the row is written inside
    -- the destroying transaction and marked only once the bytes are actually gone. An unmarked row
    -- is therefore precisely the work an interrupted removal left behind, and it is what
    -- ContainedSourceCollector sweeps.
    workspace TEXT NOT NULL,
    source_removed BOOLEAN NOT NULL DEFAULT FALSE,

    cause TEXT NOT NULL,
    collected_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT contained_collection_cause CHECK (cause IN ('DESTROYED','SWEPT')),
    CONSTRAINT contained_collection_project CHECK (project LIKE 'contained:%'),
    CONSTRAINT contained_collection_workspace CHECK (workspace <> '' AND workspace LIKE '/%'),
    -- A destroyed child had a committed record and so names both; a swept stage had none and so
    -- names neither. Explicit IS NULL / IS NOT NULL branches on both sides, so SQL CHECK's null
    -- truth cannot admit a half-described row.
    CONSTRAINT contained_collection_shape CHECK (
        (cause='DESTROYED' AND container IS NOT NULL AND revision IS NOT NULL) OR
        (cause='SWEPT' AND container IS NULL AND revision IS NULL))
);

CREATE INDEX contained_collection_container ON contained_collections(container)
    WHERE container IS NOT NULL;

-- The sweep's work list. Partial, because the rows that matter are the few not yet finished.
CREATE INDEX contained_collection_pending ON contained_collections(collected_at)
    WHERE NOT source_removed;
