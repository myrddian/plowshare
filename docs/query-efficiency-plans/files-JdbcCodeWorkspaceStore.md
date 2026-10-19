# JdbcCodeWorkspaceStore

Status: proposed; no runtime implementation. Baseline: `fc36351f239b62984363ababaa1de1139402aafb`.

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/files/JdbcCodeWorkspaceStore.java#L42) · [caller/contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/files/CodeWorkspaceMonitor.java#L1) · [schema/routines](../../plowshare-server/src/main/resources/db/migration/V98__workspace_code_tracking.sql#L1). Cache mechanics and recovery follow the [shared strategy](../query-efficiency-and-cache-design.md#server-reuse-and-tracked-database-dtos).

`CodeWorkspaceMonitor` begins observations, claims due work with `FOR UPDATE SKIP LOCKED`, publishes a generation-fenced scan, records source projections/index mappings and reports status. `begin` performs existence plus total/owner capacity counts under a global advisory lock. `claim` deletes expired mutation fences then selects one eligible workspace; source publication already uses `batchUpdate`. Generation is an existing useful cache version, while leases are execution authority.

Measure foreground `begin` capacity scans and cleanup contention before changing limits. Combine existence/capacity facts into one typed set-based query under the same lock if repeated counts dominate. Consider bounded expiry maintenance rather than unbounded deletion on every claim. V98 has due, project and mutation-expiry indexes; evaluate the actual anti-join, nullable session comparison and due/ID ordering, not just index names. Keep generation CAS and mutation exclusion authoritative. Reuse a published `View` or returned generation instead of immediately reloading status.

Cache completed immutable workspace views/index mappings by database, scope including owner/session, workspace ID, generation and source hash/parser version. Do not cache claim, capacity admission, active mutation fences or lease state. Begin/publish/invalidate/forget, mutationStarted/Finished and code-index writes invalidate workspace and scoped collection dependencies after commit. Freshness checks must reject a prior ready generation after a dirty mutation. Durable change recovery and listener bypass precede shared bounded LRU use; large source/view DTOs need byte admission. Notifications can wake discovery while due-time reconciliation remains.

Mock constant source publication/query budgets and no stale generation reuse. Focused PostgreSQL tests cover competing claims, anti-join indexes and mutation/publish races.
