# Relay topic/message query, cache and wakeup slice

Implemented locally on 2026-10-20, following the
[document/search/schedule adoption](query-efficiency-cache-adoption.md).
Deployment and production latency measurements are separate. This covers Relay's
read-only topic/publication viewer, source discovery and consumer waits; durable
claims, offsets, private Messaging state and execution remain with their owners.

## Less work in the existing queries

`JdbcRelayLogRepository` projects the fields its DTOs actually use. In particular,
branch inspection no longer transfers pinned handler source through `d.*`.
Admissions still retain their input and source for execution; inspection reads only
routing/handler hashes, state and account-filtered receipt/conversation metadata.
`latest()` remains a fresh, topic-locked read for catalogue/decision callers.

The newest branch window has a matching index on scope, topic, descending
publication position and the branch/subscriber/ID tie breakers. The order remains
stable when several subscriber branches share a publication position. Existing
limits, retained-prefix byte handling, expired-gap reporting and receipt privacy
are preserved.

Source publication now uses `COALESCE(project_id,0)=?`, matching the existing
partition/sequence index for both project and system scopes. Discovery uses a
validated `(scope,topic)` tuple cursor and a matching expression index instead of
an optional concatenated comparison over every outbox row. These are traversal
cursors, not sequence/commit high-water marks. Discovery wraps and revisits earlier
partitions; publication/removal remain atomic under the existing advisory lock.

`V176__relay_projection_versions_and_wakeups.sql` creates the two indexes in its
normal migration transaction. Index creation has its ordinary table-lock/write
cost; production rollout must account for actual table sizes. The focused fixture
uses 5,000 source rows and checks actual PostgreSQL `EXPLAIN (ANALYZE, BUFFERS)`
plans select the partition and discovery indexes. This proves the query/index
alignment for that fixture, not a deployed speedup.

## Tracked inspection DTOs

Composition supplies two bounded typed caches: `relay-topics-v1` and
`relay-inspection-v1`. They use the existing O(1) LRU/coalescing foundation and
retain the actual loader's durable token beside each immutable DTO. Topic lists
are keyed by explicit scope and limit. Pages also include topic, account,
requested position and limit. Account-filtered diagnostics can never join another
account's page load. Topic recreation, policy, heads, expiry and diagnostic
changes are covered by durable versions rather than append position alone.

The authenticated inspection owner checks current project membership, Personal
ownership or system administration on every request, including warm reads. Cached
values do not confer authority. Consumer batches, subscriber mutation decisions,
claims, leases and execution/catalogue decisions do not use these caches.

Cold pages preserve the topic-lock transaction while reading events, subscribers,
branches and recoveries. Each bounded projection checks its aggregate encoded size
in SQL before mapping rows or payloads; empty results use a scalar sentinel. An
oversize projection refuses admission and runs the complete ordinary read once.
It never shortens a page to fit the cache, drops diagnostics or truncates text.
DTO weight includes payload strings, metadata and collection members. Default
cache budgets remain per projection, as described in the foundation/adoption notes.

A warm topic list avoids its one owner projection query; a warm page avoids all
five owner projection queries. Both still pay the existing four freshness
statements: caller eligibility, committed counter-capture probe, pending/expiry
snapshot and stored-token validation. Authorization queries are separate. A cold
shared load also checks dirty state and its token before publication. These are
explicit work budgets; a cheap one-statement list can cost more round trips when
cached, so SQL/projection savings and end-to-end latency must be measured together.

The migration adds a fifth version domain, Relay. Its statement triggers cover
publications, topic metadata, subscriptions, admissions, deliveries, executions,
native recovery audits and projected project/conversation metadata, including
cascades and direct SQL writers. Zero-row statements do not change versions.
Conversation updates unrelated to identity/project fields, and unreferenced
conversations, do not invalidate the viewer during inference. Lease-only consumer
ownership updates are outside the inspection dependency set.

The completed-XID/pending-transaction fence remains required: a sequence maximum
is not commit order. Dirty outer transactions and historical reads bypass sharing;
rollback cannot publish an uncommitted DTO. Reconnect clears uncertain residency,
and every warm read validates its stored loader token even if a hint was missed.

## Notifications reduce idle database polling

Relay shares the existing PostgreSQL listener and its committed LISTEN/baseline
recovery lifecycle. No additional listener connection is opened. Triggers emit a
fixed `relay` hint for committed Relay changes and new outbox/native work. Relevant
firing requeue/completion changes can also wake discovery. Hints contain no topic,
account, position, body or request identity. They are never acknowledgements or
permission to replay an effect.

A bounded bridge wakes each registered group and the internal discovery loop.
Waiters register before the first read/scan, so a commit between reading and
waiting is retained. Each waiter has at most one coalesced pending wakeup; there
is no per-event application queue. Shared indexes and sets use concurrent
collections. Short lifecycle coordination maintains the registration bound; wait,
callback and database work run outside it. Closing a waiter releases its slot and
interrupts its wait rather than producing a database retry loop.

The bridge observes monotonic listener generations. Startup, outage and recovery
wake existing readers; stale callbacks cannot restore old health. Outage returns
to the previous polling intervals, and periodic reconciliation always remains:

| Reader | Healthy notification transport | Unhealthy/local-only transport |
| --- | --- | --- |
| Project/native consumers and internal discovery | notification or `reconciliation-interval` (default 30 s) | notification or `idle-interval` (default 5 s) |
| External long-poll consume | notification, caller deadline or at most 5 s | local notification, caller deadline or 100 ms |
| Workspace enrollment/activation | existing `configuration-interval` (default 30 s) | same |

Failures keep the existing failure backoff. Reads after a wait repeat live
membership/port checks and reject a changed project identity. The already resolved
scope is reused for the first read; a no-wait consume allocates no waiter. Cursor
acknowledgement, distributed leases, unknown-effect handling and native gap
recovery are unchanged.

`plowshare.relay.workers.reconciliation-interval` accepts 1 s through 5 min.
`max-signal-waiters` defaults to 4,096 and accepts 1 through 65,536 across consumers,
discovery and external waits. The shared listener reserves one additional bounded
observer for this bridge beyond the configured projection count.

Domain hints deliberately broadcast rather than retain private per-topic queues.
Work-only hints can conservatively invalidate inspection caches before publication;
durable versions, outbox/inbox rows, keyset wraparound and periodic reads recover
coalesced, delayed or dropped hints. Broad invalidation can reduce hit rate under
busy Relay workloads and remains a measurement/refinement target.

## Verification

Focused tests cover warm query budgets, exact key separation, revoked access,
oversize refusal before hydration, notification coalescing, waiter capacity and
cleanup, long-poll deadline/revocation, remote discovery wakeup and stale callback
rejection. Focused PostgreSQL tests cover real mapping/index plans, append/prune/
recreation, account-filtered receipts, dirty rollback, commit-only outbox hints,
and cache bypass plus durable rescan across an actual listener disconnect.

Passed locally:

- `./gradlew format` and `./gradlew formatCheck`.
- 269 focused default tests selected from the Relay and cache packages.
- `./gradlew check --continue --max-workers=1`, including all 6,104 server tests
  and the default documentation, formatting, client/SDK and boundary checks.
- 70 distinct PostgreSQL cases across explicit `:plowshare-server:test -PfullDb`
  selections: `RelayProjectionDatabaseTest`, `ProjectionVersionsDatabaseTest`,
  `JdbcRelayRepositoryTest`, `JdbcRelayWorkTest`, `RelayLifecycleDatabaseTest` and
  `JdbcRelayPortRepositoryTest`. The final two-class run repeated all 15 inspection/
  port cases after the waiter implementation was finalized, including a committed
  publication waking the bridge without any local publisher callback.

The focused database run also exposed two stale compatibility fixtures originating
with `d69ef28c`: the JSON-only mock endpoint now explicitly selects SDK legacy
transport, and the legacy byte-prefix test explicitly requests the legacy budget.
Production defaults and transport negotiation are unchanged by those test fixes.

No deployment or production benchmark is claimed.
