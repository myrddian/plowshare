# JdbcPromotionSourceRepository

Status: Proposed. Baseline: `fc36351f239b62984363ababaa1de1139402aafb`.

[`source`](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/JdbcPromotionSourceRepository.java#L18) joins proposal, memory, authority scope and project, returning a typed scope/project plus `information_memory_safe(m.id)`. It restricts scope kinds to PROJECT/PERSONAL. Promotion authorization must retain this distinction: a provenance label is not a caller identity. The ID joins are already a single query; repeated information-safety function cost is a hypothesis requiring a plan/function-count measurement.

First reuse this result within the promotion operation, passing it to scope and safety gates. If a batch really promotes multiple proposals, add a bounded typed batch lookup rather than one call per item; preserve one result per requested identity and missing semantics. A stored function would be justified only if it replaces repeated set-based authority work, not merely wraps these joins. Inspect existing primary/foreign-key plans before adding indexes.

The safety Boolean is mutable authority-sensitive state. Prefer operation-local snapshots. Shared caching could retain only immutable proposal-to-memory identity with an explicit version, while safety is currently fenced. A cached complete `Source` would require database/proposal/projection keys, source and authority versions returned together, dependencies on proposal memory, memory home/content/state, scopes and safety dependencies. Every writer and trigger must invalidate after commit; missed notifications require namespace bypass/recovery. Never treat cached `safe=true` as permission.

Mock one source resolution per promotion and batch query bounds, with unsafe/revoked and changed-home cases. A changed SQL function/index/version scheme needs focused PostgreSQL equivalence and rollback/revocation tests; no live performance result is asserted here.
