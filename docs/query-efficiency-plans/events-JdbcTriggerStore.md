# JdbcTriggerStore

Status: Proposed; no implementation. Baseline: `fc36351f239b62984363ababaa1de1139402aafb`.

[Source](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/events/JdbcTriggerStore.java#L34) implements `TriggerStore` for `Intake`, `Dispatcher` and schedule-file reconciliation. Reads are `list` ordered by name, primary-key `find`, and `listening(event)` restricted to unpaused rows ordered by name. Writes use ownership-guarded upsert `RETURNING`, or handle-scoped pause/delete; retain these race-safe semantics.

No repeated read inside this small repository is established. The composition issue is repeated trigger lookup during file reconciliation; pass the already-loaded trigger snapshot through that operation. [V40](../../plowshare-server/src/main/resources/db/migration/V40__events_and_user_inbox.sql#L69) already provides a partial event index. Compare `(event,name) WHERE NOT paused` against actual plans before expanding it; a stored procedure adds no demonstrated benefit to these single statements. Consider bounded pagination only if the management list becomes large, with a compatible client contract.

Display/listening DTO caching needs database, name or event, projection and monotonic definition version. Add that version to returned rows; include collection membership dependencies so newly inserted event listeners invalidate cached empty lists. Every `define/pause/forget`, schedule reconciliation, source cleanup and direct writer must invalidate affected old and new event groups after commit. Use the proposed tracked immutable DTO cache and bounded expected O(1) LRU; notification hints need durable recovery and generation-fenced fills. A cached trigger cannot authorize a run or replace live pause/ownership checks. Keep runtime admission authoritative, even when candidate discovery reuses configuration.

Mock tests should establish one candidate lookup per event and no redundant find after upsert. Later narrowly selected PostgreSQL tests cover cross-account upsert conflicts, pause races, mapping and any index migration. Client invalidations should update only affected schedule/trigger views.
