# JdbcRelaySourceRepository

Status: Proposed; no implementation. Baseline: `fc36351f239b62984363ababaa1de1139402aafb`.

The [Relay implementation receipt](../query-efficiency-relay-slice.md) records the
locally implemented projection/index/cache/wakeup subset and verification. The
remaining candidates below retain their original proposal/baseline context.

[Source](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/JdbcRelaySourceRepository.java#L34) implements owning-row outbox publication for `RelayInternalWorkers`. Pending topics selects distinct computed scope/topic keys with a keyset cursor. Publish-next takes a nonblocking partition advisory lock, selects the oldest source event `FOR UPDATE`, rechecks private board-message exclusion, registers the broker topic, binds native wakes when applicable, publishes with stable source-event identity and deletes the outbox row atomically. No handler effects execute in this transaction.

**Confirmed index/query mismatch candidate:** [V124](../../plowshare-server/src/main/resources/db/migration/V124__relay_lifecycle_outbox.sql#L20) indexes `COALESCE(project_id,0),topic,sequence`, while publish-next uses `project_id IS NOT DISTINCT FROM ?`. Check representative plans before selecting an exact matching expression predicate or separate null/non-null query variants and indexes. Pending discovery computes/concatenates scope per row and deduplicates every pass; evaluate a stored/generated scope key and tuple keyset projection, or a bounded distinct partition index. No claim of current latency is justified from this source alone.

Reuse the registered topic snapshot within one publication transaction to avoid repeated registration/lock hydration across nested broker calls where a typed capability can preserve invariants. A bounded per-topic publication routine could amortize round trips, but must retain ordering, byte limits, native binding, privacy filtering and rollback. Do not mark an outbox row consumed before durable broker append commits.

This is primarily notification/discovery work, not a shared result-cache candidate. Add transaction-issued opaque hints to all source producers, including [V124 capture](../../plowshare-server/src/main/resources/db/migration/V124__relay_lifecycle_outbox.sql#L22), [V126 board/message capture](../../plowshare-server/src/main/resources/db/migration/V126__relay_board_message_lifecycle.sql#L3), wake and scheduled/domain writers. Keep outbox rows, partition locks and periodic full discovery/recovery; sequence allocation alone does not establish commit order. Follow [delivery contracts](delivery.md).

Optional diagnostic event DTOs need database/source UUID, payload/projection identity and source existence/version, current authorization and bounded tracked DTO/LRU admission. Never cache pending absence, claim availability or privacy decisions as authority. Publish/delete, producer inserts, project cascades and administrative writers invalidate diagnostic membership after commit; broker publication cache updates belong to the broker's own dependencies. Mock tests should prove hint coalescing and bounded discovery while retaining fallback. Later focused PostgreSQL tests cover two publishers, rollback/idempotent source identity, privacy exclusion, null-scope plans and notifications missed around commit/reconnect.
