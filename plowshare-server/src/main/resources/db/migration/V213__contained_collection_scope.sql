-- The destroyed child's own contained scope, so a collection record can be a receipt.
--
-- V212 recorded the container a collected child belonged to but not the child's own scope, which
-- meant there was no link from a parent's collection row to its descendants' rows: a destruction
-- collects a subtree, so a retried destroy could only have answered about the one record it named.
-- That is a *different* answer from the original -- a destruction reporting one record where it
-- actually collected three -- and a caller comparing the two would be misled, which is worse than
-- being refused. With the scope recorded, the destroyed subtree is rebuilt by descending the same
-- containment edges the live rows carried, and a retry replays the original answer exactly.
--
-- V212 is this branch's own migration and is not edited: this is a separate additive migration, so
-- a database that has already applied V212 moves forward without rewriting applied history.
--
-- No foreign key, for V212's reason: authority_scopes.id is gone by the time this row is read, and
-- a constraint into it would delete the history along with the thing it is history of.
ALTER TABLE contained_collections ADD COLUMN scope UUID;

-- UNIQUE because a contained scope is one Application and so is collected once. Partial, because a
-- SWEPT stage has no scope: it had no committed record, which is what made it collectable.
CREATE UNIQUE INDEX contained_collection_scope ON contained_collections(scope)
    WHERE scope IS NOT NULL;

-- The subtree rebuild descends this edge, so it is indexed the way it is walked.
CREATE INDEX contained_collection_parent ON contained_collections(container, scope)
    WHERE scope IS NOT NULL;

-- Replaces V212's shape constraint rather than adding beside it, so the three nullable columns are
-- governed by one rule and cannot disagree: a destroyed child names all three, a swept stage names
-- none. Explicit IS NULL / IS NOT NULL branches on both sides, so SQL CHECK's null truth cannot
-- admit a half-described row.
--
-- Pre-existing rows: V212 shipped on this branch and no deployment has applied it with data, but
-- the constraint is added with a NOT VALID-free plain ADD on purpose -- if any DESTROYED row did
-- exist with a null scope, this migration must fail loudly rather than leave a row the rebuild
-- would silently treat as a leaf.
ALTER TABLE contained_collections DROP CONSTRAINT contained_collection_shape;
ALTER TABLE contained_collections ADD CONSTRAINT contained_collection_shape CHECK (
    (cause='DESTROYED' AND container IS NOT NULL AND revision IS NOT NULL AND scope IS NOT NULL) OR
    (cause='SWEPT' AND container IS NULL AND revision IS NULL AND scope IS NULL));
