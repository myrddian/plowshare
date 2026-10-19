# JdbcQueryEmbeddingRepository

Status: Proposed; no implementation. Baseline: `fc36351f239b62984363ababaa1de1139402aafb`.

[Source](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/embedding/JdbcQueryEmbeddingRepository.java#L33) implements retained semantic-query/write allowance evidence for `BoundQueryEmbeddings` and bound memory writes. `pin/require` resolve current account/scope and optional target readability. `open/openWrite` admit a unique `(account_id,invocation)` into `embedding_queries`; `claim` locks the open source then delegates allowance claim; `bind/close` update retained attempt evidence. `lockRead` takes navigation, scope and table fences before rechecking authority.

These repeat reads across provider IO intentionally fence paid work and current disclosure. Reuse one resolved typed pin inside a single valid admission snapshot, but keep the revalidation after IO and at result assembly. Audit callers for duplicate `pin` in the same boundary; no redundant safe-to-remove query is established here. [V165](../../plowshare-server/src/main/resources/db/migration/V165__semantic_query_operation_sources.sql#L7) defines unique admission and protected query/claim bindings; existing primary/unique keys fit these point accesses. No additional index or stored procedure is justified without contention measurements. A future combined claim routine must preserve allowance ownership and transaction semantics.

Keep pins, allow decisions, open/closed state, budget availability, locks and claims outside shared cache. Immutable terminal audit DTOs could be tracked by database, query ID, projection and final version only if a public diagnostic read is introduced; this class currently has no such read API, so do not invent a cache just to use the framework. Explicit writes must invalidate any downstream allowance/query diagnostic snapshots after commit, covering allowance and inference writers as dependencies. The shared proposed LRU caches source content elsewhere, never an authority lease.

Mock tests should verify retained pin reuse and exact authority boundaries without asserting fewer security checks. Focused future PostgreSQL tests are needed only for concurrent close/claim, unique invocations, lock ordering, revocation and rollback. No uncertain query invocation may be replayed to improve latency.

Coordinate live permission evaluation with the [readability-function workstream](readability-functions.md) and apply the shared [delivery contracts](delivery.md).
