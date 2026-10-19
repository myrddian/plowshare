# JdbcSkillExecutionsRepository

Status: proposed; no runtime implementation. Baseline: `fc36351f239b62984363ababaa1de1139402aafb`.

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/JdbcSkillExecutionsRepository.java#L33) · [caller/contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/agents/SkillRuntime.java#L1) · [schema/routines](../../plowshare-server/src/main/resources/db/migration/V90__skill_executions.sql#L1). Cache mechanics and recovery follow the [shared strategy](../query-efficiency-and-cache-design.md#server-reuse-and-tracked-database-dtos).

`SkillRuntime` finds account/invocation receipts, claims by unique insertion, transitions running/closed/failed state, lists active executions by conversation and stores one immutable context snapshot. `contextResult` expands retained JSONB with a lateral array scan to find an account-owned tool-result handle. Active reads use V90's partial conversation/created-at index; claims already avoid preflight existence reads.

Retain the typed `Execution` returned by find across ordinary helpers and distinguish immutable skill definition/payload from changing state. Measure context snapshot size and repeated handle redemption before changing storage: a typed child table indexed by account/conversation/handle may outperform repeatedly expanding a large JSON array, but adds write/retention cost. Ensure the active predicate and `(created_at,invocation)` ordering match the existing partial index; add a tie-break column only if plans justify it. Use narrow state projections when definition/result content is unused and `RETURNING` where callers currently reload transitions.

Only immutable pinned context/definition projections are initial shared-cache candidates, keyed by database/account/invocation/context generation or skill hash. Every redemption still checks account and child-conversation binding. Claims, active state and dispatch authority bypass cache. Context insertion, transitions and conversation deletion invalidate execution, context-handle and active-conversation collections after commit. Durable context generation and replay-safe listener recovery precede bounded expected O(1) LRU admission; context content needs strict byte admission.

Mock reuse budgets, one context fill and wrong-account handle rejection. Focused PostgreSQL tests cover JSON projection equivalence or a new normalized/indexed projection and single-winner context insertion.
