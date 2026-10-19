# CompactionStore

Status: Proposed. Source baseline: `fc36351f239b62984363ababaa1de1139402aafb`.

[The store](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/archive/CompactionStore.java#L64) inserts with `RETURNING`, reads the greatest `through_ordinal`, and lists all folds ascending. `Compaction`, todo notice construction and conversation inspection consume these different projections. They are not interchangeable: history must retain every seam. [V8](../../plowshare-server/src/main/resources/db/migration/V8__compactions.sql#L30) already supplies `(conversation_id, through_ordinal)` as the primary key, supporting both order directions. No missing index or measured bottleneck is established here.

First pass the latest `CompactionRecord` through prompt/todo composition when already loaded, rather than separately asking for its ordinal. Keep the insert's existing returned row. Add pagination only if history cardinality warrants changing the explicit contract; a stored routine adds little to these single statements.

Then admit immutable records keyed by database identity, conversation, through-ordinal and projection. A latest-pointer or history page additionally needs a durable per-conversation compaction generation, which does not currently exist as a cache contract. Publish inserts only after commit; invalidate both latest and collection membership for every writer, including direct database writers through the proposed notification/change mechanism. Conversation access must be checked currently before returning protected summaries. Reconnect bypasses the namespace until an authoritative generation baseline is recovered. Use the shared bounded expected O(1) LRU, not a private unbounded map.

Verify a mock composition budget of one latest read per operation, committed replacement reuse and no invalidation on rollback. PostgreSQL tests are needed only if changing persistence: assert insert constraints, greatest-reach ordering and commit/notification races in focused full-db tests. No tests or runtime changes are part of this plan.
