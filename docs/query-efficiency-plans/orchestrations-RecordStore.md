# RecordStore

Status: proposed; no runtime implementation. Baseline: `fc36351f239b62984363ababaa1de1139402aafb`.

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/orchestrations/RecordStore.java#L186) · [caller/contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/orchestrations/RecordKeeper.java#L1) · [schema/routines](../../plowshare-server/src/main/resources/db/migration/V59__orchestration_record.sql#L1). Cache mechanics and recovery follow the [shared strategy](../query-efficiency-and-cache-design.md#server-reuse-and-tracked-database-dtos).

`RecordKeeper` appends a root-ordered audit journal under an advisory lock, settles tool-call detail conditionally and serves cursor pages, root/run/kind detail, latest records and tool lines. `treeOfRun` and `placeOf` already use bounded recursive SQL rather than parent-by-parent JDBC calls. Append calculates `MAX(ordinal)+1`; settle returns the current watermark in the same SQL. These existing efficiencies should be retained.

Reuse resolved `Tree`/`Place` and returned ordinal/watermark within one log operation. Trace repeated `through` reads after append/settle and eliminate only redundant same-transaction reads. V59's `(root,ordinal)` primary key supports paging and root MAX, while `(root,kind,ordinal)` supports filtered pages. Profile run/conversation/kind detail queries before considering a more selective composite index. Keep bounded page projections and separate large detail/output payloads when callers only need headers; avoid per-row detail hydration. A stored routine is unnecessary unless it reduces measured append/settle trips without changing root order or conflict recovery.

Settled immutable record projections can use database/root/ordinal/detail-version keys under byte-bounded LRU; unsettled tool-call rows remain mutable. A page is immutable only under a declared upper watermark and still needs current root-owner disclosure checks. Append/settle, ownership/lineage changes and deletion invalidate root/run/conversation collection dependencies after commit. A MAX ordinal is not by itself a cross-transaction recovery cursor; durable commit-aware invalidation and listener recovery precede shared use.

Mock no redundant watermark lookup and cursor bounds. Focused PostgreSQL tests cover ordered append, settlement races and index changes when implemented.
