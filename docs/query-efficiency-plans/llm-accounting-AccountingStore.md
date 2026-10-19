# AccountingStore

Status: proposed; no runtime implementation. Baseline: `fc36351f239b62984363ababaa1de1139402aafb`.

[SQL owner](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/accounting/AccountingStore.java#L54) · [caller/contract](../../plowshare-server/src/main/java/io/aeyer/plowshare/server/llm/accounting/AccountingProjector.java#L1) · [schema/routines](../../plowshare-server/src/main/resources/db/migration/V85__durable_inference_accounting.sql#L1). Cache mechanics and recovery follow the [shared strategy](../query-efficiency-and-cache-design.md#server-reuse-and-tracked-database-dtos).

`AccountingProjector` projects journal events atomically under a journal watermark lock, validates duplicate event payloads, locks call state and updates attempts/calls/prices. Processing an event can perform several reads/writes; batches currently loop events and repeatedly load shared call/pricing state. This work is durable accounting, so ordering and conflict detection matter more than simply dropping reads.

Instrument statements per event kind and batch, then retain typed call/rate-card state within one transaction where preceding mutations are reflected correctly. Batch independent inserts while preserving journal sequence, duplicate-payload equivalence, lifecycle checks and watermark atomicity. V85 supplies call dimension/time indexes, open-instance index, attempt call index and event keys. Evaluate `(call_id,attempt_number DESC)` against latest-attempt queries before adding it; verify later migrations first. A set-based projection routine may reduce trips, but must preserve exact conflict semantics and rollback the whole batch on inconsistency.

Immutable rate cards/event evidence are cache candidates keyed by database/version or event ID/content identity; mutable call lifecycle, journal watermark, open-attempt counts and recovery locks stay authoritative. Publish only after commit. Projection/recovery and pricing writers invalidate call/instance/journal/report collections; report cache dependencies must include newly arriving attempts and watermark progress. Existing source IDs do not establish commit order: durable invalidation/recovery is a prerequisite to bounded LRU, and active projection transactions keep local state only.

Mock statement budgets by event kind and ensure duplicate/conflicting events retain behavior. Focused PostgreSQL tests cover revised batch atomicity, row locks, conflict comparisons and interruption recovery when implemented.
