-- Which destruction a collection record belongs to, so a replayed destroy answers the SAME answer.
--
-- V200 recorded the destroyed child's own scope, which lets the destroyed subtree be rebuilt by
-- descending the containment edges the live rows carried. That rebuild is still not the original
-- answer, because a containment edge says which rows are BENEATH a scope and not which destruction
-- collected them. A container may destroy a grandchild today and its parent tomorrow; both rows
-- then sit on the same edge, and a rebuild selecting every destroyed row beneath the parent's scope
-- answers the second destruction with the first one's record too -- a longer `collected` list and a
-- larger `sources` count than the destruction being replayed produced.
--
-- That matters here more than it would elsewhere: ADR 0042 accepted a collection record as the
-- resolution for an uncertain destroy INSTEAD of a request-UUID receipt, on the strength of the
-- claim that a retry replays the same answer. This column is what makes that claim true.
--
-- `root` is the child a single destroy named, written identically on every record that one
-- destruction wrote, so the top row of a replayable destruction has root = child.
--
-- No foreign key, for V199's reason: the child row this names is removed by the same cascade that
-- writes this history, so a constraint into it would delete the history along with its referent.
ALTER TABLE contained_collections ADD COLUMN root UUID;

-- V199 and V200 ship on this branch and no deployment has applied them with data. If any DESTROYED
-- row did exist, the only honest root for it is itself: that row is all its destruction's history
-- records, so a replay of it answers for that one record. Stated as a backfill rather than left to
-- the constraint below, so the migration moves such a row forward instead of failing a database
-- whose data predates the column.
UPDATE contained_collections SET root=child WHERE cause='DESTROYED' AND root IS NULL;

-- The replay walks (container, root) together, so it is indexed the way it is walked.
CREATE INDEX contained_collection_destruction ON contained_collections(container, root)
    WHERE root IS NOT NULL;

-- Replaces V200's shape constraint rather than adding beside it, so the four nullable columns are
-- governed by one rule and cannot disagree: a destroyed child names all four, a swept stage names
-- none. Explicit IS NULL / IS NOT NULL branches on both sides, so SQL CHECK's null truth cannot
-- admit a half-described row.
ALTER TABLE contained_collections DROP CONSTRAINT contained_collection_shape;
ALTER TABLE contained_collections ADD CONSTRAINT contained_collection_shape CHECK (
    (cause='DESTROYED' AND container IS NOT NULL AND revision IS NOT NULL AND scope IS NOT NULL
        AND root IS NOT NULL) OR
    (cause='SWEPT' AND container IS NULL AND revision IS NULL AND scope IS NULL AND root IS NULL));
