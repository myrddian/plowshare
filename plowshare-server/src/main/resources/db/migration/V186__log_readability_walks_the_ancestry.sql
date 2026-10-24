-- Walk a conversation's ancestry instead of materialising every ancestry in the installation.
--
-- information_log_readable answers one question: does this log, or anything it descends from,
-- carry quarantined or unreadable information. It is applied as a ROW FILTER in thirteen places
-- (EntryStore, DigestStore, JdbcPassageRepository, OrchestrationStore, JdbcInboxStore,
-- CitationStore, MemoryStore), so a listing evaluates it once per candidate row.
--
-- Its `edges` CTE was not parameterised by the target. Every call built the parent graph of the
-- WHOLE installation -- all of conversations plus orchestrations, four ways -- and only then
-- walked it from one id. Measured against the live installation (12,845 conversations):
--
--   information_log_readable, 20 calls on one target        92.076 ms   (4.6 ms per call)
--   the same predicate with the walk below, 20 calls         1.896 ms   (0.095 ms per call)
--
--   SELECT count(*) FROM conversations WHERE <predicate>   59,113 ms   per-row, whole table
--
-- 4.6 ms of fixed cost per row is what turns a 50-row page into 2.7 seconds, and the orchestration
-- listing in OrchestrationStore applies it beneath an ORDER BY / LIMIT, so it is paid for every
-- candidate row before the limit can discard any. On the live four-core host this predicate was
-- one of three queries holding the load average at 4.0 with no wait events at all -- pure CPU.
--
-- It is AGENTS.md's named anti-pattern: repeated permission resolution within one operation.
--
-- The fix is the shape, not the logic. `edges` becomes an inline subquery joined once against the
-- recursive term, so the planner pushes `e.child = a.id` into each branch and reaches it through
-- conversations_pkey, orchestrations_pkey and orchestrations_conductor_conversation_key rather
-- than scanning both tables. Every branch, every NOT EXISTS and the account condition are carried
-- over unchanged, because the admitted set must not move -- only the cost of deciding it.
--
-- No caller changes. All thirteen sites keep the same function, signature and verdict.
CREATE OR REPLACE FUNCTION information_log_readable(target TEXT, account TEXT)
RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
    WITH RECURSIVE ancestors(id) AS (
        SELECT $1
        UNION
        SELECT e.parent FROM ancestors a JOIN (
            SELECT id AS child, parent_id AS parent FROM conversations WHERE parent_id IS NOT NULL
            UNION ALL
            SELECT conductor_conversation, caller_conversation FROM orchestrations
             WHERE caller_conversation IS NOT NULL
            UNION ALL
            SELECT o.conductor_conversation, p.conductor_conversation
              FROM orchestrations o JOIN orchestrations p ON p.id = o.parent
            UNION ALL
            SELECT id, conductor_conversation FROM orchestrations
        ) e ON e.child = a.id
    )
    SELECT NOT EXISTS(SELECT 1 FROM ancestors a
                       JOIN information_quarantined_payloads q ON q.payload_id = a.id)
    AND NOT EXISTS(SELECT 1 FROM ancestors a
                    JOIN information_job_inputs i ON i.job_id = a.id
                   WHERE $2 IS NULL OR $2 <> i.owner_handle
                      OR NOT information_readable(i.revision_id, $2, i.scope, i.project_name,
                                                  i.include_shared));
$$;
