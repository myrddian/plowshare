-- The read boundary decided once per query instead of once per revision.
--
-- knowledge_document_in_boundary is applied as a row filter, so a listing evaluated it on every
-- revision it scanned: on a live installation, a sequential scan over 2,782 revisions keeping 66,
-- six seconds spent to discard 97.6% of the rows. Each call nested four more function calls, and
-- authority_project_role alone costs about 1.2ms.
--
-- The decision needs far less than the row. A revision inherits its resource's permission and a
-- resource its scope's, and an installation has a handful of scopes: those same 2,808 revisions
-- across 2,796 resources resolved to 7 distinct scopes, with no resource whose revisions
-- disagreed. So the readable scope set is resolved once and the per-row test becomes membership of
-- it, against the existing information_resources(home_scope_id, source_name) index.
--
-- Semantics are unchanged, which is checked rather than asserted: the admitted sets are compared
-- in both directions, here and in KnowledgeBoundaryHoistingDatabaseTest. An earlier attempt at
-- this rewrite was 16x faster and admitted 2,698 revisions it should have refused, because it
-- dropped the conjunct binding a document's scope to the boundary's own home. Readability and
-- containment are different questions.
--
-- knowledge_document_in_boundary is retained unchanged. It is the reference the equivalence tests
-- compare against, and removing it is a separate step once no caller remains.

-- The scopes a reader may read *within one boundary home*. Both conditions depend only on the
-- actor and the home, never on a revision, which is what makes hoisting them sound. The third
-- route into the boundary, global read, stays per revision because it genuinely varies by one.
CREATE FUNCTION knowledge_readable_scopes(actor text, home_project text) RETURNS SETOF uuid
  LANGUAGE sql STABLE AS $$
  SELECT s.id FROM authority_scopes s
  LEFT JOIN projects p ON p.id = s.project_id
  LEFT JOIN authority_accounts a ON a.id = s.account_id
  WHERE (p.name = $2 AND knowledge_scope_readable($1, s.id))
     OR (s.kind = 'ACCOUNT_KNOWLEDGE' AND s.builtin IS NULL AND a.legacy_handle = $1
         AND EXISTS(SELECT 1 FROM projects own
                    WHERE own.name = $2 AND own.personal_owner = $1));
$$;

COMMENT ON FUNCTION knowledge_readable_scopes(text, text) IS
  'Scopes readable by an actor inside one boundary home. Hoist this to an InitPlan: evaluated per row it is slower than the predicate it replaces.';

-- The per-row test. Takes the hoisted scope set and the hoisted general-read permission, so a call
-- performs no role resolution of its own.
--
-- The dependency closure is skipped for a revision that has none, which is almost all of them:
-- 10 of 2,776 on the live installation had any input or obligation. Both existence tests are index
-- lookups -- information_inputs(derived_revision, input_revision) and
-- information_reference_obligations(source) -- so the fast path costs two probes rather than a
-- recursive walk. The bounded closure, including its 1024 limit, is unchanged for the revisions
-- that do have dependencies.
CREATE FUNCTION knowledge_document_admitted(revision uuid, general boolean, readable uuid[])
  RETURNS boolean LANGUAGE sql STABLE AS $$
  SELECT CASE
    WHEN NOT EXISTS(SELECT 1 FROM information_inputs i WHERE i.derived_revision = $1)
     AND NOT EXISTS(SELECT 1 FROM information_reference_obligations o
                    JOIN information_revisions v ON v.resource_id = o.source WHERE v.id = $1)
    THEN EXISTS(SELECT 1 FROM information_revisions r
                JOIN information_resources q ON q.id = r.resource_id
                WHERE r.id = $1
                  AND (q.home_scope_id = ANY($3)
                       OR ($2 AND knowledge_general_readable(r.id))))
    ELSE (SELECT count(*) <= 1024
            AND coalesce(bool_and(q.home_scope_id = ANY($3)
                                  OR ($2 AND knowledge_general_readable(r.id))), false)
          FROM knowledge_dependency_revisions($1) dependency
          LEFT JOIN information_revisions r ON r.id = dependency.id
          LEFT JOIN information_resources q ON q.id = r.resource_id)
  END;
$$;

COMMENT ON FUNCTION knowledge_document_admitted(uuid, boolean, uuid[]) IS
  'Per-row read boundary test. Both non-revision arguments must be hoisted by the caller; resolving them per row is what this replaces.';
