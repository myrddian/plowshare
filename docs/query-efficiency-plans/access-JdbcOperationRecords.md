# JdbcOperationRecords

Status: proposed; no runtime implementation. Baseline: `fc36351f239b62984363ababaa1de1139402aafb`.

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/JdbcOperationRecords.java#L77) · [caller/contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/access/PinnedOperationAuthority.java#L1) · [schema/routines](../../plowshare-server/src/main/resources/db/migration/V159__retained_operation_authority.sql#L1). Cache mechanics and recovery follow the [shared strategy](../query-efficiency-and-cache-design.md#server-reuse-and-tracked-database-dtos).

The store retains sealed admissions/support, child-parent edges and immutable conversation bindings, then correlates jobs. `find` hydrates support with a nested query; `conversation` calls `find`, producing three reads. `bind` inserts idempotently then rehydrates that entire graph merely to compare binding fields. `PinnedOperationAuthority` consumes these records; evidence never supplies current permission.

Replace binding verification with a narrow retained-binding projection, preserving comparison of every invariant and conflict detection. Use batched support inserts within the existing seal transaction. Offer a typed joined/aggregated admission loader so callers retain one complete DTO instead of repeating `conversation`/`find`. V159's `(operation,action,grant_id)` primary key already supports support ordering; inspect V162's conversation-by-operation access before adding a targets index. Do not remove sealing or lineage triggers. A bounded table-returning routine could combine admission plus support if it measurably reduces trips.

Cache only complete sealed immutable evidence, keyed by database, admission UUID and projection; conversation bindings additionally use conversation identity. Exclude mutable job correlation or give it an explicit source generation. A retained DTO is not a live grant: `PinnedOperationAuthority` must still perform current authority/lifecycle checks. Publish only after outer commit; bind/retain-child/started and administrative changes invalidate entity and operation-target collections. Shared expected O(1) LRU admission requires durable version/change recovery, including parent/child dependencies.

Mock budgets for conversation load and bind conflict paths. Focused database checks verify seal atomicity, duplicate binding and rollback if SQL changes.
