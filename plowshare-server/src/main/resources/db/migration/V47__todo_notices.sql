-- PLANNED AS V46 AND FILED AS V47: master's V46__project_union took the number first, and V1-V46
-- are immutable (MigrationsAreImmutableTest).
--
-- What a conversation's todo notice last said, so it is sent again only when the list or the
-- compaction state changed. See implementation rationale
--
-- compacted_through is the latest compaction's through-ordinal when the notice was produced, or -1.
-- A compaction folds the logged notice into a summary, so a notice produced before it has to be
-- sent again even though the list is the same.

CREATE TABLE todo_notices (
    conversation       TEXT        PRIMARY KEY,
    list_hash          TEXT        NOT NULL,
    compacted_through  INT         NOT NULL,
    noticed_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT todo_notices_compaction_is_an_ordinal_or_none CHECK (compacted_through >= -1)
);
