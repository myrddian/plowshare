-- These set-returning authority routines are operation boundaries, not expressions to expand
-- into every caller's generic prepared plan. With unknown selection/actor parameters, inlining
-- their recursive CTEs estimates hundreds of thousands of cost units even for an empty inbox or
-- a small document page. PostgreSQL then spends seconds compiling hundreds of JIT expressions
-- for reads whose actual execution takes tens of milliseconds. Every pooled connection can
-- independently cross the custom/generic-plan threshold, making that delay intermittent.
--
-- A function-local setting both prevents SQL-function inlining and keeps its internal plan from
-- paying the same compilation cost. It is restored on return; other statements retain the
-- caller's JIT policy. The function bodies, STABLE snapshot, invoker privileges and all live
-- authority/dependency rules are unchanged. No permission result survives the statement.
ALTER FUNCTION knowledge_readable_selection(TEXT,TEXT,TEXT,BOOLEAN,TEXT,BOOLEAN,TEXT,TEXT,
 BOOLEAN,UUID,TEXT,TEXT) SET jit = 'off';
ALTER FUNCTION information_unreadable_logs(TEXT) SET jit = 'off';
