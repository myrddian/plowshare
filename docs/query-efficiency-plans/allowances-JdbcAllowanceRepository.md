# JdbcAllowanceRepository

Status: proposed; no runtime implementation. Baseline: `fc36351f239b62984363ababaa1de1139402aafb`.

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/allowances/JdbcAllowanceRepository.java#L18) · [caller/contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/allowances/AllowanceRepository.java#L1) · [schema/routines](../../plowshare-server/src/main/resources/db/migration/V173__standalone_execution_allowances.sql#L1). Cache mechanics and recovery follow the [shared strategy](../query-efficiency-and-cache-design.md#server-reuse-and-tracked-database-dtos).

The owner retains execution allowances, reads ceiling/spent, invokes `execution_allowance_claim`/`accept`, and conditionally adjusts ceilings. Atomic SQL functions in V173 already own spending and claim acceptance. `AllowanceRepository.attach` verifies existence with `find`; `restore` reads once and wires callbacks, while later budget snapshots deliberately read current usage. These reads must be classified before calling them redundant.

Trace budget hydration across conversation, board, information and inference owners. Pass an already loaded `AllowanceSnapshot` to a typed attach/restore variant when the same operation has just obtained it; do not issue an existence read solely to discard ceiling/spent. Keep claim and accept server-authoritative under their existing atomic routines. If a caller needs the post-adjustment snapshot, consider `UPDATE ... RETURNING call_limit,spent` rather than a second find. Primary-key access is already appropriate; an index or new stored procedure has no demonstrated need.

Do not share-cache mutable spent/ceiling values for admission, reserve calculation or allocation changes. Immutable claim evidence may be reused request-locally, but a cached count cannot grant another call. Historical display snapshots require database/allowance/version keys and an explicit stale-display contract before bounded LRU admission. Retain/claim/ceiling/increase/add and trigger-based allowance attachment must invalidate source and owning conversation/revision/report dependencies after commit. Durable versions and listener recovery are prerequisites, not replacements for atomic spending.

Mock hydration query budgets and ensure zero SQL savings are claimed for required spending. Focused PostgreSQL tests apply to changed claim/CAS/RETURNING behavior and concurrent finite limits.
