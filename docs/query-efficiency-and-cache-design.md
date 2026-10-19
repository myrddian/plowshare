# Query efficiency and cache design

Status: proposed implementation strategy. The
[query efficiency rules](coding-standards.md#query-efficiency-and-reuse) apply to
new and changed code. The [first implementation slice](query-efficiency-first-slice.md)
consolidates facets, separates list rendering from facets and permits bounded
independent reads. The [second slice](query-efficiency-second-slice.md) adds
document/search snapshot reuse, keyed schedule reads and explicit DTO linkage
contracts. The cache and database notification bridge remain proposed;
the broader changes described here require separate implementation and verification.

The [JDBC owner inventory and individual plans](query-efficiency-plans/README.md)
apply this strategy to the latest reviewed source baseline. The
[cross-repository delivery plan](query-efficiency-plans/delivery.md) connects SQL,
server/client reuse, dispatch, tracked DTOs and durable invalidation work.

Improve latency by doing less work, reusing information already loaded and
preventing slow operations from delaying independent requests. SQL optimisation,
server composition, client refresh behaviour, request dispatch and a bounded RAM
cache are complementary parts of this approach. PostgreSQL remains the durable
authority; specialist repositories retain ownership of persistence and mapping.

## Improvement areas and query audit

Audit a complete user operation from client request to repository query and back.
Record each query's caller, projection, parameters by type, cardinality, frequency,
indexes, repeated function calls and consistency requirements. Identify which
previously loaded DTO or result could replace a repeated read. Keep query
execution, request queue time, transport, decoding and rendering measurements
separate so a fast query waiting behind a slow operation is diagnosed correctly.

The initial areas identified in the JDBC and client audit are:

| Area | Work to investigate | Direction |
| --- | --- | --- |
| [Catalogue facets](../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/JdbcInformationCatalogueRepository.java) | Separate facet, graph and total queries repeat the same selection and permission traversal | Select authorized candidates once and reuse them for all aggregates |
| [Permission evaluation](../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/JdbcInformationEventRepository.java) | Nested functions repeat account, scope and dependency checks; event delivery tries several selections for the same revision | Resolve relevant authority and dependency sets once within the operation's consistency boundary |
| [Document status and reads](../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/InformationCatalogue.java) | An existence/readability check fetches metadata that is discarded; later calls reload metadata or full text to inspect a field | Return and pass the authorized typed snapshot; use a narrow projection when only metadata is needed |
| [Search](../plowshare-server/src/main/java/io/aeyer/plowshare/server/documents/RetrievalService.java) | Coverage scans repeat; catalogue text search constructs vectors while searching | Reuse coverage within the operation and evaluate a maintained, indexed search projection |
| [Event history](../plowshare-server/src/main/java/io/aeyer/plowshare/server/information/JdbcInformationCatalogueRepository.java) | Revision-filtered history is ordered by sequence without a matching composite index | Evaluate a `(revision_id, sequence)` index against actual plans and growth |
| [Schedules](../plowshare-server/src/main/java/io/aeyer/plowshare/server/events/JdbcScheduleDefinitionStore.java) | Each per-file reconciliation reloads the entire source collection | Reconcile from one source snapshot or use a single-file lookup |
| [Relay discovery](../plowshare-server/src/main/java/io/aeyer/plowshare/server/relay/RelayInternalWorkers.java) | Workers repeatedly query unchanged durable work; local signals do not cross server processes | Use database notifications to wake bounded discovery and retain durable recovery |
| Client reads | Timed refreshes repeat unchanged lists and facets; identical reads can overlap | Reuse state, coalesce pending reads and refresh from relevant invalidations |
| Dispatch | Slow reads delay unrelated operations sharing a serial connection | Evaluate bounded asynchronous read dispatch with explicit ordering and resource limits |

These are audit targets, not a claim that each is the dominant cost in every
deployment. Preserve reproducible evidence with the source revision and data
shape. Keep private query parameters and operational dumps out of the repository.
Compare cold and warm reads, unchanged refreshes, concurrent activity and recovery.
Define an explicit latency and work budget for each operation before accepting an
implementation; seconds of repeated permission work for a small facet result are
not an acceptable steady-state target.

## SQL and PostgreSQL capabilities

Remove duplicate queries before concealing their cost behind a cache. Prefer
bounded projections, batch reads, joins, set-based permission evaluation and
`RETURNING` when a write can provide the result the caller needs. Keep exact
revision, ownership, ordering, pagination and revocation semantics intact.

Match indexes to the executed predicate and ordering. Consider composite and
partial B-tree indexes, GIN for suitable JSONB and text-search predicates, and
expression indexes where the query actually uses the indexed expression. An
index on a raw column does not automatically support a wrapped expression. Avoid
adding indexes without checking selectivity, write cost and representative plans.
Maintain search vectors at their owning write boundary when that avoids parsing
the same large text on every search; changes to contributing metadata must update
the projection too. PostgreSQL documents
[text-search storage and indexing](https://www.postgresql.org/docs/16/textsearch-tables.html).

Use a request-local `MATERIALIZED` CTE where one expensive candidate selection
should feed several aggregates without repeated evaluation. This is different
from a persistent materialized view: the latter needs its own refresh and access
consistency contract. Compare both query plans rather than assuming
materialization always helps. See
[PostgreSQL CTE materialization](https://www.postgresql.org/docs/16/queries-with.html#QUERIES-WITH-CTE-MATERIALIZATION).

Stored SQL functions and procedures are valid repository implementation tools.
Candidates include a typed authorized document snapshot, a complete facet result
and atomic claims or state transitions. A table-returning function can expose a
bounded typed result without repeated JDBC round trips. Move cohesive set-based
work into the routine; wrapping the same per-row queries preserves the waste.
Document volatility, transaction ownership, privileges and result mapping. Retain
the caller's authority and explicit bindings; do not introduce an authority bypass
or expose SQL across the repository interface. See
[PostgreSQL SQL functions and procedures](https://www.postgresql.org/docs/16/xfunc-sql.html).

All new schema objects, indexes, functions, procedures and triggers belong in new
Flyway migrations. Performance measurements and persistence assertions require the
smallest relevant PostgreSQL checks under the existing database test policy.

## Server reuse and tracked database DTOs

The first level of reuse is the current operation. Once a repository has loaded
and authorized an immutable DTO snapshot, pass that snapshot to helpers that need
its fields. Do not reduce it to an identifier and hydrate it again. A refresh
required by changed authority, scope, version or transaction semantics must be
explicit. Reuse within one operation must obey that operation's defined database
snapshot or version fences.

The shared cache tracks eligible database DTO snapshots through their owning
typed repository capability. Tracking records the source identity, source version,
projection kind, dependencies, invalidation generation, memory weight and LRU
position. Callers reuse the loaded immutable DTO; cache bookkeeping stays behind
the capability boundary. A typed envelope may expose identity and version when
the caller needs an optimistic-concurrency contract.

Distinguish an immutable revision's retained content from mutable heads, lifecycle,
audience and access policy. An entity can have several projections and versions;
one mutable object per database identifier is insufficient. Cached lists and
facets must also depend on membership of the selected set, including newly added
or removed rows, not only the entities already present in the result.

Cache tracking does not provide automatic database dirty checking. Services use
explicit repository mutations with existing validation and expected versions.
After commit, a mutation invalidates affected projections or supplies a proven
committed replacement, such as a validated `RETURNING` result. A rollback never
publishes a new value into the shared cache. Uncommitted reads and transaction-local
changes stay local until their publication semantics are established.

Each cached repository capability defines:

- Keys covering database/server identity, entity and projection, version, and all
  scope or selection inputs that change the result. Account-specific projections
  include their authorization context; client keys include connection identity.
- Immutable DTO values with bounded size and a declared version source. Old and
  new revisions cannot silently share one entry.
- Entity and collection dependencies for mutations, deletion, relationship edits,
  policy changes, source changes and query-result membership changes.
- Admission, expiration, maximum entries and memory weight, loading failures,
  concurrent fills, permitted staleness and invalidation/recovery behaviour.

Declare consistency for each capability. Immutable content can remain reusable
while mutable heads or aggregates require invalidation. Reads requiring the latest
committed state use an authoritative version/snapshot fence; an apparently healthy
listener does not prove that every recent commit has already been observed. Return
the source version with the loaded DTO rather than issuing a separate version
query when the same repository read can provide both.

Current authorization remains a server responsibility. A cache key or an earlier
allow decision is not proof that access remains valid. Permission-sensitive reads
require a current authorization/version fence or an equivalently strong,
documented mechanism. Asynchronous invalidation alone cannot guarantee immediate
revocation across processes. Do not serve protected data from a stale allow
decision while waiting for a notification. Reuse expensive authority calculations
within a valid operation snapshot instead of omitting required checks.

## Bounded RAM cache and LRU contract

Use least recently used eviction with a hash map from key to node and a doubly
linked recency list, or an existing implementation with equivalent semantics.
Each admitted immutable DTO snapshot has one cache node. A successful hit moves
its node to the most recent end; eviction removes the least recent node.

| Operation | Required bookkeeping complexity |
| --- | --- |
| Lookup by key | Expected O(1) |
| Update recency after a hit | O(1) |
| Insert or replace one entry | Expected O(1), excluding value loading and any eviction loop |
| Remove a known entry | Expected O(1) |
| Choose and evict one least recent entry | Expected O(1) |
| Evict or invalidate k entries | O(k), excluding dependency discovery |

These bounds describe cache bookkeeping, not database work, hashing arbitrary
content, DTO serialization, memory-size estimation or contention latency. Bound
key size. A byte-budget insertion may evict several entries and therefore costs
O(k); do not describe the entire insertion or a dependency-wide invalidation as
strict O(1). Admission and eviction must not scan or sort the cache.

Configure both entry and memory-weight limits. Include keys, DTOs, nodes,
dependency metadata and outstanding load bookkeeping in the capacity model.
Oversized values bypass admission; in-flight values and waiters still need bounds.
Index dependency fan-out and bound its metadata, using a broader namespace
invalidation when exact tracking would exceed the budget.

Coordinate map and list changes under a short lock or an equivalent concurrency
protocol. Do not perform SQL, remote work or notifications while holding the LRU
lock. Share one in-flight load for the same key and version, bounded separately
from retained entries, so concurrent misses do not issue duplicate queries. Define
waiter cancellation, timeouts and cleanup without cancelling another caller's
required read. Do not retain failed loads or cache partial results as success.

Eviction removes the cache's reference; it does not mutate a DTO still held by a
caller. That caller remains bound by its operation's consistency and authorization
contract. Negative results need explicit version and invalidation semantics,
including invalidation when a previously missing entity appears. Expiration is a
resource/freshness policy, not a substitute for correct invalidation or authority.

## Database notifications and invalidation

Use PostgreSQL LISTEN/NOTIFY to signal committed state changes and wake work
discovery. Maintain a dedicated listening connection in database infrastructure,
with a bounded local fan-out mechanism. Repository changes and database triggers
must cover every writer of the cached state; an in-process write hook alone does
not cover changes made by another server or an administrative database operation.

Persist the owning mutation and its durable change/version evidence atomically.
Issue the notification in that transaction so it is delivered only after commit.
Recovery cursors must account for commit ordering: allocating a sequence before
commit does not by itself establish the order in which changes become visible.
Send a small opaque key or change reference, never content or credentials. Coalesce
duplicate invalidations and invalidate affected objects, projections and query
sets. Refetch on demand, or deliberately refresh an active view, rather than
eagerly reloading every invalidated entry. PostgreSQL defines delivery and payload
behaviour in [NOTIFY](https://www.postgresql.org/docs/16/sql-notify.html).

A cache fill must not overwrite a newer invalidation. Capture its key/namespace
generation before loading and return the durable source version with the loaded
DTO. Publish only if the relevant generation and required version fences remain
valid. If invalidated during the load, discard the candidate and retry only within
the read's bounded policy.
Keep invalidation generations independent of LRU residency until affected loads
finish, so evicting and re-creating an entry cannot admit a stale loader.

On startup or reconnect, commit LISTEN registration before establishing the
authoritative baseline. Catch up from durable change/version evidence and close
the race between that baseline and subsequently delivered notifications. While
recovery is incomplete, bypass affected shared entries and perform authoritative
reads under the required fences. If change history is unavailable, compacted or
ambiguous, clear the affected namespace and rebuild from a valid baseline. See
[LISTEN initialization ordering](https://www.postgresql.org/docs/16/sql-listen.html).

The notification stream is a hint, not a durable event ledger. A reduced-frequency
recovery sweep remains useful for missed signals and delayed work. Relay retains
its outbox, subscription offsets, leases, idempotency and owning lifecycle checks;
notifications wake discovery rather than replacing those mechanisms. A notification
of changed state does not authorize replaying a mutation with uncertain delivery.

## Client refresh and request dispatch

Clients should preserve valid visible state, share identical pending reads and
avoid fetching objects or fields already supplied by the current response. Track
revision/generation and invalidate only the affected views. Discard stale responses
after a selection or connection changes. Use change notifications to refresh active
views; periodic reconciliation should not recreate every list and facet unchanged.
Clear private cached state on logout or account/server changes.

The server translates database changes into appropriately scoped hints over the
existing WebSocket contracts. Clients do not connect to PostgreSQL or receive
unfiltered database notifications; an invalidation must not disclose a protected
entity's identity or existence to an unauthorized account.

Render a valid document list without waiting for unrelated facet aggregates.
Separate loading and error states so one slow panel cannot leave an entire screen
blank. Apply the same discipline to Relay topic selection, messages and delivery
details. A client cache never expands the server's disclosure contract.

Evaluate bounded asynchronous dispatch for independent reads on a shared
connection. Declare operation scheduling policy explicitly: some apparent reads
also acquire leases, advance cursors, subscribe or perform other side effects.
An unknown operation remains ordered until its semantics are classified. Preserve
write ordering, read-after-write dependencies, live authority admission and
idempotency. Bound concurrency, queued requests, response bytes and database pool
use; unbounded parallelism can move the bottleneck into PostgreSQL. Existing
correlation IDs permit responses to complete independently, but cancellation and
uncertain completion retain their owning contracts.

## Verification and delivery

Deliver separately reviewable changes for query/projection reuse, PostgreSQL
optimisations, client refresh behaviour, dispatch, and cache/invalidation support.
The [coding standards](coding-standards.md#verification-and-review) govern test
selection. This proposal does not imply that the runtime improvements have shipped.

Use mock repository and transport tests to establish query budgets and duplicate
read suppression, and controlled-clock tests for cache behaviour. Verify LRU order,
entry/byte bounds, oversized bypass, concurrent misses, failed loads, cancellation,
dependency invalidation, deletion/re-creation and eviction during a load. Use
barriers to exercise commit/rollback and invalidation/fill races rather than sleeps.

Focused PostgreSQL verification covers SQL plans, result equivalence, authorization,
stored routine semantics, notification commit/rollback behaviour, LISTEN startup,
listener loss/reconnect and recovery across two server processes. Verify revocation
and invalidation of list/facet membership, not just individual entity values.

Measure query and SQL-function call counts, rows/bytes read, cache hits/misses,
coalesced loads, evictions, resident weight, invalidation fan-out, listener health,
recovery progress and latency percentiles. A warm read should demonstrate the
declared saved work; a cold read still needs an efficient query. Measure interactive
Relay reads while catalogue work is active and verify that completion no longer
depends on an unrelated slow operation. Report repository checks separately from
live deployment results.
