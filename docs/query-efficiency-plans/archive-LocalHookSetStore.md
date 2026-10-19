# LocalHookSetStore

Status: Proposed. Baseline: `fc36351f239b62984363ababaa1de1139402aafb`.

[`remember` and `find`](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/LocalHookSetStore.java#L45) persist canonical hook files by content hash with `ON CONFLICT DO NOTHING`, then decode one JSONB row on lookup. `PinnedLocalHooks`, `LocalHookSets` and script-hook composition consume the same sets. This is a strong immutable-content reuse candidate: conversations pin the hash separately. Verify repeated caller loads before calling them a measured bottleneck.

First pass the already ordered/validated hook DTOs from resolution to consumers and avoid loading a hash twice during one hook composition. Retain idempotent insert; a stored procedure or additional index is unnecessary for a hash primary-key lookup. Avoid reading entire conversation/project rows merely to recover an already known pinned hash.

Track a defensive immutable ordered file list under database identity, hash and hook-file schema/projection version in the shared bounded LRU. Content hash supplies the value version; bound total text weight and bypass oversized sets. Do not publish an uncommitted `remember`; a conflict is not proof a caller's surrounding transaction committed. Invalidate negatives on committed insertion and cover exceptional maintenance deletion/replacement via database change evidence. Conversation pin changes invalidate conversation projections, not unrelated hash content. Current project/conversation authority still gates disclosure and execution; a cached hook set grants no executable authority. Reconnect clears uncertain mutable pointers/negatives and recovers immutable content provenance before reuse.

Mock single-load coalescing, immutable results, oversized bypass and identical-hash reuse. Only persistence changes require focused PostgreSQL conflict/rollback and notification coverage tests. Preserve the existing retained-reference semantics; this plan adds no garbage-collection policy.
